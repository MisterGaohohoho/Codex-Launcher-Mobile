package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AttachmentProtocolTest {
    @Test
    fun binaryTransfersUseThePairedCertificateAndTokenWithoutFollowingRedirects() = runBlocking {
        val held = HeldCertificate.Builder().commonName("Attachment fixture").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(),
            false,
        )
        server.start()
        val token = "c".repeat(64)
        val endpoint = "https://127.0.0.1:" + server.port
        val api = LauncherApi(endpoint, LauncherApi.sha256(held.certificate), token)
        val id = UUID.randomUUID().toString()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val destination = File.createTempFile("attachment-protocol-", ".bin")
        try {
            server.enqueue(
                MockResponse()
                    .setBody(
                        WireJson.encodeToString(
                            AttachmentUpload(
                                id,
                                "uploading",
                                bytes.size.toLong(),
                                ATTACHMENT_MIB.toInt(),
                            )
                        )
                    )
            )
            api.uploadChunk(id, 0, bytes.size.toLong(), bytes)
            val chunk = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer " + token, chunk.getHeader("Authorization"))
            assertEquals("application/octet-stream", chunk.getHeader("Content-Type"))
            assertEquals("bytes 0-199999/200000", chunk.getHeader("Content-Range"))
            assertEquals(bytesSha256(bytes), chunk.getHeader("X-Chunk-SHA256"))
            assertArrayEquals(bytes, chunk.body.readByteArray())
            val attachment = "att_" + UUID.randomUUID()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            api.downloadAttachment("thread-fixture", attachment, false, destination)
            assertArrayEquals(bytes, destination.readBytes())
            val download = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer " + token, download.getHeader("Authorization"))
            assertEquals(
                "/api/v2/attachments/" + attachment + "/content?threadId=thread-fixture",
                download.path,
            )
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", endpoint + "/elsewhere")
            )
            try {
                api.downloadAttachment("thread-fixture", attachment, false, destination)
                fail("Redirect was accepted")
            } catch (error: ApiException) {
                assertEquals(302, error.status)
            }
            assertEquals(3, server.requestCount)
            val untrusted = LauncherApi(endpoint, "0".repeat(64), token)
            try {
                untrusted.uploadChunk(id, 0, bytes.size.toLong(), bytes)
                fail("Wrong pin was accepted")
            } catch (_: SSLException) {} finally {
                untrusted.close()
            }
            assertEquals(3, server.requestCount)
        } finally {
            api.close()
            server.shutdown()
            destination.delete()
        }
    }

    @Test
    fun boundedDownloadsRemovePartialFilesAndUploadErrorsRetainMachineCodes() = runBlocking {
        val held = HeldCertificate.Builder().commonName("Limits fixture").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(),
            false,
        )
        server.start()
        val api =
            LauncherApi(
                "https://127.0.0.1:" + server.port,
                LauncherApi.sha256(held.certificate),
                "d".repeat(64),
            )
        val destination = File.createTempFile("attachment-limit-", ".bin")
        try {
            server.enqueue(
                MockResponse()
                    .setChunkedBody(Buffer().write(ByteArray(ATTACHMENT_MIB.toInt() + 1)), 65536)
            )
            try {
                api.downloadAttachment("fixture", "att_" + UUID.randomUUID(), true, destination)
                fail("Oversized preview accepted")
            } catch (_: java.io.IOException) {}
            assertFalse(destination.exists())
            server.enqueue(
                MockResponse()
                    .setResponseCode(413)
                    .setBody("{\"code\":\"UPLOAD_TOO_LARGE\",\"message\":\"Fixture size limit\"}")
            )
            try {
                api.uploadChunk(UUID.randomUUID().toString(), 0, 1, byteArrayOf(1))
                fail("Upload error ignored")
            } catch (error: ApiException) {
                assertEquals(413, error.status)
                assertEquals("UPLOAD_TOO_LARGE", error.code)
            }
            assertEquals(2, server.requestCount)
        } finally {
            api.close()
            server.shutdown()
            destination.delete()
        }
    }
}
