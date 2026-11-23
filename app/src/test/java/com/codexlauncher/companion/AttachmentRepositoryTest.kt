package com.codexlauncher.companion

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = files

            override fun getCacheDir(): File = cache
        }
    }

    private fun target(thread: String = UUID.randomUUID().toString()) =
        DraftTarget("a".repeat(64), "scope-a", thread)

    @Test
    fun privateCopySurvivesOriginalRemovalAndKeepsDraftsInTheirOwnScope() {
        val context = context()
        val repo = AttachmentRepository(context)
        val a = target()
        val b = a.copy(scope = "different-home")
        val original = temporary.newFile("reference.log").apply { writeText("failure at line 42") }
        val item = repo.import(a, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        repo.edit(a, "Check the error")
        repo.edit(b, "Another draft")
        assertTrue(original.delete())
        val restored = AttachmentRepository(context)
        assertEquals("failure at line 42", restored.localFile(item.id).readText())
        assertEquals("Check the error", restored.state().drafts[a.key]?.text)
        assertEquals("Another draft", restored.state().drafts[b.key]?.text)
        assertTrue(restored.state().drafts[b.key]!!.attachments.isEmpty())
    }

    @Test
    fun frozenPureFileMessageRestoresUnknownWithoutCreatingANewSubmission() {
        val context = context()
        val repo = AttachmentRepository(context)
        val target = target()
        val original = temporary.newFile("code.txt").apply { writeText("const answer = 42;") }
        val item =
            repo.import(target, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        val id = UUID.randomUUID().toString()
        val pending = repo.freeze(target, id, "send", "", null, listOf(item.id))
        repo.update(id) {
            it.copy(
                state = "submitting",
                request =
                    AdminCommand(
                        "receipt",
                        "workbench.send",
                        buildJsonObject { put("messageId", id) },
                    ),
            )
        }
        val restored = AttachmentRepository(context)
        assertEquals("unknown", restored.state().pending[id]?.state)
        assertEquals(pending.attachments, restored.state().pending[id]?.attachments)
        assertTrue(restored.state().drafts[target.key]!!.attachments.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            restored.freeze(
                target,
                UUID.randomUUID().toString(),
                "send",
                "duplicate",
                null,
                emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { restored.discard(id) }
    }

    @Test
    fun uploadResumesFromAcknowledgedOffsetAfterLostChunkReply() = runBlocking {
        val context = context()
        val repo = AttachmentRepository(context)
        val target = target()
        val bytes = ByteArray(ATTACHMENT_MIB.toInt() + 11) { (it % 127).toByte() }
        val original = temporary.newFile("source.bin").apply { writeBytes(bytes) }
        val item =
            repo.import(target, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        val message =
            repo.freeze(target, UUID.randomUUID().toString(), "send", "", null, listOf(item.id))
        val api = UploadApi(item)
        val result = uploadAttachment(api, repo, message, item) {}
        assertEquals("ready", result.state)
        assertArrayEquals(bytes, api.bytes.toByteArray())
        assertEquals(listOf(0, 1), api.chunks)
        assertEquals(1, api.statusReads)
        assertEquals(0, api.modelCommands)
    }

    @Test
    fun removalAndSuccessfulDeliveryNeverDeleteThePhoneOriginal() {
        val context = context()
        val repo = AttachmentRepository(context)
        val target = target()
        val original = temporary.newFile("original.txt").apply { writeText("retained source") }
        val selected =
            repo.import(target, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        repo.remove(target, selected.id)
        assertTrue(original.exists())
        assertFalse(repo.localFile(selected.id).exists())
        val item =
            repo.import(target, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        val message =
            repo.freeze(target, UUID.randomUUID().toString(), "send", "", null, listOf(item.id))
        repo.update(message.messageId) { it.copy(state = "sent") }
        repo.forgetDelivered(message.messageId)
        assertTrue(original.exists())
        assertFalse(repo.localFile(item.id).exists())
        assertTrue(repo.state().pending.isEmpty())
    }

    @Test
    fun fileLimitsUseActualBytesAndNewSessionDraftMovesAfterCreation() {
        val context = context()
        val repo = AttachmentRepository(context)
        val draft = target("new")
        val original = temporary.newFile("large.txt").apply { writeText("123456789") }
        assertThrows(IllegalArgumentException::class.java) {
            repo.import(
                draft,
                Uri.fromFile(original),
                false,
                AttachmentPolicy(upload = true, maxFileBytes = 4),
            )
        }
        val item =
            repo.import(draft, Uri.fromFile(original), false, AttachmentPolicy(upload = true))
        val created = draft.copy(thread = UUID.randomUUID().toString())
        repo.moveDraft(draft, created)
        assertNull(repo.state().drafts[draft.key])
        assertEquals(item.id, repo.state().drafts[created.key]?.attachments?.single()?.id)
    }

    @Test
    fun manualResumeRestartsExpiredTransportWithTheSameFrozenMessageAndPrivateFile() = runBlocking {
        val repo = AttachmentRepository(context())
        val target = target()
        val source = temporary.newFile().apply { writeText("Retained local source") }
        val original =
            repo.import(target, Uri.fromFile(source), false, AttachmentPolicy(upload = true))
        val message =
            repo.freeze(target, UUID.randomUUID().toString(), "send", "", null, listOf(original.id))
        val stale = original.copy(uploadId = UUID.randomUUID().toString(), state = "paused")
        val api = UploadApi(original, expired = true)
        val result = uploadAttachment(api, repo, message, stale, restartExpired = true) {}
        assertNotEquals(original.clientUploadId, result.clientUploadId)
        assertEquals(message.messageId, api.createdRequest?.messageId)
        assertEquals(target.thread, api.createdRequest?.threadId)
        assertEquals("ready", result.state)
        assertArrayEquals(source.readBytes(), api.bytes.toByteArray())
        assertTrue(repo.localFile(original.id).exists())
        assertEquals(0, api.modelCommands)
    }

    private class UploadApi(private val item: LocalAttachment, var expired: Boolean = false) :
        CompanionApi {
        var createdRequest: UploadRequest? = null
        val bytes = java.io.ByteArrayOutputStream()
        val chunks = mutableListOf<Int>()
        var statusReads = 0
        var modelCommands = 0
        private val id = UUID.randomUUID().toString()

        private fun status() =
            AttachmentUpload(id, "uploading", bytes.size().toLong(), ATTACHMENT_MIB.toInt())

        override suspend fun createUpload(request: UploadRequest): AttachmentUpload {
            createdRequest = request
            return status()
        }

        override suspend fun uploadStatus(id: String): AttachmentUpload {
            statusReads++
            if (expired) {
                expired = false
                throw ApiException(410, "Upload expired")
            }
            return status()
        }

        override suspend fun uploadChunk(
            id: String,
            index: Int,
            total: Long,
            bytes: ByteArray,
        ): AttachmentUpload {
            chunks += index
            this.bytes.write(bytes)
            if (index == 0) throw IOException("reply lost after fsync")
            return status()
        }

        override suspend fun completeUpload(id: String) =
            status()
                .copy(
                    state = "ready",
                    attachment =
                        AttachmentMeta(
                            "att_" + UUID.randomUUID(),
                            item.fileName,
                            size = item.size,
                            sha256 = item.sha256,
                        ),
                )

        override suspend fun command(request: AdminCommand): JsonObject {
            modelCommands++
            return buildJsonObject {}
        }

        override suspend fun pair(code: PairingCode, name: String): PairResponse = error("Unused")

        override suspend fun snapshot(): Snapshot = error("Unused")

        override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
            error("Unused")

        override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

        override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

        override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse = error("Unused")

        override fun close() {}
    }
}
