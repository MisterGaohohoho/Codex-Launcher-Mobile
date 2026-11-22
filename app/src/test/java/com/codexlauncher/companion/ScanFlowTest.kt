package com.codexlauncher.companion

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.codexlauncher.companion.data.*
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()

    @Before
    fun before() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After
    fun after() {
        models.clear()
    }

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val api = FakeApi()
    private val registry =
        object : ActivityResultRegistry() {
            var scanCode = -1
            var permissionCode = -1
            var scans = 0

            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (contract is ScanContract) {
                    scanCode = requestCode
                    scans++
                } else permissionCode = requestCode
            }
        }

    private class FakeApi : CompanionApi {
        var pairs = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun pair(code: PairingCode, name: String): PairResponse {
            pairs++
            gate?.await()
            return PairResponse(
                "fixture-device",
                "c".repeat(64),
                "Fixture",
                true,
                "2030-01-01T00:00:00Z",
                1,
            )
        }

        override suspend fun snapshot() =
            Snapshot(
                1,
                "2026-11-20T00:00:00Z",
                "Fixture",
                "2.7.0",
                configuration = RunConfiguration(startAvailable = true),
            )

        override suspend fun details(run: String, worker: Int, attempt: Long): WorkerDetails =
            error("Unused")

        override suspend fun start(request: StartRequest): ActionResponse = error("Unused")

        override suspend fun stopRun(request: StopRequest): ActionResponse = error("Unused")

        override suspend fun keepAlive(request: KeepAliveRequest): ActionResponse = error("Unused")

        override fun close() = Unit
    }

    private fun screen(): Pair<StateRestorationTester, CompanionViewModel> {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        val store =
            object : SessionStore {
                var value: PairedComputer? = null

                override fun load() = value

                override fun save(value: PairedComputer) {
                    this.value = value
                }

                override fun clear() {
                    value = null
                }

                override var theme = "system"
            }
        val model = CompanionViewModel(application, store) { _, _, _ -> api }
        models.put("fixture", model)
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                CompanionApp(model)
            }
        }
        return restoration to model
    }

    private fun result(cancelled: Boolean = false) {
        val contents =
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
        compose.runOnIdle {
            registry.dispatchResult(
                registry.scanCode,
                if (cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                if (cancelled) null
                else
                    Intent()
                        .putExtra(Intents.Scan.RESULT, contents)
                        .putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"),
            )
        }
    }

    private fun awaitOnline(model: CompanionViewModel, generation: Long = 1) {
        // Storage returns from Dispatchers.IO through Android's Handler. The
        // Robolectric looper must run too, not only Compose's virtual frames.
        try {
            compose.waitUntil(15000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.state.value.online && model.state.value.pairingGeneration == generation
            }
        } catch (e: ComposeTimeoutException) {
            val state = model.state.value
            throw AssertionError(
                "Pairing stalled: lifecycle=${compose.activity.lifecycle.currentState}, paired=${state.computer != null}, busy=${state.busy}, generation=${state.pairingGeneration}, error=${state.pairingError ?: state.connectionError}",
                e,
            )
        }
    }

    @Test
    fun scanReturnWaitsForForegroundAndSurvivesStateRestorationExactlyOnce() {
        val (restoration, model) = screen()
        api.gate = CompletableDeferred()
        compose.onNodeWithText("扫码连接电脑").performClick()
        assertEquals(1, registry.scans)
        restoration.emulateSavedInstanceStateRestore()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        result()
        assertEquals(0, api.pairs)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { api.pairs == 1 }
        compose.onNodeWithText("正在验证并连接电脑…").assertIsDisplayed()
        compose.runOnIdle { api.gate!!.complete(Unit) }
        awaitOnline(model)
        compose.onNodeWithTag("tab-0").assertIsSelected()
        compose.onNodeWithTag("run-control").assertIsEnabled()
        result()
        assertEquals(1, api.pairs)
        compose.onNodeWithTag("tab-2").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-2").assertIsSelected()
        assertEquals(1, api.pairs)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun cancellingAndPairingTheSameComputerAgainReturnToMonitor() {
        val (_, model) = screen()
        compose.onNodeWithText("扫码连接电脑").performClick()
        result(cancelled = true)
        assertEquals(0, api.pairs)
        compose.onNodeWithText("扫码连接电脑").assertIsEnabled().performClick()
        result()
        awaitOnline(model)
        // Let the transient success snackbar leave before tapping a settings
        // action near the bottom of the content area.
        compose.mainClock.advanceTimeBy(5000)
        compose.waitForIdle()
        compose.onNodeWithTag("tab-3").performClick()
        compose.onNodeWithText("重新配对").performScrollTo().assertIsEnabled().performClick()
        try {
            compose.onNodeWithText("扫码连接电脑").assertExists()
        } catch (e: AssertionError) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
        compose.onNodeWithText("扫码连接电脑").performClick()
        result()
        awaitOnline(model, 2)
        compose.onNodeWithTag("tab-0").assertIsSelected()
        compose.onNodeWithTag("tab-0").assertIsSelected()
        assertEquals(2, api.pairs)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun deniedCameraPermissionKeepsPairingAndPasteAvailable() {
        screen()
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        compose.onNodeWithText("扫码连接电脑").performClick()
        compose.runOnIdle { registry.dispatchResult(registry.permissionCode, false) }
        compose.onNodeWithText("相机权限未开启，也可以在下方粘贴配对内容。").assertIsDisplayed()
        compose.onNodeWithText("电脑复制的配对内容").assertExists()
        assertEquals(0, api.pairs)
        assertEquals(0, registry.scans)
        assertFalse(compose.activity.isFinishing)
    }
}
