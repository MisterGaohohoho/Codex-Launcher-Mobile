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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private val destinations =
    listOf(
        "总览" to Icons.Outlined.SpaceDashboard,
        "工作台" to Icons.Outlined.Forum,
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
    BackHandler(
        (pairing && connected) || subpage.isNotEmpty() || showThread || page != 0,
        onBack = goBack,
    )
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("launcher-screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
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
                                    else -> if (showThread) "开发会话" else destinations[page].first
                                },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (showPairing) "连接你的开发电脑" else state.computer?.computerName.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if ((pairing && connected) || subpage.isNotEmpty() || showThread)
                        IconButton(onClick = goBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                },
                actions = {
                    if (connected && !showPairing) {
                        StatusLabel(if (fresh) "在线" else "离线", fresh)
                        IconButton(onClick = actions.refresh, enabled = !state.busy) {
                            Icon(Icons.Outlined.Refresh, "刷新电脑状态")
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
        bottomBar = {
            if (connected && !showPairing && !showThread)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    destinations.forEachIndexed { index, (title, icon) ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { navigate(index) },
                            icon = {
                                if (index == 1 && state.workbench.approvals.isNotEmpty())
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(state.workbench.approvals.size.toString())
                                            }
                                        }
                                    ) {
                                        Icon(icon, null)
                                    }
                                else Icon(icon, null)
                            },
                            label = { Text(title, maxLines = 1) },
                            modifier = Modifier.testTag("tab-$index"),
                        )
                    }
                }
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
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 16 }).togetherWith(
                        fadeOut(tween(100))
                    )
                },
                label = "pageTransition",
                modifier = Modifier.weight(1f),
            ) { route ->
                when (route) {
                    "pairing" -> PairingPage(state, cameraError, actions)
                    "configuration" -> ConfigurationPage(state, canControl, actions)
                    "workbench-settings" -> WorkbenchSettingsPage(state, canControl, actions)
                    "usage",
                    "providers",
                    "billing",
                    "credentials",
                    "schedule",
                    "maintenance" ->
                        AdminPage(route, state, canControl, actions) { confirmation = it }
                    "thread" ->
                        WorkbenchDetailPage(state, canControl, actions) { confirmation = it }
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { EmptyState(Icons.Outlined.Devices, "把电脑带在身边", "查看运行状态、调整配置，从手机继续你的开发会话。") }
        item {
            Panel {
                Text("连接开发电脑", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "在 PC 的手机连接页生成配对码。两端使用同一局域网，外出时连接 Tailscale。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = actions.scan,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Spacer(Modifier.width(10.dp))
                    Text("扫码连接电脑")
                }
            }
        }
        if (state.pairing)
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("正在验证并连接电脑…")
                }
            }
        (cameraError ?: state.pairingError)?.let { item { Banner(it, true) } }
        item {
            Panel {
                Text("也可以粘贴配对内容", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    text,
                    { if (it.length <= 4096) text = it },
                    label = { Text("电脑复制的配对内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    enabled = !state.busy,
                )
                OutlinedButton(
                    onClick = { actions.pair(text) },
                    enabled = text.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("验证并配对")
                }
            }
        }
        item { UpdatePanel(state.update, actions) }
    }
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
        "interrupted" -> "已中断"
        "failed",
        "error",
        "systemError" -> "失败"
        "notLoaded" -> "历史会话"
        else -> status.ifBlank { "等待同步" }
    }

fun timeLabel(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}
    .getOrDefault(value.take(19))

fun durationLabel(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
