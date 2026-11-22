@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.codexlauncher.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.R
import com.codexlauncher.companion.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class LauncherActions(
    val refresh: () -> Unit = {},
    val start: (String?) -> Unit = {},
    val stop: (String) -> Unit = {},
    val keepAlive: (Boolean?, String?, String?) -> Unit = { _, _, _ -> },
    val theme: (String) -> Unit = {},
    val pair: (String) -> Unit = {},
    val forget: () -> Unit = {},
    val scan: () -> Unit = {},
    val openDetails: (WorkerSummary) -> Unit = {},
    val closeDetails: () -> Unit = {},
    val refreshDetails: () -> Unit = {},
    val consumeNotice: () -> Unit = {},
    val loadWorkbench: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    val openThread: (String) -> Unit = {},
    val closeThread: () -> Unit = {},
    val olderMessages: () -> Unit = {},
    val workbenchAction: (String, JsonObject) -> Unit = { _, _ -> },
    val loadConfiguration: () -> Unit = {},
    val saveConfiguration: (String, JsonObject) -> Unit = { _, _ -> },
    val adminQuery: (String, JsonObject) -> Unit = { _, _ -> },
    val adminCommand: (String, JsonObject) -> Unit = { _, _ -> },
    val retryCommand: () -> Unit = {},
    val checkUpdate: () -> Unit = {},
    val downloadUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
)

internal val destinations =
    listOf(
        "总览" to Icons.Outlined.SpaceDashboard,
        "工作台" to Icons.Outlined.ChatBubbleOutline,
        "运行" to Icons.Outlined.Tune,
        "设置" to Icons.Outlined.Settings,
    )

data class ConfirmAction(val title: String, val message: String, val action: () -> Unit)

