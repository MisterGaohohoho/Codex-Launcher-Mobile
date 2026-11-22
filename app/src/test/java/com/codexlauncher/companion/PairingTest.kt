@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codexlauncher.companion

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingTest {
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
        @Volatile var value: PairedComputer? = null
        val loads = AtomicInteger()
        val saves = AtomicInteger()

        override fun load(): PairedComputer? {
            loads.incrementAndGet()
            return value
        }

        override fun save(value: PairedComputer) {
            this.value = value
            saves.incrementAndGet()
        }

        override fun clear() {
            value = null
        }

        override var theme = "system"
    }

    private class FakeApi : CompanionApi {
        val pairs = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        var offline = false
        var failPair = false
        var failClose = false
        @Volatile var closedOnMain: Boolean? = null
        var reply: CompletableDeferred<PairResponse>? = null

        override suspend fun pair(code: PairingCode, name: String): PairResponse {
            pairs.incrementAndGet()
            if (failPair) throw IOException("fixture pairing failed")
            return reply?.await() ?: paired()
        }

        override suspend fun snapshot(): Snapshot {
            reads.incrementAndGet()
            if (offline) throw IOException("fixture offline")
            return Snapshot(
                1,
                "2026-11-20T00:00:00Z",
                "Fixture",
                "2.7.0",
                configuration = RunConfiguration(startAvailable = true),
            )
        }

        override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
            error("Unused")

        override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

        override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

        override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse = error("Unused")

        override fun close() {
            closedOnMain = Looper.myLooper() == Looper.getMainLooper()
            closes.incrementAndGet()
            if (failClose) throw IOException("fixture TLS close failure")
        }
    }

    private fun model(
        store: MemoryStore,
        pairing: FakeApi,
        authenticated: FakeApi,
    ): CompanionViewModel {
        val model =
            CompanionViewModel(ApplicationProvider.getApplicationContext<Application>(), store) {
                _,
                _,
                token ->
                if (token == null) pairing else authenticated
            }
        models.put("fixture", model)
        await { store.loads.get() == 1 }
        return model
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5000) { while (!condition()) delay(5) }
    }

    private fun code() =
        WireJson.encodeToString(
            PairingCode(
                "codex-launcher-pair",
                1,
                "https://192.168.1.2:39173",
                "a".repeat(64),
                "b".repeat(64),
                "2030-01-01T00:00:00Z",
                "Fixture",
            )
        )

    @Test
    fun successfulPairingSurvivesTlsCleanupFailureAndNeverClosesOnMain() {
        val store = MemoryStore()
        val candidate = FakeApi().apply { failClose = true }
        val remote = FakeApi()
        val model = model(store, candidate, remote)
        model.setForeground(true)
        model.pair(code())
        await { candidate.closes.get() == 1 && model.state.value.online }
        assertEquals(false, candidate.closedOnMain)
        assertEquals("paired-device", model.state.value.computer?.deviceId)
        assertEquals(store.value, model.state.value.computer)
        assertEquals(1, store.saves.get())
        assertEquals(0, remote.closes.get())
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.pairingError)
    }

    @Test
    fun firstSyncFailurePreservesAuthorizationAndRecoversWithoutPairingAgain() {
        val store = MemoryStore()
        val candidate = FakeApi()
        val remote = FakeApi().apply { offline = true }
        val model = model(store, candidate, remote)
        model.setForeground(true)
        model.pair(code())
        await { candidate.closes.get() == 1 && model.state.value.connectionError != null }
        assertNotNull(model.state.value.computer)
        assertEquals(store.value, model.state.value.computer)
        assertNull(model.state.value.pairingError)
        assertFalse(model.state.value.online)
        remote.offline = false
        dispatcher.scheduler.advanceTimeBy(2001)
        assertTrue(model.state.value.online)
        assertNull(model.state.value.connectionError)
        assertEquals(1, candidate.pairs.get())
        assertEquals(1, store.saves.get())
    }

    @Test
    fun duplicatePairingAndBackgroundReturnDoNotRepeatTheRequestOrPolling() {
        val store = MemoryStore()
        val candidate = FakeApi().apply { reply = CompletableDeferred() }
        val remote = FakeApi()
        val model = model(store, candidate, remote)
        model.setForeground(true)
        model.pair(code())
        await { candidate.pairs.get() == 1 }
        assertTrue(model.state.value.pairing)
        model.pair(code())
        assertEquals(1, candidate.pairs.get())
        model.setForeground(false)
        candidate.reply!!.complete(paired())
        await { candidate.closes.get() == 1 }
        assertNotNull(store.value)
        assertEquals(0, remote.reads.get())
        assertFalse(model.state.value.online)
        model.setForeground(true)
        model.setForeground(true)
        assertEquals(1, remote.reads.get())
        dispatcher.scheduler.advanceTimeBy(2001)
        assertEquals(2, remote.reads.get())
        assertTrue(model.state.value.online)
    }

    @Test
    fun failedReplacementKeepsThePreviousComputerAndDoesNotCloseItsClient() {
        val store =
            MemoryStore().apply {
                value =
                    PairedComputer(
                        "https://192.168.1.2:39173",
                        "a".repeat(64),
                        "previous",
                        "c".repeat(64),
                        "Previous",
                        true,
                        "2030-01-01T00:00:00Z",
                    )
            }
        val candidate =
            FakeApi().apply {
                failPair = true
                failClose = true
            }
        val remote = FakeApi()
        val model = model(store, candidate, remote)
        await { model.state.value.computer != null }
        model.setForeground(true)
        model.pair(code())
        await { candidate.closes.get() == 1 }
        assertEquals("previous", model.state.value.computer?.deviceId)
        assertEquals("previous", store.value?.deviceId)
        assertEquals(0, store.saves.get())
        assertEquals(0, remote.closes.get())
        assertNotNull(model.state.value.pairingError)
        assertFalse(model.state.value.busy)
    }

    companion object {
        private fun paired() =
            PairResponse(
                "paired-device",
                "d".repeat(64),
                "Fixture",
                true,
                "2030-01-01T00:00:00Z",
                1,
            )
    }
}
