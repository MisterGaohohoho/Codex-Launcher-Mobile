@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.codexlauncher.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*

@Composable
fun OverviewPage(
    state: CompanionState,
    now: Long,
    actions: LauncherActions,
    navigate: (Int) -> Unit,
    settings: (String) -> Unit,
    confirm: (ConfirmAction) -> Unit,
) {
    val run = state.snapshot?.status
    LazyColumn(
        Modifier.fillMaxSize().testTag("monitor-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Text("你的工作台", style = MaterialTheme.typography.headlineMedium)
            Text(
                "电脑上的工作，随时接着做。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Hub, null, Modifier.size(20.dp))
                    Text(
                        "ANY",
                        Modifier.weight(1f).padding(start = 9.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        state.snapshot?.configuration?.provider.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    runTitle(run),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "运行时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            durationLabel(run?.elapsedSeconds ?: 0),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    VerticalDivider(
                        Modifier.height(42.dp).padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "请求次数",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            (run?.attempt ?: 0).toString(),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                RunControl(state, now, actions, confirm)
            }
        }
        if (state.workbench.approvals.isNotEmpty())
            item {
                SettingsGroup {
                    SettingRow(
                        "有 ${state.workbench.approvals.size} 项等待确认",
                        subtitle = "打开工作台处理",
                        icon = Icons.Outlined.PendingActions,
                        onClick = { navigate(1) },
                    )
                }
            }
        item {
            SectionTitle("最近会话", action = { TextButton(onClick = { navigate(1) }) { Text("全部") } })
        }
        if (state.workbench.threads.isEmpty())
            item {
                SettingsGroup {
                    SettingRow(
                        if (state.workbench.loading) "正在读取会话…" else "开始一段开发对话",
                        subtitle = "查看回复、执行进展与代码变更",
                        icon = Icons.Outlined.ChatBubbleOutline,
                        onClick = { navigate(1) },
                    )
                }
            }
        items(state.workbench.threads.take(3), key = { it.id }) { thread ->
            ThreadRow(thread) {
                navigate(1)
                actions.openThread(thread.id)
            }
        }
        item {
            SettingsGroup("快捷管理") {
                SettingRow(
                    "PC 配置",
                    "实时同步",
                    icon = Icons.Outlined.Tune,
                    onClick = { settings("configuration") },
                )
                SettingDivider(50)
                SettingRow(
                    "用量与请求记录",
                    icon = Icons.Outlined.BarChart,
                    onClick = { settings("usage") },
                )
            }
        }
        item {
            Text(
                "断开手机后，任务仍在电脑上继续。",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunControl(
    state: CompanionState,
    now: Long,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    val run = state.snapshot?.status
    val running = run?.active == true
    Button(
        onClick = {
            if (running)
                confirm(
                    ConfirmAction("停止当前任务", "结束电脑当前的 ANY 任务和保活。开发工作台会话继续运行。") {
                        actions.stop(run!!.runId)
                    }
                )
            else actions.start(run?.runId)
        },
        enabled =
            state.canControl(now) &&
                (running || state.snapshot?.configuration?.startAvailable == true),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("run-control"),
    ) {
        Icon(if (running) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, null)
        Spacer(Modifier.width(10.dp))
        Text(if (state.busy) "正在处理…" else if (running) "停止任务" else "启动任务")
    }
}

@Composable
fun RunPage(
    state: CompanionState,
    now: Long,
    actions: LauncherActions,
    configuration: () -> Unit,
    confirm: (ConfirmAction) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val config = state.snapshot?.configuration
    val run = state.snapshot?.status
    val canControl = state.canControl(now)
    Column {
        SegmentedBar(
            listOf("控制", "进程", "日志"),
            tab,
            { tab = it },
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            "run-tab",
        )
        when (tab) {
            0 ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        Panel {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    runTitle(run),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                StatusLabel(
                                    config?.executionMode?.uppercase() ?: "等待同步",
                                    run?.active == true,
                                )
                            }
                            RunControl(state, now, actions, confirm)
                            KeyValue("模型", config?.model?.ifBlank { "PC 未返回模型名称" } ?: "等待同步")
                            KeyValue(
                                "并发进程",
                                "${run?.activeWorkers ?: 0} / ${config?.concurrency ?: 0}",
                            )
                            KeyValue("累计重试", (run?.retryCount ?: 0).toString())
                        }
                    }
                    item {
                        SettingsGroup("保持连接", footer = "这些选项对当前运行立即生效。") {
                            ToggleSetting(
                                "成功后自动保活",
                                config?.keepAliveEnabled == true,
                                enabled = canControl,
                            ) { enabled ->
                                val id = run?.takeIf { it.active }?.runId
                                if (enabled) actions.keepAlive(true, null, id)
                                else
                                    confirm(
                                        ConfirmAction("关闭保活", "当前请求完成后不再安排下一次保活。") {
                                            actions.keepAlive(false, null, id)
                                        }
                                    )
                            }
                            SettingDivider()
                            val choices =
                                listOf("configured" to "原始内容") +
                                    (if (
                                        config?.questionBankAvailable == true &&
                                            config.contentModeSupported
                                    )
                                        listOf("questionBank" to "随机题库")
                                    else emptyList()) +
                                    (if (config?.contentModeSupported == true)
                                        listOf("ascii" to "ASCII 短码")
                                    else emptyList())
                            ChoiceSetting(
                                "保活内容",
                                config?.contentMode.orEmpty(),
                                choices,
                                enabled = canControl,
                            ) { mode ->
                                actions.keepAlive(null, mode, run?.takeIf { it.active }?.runId)
                            }
                            SettingDivider()
                            SettingRow("保活间隔", "${(config?.keepAliveIntervalSeconds ?: 0) / 60} 分钟")
                            run?.nextRequestAt?.let {
                                SettingDivider()
                                SettingRow("下一次请求", timeLabel(it))
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = configuration,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Edit, null)
                            Spacer(Modifier.width(8.dp))
                            Text("编辑完整 PC 配置")
                        }
                    }
                    item {
                        Text(
                            "运行参数保存后用于下次启动；上方保活开关和内容类型可即时调整。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            1 ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { SectionTitle("请求进程", "打开任一进程查看本轮请求与模型回复") }
                    if (state.snapshot?.workers.isNullOrEmpty())
                        item { EmptyState(Icons.Outlined.Dns, "暂无进程", "启动任务后会显示每个进程的实时请求。") }
                    items(state.snapshot?.workers.orEmpty(), key = { it.workerId }) { worker ->
                        Panel(
                            Modifier.testTag("worker-${worker.workerId}").clickable(
                                enabled = state.fresh(now) && worker.attempt > 0
                            ) {
                                actions.openDetails(worker)
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "进程 ${worker.workerId}",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                StatusLabel(
                                    when (worker.state) {
                                        "keepAliveWait" -> "等待保活"
                                        "retrying" -> "重试中"
                                        "succeeded" -> "已完成"
                                        else -> statusTitle(worker.state)
                                    }
                                )
                            }
                            Text(worker.summary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                "第 ${worker.attempt} 次请求 · ${timeLabel(worker.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            else ->
                LogPage(state.snapshot?.logs.orEmpty(), state.snapshot?.historyTruncated == true)
        }
    }
}

@Composable
private fun LogPage(logs: List<LogLine>, truncated: Boolean) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SearchField(search, { search = it }, "搜索运行日志") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "warn" to "警告", "error" to "错误").forEach { (id, title) ->
                    FilterChip(filter == id, { filter = id }, label = { Text(title) })
                }
            }
        }
        if (truncated)
            item { Text("显示最近日志，较早记录保存在 PC。", style = MaterialTheme.typography.labelSmall) }
        val visible =
            logs.asReversed().filter {
                (filter == "all" || it.level == filter) &&
                    (search.isBlank() || it.text.contains(search, ignoreCase = true))
            }
        if (visible.isEmpty())
            item { EmptyState(Icons.Outlined.Terminal, "没有匹配的日志", "新的运行事件会自动显示在这里。") }
        items(visible, key = { it.id }) { line ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(timeLabel(line.time), style = MaterialTheme.typography.labelSmall)
                        Text(
                            line.level.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (line.level == "error") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        )
                        line.workerId?.let {
                            Text("进程 $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    SelectionContainer {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WorkerDetailSheet(state: CompanionState, actions: LauncherActions) {
    var tab by rememberSaveable(state.detailTarget) { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = actions.closeDetails,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("请求详情", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "进程 ${state.detailTarget?.worker?.workerId} · 第 ${state.detailTarget?.worker?.attempt} 次",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = actions.closeDetails) { Icon(Icons.Outlined.Close, "关闭请求详情") }
            }
            SegmentedBar(listOf("请求内容", "模型回复", "诊断"), tab, { tab = it })
            if (state.detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.detailsError?.let {
                Banner(it, true)
                TextButton(onClick = actions.refreshDetails) { Text("重试") }
            }
            state.details?.let { detail ->
                if (detail.truncated)
                    Text("较长内容已截断，完整记录可在 PC 查看。", style = MaterialTheme.typography.labelSmall)
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        when (tab) {
                            0 -> detail.request
                            1 -> detail.response
                            else -> detail.diagnostic
                        }.ifBlank { if (detail.completed) "无内容" else "等待内容…" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    if (detail.completed) "本轮已完成" else "正在执行，内容自动同步",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
