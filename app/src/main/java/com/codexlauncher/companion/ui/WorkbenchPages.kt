@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.codexlauncher.companion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import java.util.UUID
import kotlinx.serialization.json.*

@Composable
fun ThreadRow(thread: WorkThread, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("thread-${thread.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    thread.project,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLabel(statusTitle(thread.status), thread.status == "active")
            }
            Text(
                thread.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    thread.provider.ifBlank { "Codex" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (thread.control == "readOnly") "仅查看" else "可控制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun WorkbenchListPage(
    state: CompanionState,
    canControl: Boolean,
    actions: LauncherActions,
    settings: () -> Unit,
) {
    val workbench = state.workbench
    var search by rememberSaveable { mutableStateOf(workbench.search) }
    var create by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding =
                PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedTextField(
                    search,
                    { search = it },
                    label = { Text("搜索开发会话") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        IconButton(
                            onClick = { actions.loadWorkbench(search, workbench.archived, false) }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "搜索")
                        }
                    },
                    singleLine = true,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        !workbench.archived,
                        { actions.loadWorkbench(search, false, false) },
                        label = { Text("最近") },
                    )
                    FilterChip(
                        workbench.archived,
                        { actions.loadWorkbench(search, true, false) },
                        label = { Text("已归档") },
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = settings) {
                        Icon(Icons.Outlined.SettingsEthernet, "工作台连接设置")
                    }
                    IconButton(
                        onClick = { actions.loadWorkbench(search, workbench.archived, false) },
                        enabled = !workbench.loading,
                    ) {
                        Icon(Icons.Outlined.Refresh, "刷新会话")
                    }
                }
            }
            if (workbench.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            workbench.error?.let { error -> item { Banner(error, true) } }
            if (state.computer?.canControl == false)
                item { Banner("此设备仅有查看权限。使用工作台需在 PC 重新配对并启用管理权限。") }
            if (workbench.threads.isEmpty() && !workbench.loading)
                item {
                    EmptyState(
                        Icons.Outlined.Forum,
                        "开始一项开发任务",
                        "选择 PC 的项目目录，新建会话后即可对话。已有会话的控制能力取决于所连接的后台。",
                    )
                }
            workbench.threads
                .groupBy { it.project }
                .forEach { (project, threads) ->
                    item(key = "group-$project") {
                        Text(
                            project,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(threads, key = { it.id }) { ThreadRow(it) { actions.openThread(it.id) } }
                }
            if (workbench.nextCursor != null)
                item {
                    OutlinedButton(
                        onClick = { actions.loadWorkbench(search, workbench.archived, true) },
                        enabled = !workbench.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更多会话")
                    }
                }
            workbench.capabilities?.get("message")?.text()?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { create = true },
            expanded = true,
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text("新会话") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("new-thread"),
        )
    }
    if (create) {
        var cwd by rememberSaveable { mutableStateOf(workbench.settings?.get("workDir").text()) }
        var model by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("在 PC 新建会话") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("任务将使用 PC 的 Codex 配置、工具和权限。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        cwd,
                        { cwd = it },
                        label = { Text("PC 项目绝对路径") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        workbench.threads
                            .distinctBy { it.cwd }
                            .take(5)
                            .forEach { thread ->
                                SuggestionChip(
                                    onClick = { cwd = thread.cwd },
                                    label = { Text(thread.project) },
                                )
                            }
                    }
                    ModelPicker(model, workbench.models) { model = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.workbenchAction(
                            "create",
                            buildJsonObject {
                                put("cwd", cwd.trim())
                                if (model.isNotBlank()) put("model", model.trim())
                            },
                        )
                        create = false
                    },
                    enabled = canControl && cwd.isNotBlank(),
                ) {
                    Text("创建会话")
                }
            },
            dismissButton = { TextButton(onClick = { create = false }) { Text("取消") } },
        )
    }
}

