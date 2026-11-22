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
                    buildJsonObject {
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
                    }
                }
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
}
