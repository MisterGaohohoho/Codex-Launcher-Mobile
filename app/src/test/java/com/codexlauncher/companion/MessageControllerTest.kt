@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codexlauncher.companion

import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After
    fun after() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun awaitState(ready: () -> Boolean) = runBlocking {
        withTimeout(5000) {
            while (!ready()) {
                dispatcher.scheduler.runCurrent()
                delay(5)
            }
        }
    }

    private inner class Fixture(active: String? = null) {
        val target = DraftTarget("a".repeat(64), "backend-a", UUID.randomUUID().toString())
        val files = temporary.newFolder()
        private val cache = temporary.newFolder()
        private val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
                override fun getFilesDir() = files

                override fun getCacheDir() = cache
            }
        private val repository = AttachmentRepository(context)
        val original = temporary.newFile().apply { writeText("User-supplied build log") }
        val attachment =
            repository.import(
                target,
                Uri.fromFile(original),
                false,
                AttachmentPolicy(upload = true),
            )
        val api = UploadApi(active)
        val submitted = mutableListOf<AdminCommand>()
        val reconciled = mutableListOf<String>()
        var state =
            CompanionState(
                computer =
                    PairedComputer(
                        "https://127.0.0.1:39173",
                        target.pc,
                        "fixture",
                        "b".repeat(64),
                        "Fixture",
                        true,
                        "2030-01-01T00:00:00Z",
                    ),
                snapshot =
                    Snapshot(
                        1,
                        "2026-11-23T00:00:00Z",
                        "Fixture",
                        "2.8.0-beta.2",
                        capabilities = listOf("workbench.attachments.v1"),
                        commandEpoch = "pc-process-1",
                    ),
                online = true,
                lastSyncElapsed = SystemClock.elapsedRealtime(),
                workbench = WorkbenchState(detail = ThreadDetail.parse(api.thread(target.thread))),
            )
        val controller =
            MessageController(
                context,
                scope,
                { api },
                { state },
                { state = it(state) },
                { command, _ ->
                    submitted += command
                    true
                },
                { _, status, _ -> reconciled += status },
            )
        val messageId = UUID.randomUUID().toString()

        fun start() {
            controller.send(
                if (api.active == null) "send" else "steer",
                buildJsonObject {
                    put("threadId", target.thread)
                    put("messageId", messageId)
                    put("text", "")
                    api.active?.let { put("turnId", it) }
                    putJsonArray("localAttachmentIds") { add(attachment.id) }
                },
            )
            awaitState { api.chunkStarted }
        }

        fun localFile() = repository.localFile(attachment.id)

        inner class UploadApi(var active: String?) : CompanionApi {
            val uploadId = UUID.randomUUID().toString()
            var request: UploadRequest? = null
            var offset = 0L
            var chunkStarted = false
            val gate = CompletableDeferred<Unit>()
            var backend = target.scope

            fun thread(id: String) = buildJsonObject {
                put("control", "managed")
                put("backendScopeId", backend)
                active?.let { put("activeTurnId", it) }
                put(
                    "thread",
                    buildJsonObject {
                        put("id", id)
                        put("model", "fixture-model")
                        putJsonArray("turns") {}
                    },
                )
            }

            private fun status() =
                AttachmentUpload(uploadId, "uploading", offset, ATTACHMENT_MIB.toInt())

            override suspend fun createUpload(request: UploadRequest): AttachmentUpload {
                this.request = request
                return status()
            }

            override suspend fun uploadStatus(id: String) = status()

            override suspend fun uploadChunk(
                id: String,
                index: Int,
                total: Long,
                bytes: ByteArray,
            ): AttachmentUpload {
                chunkStarted = true
                gate.await()
                offset += bytes.size
                return status()
            }

            override suspend fun completeUpload(id: String): AttachmentUpload {
                val item = requireNotNull(request)
                return status()
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
            }

            override suspend fun query(resource: String, input: JsonObject) =
                when (resource) {
                    "workbench-thread" -> thread(input["threadId"].text())
                    "workbench-message" ->
                        buildJsonObject {
                            put("state", "sent")
                            put("reply", buildJsonObject { put("turnId", "turn-confirmed") })
                        }
                    else -> error("Unexpected query")
                }

            override suspend fun pair(code: PairingCode, name: String): PairResponse =
                error("Unused")

            override suspend fun snapshot(): Snapshot = error("Unused")

            override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
                error("Unused")

            override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

            override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

            override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse =
                error("Unused")

            override fun close() {}
        }
    }

    @Test
    fun switchingConversationDuringUploadKeepsTheFrozenDestination() {
        val f = Fixture()
        f.start()
        val other = UUID.randomUUID().toString()
        f.state =
            f.state.copy(
                workbench = f.state.workbench.copy(detail = ThreadDetail.parse(f.api.thread(other)))
            )
        f.api.gate.complete(Unit)
        awaitState { f.submitted.isNotEmpty() }
        assertEquals(f.target.thread, f.api.request?.threadId)
        assertEquals(f.target.thread, f.submitted.single().input["threadId"].text())
        assertEquals(f.messageId, f.submitted.single().input["messageId"].text())
        assertEquals(other, f.state.workbench.detail?.thread?.id)
    }

    @Test
    fun completedTurnRequiresConfirmationBeforeStartingANewTurnWithTheSameMessage() {
        val f = Fixture("original-turn")
        f.start()
        f.api.active = null
        f.state =
            f.state.copy(
                workbench =
                    f.state.workbench.copy(
                        detail = ThreadDetail.parse(f.api.thread(f.target.thread))
                    )
            )
        f.api.gate.complete(Unit)
        awaitState { f.state.pendingMessages[f.messageId]?.state == "needsConfirmation" }
        assertTrue(f.submitted.isEmpty())
        f.controller.resume(f.messageId, true)
        awaitState { f.submitted.isNotEmpty() }
        assertEquals("workbench.send", f.submitted.single().action)
        assertEquals(f.messageId, f.submitted.single().input["messageId"].text())
        assertNull(f.submitted.single().input["turnId"])
    }

    @Test
    fun confirmedLostReplyRemovesOnlyTheLocalCopyAndNeverRepeatsTheModelCommand() = runBlocking {
        val f = Fixture()
        f.start()
        f.api.gate.complete(Unit)
        awaitState { f.submitted.isNotEmpty() }
        f.controller.settled(f.submitted.single(), null, IOException("Reply lost"))
        assertEquals("unknown", f.state.pendingMessages[f.messageId]?.state)
        f.controller.verify(f.messageId)
        awaitState { f.state.pendingMessages.isEmpty() }
        assertEquals(listOf("sent"), f.reconciled)
        assertEquals(1, f.submitted.size)
        assertFalse(f.localFile().exists())
        assertTrue(f.original.exists())
    }

    @Test
    fun pausingBeforeAChunkCompletesRetainsTheDraftForManualResume() {
        val f = Fixture()
        f.start()
        f.controller.pause(f.messageId)
        awaitState { f.state.pendingMessages[f.messageId]?.state == "paused" }
        assertTrue(f.localFile().exists())
        assertTrue(f.submitted.isEmpty())
        f.api.gate.complete(Unit)
        f.controller.resume(f.messageId)
        awaitState { f.submitted.isNotEmpty() }
        assertEquals(f.attachment.size, f.api.offset)
        assertEquals(1, f.submitted.size)
    }

    @Test
    fun changedBackendRetainsFilesWithoutSubmittingToTheOtherBackend() {
        val f = Fixture()
        f.start()
        f.api.backend = "another-backend"
        f.api.gate.complete(Unit)
        awaitState { f.state.pendingMessages[f.messageId]?.state == "failed" }
        assertTrue(f.submitted.isEmpty())
        assertTrue(f.localFile().exists())
    }
}
