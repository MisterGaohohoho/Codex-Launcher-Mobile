package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class ConnectionTransportTest {
    private val held = HeldCertificate.Builder().commonName("Fixture PC").build()
    private val token = "b".repeat(64)

    private fun server(certificate: HeldCertificate = held) =
        MockWebServer().apply {
            useHttps(
                HandshakeCertificates.Builder()
                    .heldCertificate(certificate)
                    .build()
                    .sslSocketFactory(),
                false,
            )
            start()
        }

    private fun endpoint(server: MockWebServer) = "https://127.0.0.1:${server.port}"

    private fun api(server: MockWebServer) =
        LauncherApi(endpoint(server), LauncherApi.sha256(held.certificate), token)

    private fun snapshot() =
        MockResponse()
            .setBody(
                WireJson.encodeToString(Snapshot(1, "2026-11-23T00:00:00Z", "Fixture PC", "2.8.0"))
            )

    @Test
    fun heartbeatDoesNotQueueBehindFiveSlowHistoryRequests() = runBlocking {
        val server = server()
        val entered = CountDownLatch(5)
        val release = CountDownLatch(1)
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/v1/snapshot") return snapshot()
                    entered.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                    return MockResponse().setBody("{\"data\":[]}")
                }
            }
        val api = api(server)
        val readers = (1..5).map { async(Dispatchers.IO) { api.query("workbench-thread") } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(4, TimeUnit.SECONDS) })
            val value = withTimeout(2500) { api.snapshot() }
            assertEquals("Fixture PC", value.computerName)
            assertTrue(readers.all { it.isActive })
            assertEquals(6, server.requestCount)
        } finally {
            release.countDown()
            readers.forEach { it.cancelAndJoin() }
            api.close()
            server.shutdown()
        }
    }

    @Test
    fun aReadRetriesOnceButAWriteAndAuthorizationFailureNeverRetry() = runBlocking {
        val server = server()
        val api = api(server)
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("{\"revision\":\"r2\"}"))
            assertEquals("r2", api.query("configuration")["revision"].text())
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                api.command(
                    AdminCommand("fixture", "workbench.create", JsonObject(emptyMap()), "epoch", 1)
                )
                fail("Write retried or swallowed")
            } catch (e: ApiException) {
                assertEquals(503, e.status)
            }
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            try {
                api.query("configuration")
                fail("Authorization failure swallowed")
            } catch (e: ApiException) {
                assertEquals(401, e.status)
            }
            assertEquals(4, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                api.query("configuration")
                fail("Read failure swallowed")
            } catch (e: ApiException) {
                assertEquals(503, e.status)
            }
            assertEquals(6, server.requestCount)
        } finally {
            api.close()
            server.shutdown()
        }
    }

    @Test
    fun aReachableAlternateMustPresentTheSamePcCertificate() = runBlocking {
        val primary = server()
        val alternate = server()
        val api = api(primary)
        try {
            primary.enqueue(MockResponse().setResponseCode(503))
            alternate.enqueue(snapshot())
            api.updateEndpoints(
                listOf(endpoint(alternate), "https://8.8.8.8:39173", "http://192.168.1.2:39173")
            )
            assertEquals("Fixture PC", withTimeout(2500) { api.snapshot() }.computerName)
            assertEquals(endpoint(alternate), api.connectionInfo.endpoint)
            assertEquals(
                "Bearer $token",
                alternate.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"),
            )
            alternate.enqueue(MockResponse().setBody("{\"revision\":\"alternate\"}"))
            assertEquals("alternate", api.query("configuration")["revision"].text())
        } finally {
            api.close()
            primary.shutdown()
            alternate.shutdown()
        }
    }

    @Test
    fun aDifferentCertificateCannotReceiveTheSavedAuthorization() = runBlocking {
        val primary = server()
        val wrong = server(HeldCertificate.Builder().commonName("Other PC").build())
        val api = api(primary)
        try {
            primary.enqueue(MockResponse().setResponseCode(503))
            api.updateEndpoints(listOf(endpoint(wrong)))
            try {
                withTimeout(2500) { api.snapshot() }
                fail("Different PC certificate accepted")
            } catch (_: SSLException) {}
            assertEquals(0, wrong.requestCount)
        } finally {
            api.close()
            primary.shutdown()
            wrong.shutdown()
        }
    }

    @Test
    fun prefersTheLocalSubnetAndCoolsDownFailedAlternates() {
        val route =
            ConnectionRouting("https://100.72.1.2:39173") { listOf(0xC0A80164.toInt() to 24) }
        route.update(
            listOf("https://192.168.1.2:39173", "https://10.1.0.3:39173", "https://8.8.8.8:39173")
        )
        val lan = validateEndpoint("https://192.168.1.2:39173")
        assertEquals(lan, route.plan(1000).first())
        route.failed(lan, 1000)
        assertFalse(lan in route.plan(2000))
        assertTrue(lan in route.plan(31_001))
        assertFalse(route.allows("8.8.8.8"))
        assertTrue(route.plan(2000).size <= 4)
    }
}
