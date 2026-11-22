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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Hub,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "ANY 运行状态",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            state.snapshot?.configuration?.provider.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        runTitle(run),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (run?.active == true) "任务在电脑上持续执行" else "启动后，随时从这里查看进展",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RunControl(state, now, actions, confirm)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f)) {
                    Text("运行时间", style = MaterialTheme.typography.labelMedium)
                    Text(
                        durationLabel(run?.elapsedSeconds ?: 0),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Panel(Modifier.weight(1f)) {
                    Text("请求次数", style = MaterialTheme.typography.labelMedium)
                    Text(
                        (run?.attempt ?: 0).toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (state.workbench.approvals.isNotEmpty())
            item {
                Panel(Modifier.clickable { navigate(1) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.PendingActions,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                "${state.workbench.approvals.size} 项待你确认",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("打开工作台继续处理", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        item {
            SectionTitle(
                "开发工作台",
                "电脑执行，手机指挥",
                action = { TextButton(onClick = { navigate(1) }) { Text("全部会话") } },
            )
        }
        if (state.workbench.threads.isEmpty())
            item {
                Panel {
                    Text(
                        if (state.workbench.loading) "正在读取开发会话…" else "从一个开发会话开始",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "选择 PC 上的项目，查看 AI 回复、执行记录和代码变更。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { navigate(1) }) { Text("打开工作台") }
                }
            }
        items(state.workbench.threads.take(3), key = { it.id }) { thread ->
            ThreadRow(thread) {
                navigate(1)
                actions.openThread(thread.id)
            }
        }
        item { SectionTitle("快捷管理") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { settings("configuration") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text("PC 配置")
                }
                OutlinedButton(onClick = { settings("usage") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.BarChart, null)
                    Spacer(Modifier.width(6.dp))
                    Text("用量")
                }
            }
        }
        item {
            Text(
                "PC ${state.snapshot?.version ?: "等待同步"} · 断开手机不会停止电脑任务",
                style = MaterialTheme.typography.labelSmall,
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
        PrimaryTabRow(tab) {
            listOf("控制", "进程", "日志").forEachIndexed { index, text ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(text) },
                    modifier = Modifier.testTag("run-tab-$index"),
                )
            }
        }
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
                            KeyValue("模型", config?.model?.ifBlank { "使用 PC 配置" } ?: "等待同步")
                            KeyValue(
                                "并发进程",
                                "${run?.activeWorkers ?: 0} / ${config?.concurrency ?: 0}",
                            )
                            KeyValue("累计重试", (run?.retryCount ?: 0).toString())
                        }
                    }
                    item {
                        Panel {
                            SectionTitle("保活设置", "对当前运行立即生效")
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("成功后自动保活", Modifier.weight(1f))
                                Switch(
                                    checked = config?.keepAliveEnabled == true,
                                    enabled = canControl,
                                    onCheckedChange = { enabled ->
                                        val id = run?.takeIf { it.active }?.runId
                                        if (enabled) actions.keepAlive(true, null, id)
                                        else
                                            confirm(
                                                ConfirmAction("关闭保活", "当前请求完成后不再安排下一次保活。") {
                                                    actions.keepAlive(false, null, id)
                                                }
                                            )
                                    },
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                        "configured" to "原始内容",
                                        "questionBank" to "随机题库",
                                        "ascii" to "ASCII 短码",
                                    )
                                    .forEach { (mode, name) ->
                                        FilterChip(
                                            selected = config?.contentMode == mode,
                                            onClick = {
                                                actions.keepAlive(
                                                    null,
                                                    mode,
                                                    run?.takeIf { it.active }?.runId,
                                                )
                                            },
                                            enabled =
                                                canControl &&
                                                    (mode == "configured" ||
                                                        config?.contentModeSupported == true) &&
                                                    (mode != "questionBank" ||
                                                        config?.questionBankAvailable == true),
                                            label = { Text(name) },
                                        )
                                    }
                            }
                            KeyValue("保活间隔", "${(config?.keepAliveIntervalSeconds ?: 0) / 60} 分钟")
                            run?.nextRequestAt?.let { KeyValue("下一次请求", timeLabel(it)) }
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
        item {
            OutlinedTextField(
                search,
                { search = it },
                label = { Text("搜索运行日志") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
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
            PrimaryTabRow(tab) {
                listOf("请求内容", "模型回复", "诊断").forEachIndexed { index, title ->
                    Tab(tab == index, { tab = index }, text = { Text(title) })
                }
            }
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
