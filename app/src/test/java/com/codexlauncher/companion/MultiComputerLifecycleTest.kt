@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codexlauncher.companion

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.*
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
class MultiComputerLifecycleTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    private val first =
        PairedComputer(
            "https://192.168.1.2:39173",
            "1".repeat(64),
            "phone-a",
            "3".repeat(64),
            "Studio PC",
            true,
            "2030-01-01T00:00:00Z",
        )
    private val second =
        first.copy(
            certificateSha256 = "2".repeat(64),
            deviceId = "phone-b",
            computerName = "Travel PC",
        )
    private val a = Pc(first)
    private val b = Pc(second)
    private val store =
        MemoryStore(ComputerProfiles().upsert(first).upsert(second).select(first.id))

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After
    fun after() {
        a.historyGate?.complete(Unit)
        b.historyGate?.complete(Unit)
        a.commandGate?.complete(Unit)
        b.commandGate?.complete(Unit)
        models.clear()
        Dispatchers.resetMain()
    }

    private class MemoryStore(var value: ComputerProfiles) : SessionStore {
        var failSave = false

        override fun load() = value.active

        override fun save(value: PairedComputer) {
            saveProfiles(this.value.upsert(value))
        }

        override fun clear() {
            saveProfiles(value.activeId?.let(value::remove) ?: value)
        }

        override fun loadProfiles() = value

        override fun saveProfiles(value: ComputerProfiles) {
            if (failSave) throw IOException("fixture storage unavailable")
            this.value = value
        }

        override var theme = "system"
    }

    private class Pc(val computer: PairedComputer) {
        var scope = "scope-${computer.deviceId}"
        var historyGate: CompletableDeferred<Unit>? = null
        var commandGate: CompletableDeferred<Unit>? = null
        var unavailable = false
        var loseReply = false
        var reads = 0
        var closes = 0
        val requests = mutableListOf<AdminCommand>()

        fun client() =
            object : CompanionApi {
                override suspend fun pair(code: PairingCode, name: String): PairResponse =
                    error("Unused")

                override suspend fun snapshot(): Snapshot {
                    if (unavailable) throw IOException("fixture unreachable")
                    return Snapshot(
                        1,
                        "2026-11-23T00:00:00Z",
                        computer.computerName,
                        "2.8.0",
                        capabilities = listOf("workbench"),
                        commandEpoch = computer.deviceId,
                    )
                }

                override suspend fun details(
                    run: String,
                    worker: Int,
                    attempt: Long,
                ): WorkerDetails = error("Unused")

                override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

                override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

                override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse =
                    error("Unused")

                override suspend fun query(resource: String, input: JsonObject): JsonObject =
                    when (resource) {
                        "workbench-capabilities" ->
                            buildJsonObject {
                                put("backendScopeId", scope)
                                put("connected", true)
                            }
                        "workbench-threads",
                        "workbench-models" ->
                            buildJsonObject { put("data", JsonArray(emptyList())) }
                        "configuration" ->
                            buildJsonObject {
                                put("revision", computer.deviceId)
                                put("value", buildJsonObject { put("name", computer.computerName) })
                            }
                        "workbench-thread" -> {
                            reads++
                            val readScope = scope
                            historyGate?.let { withContext(NonCancellable) { it.await() } }
                            buildJsonObject {
                                put("control", "managed")
                                put("backendScopeId", readScope)
                                putJsonObject("thread") {
                                    put("id", input["threadId"]!!)
                                    put("name", computer.computerName)
                                    putJsonArray("turns") {
                                        add(
                                            buildJsonObject {
                                                put("id", "turn-$readScope")
                                                putJsonArray("items") {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "agentMessage")
                                                            put("id", "reply-$readScope")
                                                            put("text", computer.computerName)
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        else -> JsonObject(emptyMap())
                    }

                override suspend fun command(request: AdminCommand): JsonObject {
                    requests += request
                    commandGate?.await()
                    if (loseReply) throw IOException("fixture reply lost")
                    return buildJsonObject { put("message", "done") }
                }

                override fun close() {
                    closes++
                }
            }
    }

    private fun model(): CompanionViewModel {
        val model =
            CompanionViewModel(ApplicationProvider.getApplicationContext<Application>(), store) {
                _,
                pin,
                _ ->
                (if (pin == first.id) a else b).client()
            }
        models.put("fixture", model)
        awaitState(model) { it.computer != null }
        model.setForeground(true)
        awaitState(model) { it.online && it.workbench.capabilities != null }
        return model
    }

    private fun awaitState(model: CompanionViewModel, condition: (CompanionState) -> Boolean) =
        runBlocking {
            withTimeout(5000) {
                while (!condition(model.state.value)) {
                    dispatcher.scheduler.runCurrent()
                    delay(5)
                }
            }
        }

    private fun select(model: CompanionViewModel, computer: PairedComputer) {
        model.selectComputer(computer.id)
        awaitState(model) {
            it.computer?.id == computer.id &&
                !it.busy &&
                it.online &&
                it.workbench.capabilities != null
        }
    }

    private fun open(model: CompanionViewModel) {
        model.openThread("same-session-id")
        awaitState(model) { it.workbench.detail != null && !it.workbench.detailLoading }
    }

    @Test
    fun lateHistoryFromThePreviousPcCannotOverwriteTheSelectedPc() {
        val model = model()
        val gate = CompletableDeferred<Unit>()
        a.historyGate = gate
        model.openThread("same-session-id")
        assertTrue(model.state.value.workbench.detailLoading)
        select(model, second)
        open(model)
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(second.computerName, model.state.value.workbench.detail!!.thread.title)
        assertEquals(b.scope, model.state.value.workbench.detail!!.backendScopeId)
        assertNull(model.state.value.workbench.error)
        model.closeThread()
        assertNull(model.state.value.workbench.openingThreadId)
        assertNull(model.state.value.workbench.detail)
        assertFalse(model.state.value.workbench.detailLoading)
    }

    @Test
    fun switchingRestoresIndependentCachedConversationsAndDrafts() {
        val model = model()
        open(model)
        val targetA = model.state.value.workbench.detail!!.draftTarget(first)
        model.editDraft(targetA, "Draft for Studio")
        dispatcher.scheduler.advanceTimeBy(121)
        awaitState(model) { it.attachmentDrafts[targetA.key]?.text == "Draft for Studio" }
        select(model, second)
        assertNull(model.state.value.workbench.detail)
        open(model)
        val targetB = model.state.value.workbench.detail!!.draftTarget(second)
        model.editDraft(targetB, "Draft for Travel")
        dispatcher.scheduler.advanceTimeBy(121)
        awaitState(model) { it.attachmentDrafts[targetB.key]?.text == "Draft for Travel" }
        a.historyGate = CompletableDeferred()
        select(model, first)
        assertEquals(first.computerName, model.state.value.workbench.detail!!.thread.title)
        assertEquals("Draft for Studio", model.state.value.attachmentDrafts[targetA.key]!!.text)
        assertEquals("Draft for Travel", model.state.value.attachmentDrafts[targetB.key]!!.text)
        assertEquals(first.id, store.value.activeId)
    }

    @Test
    fun controlSubmissionBlocksSwitchingAndLaterCommandsOnlyReachTheSelectedPc() {
        val model = model()
        a.commandGate = CompletableDeferred()
        val input = buildJsonObject {
            put("threadId", "same-session-id")
            put("name", "Renamed")
        }
        model.workbenchAction("rename", input)
        assertTrue(model.state.value.busy)
        model.selectComputer(second.id)
        assertEquals(first.id, model.state.value.computer!!.id)
        assertTrue(b.requests.isEmpty())
        a.commandGate!!.complete(Unit)
        awaitState(model) { !it.busy }
        select(model, second)
        model.workbenchAction("rename", input)
        awaitState(model) { !it.busy }
        assertEquals(1, a.requests.size)
        assertEquals(1, b.requests.size)
        assertEquals(second.deviceId, b.requests.single().commandEpoch)
    }

    @Test
    fun uncertainCommandsStayWithTheirPcAndAreNeverReplayedOnSwitch() {
        val model = model()
        a.loseReply = true
        model.workbenchAction(
            "rename",
            buildJsonObject {
                put("threadId", "same-session-id")
                put("name", "Renamed")
            },
        )
        awaitState(model) { !it.busy && it.uncertainCommand != null }
        val request = model.state.value.uncertainCommand!!
        select(model, second)
        assertNull(model.state.value.uncertainCommand)
        select(model, first)
        assertEquals(request, model.state.value.uncertainCommand)
        assertEquals(1, a.requests.size)
        assertTrue(b.requests.isEmpty())
        a.loseReply = false
        model.retryUncertainCommand()
        awaitState(model) { !it.busy && it.uncertainCommand == null }
        assertEquals(listOf(request.requestId, request.requestId), a.requests.map { it.requestId })
    }

    @Test
    fun failedProfilePersistenceKeepsThePreviousPcConnectionUsable() {
        val model = model()
        store.failSave = true
        model.selectComputer(second.id)
        awaitState(model) { !it.busy && it.notice != null }
        assertEquals(first.id, model.state.value.computer!!.id)
        assertEquals(first.id, store.value.activeId)
        assertEquals(0, a.closes)
        model.workbenchAction(
            "rename",
            buildJsonObject {
                put("threadId", "same-session-id")
                put("name", "Working")
            },
        )
        assertEquals(1, a.requests.size)
        assertTrue(b.requests.isEmpty())
    }

    @Test
    fun anUnreachableOtherPcDoesNotDisconnectTheCurrentPc() {
        val model = model()
        b.unavailable = true
        model.refreshComputers()
        awaitState(model) { it.computerStatuses[second.id]?.error != null }
        assertEquals(first.id, model.state.value.computer!!.id)
        assertTrue(model.state.value.online)
        assertNull(model.state.value.connectionError)
    }

    @Test
    fun changingTheBackendDoesNotMergeCachedHistoryFromThePreviousScope() {
        val model = model()
        open(model)
        select(model, second)
        a.scope = "new-backend"
        select(model, first)
        assertNull(model.state.value.workbench.detail)
        assertNull(model.state.value.workbench.openingThreadId)
        open(model)
        assertEquals("new-backend", model.state.value.workbench.detail!!.backendScopeId)
        assertEquals(
            listOf("reply-new-backend"),
            model.state.value.workbench.detail!!.items.map { it.id },
        )
    }
}
