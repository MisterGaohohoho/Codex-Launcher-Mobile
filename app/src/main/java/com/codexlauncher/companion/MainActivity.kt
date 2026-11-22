package com.codexlauncher.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import com.codexlauncher.companion.ui.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CompanionApp() }
    }
}

@Composable
fun CompanionApp(model: CompanionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val dark =
        when (state.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    SideEffect {
        (context as? ComponentActivity)?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars =
                !dark
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.setForeground(true)
                Lifecycle.Event.ON_STOP -> model.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        model.setForeground(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            model.setForeground(false)
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var pendingScan by rememberSaveable { mutableStateOf<String?>(null) }
    val scan =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            if (scanning) {
                scanning = false
                pendingScan = result.contents
            }
        }
    // Activity results may arrive before the main screen is resumed. Persist
    // an unconsumed result across recreation, and consume it before pairing.
    LaunchedEffect(pendingScan, lifecycle) {
        val contents = pendingScan ?: return@LaunchedEffect
        lifecycle.lifecycle.withResumed {
            pendingScan = null
            model.pair(contents)
        }
    }
    val launchScan = {
        if (!scanning && !state.busy) {
            scanning = true
            try {
                scan.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                        .setPrompt("扫描电脑“手机连接”页面的配对二维码")
                )
            } catch (_: Exception) {
                scanning = false
                cameraError = "无法打开扫码页面，请重试或粘贴电脑复制的配对内容。"
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScan() else cameraError = "相机权限未开启，也可以在下方粘贴配对内容。"
        }
    LauncherTheme(dark = dark) {
        LauncherScreen(
            state,
            now,
            cameraError,
            LauncherActions(
                refresh = model::refresh,
                start = model::start,
                stop = model::stop,
                keepAlive = { enabled, mode, run -> model.keepAlive(enabled, mode, run) },
                theme = model::theme,
                pair = model::pair,
                forget = model::forget,
                scan = {
                    cameraError = null
                    if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                        launchScan()
                    else permission.launch(Manifest.permission.CAMERA)
                },
                openDetails = model::openDetails,
                closeDetails = model::closeDetails,
                refreshDetails = model::refreshDetails,
                consumeNotice = model::consumeNotice,
                loadWorkbench = { search, archived, more ->
                    model.loadWorkbench(search, archived, more)
                },
                openThread = { model.openThread(it) },
                closeThread = model::closeThread,
                olderMessages = {
                    state.workbench.detail?.thread?.id?.let { model.openThread(it, older = true) }
                },
                workbenchAction = model::workbenchAction,
                loadConfiguration = model::loadConfiguration,
                saveConfiguration = model::saveConfiguration,
                adminQuery = { resource, input -> model.adminQuery(resource, input) },
                adminCommand = { action, input -> model.adminCommand(action, input) },
                retryCommand = model::retryUncertainCommand,
                checkUpdate = model::checkUpdate,
                downloadUpdate = model::downloadUpdate,
                installUpdate = {
                    state.update.downloaded?.let { file ->
                        try {
                            if (!context.packageManager.canRequestPackageInstalls())
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            else
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.updates",
                                                file,
                                            ),
                                            "application/vnd.android.package-archive",
                                        )
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                        } catch (_: Exception) {
                            model.updateInstallError()
                        }
                    }
                },
            ),
        )
    }
}
