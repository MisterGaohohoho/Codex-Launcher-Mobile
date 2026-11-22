@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codexlauncher.companion

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkbenchLifecycleTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After
    fun after() {
        models.clear()
        Dispatchers.resetMain()
    }

    private class MemoryStore : SessionStore {
        private var value: PairedComputer? =
            PairedComputer(
                "https://127.0.0.1:39173",
                "a".repeat(64),
                "fixture",
                "b".repeat(64),
                "Fixture",
                true,
                "2030-01-01T00:00:00Z",
            )

        override fun load() = value

        override fun save(value: PairedComputer) {
            this.value = value
        }

        override fun clear() {
            value = null
        }

        override var theme = "system"
    }

    private class FakeApi : CompanionApi {
        val stream = MutableSharedFlow<StreamBatch>(extraBufferCapacity = 10)
        val requests = mutableListOf<AdminCommand>()
        val receipts = mutableMapOf<String, JsonObject>()
        var executions = 0
        var loseFirstReply = false
        var historyGate: CompletableDeferred<Unit>? = null
        var commandGate: CompletableDeferred<Unit>? = null
        var threadHistory: JsonObject? = null

        override suspend fun pair(code: PairingCode, name: String): PairResponse = error("Unused")

        override suspend fun snapshot() =
            Snapshot(
                1,
                "2026-11-22T00:00:00Z",
                "Fixture",
                "2.8.0",
                capabilities = listOf("workbench", "configuration", "admin", "events"),
                commandEpoch = "pc-process-1",
            )

        override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
            error("Unused")

        override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

        override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

        override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse = error("Unused")

        override fun events(epoch: String, since: Long) = stream

        override suspend fun query(resource: String, input: JsonObject): JsonObject =
            when (resource) {
                "workbench-capabilities" ->
                    buildJsonObject {
                        put("connected", true)
                        put("settings", buildJsonObject { put("workDir", "C:/Projects/fixture") })
                    }
                "workbench-threads" -> buildJsonObject { put("data", JsonArray(emptyList())) }
                "configuration" ->
                    buildJsonObject {
                        put("revision", "r1")
                        put("value", buildJsonObject { put("keepAlive", true) })
                    }
                "workbench-thread" -> {
                    historyGate?.let { withContext(NonCancellable) { it.await() } }
                    threadHistory
                        ?: buildJsonObject {
                            put("control", "managed")
                            put(
                                "thread",
                                buildJsonObject {
                                    put("id", input["threadId"]!!)
                                    put("turns", JsonArray(emptyList()))
                                },
                            )
                        }
                }
                else -> JsonObject(emptyMap())
            }

        override suspend fun command(request: AdminCommand): JsonObject {
            requests += request
            val receipt =
                receipts.getOrPut(request.requestId) {
                    executions++
                    buildJsonObject {
                        put("message", "done")
                        if (request.action == "workbench.create")
                            put("thread", buildJsonObject { put("id", "created-fixture") })
                        if (request.action == "workbench.fork")
                            put("thread", buildJsonObject { put("id", "fork-fixture") })
                        if (request.action == "workbench.send")
                            put("turn", buildJsonObject { put("id", "turn-fixture") })
                        if (request.action == "workbench.steer") put("turnId", "turn-fixture")
                    }
                }
            commandGate?.await()
            if (loseFirstReply && requests.size == 1) throw IOException("reply lost after commit")
            return receipt
        }

        override fun close() = Unit
    }

    private fun model(api: FakeApi): CompanionViewModel {
        val model =
            CompanionViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                MemoryStore(),
            ) { _, _, _ ->
                api
            }
        models.put("fixture", model)
        runBlocking { withTimeout(3000) { while (model.state.value.computer == null) delay(5) } }
        model.setForeground(true)
        return model
    }

    @Test
    fun lateHistoryDoesNotReopenAClosedThreadAndBackgroundClearsStreaming() = runBlocking {
        val api = FakeApi()
        val model = model(api)
        api.stream.emit(StreamBatch(epoch = "connection", connected = true))
        assertTrue(model.state.value.workbench.streaming)
        api.historyGate = CompletableDeferred()
        model.openThread("fixture")
        model.closeThread()
        api.historyGate!!.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertNull(model.state.value.workbench.detail)
        model.setForeground(false)
        assertFalse(model.state.value.workbench.streaming)
        assertFalse(model.state.value.online)
    }

    @Test
    fun uncertainMutationReusesItsReceiptAndIsNeverAutomaticallyRepeated() {
        val api = FakeApi()
        api.loseFirstReply = true
        val model = model(api)
        model.adminCommand(
            "manager",
            buildJsonObject {
                put("operation", "providers.activate")
                put("input", buildJsonObject { put("id", "fixture") })
            },
        )
        assertNotNull(model.state.value.uncertainCommand)
        assertEquals(1, api.executions)
        model.adminCommand("workbench.create", JsonObject(emptyMap()))
        assertEquals(1, api.requests.size)
        dispatcher.scheduler.advanceTimeBy(2001)
        assertEquals(1, api.requests.size)
        model.retryUncertainCommand()
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0].requestId, api.requests[1].requestId)
        assertEquals("pc-process-1", api.requests[1].commandEpoch)
        assertEquals(api.requests[0].issuedAt, api.requests[1].issuedAt)
        assertEquals(1, api.executions)
        assertNull(model.state.value.uncertainCommand)
        model.setForeground(false)
        model.adminCommand("manager", JsonObject(emptyMap()))
        assertEquals(2, api.requests.size)
    }

    @Test
    fun retryingCreationOpensTheOriginalSessionWithoutCreatingAnother() {
        val api = FakeApi()
        api.loseFirstReply = true
        val model = model(api)
        model.workbenchAction("create", buildJsonObject { put("cwd", "C:/Projects/fixture") })
        assertNull(model.state.value.workbench.detail)
        model.retryUncertainCommand()
        assertEquals(1, api.executions)
        assertEquals("created-fixture", model.state.value.workbench.detail?.thread?.id)
    }

    private fun json(value: String) = WireJson.parseToJsonElement(value).obj()

    @Test
    fun forkMessageIsVisibleDuringCompactionBeforeItsCommandReplyAndAfterReopening() = runBlocking {
        val api = FakeApi()
        val model = model(api)
        model.workbenchAction("fork", buildJsonObject { put("threadId", "original") })
        assertEquals("fork-fixture", model.state.value.workbench.detail?.thread?.id)
        api.commandGate = CompletableDeferred()
        model.workbenchAction(
            "send",
            buildJsonObject {
                put("threadId", "fork-fixture")
                put("messageId", "client-fixture")
                put("text", "Continue the fork")
            },
        )
        assertTrue(model.state.value.busy)
        assertEquals("sending", model.state.value.workbench.detail?.items?.single()?.status)
        api.stream.emit(
            StreamBatch(
                events =
                    listOf(
                        json(
                            """{"method":"turn/started","params":{"threadId":"fork-fixture","turn":{"id":"turn-fixture","items":[]}}}"""
                        ),
                        json(
                            """{"method":"item/started","params":{"threadId":"fork-fixture","turnId":"turn-fixture","item":{"id":"compact","type":"contextCompaction"}}}"""
                        ),
                    )
            )
        )
        assertEquals("Continue the fork", model.state.value.workbench.detail?.items?.first()?.text)
        assertEquals("inProgress", model.state.value.workbench.detail?.items?.last()?.status)

        api.threadHistory =
            json(
                """{"thread":{"id":"fork-fixture","status":{"type":"active"}},"control":"managed","activeTurnId":"turn-fixture","turnsPage":{"data":[]}}"""
            )
        api.commandGate!!.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertFalse(model.state.value.busy)
        val sent = model.state.value.workbench.detail!!.items.single { it.kind == "userMessage" }
        assertEquals("sent", sent.status)
        assertEquals("turn-fixture", sent.turnId)
        model.closeThread()
        model.setForeground(false)
        model.setForeground(true)
        model.openThread("fork-fixture")
        assertEquals(
            sent,
            model.state.value.workbench.detail!!.items.single { it.kind == "userMessage" },
        )

        api.stream.emit(
            StreamBatch(
                events =
                    listOf(
                        json(
                            """{"method":"item/completed","params":{"threadId":"fork-fixture","turnId":"turn-fixture","item":{"id":"server-fixture","clientId":"client-fixture","type":"userMessage","content":[{"type":"text","text":"Continue the fork"}]}}}"""
                        )
                    )
            )
        )
        assertEquals("server-fixture", model.state.value.workbench.detail!!.items.single().id)
        assertFalse(model.state.value.workbench.detail!!.items.single().localEcho)
    }

    @Test
    fun lostSendReplyKeepsOneVisibleMessageAndRetryUsesTheSameReceipt() {
        val api = FakeApi()
        api.loseFirstReply = true
        val model = model(api)
        model.openThread("fixture")
        model.workbenchAction(
            "send",
            buildJsonObject {
                put("threadId", "fixture")
                put("messageId", "client-fixture")
                put("text", "Please continue")
            },
        )
        assertEquals("unconfirmed", model.state.value.workbench.detail!!.items.single().status)
        assertNotNull(model.state.value.uncertainCommand)
        model.closeThread()
        model.openThread("fixture")
        assertEquals("Please continue", model.state.value.workbench.detail!!.items.single().text)
        assertEquals(1, api.requests.size)
        model.retryUncertainCommand()
        assertEquals(1, api.executions)
        assertEquals(api.requests.first(), api.requests.last())
        assertEquals("sent", model.state.value.workbench.detail!!.items.single().status)
        assertNull(model.state.value.uncertainCommand)
    }
}
