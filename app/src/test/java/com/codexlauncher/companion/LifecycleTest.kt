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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifecycleTest {
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
        var value: PairedComputer? =
            PairedComputer(
                "https://192.168.1.2:39173",
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
        var reads = 0
        var starts = 0
        var stops = 0
        var offline = false
        var uncertainStart = false
        var run: RunSummary? = null

        override suspend fun pair(code: PairingCode, name: String): PairResponse = error("Unused")

        override suspend fun snapshot(): Snapshot {
            reads++
            if (offline) throw IOException("fixture connection lost")
            return Snapshot(
                1,
                "2026-11-20T00:00:00Z",
                "Fixture",
                "2.6.0",
                run,
                RunConfiguration(startAvailable = true),
            )
        }

        override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
            error("Unused")

        override suspend fun start(request: StartRequest): ActionResponse {
            starts++
            run =
                RunSummary(
                    "new-run",
                    "running",
                    "retry",
                    1,
                    0,
                    1,
                    1,
                    "2026-11-20T00:00:00Z",
                    "2026-11-20T00:00:00Z",
                    0,
                    true,
                )
            if (uncertainStart) throw IOException("fixture response lost after starting")
            return ActionResponse("new-run", "started")
        }

        override suspend fun stopRun(request: StopRequest): ActionResponse {
            stops++
            return ActionResponse(request.runId, "stopped")
        }

        override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse =
            ActionResponse(request.runId, "updated")

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
        // Keystore/storage IO remains off Main in production; allow that one
        // asynchronous initialization to return before advancing virtual timers.
        runBlocking { withTimeout(3000) { while (model.state.value.computer == null) delay(5) } }
        return model
    }

    @Test
    fun backgroundStopsPollingAndReconnectRestoresFreshState() {
        val api = FakeApi()
        val model = model(api)
        model.setForeground(true)
        assertEquals(1, api.reads)
        dispatcher.scheduler.advanceTimeBy(2001)
        assertEquals(2, api.reads)
        model.setForeground(false)
        dispatcher.scheduler.advanceTimeBy(10000)
        assertEquals(2, api.reads)
        assertFalse(model.state.value.online)
        api.offline = true
        model.setForeground(true)
        assertFalse(model.state.value.online)
        assertNotNull(model.state.value.snapshot)
        api.offline = false
        dispatcher.scheduler.advanceTimeBy(2001)
        assertTrue(model.state.value.online)
        model.setForeground(false)
    }

    @Test
    fun uncertainWriteIsNotResentAndStaleConfirmationCannotStopNewRun() {
        val api = FakeApi()
        val model = model(api)
        model.setForeground(true)
        api.uncertainStart = true
        model.start(null)
        assertEquals(1, api.starts)
        assertEquals("new-run", model.state.value.snapshot?.status?.runId)
        assertTrue(model.state.value.notice!!.contains("未确认操作结果"))
        model.stop("previous-run")
        assertEquals(0, api.stops)
        model.stop("new-run")
        assertEquals(1, api.stops)
        model.setForeground(false)
    }
}
