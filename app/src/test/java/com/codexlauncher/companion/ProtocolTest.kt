package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun code(endpoint: String = "https://192.168.1.2:39173") =
        PairingCode(
            "codex-launcher-pair",
            1,
            endpoint,
            "a".repeat(64),
            "b".repeat(64),
            "2030-01-01T00:00:00Z",
            "Fixture computer",
        )

    @Test
    fun rejectsPublicHttpAndMalformedPairing() {
        assertEquals(
            "Fixture computer",
            PairingCode.parse(
                    WireJson.encodeToString(code()),
                    Instant.parse("2026-01-01T00:00:00Z"),
                )
                .computerName,
        )
        for (endpoint in
            listOf(
                "http://192.168.1.2:39173",
                "https://8.8.8.8:39173",
                "https://fixture.test:39173",
                "https://10.0.0.1.attacker.invalid:39173",
                "https://010.0.0.1:39173",
                "https://192.168.1.2:39173/private",
                "https://user:pass@192.168.1.2:39173",
                "https://192.168.1.2:39173/?token=foo",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                PairingCode.parse(WireJson.encodeToString(code(endpoint)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.parse(WireJson.encodeToString(code().copy(version = 99)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.parse(
                WireJson.encodeToString(code().copy(expiresAt = "2020-01-01T00:00:00Z"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCode.parse(
                WireJson.encodeToString(code().copy(certificateSha256 = "not-a-fingerprint"))
            )
        }
        validateEndpoint("https://100.80.3.4:39173")
    }

    @Test
    fun authenticatedHttpsUsesExactPinAndNeverRetriesOrFollowsRedirects() = runBlocking {
        val held = HeldCertificate.Builder().commonName("Fixture computer").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
        val endpoint = "https://127.0.0.1:${server.port}"
        val token = "c".repeat(64)
        val api = LauncherApi(endpoint, LauncherApi.sha256(held.certificate), token)
        try {
            server.enqueue(
                MockResponse()
                    .setBody(
                        WireJson.encodeToString(
                            Snapshot(1, "2026-11-20T00:00:00Z", "Fixture", "2.6.0")
                        )
                    )
            )
            assertEquals(1, api.snapshot().protocolVersion)
            val snapshotRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer $token", snapshotRequest.getHeader("Authorization"))
            assertNull(snapshotRequest.getHeader("Origin"))
            val requestId = UUID.randomUUID().toString()
            server.enqueue(
                MockResponse().setBody("{\"runId\":\"fixture-run\",\"message\":\"started\"}")
            )
            api.start(StartRequest(requestId, null))
            val start = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/start", start.path)
            val sent = WireJson.decodeFromString<StartRequest>(start.body.readUtf8())
            assertEquals(requestId, sent.requestId)
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "https://127.0.0.1:${server.port}/secret")
            )
            try {
                api.snapshot()
                fail("Redirect must be rejected")
            } catch (e: ApiException) {
                assertEquals(302, e.status)
            }
            assertEquals(3, server.requestCount)
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"message\":\"busy\"}"))
            try {
                api.start(StartRequest(UUID.randomUUID().toString(), null))
                fail("503 must propagate")
            } catch (e: ApiException) {
                assertEquals(503, e.status)
            }
            assertEquals(4, server.requestCount)
            val untrusted = LauncherApi(endpoint, "0".repeat(64), token)
            try {
                untrusted.snapshot()
                fail("Wrong certificate was accepted")
            } catch (_: javax.net.ssl.SSLException) {} finally {
                untrusted.close()
            }
            assertEquals(4, server.requestCount)
        } finally {
            api.close()
            server.shutdown()
        }
    }

    @Test
    fun rejectsExcessiveResponsesAndMismatchedWorkerDetails() = runBlocking {
        val held = HeldCertificate.Builder().commonName("Fixture").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(),
            false,
        )
        server.start()
        val api =
            LauncherApi(
                "https://127.0.0.1:${server.port}",
                LauncherApi.sha256(held.certificate),
                "d".repeat(64),
            )
        try {
            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            try {
                api.snapshot()
                fail("Oversized response accepted")
            } catch (_: java.io.IOException) {}
            server.enqueue(
                MockResponse()
                    .setBody(
                        WireJson.encodeToString(
                            WorkerDetails(
                                "other-run",
                                1,
                                1,
                                "prompt",
                                "fixture",
                                "fixture",
                                "request",
                                "reply",
                                "",
                                true,
                                false,
                            )
                        )
                    )
            )
            try {
                api.details("fixture-run", 1, 1)
                fail("Wrong run details accepted")
            } catch (_: IllegalArgumentException) {}
        } finally {
            api.close()
            server.shutdown()
        }
    }

    @Test
    fun staleOfflineAndReadOnlyStatesDisableControl() {
        val computer =
            PairedComputer(
                "https://127.0.0.1:39173",
                "a".repeat(64),
                "fixture-id",
                "b".repeat(64),
                "Fixture",
                true,
                "2030-01-01T00:00:00Z",
            )
        val state = CompanionState(computer = computer, online = true, lastSyncElapsed = 1000)
        assertTrue(state.canControl(2000))
        assertTrue(
            "A successful sync newer than the UI tick must stay online",
            state.canControl(900),
        )
        assertTrue(state.fresh(9000))
        assertFalse(state.fresh(9001))
        assertFalse(state.copy(lastSyncElapsed = 0).fresh(900))
        assertFalse(state.canControl(10000))
        assertFalse(state.copy(online = false).canControl(2000))
        assertFalse(state.copy(computer = computer.copy(canControl = false)).canControl(2000))
        assertFalse(state.copy(busy = true).canControl(2000))
    }

    @Test
    fun administratorRequestsAndEventCancellationKeepThePinnedConnectionUsable() = runBlocking {
        val held = HeldCertificate.Builder().commonName("Fixture").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(),
            false,
        )
        server.start()
        val api =
            LauncherApi(
                "https://127.0.0.1:${server.port}",
                LauncherApi.sha256(held.certificate),
                "d".repeat(64),
            )
        try {
            server.enqueue(MockResponse().setBody("{\"revision\":\"r1\",\"value\":{}}"))
            assertEquals("r1", api.query("configuration")["revision"].text())
            assertEquals("/api/v2/query/configuration", server.takeRequest().path)
            val command =
                AdminCommand(
                    UUID.randomUUID().toString(),
                    "configuration.patch",
                    buildJsonObject {
                        put("revision", "r1")
                        put("patch", buildJsonObject { put("keepAlive", false) })
                    },
                )
            server.enqueue(MockResponse().setBody("{\"message\":\"saved\"}"))
            api.command(command)
            val request = server.takeRequest()
            assertEquals("/api/v2/command", request.path)
            assertEquals(command, WireJson.decodeFromString<AdminCommand>(request.body.readUtf8()))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "event: state\ndata: {\"workbench\":{\"epoch\":\"fixture\",\"cursor\":12,\"events\":[],\"approvals\":[]}}\n\n"
                    )
            )
            val batch = withTimeout(5000) { api.events("fixture", 11).first() }
            assertEquals(12, batch.cursor)
            assertEquals("/api/v2/events?epoch=fixture&since=11", server.takeRequest().path)
            server.enqueue(
                MockResponse()
                    .setBody(
                        WireJson.encodeToString(
                            Snapshot(1, "2026-11-22T00:00:00Z", "Fixture", "2.8.0")
                        )
                    )
            )
            assertEquals("Fixture", api.snapshot().computerName)
            assertEquals("/api/v1/snapshot", server.takeRequest().path)
        } finally {
            api.close()
            server.shutdown()
        }
    }
}