@Composable
fun WorkbenchDetailPage(
    state: CompanionState,
    canControl: Boolean,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    val detail = state.workbench.detail ?: return
    var tab by rememberSaveable(detail.thread.id) { mutableIntStateOf(0) }
    var draft by rememberSaveable(detail.thread.id) { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var submittedId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var selectedApproval by remember { mutableStateOf<JsonObject?>(null) }
    val readOnly = detail.thread.control == "readOnly"
    val activeItems =
        detail.items.filter { detail.activeTurn != null && it.turnId == detail.activeTurn }
    val retrying = activeItems.any { it.kind == "retry" }
    val compacting = activeItems.any { it.kind == "contextCompaction" && it.status == "inProgress" }
    val activityStatus =
        when {
            retrying -> "retrying"
            compacting -> "compacting"
            else -> detail.thread.status
        }
    val approvals =
        state.workbench.approvals.filter {
            it["params"].obj()["threadId"].text() == detail.thread.id
        }
    LaunchedEffect(state.lastAdminOutcome?.requestId) {
        if (submittedId != null && state.lastAdminOutcome?.messageId == submittedId) {
            if (draft == submitted) draft = ""
            submitted = null
            submittedId = null
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail.thread.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("创建上下文分支") },
                            enabled = canControl,
                            onClick = {
                                menu = false
                                confirm(
                                    ConfirmAction("创建分支继续开发", "从已有上下文创建一个可控制的新会话。") {
                                        actions.workbenchAction(
                                            "fork",
                                            buildJsonObject { put("threadId", detail.thread.id) },
                                        )
                                    }
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            enabled = canControl && !readOnly,
                            onClick = {
                                menu = false
                                rename = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("归档会话") },
                            enabled = canControl && !readOnly && detail.activeTurn == null,
                            onClick = {
                                menu = false
                                confirm(
                                    ConfirmAction("归档此会话", "会话将移至已归档列表，历史记录仍然保留。") {
                                        actions.workbenchAction(
                                            "archive",
                                            buildJsonObject { put("threadId", detail.thread.id) },
                                        )
                                    }
                                )
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail.thread.project,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                StatusLabel(statusTitle(activityStatus), detail.activeTurn != null)
                IconButton(onClick = { actions.openThread(detail.thread.id) }) {
                    Icon(Icons.Outlined.Refresh, "刷新当前会话")
                }
            }
        }
        if (readOnly) Banner("当前会话仅查看。创建分支可保留已有上下文，并在工作台继续开发。")
        if (approvals.isNotEmpty())
            FilledTonalButton(
                onClick = { selectedApproval = approvals.first() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Outlined.PendingActions, null)
                Spacer(Modifier.width(8.dp))
                Text("${approvals.size} 项等待你确认")
            }
        state.workbench.error?.let { Banner(it, true) }
        state.adminError?.let { Banner(it, true) }
        PrimaryTabRow(tab) {
            listOf("对话", "执行", "变更").forEachIndexed { index, title ->
                Tab(
                    tab == index,
                    { tab = index },
                    text = { Text(title) },
                    modifier = Modifier.testTag("thread-tab-$index"),
                )
            }
        }
        val visible =
            detail.items.filter { item ->
                when (tab) {
                    0 ->
                        item.kind in
                            listOf(
                                "userMessage",
                                "agentMessage",
                                "plan",
                                "error",
                                "retry",
                                "contextCompaction",
                            )
                    1 -> item.kind !in listOf("userMessage", "agentMessage", "fileChange", "plan")
                    else -> item.kind == "fileChange"
                }
            }
        val scroll = rememberLazyListState()
        val nearEnd by remember {
            derivedStateOf {
                scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                    it >= scroll.layoutInfo.totalItemsCount - 2
                } ?: true
            }
        }
        val leadingRows =
            (if (detail.nextCursor != null) 1 else 0) + (if (state.workbench.loading) 1 else 0)
        LaunchedEffect(visible.size, visible.lastOrNull()?.text?.length, leadingRows) {
            if (nearEnd && visible.isNotEmpty())
                scroll.scrollToItem(leadingRows + visible.lastIndex)
        }
        val outgoingId = visible.lastOrNull { it.localEcho }?.id
        LaunchedEffect(outgoingId) {
            if (outgoingId != null)
                scroll.scrollToItem(leadingRows + visible.indexOfFirst { it.id == outgoingId })
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("thread-timeline"),
            state = scroll,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detail.nextCursor != null)
                item {
                    TextButton(
                        onClick = actions.olderMessages,
                        enabled = !state.workbench.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更早记录")
                    }
                }
            if (state.workbench.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (visible.isEmpty() && (tab != 2 || detail.diff.isBlank()))
                item {
                    EmptyState(
                        when (tab) {
                            0 -> Icons.Outlined.ChatBubbleOutline
                            1 -> Icons.Outlined.Terminal
                            else -> Icons.Outlined.Difference
                        },
                        when (tab) {
                            0 -> "开始对话"
                            1 -> "暂无执行记录"
                            else -> "暂无文件变更"
                        },
                        if (tab == 0) "描述你希望在这个项目中完成的任务。" else "任务执行后的记录会实时显示。",
                    )
                }
            items(visible, key = { it.id }) { MessageItem(it) }
            if (tab == 2 && detail.diff.isNotBlank())
                item {
                    MessageItem(
                        WorkItem(
                            "live-diff",
                            detail.activeTurn.orEmpty(),
                            "fileChange",
                            "本轮变更",
                            detail.diff,
                        )
                    )
                }
        }
        if (readOnly)
            OutlinedButton(
                onClick = {
                    confirm(
                        ConfirmAction("创建分支继续开发", "将从此会话的已有上下文创建一个可控制的新会话，原会话保持独立。") {
                            actions.workbenchAction(
                                "fork",
                                buildJsonObject { put("threadId", detail.thread.id) },
                            )
                        }
                    )
                },
                enabled = canControl,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Outlined.ForkRight, null)
                Spacer(Modifier.width(8.dp))
                Text("创建分支继续")
            }
        else
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.activeTurn != null)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    retrying -> "连接重试中，任务仍在继续"
                                    compacting -> "正在整理上下文，随后处理消息"
                                    else -> "正在执行，可补充要求"
                                },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            TextButton(
                                onClick = {
                                    val turn = detail.activeTurn
                                    confirm(
                                        ConfirmAction("中断开发任务", "请求 Codex 停止当前轮次，已完成的文件修改会保留。") {
                                            actions.workbenchAction(
                                                "interrupt",
                                                buildJsonObject {
                                                    put("threadId", detail.thread.id)
                                                    put("turnId", turn)
                                                },
                                            )
                                        }
                                    )
                                },
                                enabled = canControl,
                            ) {
                                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止")
                            }
                        }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            draft,
                            { if (it.toByteArray().size <= 64 * 1024) draft = it },
                            placeholder = {
                                Text(
                                    if (detail.activeTurn == null) "告诉 Codex 接下来做什么…" else "补充开发要求…"
                                )
                            },
                            modifier = Modifier.weight(1f).testTag("message-input"),
                            maxLines = 6,
                            shape = MaterialTheme.shapes.large,
                        )
                        FilledIconButton(
                            onClick = {
                                submitted = draft
                                submittedId = UUID.randomUUID().toString()
                                actions.workbenchAction(
                                    if (detail.activeTurn == null) "send" else "steer",
                                    buildJsonObject {
                                        put("threadId", detail.thread.id)
                                        put("text", draft)
                                        put("messageId", submittedId)
                                        detail.activeTurn?.let { put("turnId", it) }
                                    },
                                )
                            },
                            enabled =
                                canControl &&
                                    draft.isNotBlank() &&
                                    !state.workbench.loading &&
                                    state.uncertainCommand == null,
                            modifier = Modifier.size(52.dp).testTag("send-message"),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "发送指令")
                        }
                    }
                    Text(
                        if (state.workbench.streaming) "实时同步 · 执行位置：${detail.thread.project}"
                        else "正在恢复实时连接，任务继续在 PC 执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    selectedApproval?.let { request ->
        ApprovalDialog(request, canControl, { selectedApproval = null }) { answer ->
            actions.workbenchAction(
                "answer",
                buildJsonObject {
                    put("epoch", request["epoch"] ?: JsonNull)
                    put("id", request["id"] ?: JsonNull)
                    put("answer", answer)
                },
            )
            selectedApproval = null
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(detail.thread.title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    name,
                    { if (it.toByteArray().size <= 256) name = it },
                    label = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.workbenchAction(
                            "rename",
                            buildJsonObject {
                                put("threadId", detail.thread.id)
                                put("name", name.trim())
                            },
                        )
                        rename = false
                    },
                    enabled = canControl && name.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ModelPicker(value: String, models: List<JsonObject>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value,
            changed,
            label = { Text("模型（留空使用 PC 默认值）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.ExpandMore, "选择 Codex 模型")
                }
            },
        )
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 300.dp)) {
            DropdownMenuItem(
                text = { Text("PC 默认模型") },
                onClick = {
                    changed("")
                    expanded = false
                },
            )
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model["displayName"].text(model["model"].text())) },
                    onClick = {
                        changed(model["model"].text())
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun MessageItem(item: WorkItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val code = item.kind in listOf("commandExecution", "fileChange")
    Surface(
        shape = MaterialTheme.shapes.large,
        color =
            if (item.kind == "userMessage") MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    item.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (item.status.isNotBlank())
                    Text(statusTitle(item.status), style = MaterialTheme.typography.labelSmall)
            }
            val content = if (expanded) item.text else item.text.take(8000)
            if (item.kind == "agentMessage") MarkdownReply(content)
            else
                SelectionContainer {
                    Text(
                        content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (code) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            if (item.text.length > 8000)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开完整内容")
                }
        }
    }
}