@Composable
fun LauncherScreen(
    state: CompanionState,
    now: Long,
    cameraError: String? = null,
    actions: LauncherActions = LauncherActions(),
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var subpage by rememberSaveable { mutableStateOf("") }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var handledPairing by rememberSaveable { mutableLongStateOf(state.pairingGeneration) }
    var confirmation by remember { mutableStateOf<ConfirmAction?>(null) }
    var newThread by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val savedPages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val connected = state.computer != null
    val showPairing = !connected || pairing || state.pairing
    val fresh = state.fresh(now)
    val canControl = state.canControl(now)
    val showThread = page == 1 && state.workbench.detail != null && subpage.isEmpty()
    val navigate: (Int) -> Unit = { next ->
        page = next
        subpage = ""
        if (next == 1)
            actions.loadWorkbench(state.workbench.search, state.workbench.archived, false)
    }
    val settingsPage: (String) -> Unit = { target ->
        subpage = target
        when (target) {
            "configuration" -> actions.loadConfiguration()
            "workbench-settings" -> actions.adminQuery("workbench-settings", JsonObject(emptyMap()))
            "usage" -> actions.adminQuery("usage", buildJsonObject { put("limit", 30) })
            "providers" ->
                actions.adminQuery(
                    "manager",
                    buildJsonObject { put("operation", "providers.list") },
                )
            "credentials",
            "billing",
            "schedule" -> actions.adminQuery(target, JsonObject(emptyMap()))
            "maintenance" -> actions.adminQuery("maintenance", buildJsonObject { put("limit", 50) })
        }
    }
    val goBack = {
        if (showPairing && connected) pairing = false
        else if (subpage.isNotEmpty()) subpage = ""
        else if (showThread) actions.closeThread() else page = 0
    }
    LaunchedEffect(state.pairingGeneration) {
        if (handledPairing != state.pairingGeneration) {
            handledPairing = state.pairingGeneration
            if (connected) {
                pairing = false
                page = 0
                subpage = ""
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            actions.consumeNotice()
        }
    }
    LaunchedEffect(drawer.isOpen) {
        if (drawer.isOpen)
            actions.loadWorkbench(state.workbench.search, state.workbench.archived, false)
    }
    BackHandler(
        drawer.isOpen || (pairing && connected) || subpage.isNotEmpty() || showThread || page != 0,
        onBack = { if (drawer.isOpen) scope.launch { drawer.close() } else goBack() },
    )
    val closeDrawer = {
        scope.launch { drawer.close() }
        Unit
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = connected && !showPairing && subpage.isEmpty(),
        drawerContent = {
            LauncherSidebar(
                state,
                page,
                canControl,
                actions,
                closeDrawer,
                navigate = {
                    closeDrawer()
                    navigate(it)
                },
                openThread = {
                    closeDrawer()
                    page = 1
                    subpage = ""
                    actions.openThread(it)
                },
                create = {
                    closeDrawer()
                    page = 1
                    subpage = ""
                    newThread = true
                },
                connectionSettings = {
                    closeDrawer()
                    settingsPage("workbench-settings")
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("launcher-screen"),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (showPairing) "Launcher"
                                else
                                    when (subpage) {
                                        "configuration" -> "PC 配置"
                                        "workbench-settings" -> "工作台连接"
                                        "usage" -> "用量统计"
                                        "providers" -> "供应商"
                                        "credentials" -> "凭据管理"
                                        "billing" -> "计费管理"
                                        "schedule" -> "计划任务"
                                        "maintenance" -> "会话维护"
                                        "updates" -> "应用更新"
                                        else ->
                                            if (showThread)
                                                state.workbench.detail?.thread?.title.orEmpty()
                                            else destinations[page].first
                                    },
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (showPairing) "你的移动工作台"
                                else if (showThread)
                                    state.workbench.detail?.thread?.project.orEmpty()
                                else
                                    state.computer?.computerName.orEmpty() +
                                        if (fresh) " · 在线" else " · 离线",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        if ((pairing && connected) || subpage.isNotEmpty())
                            CircleAction(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                "返回",
                                goBack,
                                Modifier.padding(start = 4.dp),
                            )
                        else if (connected && !showPairing)
                            CircleAction(
                                Icons.Outlined.Menu,
                                "打开会话侧栏",
                                { scope.launch { drawer.open() } },
                                Modifier.padding(start = 4.dp).testTag("open-sidebar"),
                            )
                    },
                    actions = {
                        if (connected && !showPairing) {
                            if (page == 1 && subpage.isEmpty())
                                CircleAction(
                                    Icons.Outlined.Create,
                                    "新会话",
                                    { newThread = true },
                                    Modifier.padding(end = 4.dp).testTag("toolbar-new-thread"),
                                    enabled = canControl,
                                )
                            else
                                CircleAction(
                                    Icons.Outlined.Refresh,
                                    "刷新电脑状态",
                                    actions.refresh,
                                    Modifier.padding(end = 4.dp),
                                    enabled = !state.busy,
                                )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
            },
            bottomBar = {
                if (connected && !showPairing && !showThread && subpage.isEmpty())
                    BottomDock(page, navigate, state.workbench.approvals.size)
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (connected && !showPairing && !fresh)
                    Banner(state.connectionError ?: "正在连接电脑…", error = true)
                if (state.uncertainCommand != null)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "有一项操作尚未确认结果",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = actions.retryCommand, enabled = canControl) {
                            Text("核对结果")
                        }
                    }
                AnimatedContent(
                    targetState =
                        if (showPairing) "pairing"
                        else subpage.ifEmpty { if (showThread) "thread" else "page-$page" },
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 16 })
                            .togetherWith(fadeOut(tween(100)))
                    },
                    label = "pageTransition",
                    modifier = Modifier.weight(1f),
                ) { route ->
                    when (route) {
                        "pairing" -> PairingPage(state, cameraError, actions)
                        "configuration" -> ConfigurationPage(state, canControl, actions)
                        "workbench-settings" -> WorkbenchSettingsPage(state, canControl, actions)
                        "updates" ->
                            LazyColumn(contentPadding = PaddingValues(20.dp)) {
                                item { UpdatePanel(state.update, actions) }
                            }
                        "usage",
                        "providers",
                        "billing",
                        "credentials",
                        "schedule",
                        "maintenance" ->
                            AdminPage(route, state, canControl, actions) { confirmation = it }
                        "thread" ->
                            savedPages.SaveableStateProvider(
                                "thread-" + state.workbench.detail?.thread?.id.orEmpty()
                            ) {
                                WorkbenchDetailPage(
                                    state,
                                    canControl,
                                    actions,
                                    settings = { settingsPage("workbench-settings") },
                                    confirm = { confirmation = it },
                                )
                            }
                        "page-0" ->
                            OverviewPage(state, now, actions, navigate, settingsPage) {
                                confirmation = it
                            }
                        "page-1" ->
                            WorkbenchListPage(
                                state,
                                canControl,
                                actions,
                                { settingsPage("workbench-settings") },
                            )
                        "page-2" ->
                            RunPage(state, now, actions, { settingsPage("configuration") }) {
                                confirmation = it
                            }
                        else ->
                            SettingsPage(state, actions, settingsPage, { pairing = true }) {
                                confirmation = it
                            }
                    }
                }
            }
        }
    }
    if (newThread) NewThreadSheet(state, canControl, actions) { newThread = false }
    confirmation?.let { confirm ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        confirm.action()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
    if (state.detailTarget != null) WorkerDetailSheet(state, actions)
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun StatusLabel(text: String, positive: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color =
            if (positive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (positive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Banner(text: String, error: Boolean = false) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

@Composable
fun PairingPage(state: CompanionState, cameraError: String?, actions: LauncherActions) {
    var text by rememberSaveable { mutableStateOf("") }
    var paste by rememberSaveable { mutableStateOf(false) }
    var updates by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.launcher_artwork),
                    null,
                    Modifier.size(76.dp),
                )
                Text("连接你的电脑", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "运行、配置和开发对话，都在手边。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Panel {
                Text("在电脑的“手机连接”页生成配对码。", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "两端连接同一局域网，外出时使用 Tailscale。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = actions.scan,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("扫码连接电脑")
                }
                if (state.pairing)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在验证并连接电脑…", style = MaterialTheme.typography.bodySmall)
                    }
            }
        }
        (cameraError ?: state.pairingError)?.let { item { Banner(it, true) } }
        item {
            SettingsGroup {
                SettingRow(
                    "粘贴配对内容",
                    icon = Icons.Outlined.ContentPaste,
                    onClick = { paste = !paste },
                )
                AnimatedVisibility(paste) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            text,
                            { if (it.length <= 4096) text = it },
                            label = { Text("电脑复制的配对内容") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            enabled = !state.busy,
                        )
                        Button(
                            onClick = { actions.pair(text) },
                            enabled = text.isNotBlank() && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("验证并配对")
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingRow(
                    "应用更新",
                    BuildConfig.VERSION_NAME,
                    icon = Icons.Outlined.SystemUpdate,
                    onClick = { updates = true },
                )
            }
        }
    }
    if (updates) EditorSheet("应用更新", { updates = false }) { UpdatePanel(state.update, actions) }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

fun runTitle(run: RunSummary?) =
    when {
        run == null -> "准备就绪"
        run.active && run.phase == "keepAlive" -> "保活中"
        run.active -> "正在连接 ANY"
        run.state == "success" -> "任务已完成"
        run.state == "failed" -> "执行失败"
        else -> "已停止"
    }

fun statusTitle(status: String) =
    when (status) {
        "active",
        "running",
        "inProgress" -> "执行中"
        "idle" -> "已就绪"
        "completed",
        "success" -> "已完成"
        "paused" -> "已暂停"
        "cancelled" -> "已取消"
        "ready" -> "等待执行"
        "pausing" -> "正在暂停"
        "cancelling" -> "正在取消"
        "waitingForApproval" -> "等待确认"
        "sending" -> "正在发送"
        "sent" -> "已发送"
        "unconfirmed" -> "待核对"
        "notSent" -> "未发送"
        "retrying" -> "连接重试中"
        "compacting" -> "整理上下文"
        "interrupted" -> "已中断"
        "failed",
        "error",
        "systemError" -> "失败"
        "notLoaded" -> "历史会话"
        else -> status.ifBlank { "等待同步" }
    }

fun timeLabel(value: String): String =
    runCatching {
            DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(value))
        }
        .getOrDefault(value.take(19))

fun durationLabel(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