@Composable
private fun ApprovalDialog(
    request: JsonObject,
    enabled: Boolean,
    dismiss: () -> Unit,
    answer: (JsonObject) -> Unit,
) {
    val params = request["params"].obj()
    val method = request["method"].text()
    val questions = params["questions"].list()
    val answers = remember(request["id"]) { mutableStateMapOf<String, String>() }
    val userInput = method == "item/tool/requestUserInput"
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (userInput) "Codex 需要你的回答" else "确认本次操作") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (userInput)
                    questions.forEach { value ->
                        val question = value.obj()
                        val id = question["id"].text()
                        Text(question["question"].text())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            question["options"].list().forEach { option ->
                                val title = option.obj()["label"].text()
                                FilterChip(
                                    answers[id] == title,
                                    { answers[id] = title },
                                    label = { Text(title) },
                                )
                            }
                        }
                        OutlinedTextField(
                            answers[id].orEmpty(),
                            { answers[id] = it },
                            label = { Text("你的回答") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                else {
                    Text(params["reason"].text("Codex 请求你确认下列操作。"))
                    SelectionContainer {
                        Text(
                            params["command"].text().ifBlank {
                                params["permissions"]?.toString()
                                    ?: params["changes"]?.toString()
                                    ?: params.toString()
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("仅应用于本次操作或当前轮次。", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    answer(
                        if (userInput)
                            buildJsonObject {
                                put(
                                    "answers",
                                    buildJsonObject {
                                        questions.forEach { q ->
                                            val id = q.obj()["id"].text()
                                            put(
                                                id,
                                                buildJsonObject {
                                                    put(
                                                        "answers",
                                                        JsonArray(
                                                            listOf(
                                                                JsonPrimitive(answers[id].orEmpty())
                                                            )
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        else if (method.contains("permissions"))
                            buildJsonObject { put("allow", true) }
                        else buildJsonObject { put("decision", "accept") }
                    )
                },
                enabled =
                    enabled &&
                        (!userInput ||
                            questions.all { answers[it.obj()["id"].text()]?.isNotBlank() == true }),
            ) {
                Text(if (userInput) "提交回答" else "允许本次")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (userInput) dismiss()
                    else
                        answer(
                            if (method.contains("permissions"))
                                buildJsonObject { put("allow", false) }
                            else buildJsonObject { put("decision", "decline") }
                        )
                },
                enabled = enabled,
            ) {
                Text(if (userInput) "稍后回答" else "拒绝")
            }
        },
    )
}

@Composable
fun WorkbenchSettingsPage(state: CompanionState, enabled: Boolean, actions: LauncherActions) {
    val settings = state.adminResult?.get("settings") as? JsonObject ?: state.workbench.settings
    if (settings == null) {
        EmptyState(Icons.Outlined.SettingsEthernet, "读取连接设置", state.adminError ?: "正在连接电脑…")
        return
    }
    var draft by remember(settings) { mutableStateOf(settings) }
    fun field(key: String, value: String) {
        draft = JsonObject(draft + (key to JsonPrimitive(value)))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Banner("默认自动连接共享后台；无法共享时管理独立会话。切换设置前需先结束工作台中的运行任务。") }
        item {
            Panel {
                Text("连接方式", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "自动", "shared" to "共享后台", "managed" to "独立后台").forEach {
                        (id, title) ->
                        FilterChip(
                            draft["connectionMode"].text() == id,
                            { field("connectionMode", id) },
                            label = { Text(title) },
                        )
                    }
                }
            }
        }
        listOf(
                "workDir" to "默认 PC 项目路径",
                "codexHome" to "CODEX_HOME（留空继承 PC 环境）",
                "executable" to "Codex 可执行文件（留空自动查找）",
                "socketPath" to "共享 Socket（留空自动发现）",
            )
            .forEach { (key, title) ->
                item {
                    OutlinedTextField(
                        draft[key].text(),
                        { field(key, it) },
                        label = { Text(title) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        state.adminError?.let { item { Banner(it, true) } }
        item {
            Button(
                onClick = { actions.workbenchAction("configure", draft) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并重新连接")
            }
        }
    }
}
