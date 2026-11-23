@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.codexlauncher.companion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import java.util.UUID
import kotlinx.serialization.json.*

@Composable
fun ThreadRow(
    thread: WorkThread,
    selected: Boolean = false,
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "会话继续方式",
            )
            .testTag("thread-${thread.id}"),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
    ) {
        Column(
            Modifier.padding(
                horizontal = if (compact) 12.dp else 4.dp,
                vertical = if (compact) 12.dp else 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    thread.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (thread.status in listOf("active", "running", "inProgress"))
                    Surface(Modifier.size(7.dp), shape = CircleShape, color = Color(0xFF25844E)) {}
                else if (!compact)
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
            }
            Text(
                thread.project +
                    if (compact) ""
                    else
                        " · " +
                            if (thread.canContinueOriginal) statusTitle(thread.status) else "历史会话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    LaunchedEffect(workbench.search) { search = workbench.search }
    var create by rememberSaveable { mutableStateOf(false) }
    var contextThread by remember { mutableStateOf<WorkThread?>(null) }
    var renameThread by remember { mutableStateOf<WorkThread?>(null) }
    val threads =
        workbench.threads.filter {
            search.isBlank() || it.title.contains(search, true) || it.project.contains(search, true)
        }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("workbench-list"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("继续你的想法", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "项目留在电脑，进展随时查看。",
                    Modifier.padding(top = 6.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SearchField(
                    search,
                    { search = it },
                    "搜索项目或会话",
                    Modifier.testTag("session-search"),
                ) {
                    actions.loadWorkbench(search, workbench.archived, false)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SegmentedBar(
                        listOf("最近", "已归档"),
                        if (workbench.archived) 1 else 0,
                        { actions.loadWorkbench(search, it == 1, false) },
                        Modifier.weight(1f),
                    )
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
            workbench.error?.let { item { Banner(it, true) } }
            if (state.computer?.canControl == false) item { Banner("此设备仅有查看权限。工作台控制需要 PC 授予管理权限。") }
            if (threads.isEmpty() && !workbench.loading)
                item {
                    EmptyState(
                        Icons.Outlined.ChatBubbleOutline,
                        if (search.isBlank()) "从一个想法开始" else "没有找到会话",
                        if (search.isBlank()) "新建会话，在手机上指挥电脑完成开发任务。" else "换个关键词，或按键盘搜索查找更多记录。",
                    )
                }
            threads
                .groupBy { it.project }
                .forEach { (project, rows) ->
                    item(key = "project-$project") {
                        Text(
                            project,
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(rows, key = { it.id }) { thread ->
                        ThreadRow(thread, onLongClick = { contextThread = thread }) {
                            actions.openThread(thread.id)
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            if (workbench.nextCursor != null)
                item {
                    TextButton(
                        onClick = { actions.loadWorkbench(search, workbench.archived, true) },
                        enabled = !workbench.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更多会话")
                    }
                }
        }
        Button(
            onClick = { create = true },
            enabled = canControl,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .heightIn(min = 52.dp)
                    .testTag("new-thread"),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        ) {
            Icon(Icons.Outlined.Create, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("新会话")
        }
    }
    if (create) NewThreadSheet(state, canControl, actions) { create = false }
    renameThread?.let { RenameThreadSheet(it, canControl, actions) { renameThread = null } }
    contextThread?.let { thread ->
        SessionChoiceSheet(
            thread,
            canControl,
            { contextThread = null },
            original = {
                contextThread = null
                actions.openThread(thread.id)
                actions.workbenchAction("attach", buildJsonObject { put("threadId", thread.id) })
            },
            branch = {
                contextThread = null
                actions.workbenchAction("fork", buildJsonObject { put("threadId", thread.id) })
            },
            connectionSettings = {
                contextThread = null
                settings()
            },
            rename = {
                contextThread = null
                renameThread = thread
            },
        )
    }
}

@Composable
fun NewThreadSheet(
    state: CompanionState,
    enabled: Boolean,
    actions: LauncherActions,
    dismiss: () -> Unit,
) {
    val workbench = state.workbench
    var cwd by rememberSaveable { mutableStateOf(workbench.settings?.get("workDir").text()) }
    var model by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val newTarget =
        DraftTarget(
            state.computer?.certificateSha256.orEmpty(),
            workbench.capabilities?.get("backendScopeId").text(),
            "new",
        )
    EditorSheet(
        "新建开发会话",
        dismiss,
        save = {
            actions.workbenchAction(
                "create",
                buildJsonObject {
                    put("cwd", cwd.trim())
                    if (name.isNotBlank()) put("name", name.trim())
                    put("draftScopeId", newTarget.scope)
                    if (model.isNotBlank()) put("model", model.trim())
                },
            )
            dismiss()
        },
        saveEnabled = enabled && cwd.isNotBlank() && newTarget.key !in state.importingAttachments,
        saveLabel = "创建",
    ) {
        Text(
            "选择电脑上的项目，开始一段新对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup("项目") {
            TextSetting(
                "会话名称",
                name,
                "可选，稍后也能重命名",
                multiline = false,
                tag = "new-thread-name",
                changed = { if (it.toByteArray().size <= 256) name = it },
            )
            SettingDivider()
            TextSetting(
                "项目目录",
                cwd,
                "选择或输入 PC 路径",
                multiline = false,
                tag = "new-thread-cwd",
                changed = { cwd = it },
            )
            SettingDivider()
            ModelPicker(model, workbench.models) { model = it }
        }
        val recent = workbench.threads.filter { it.cwd.isNotBlank() }.distinctBy { it.cwd }.take(6)
        if (recent.isNotEmpty())
            SettingsGroup("最近使用") {
                recent.forEachIndexed { index, thread ->
                    if (index > 0) SettingDivider()
                    SettingRow(
                        thread.project,
                        subtitle = thread.cwd,
                        icon = Icons.Outlined.FolderOpen,
                        onClick = { cwd = thread.cwd },
                        trailing = {
                            if (cwd == thread.cwd)
                                Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp))
                        },
                    )
                }
            }
        Text(
            "使用电脑上的 Codex 配置、工具和权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachmentPickerButton(newTarget, state, actions, enabled = true)
            Text(
                "可先添加资料，创建后发送",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ComposerAttachments(state, newTarget, actions) {}
    }
}

@Composable
fun SessionChoiceSheet(
    thread: WorkThread,
    enabled: Boolean,
    dismiss: () -> Unit,
    original: () -> Unit,
    branch: () -> Unit,
    connectionSettings: () -> Unit,
    rename: (() -> Unit)? = null,
) {
    EditorSheet("选择继续方式", dismiss) {
        Text(thread.title, style = MaterialTheme.typography.titleMedium)
        SettingsGroup {
            SettingRow(
                "沿用原会话",
                subtitle =
                    if (thread.canContinueOriginal) "保留同一个会话和历史，在当前对话中继续。运行中发送的内容会补充给当前任务。"
                    else "当前只连接到历史记录。需要连接原会话所在的共享后台后才能接入。",
                icon = Icons.Outlined.ChatBubbleOutline,
                enabled = enabled && thread.canContinueOriginal,
                modifier = Modifier.testTag("continue-original"),
                onClick = original,
            )
            SettingDivider()
            SettingRow(
                "创建分支",
                subtitle = "继承已有上下文，创建独立会话。后续消息分别保存，项目文件仍共用。",
                icon = Icons.Outlined.ForkRight,
                enabled = enabled,
                modifier = Modifier.testTag("continue-fork"),
                onClick = branch,
            )
            if (rename != null) {
                SettingDivider()
                SettingRow(
                    "重命名会话",
                    icon = Icons.Outlined.Edit,
                    enabled = enabled && thread.canContinueOriginal,
                    onClick = rename,
                    modifier = Modifier.testTag("rename-thread-choice"),
                )
            }
        }
        if (!thread.canContinueOriginal)
            TextButton(onClick = connectionSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.SettingsEthernet, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("连接原会话后台")
            }
        if (!enabled)
            Text(
                "电脑在线且当前操作完成后，可继续会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
fun WorkbenchDetailPage(
    state: CompanionState,
    canControl: Boolean,
    actions: LauncherActions,
    settings: () -> Unit = {},
    confirm: (ConfirmAction) -> Unit,
) {
    val detail = state.workbench.detail
    if (detail == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).testTag("thread-loading"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (state.workbench.detailLoading) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("正在加载会话…")
                Text(
                    "正在读取电脑上的历史消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Banner(state.workbench.error ?: "暂时无法读取这条会话", true)
                Button(onClick = { state.workbench.openingThreadId?.let(actions.openThread) }) {
                    Text("重新加载")
                }
            }
            TextButton(onClick = actions.closeThread) { Text("返回会话列表") }
        }
        return
    }
    val target = detail.draftTarget(state.computer)
    val savedDraft = state.attachmentDrafts[target.key]
    val draftAttachments = savedDraft?.attachments.orEmpty()
    var tab by remember(detail.thread.id) { mutableIntStateOf(0) }
    var draft by rememberSaveable(target.key) { mutableStateOf(savedDraft?.text.orEmpty()) }
    var submitted by rememberSaveable(detail.thread.id) { mutableStateOf<String?>(null) }
    var submittedId by rememberSaveable(detail.thread.id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var continuation by remember { mutableStateOf(false) }
    var selectedApproval by remember { mutableStateOf<JsonObject?>(null) }
    val readOnly = !detail.thread.canContinueOriginal
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
    LaunchedEffect(state.lastDraftSubmissionId, state.pendingMessages.keys) {
        if (
            submittedId != null &&
                (state.lastDraftSubmissionId == submittedId ||
                    state.pendingMessages.containsKey(submittedId))
        ) {
            if (draft == submitted) draft = ""
            submitted = null
            submittedId = null
        }
    }
    val canSend =
        canControl &&
            !readOnly &&
            (draft.isNotBlank() || draftAttachments.isNotEmpty()) &&
            target.key !in state.importingAttachments &&
            state.pendingMessages.values.none {
                it.target == target && it.state !in listOf("sent", "discarded")
            } &&
            !state.workbench.detailLoading &&
            state.uncertainCommand == null
    val send = {
        if (canSend) {
            submitted = draft
            submittedId = UUID.randomUUID().toString()
            actions.workbenchAction(
                if (detail.activeTurn == null) "send" else "steer",
                buildJsonObject {
                    put("threadId", detail.thread.id)
                    put("text", draft)
                    put("messageId", submittedId)
                    put("backendScopeId", target.scope)
                    if (draftAttachments.isNotEmpty())
                        putJsonArray("localAttachmentIds") {
                            draftAttachments.forEach { add(it.id) }
                        }
                    detail.activeTurn?.let { put("turnId", it) }
                },
            )
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { continuation = true },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.testTag("session-mode"),
            ) {
                Text(
                    if (readOnly) "选择继续方式" else "沿用原会话",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                statusTitle(activityStatus),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "会话操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("执行记录") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                        modifier = Modifier.testTag("show-executions"),
                        onClick = {
                            menu = false
                            tab = 1
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("文件变更") },
                        leadingIcon = { Icon(Icons.Outlined.Difference, null) },
                        modifier = Modifier.testTag("show-changes"),
                        onClick = {
                            menu = false
                            tab = 2
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("选择继续方式") },
                        onClick = {
                            menu = false
                            continuation = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新会话") },
                        onClick = {
                            menu = false
                            actions.openThread(detail.thread.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名会话") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        modifier = Modifier.testTag("rename-thread"),
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
        if (approvals.isNotEmpty())
            FilledTonalButton(
                onClick = { selectedApproval = approvals.first() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.PendingActions, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${approvals.size} 项等待你确认")
            }
        state.workbench.error?.let { Banner(it, true) }
        state.adminError?.let { Banner(it, true) }
        if (tab != 0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { tab = 0 },
                    modifier = Modifier.testTag("back-to-conversation"),
                ) {
                    Icon(Icons.Outlined.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("返回对话")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (tab == 1) "执行记录" else "文件变更",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
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
        val leadingRows = if (detail.nextCursor != null) 1 else 0
        LaunchedEffect(visible.lastOrNull()?.id, visible.lastOrNull()?.text?.length, tab) {
            if (nearEnd && visible.isNotEmpty())
                scroll.scrollToItem(leadingRows + visible.lastIndex)
        }
        val outgoingId = visible.lastOrNull { it.localEcho }?.id
        LaunchedEffect(outgoingId) {
            if (outgoingId != null)
                scroll.scrollToItem(leadingRows + visible.indexOfFirst { it.id == outgoingId })
        }
        PullToRefreshBox(
            isRefreshing = state.workbench.detailLoading,
            onRefresh = {
                if (detail.nextCursor != null) actions.olderMessages()
                else actions.openThread(detail.thread.id)
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("thread-pull-refresh"),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("thread-timeline"),
                state = scroll,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (detail.nextCursor != null)
                    item(key = "older-messages") {
                        TextButton(
                            onClick = actions.olderMessages,
                            enabled = !state.workbench.detailLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("加载更早记录")
                        }
                    }
                if (visible.isEmpty() && (tab != 2 || detail.diff.isBlank()))
                    item {
                        EmptyState(
                            when (tab) {
                                0 -> Icons.Outlined.ChatBubbleOutline
                                1 -> Icons.Outlined.Terminal
                                else -> Icons.Outlined.Difference
                            },
                            when (tab) {
                                0 -> "有什么想完成的？"
                                1 -> "暂无执行记录"
                                else -> "暂无文件变更"
                            },
                            if (tab == 0) "从这里开始，和电脑上的 Codex 一起开发。" else "任务执行后的记录会实时显示。",
                        )
                    }
                items(visible, key = { it.id }) { MessageItem(it, target, state, actions) }
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
        }
        if (readOnly) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerAttachments(state, target, actions, confirm)
                Text(
                    "正在查看历史记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { continuation = true },
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("choose-session-mode"),
                ) {
                    Text("选择继续方式")
                }
            }
        } else {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ComposerAttachments(state, target, actions, confirm)
                if (detail.activeTurn != null)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                confirm(
                                    ConfirmAction("中断开发任务", "请求 Codex 停止当前轮次，已完成的文件修改会保留。") {
                                        actions.workbenchAction(
                                            "interrupt",
                                            buildJsonObject {
                                                put("threadId", detail.thread.id)
                                                put("turnId", detail.activeTurn)
                                            },
                                        )
                                    }
                                )
                            },
                            enabled = canControl,
                        ) {
                            Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("停止")
                        }
                    }
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AttachmentPickerButton(target, state, actions, enabled = canControl)
                        Box(
                            Modifier.weight(1f).heightIn(min = 42.dp).padding(vertical = 9.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                draft,
                                {
                                    if (it.toByteArray().size <= 64 * 1024) {
                                        draft = it
                                        actions.editDraft(target, it)
                                    }
                                },
                                Modifier.fillMaxWidth().testTag("message-input"),
                                textStyle =
                                    MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                decorationBox = { inner ->
                                    if (draft.isEmpty())
                                        Text(
                                            if (detail.activeTurn == null) "向 Codex 发送消息…"
                                            else "补充开发要求…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    inner()
                                },
                            )
                        }
                        FilledIconButton(
                            onClick = send,
                            enabled = canSend,
                            shape = CircleShape,
                            modifier = Modifier.size(44.dp).testTag("send-message"),
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, "发送指令", Modifier.size(22.dp))
                        }
                    }
                }
                Text(
                    if (state.workbench.streaming) "在 PC 上执行 · 实时同步" else "正在恢复实时连接，任务继续在 PC 执行",
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (continuation)
        SessionChoiceSheet(
            detail.thread,
            canControl,
            { continuation = false },
            original = {
                continuation = false
                actions.workbenchAction(
                    "attach",
                    buildJsonObject { put("threadId", detail.thread.id) },
                )
            },
            branch = {
                continuation = false
                actions.workbenchAction(
                    "fork",
                    buildJsonObject { put("threadId", detail.thread.id) },
                )
            },
            connectionSettings = {
                continuation = false
                settings()
            },
        )
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
    if (rename) RenameThreadSheet(detail.thread, canControl, actions) { rename = false }
}

@Composable
fun RenameThreadSheet(
    thread: WorkThread,
    enabled: Boolean,
    actions: LauncherActions,
    dismiss: () -> Unit,
) {
    var name by rememberSaveable(thread.id) { mutableStateOf(thread.title) }
    EditorSheet(
        "重命名会话",
        dismiss,
        save = {
            actions.workbenchAction(
                "rename",
                buildJsonObject {
                    put("threadId", thread.id)
                    put("name", name.trim())
                },
            )
            dismiss()
        },
        saveEnabled = enabled && name.isNotBlank(),
    ) {
        OutlinedTextField(
            name,
            { if (it.toByteArray().size <= 256) name = it },
            label = { Text("会话名称") },
            modifier = Modifier.fillMaxWidth().testTag("rename-thread-input"),
        )
    }
}

@Composable
private fun ModelPicker(value: String, models: List<JsonObject>, changed: (String) -> Unit) {
    val choices =
        listOf("" to "PC 默认模型") +
            models.map { it["model"].text() to it["displayName"].text(it["model"].text()) }
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    SettingRow("模型", choices.find { it.first == value }?.second ?: value, onClick = { open = true })
    if (open)
        EditorSheet("选择模型", { open = false }) {
            SettingsGroup {
                choices
                    .distinctBy { it.first }
                    .forEachIndexed { index, (id, label) ->
                        if (index > 0) SettingDivider()
                        SettingRow(
                            label,
                            onClick = {
                                changed(id)
                                open = false
                            },
                            trailing = {
                                if (value == id)
                                    Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp))
                            },
                        )
                    }
                SettingDivider()
                SettingRow(
                    "自定义模型",
                    icon = Icons.Outlined.Edit,
                    onClick = {
                        open = false
                        custom = true
                    },
                )
            }
        }
    if (custom) {
        var draft by rememberSaveable { mutableStateOf(value) }
        EditorSheet(
            "自定义模型",
            { custom = false },
            save = {
                changed(draft.trim())
                custom = false
            },
            saveEnabled = draft.isNotBlank(),
        ) {
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
            )
            Text(
                "模型需要能通过电脑当前配置的供应商使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MessageItem(
    item: WorkItem,
    target: DraftTarget? = null,
    state: CompanionState? = null,
    actions: LauncherActions = LauncherActions(),
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val user = item.kind == "userMessage"
    val agent = item.kind == "agentMessage"
    val code = item.kind in listOf("commandExecution", "fileChange")
    val content = if (expanded) item.text else item.text.take(8000)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            Modifier.then(
                    if (user) Modifier.widthIn(max = maxWidth * 0.88f).align(Alignment.CenterEnd)
                    else Modifier.fillMaxWidth()
                )
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (agent)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(17.dp))
                    Text(item.title, style = MaterialTheme.typography.labelMedium)
                }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    when {
                        user -> MaterialTheme.colorScheme.surfaceContainer
                        agent -> Color.Transparent
                        item.kind == "error" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ) {
                Column(
                    Modifier.then(
                        if (agent) Modifier.padding(top = 5.dp) else Modifier.padding(16.dp)
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!user && !agent)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (code) Icons.Outlined.Terminal else Icons.Outlined.Info,
                                null,
                                Modifier.size(16.dp),
                            )
                            Text(
                                item.title,
                                Modifier.weight(1f).padding(start = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (item.status.isNotBlank())
                                Text(
                                    statusTitle(item.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    if (target != null && state != null && item.attachments.isNotEmpty()) {
                        item.attachments.forEach {
                            AttachmentMessageCard(it, target, state, actions)
                        }
                    }
                    if (agent) MarkdownReply(content)
                    else if (content.isNotBlank())
                        SelectionContainer {
                            Text(
                                content,
                                style =
                                    if (code) MaterialTheme.typography.bodySmall
                                    else MaterialTheme.typography.bodyLarge,
                                fontFamily = if (code) FontFamily.Monospace else FontFamily.Default,
                            )
                        }
                    if (item.text.length > 8000)
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开完整内容")
                        }
                }
            }
            if (user && item.status.isNotBlank())
                Text(
                    statusTitle(item.status),
                    Modifier.align(Alignment.End).padding(end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (agent)
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(item.text)) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        "复制回复",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Text("连接你的开发会话", style = MaterialTheme.typography.headlineMedium)
            Text(
                "共享后台可以接入其中已加载的原会话。独立后台用于新建和分支会话。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SettingsGroup("连接", footer = "自动模式优先连接共享后台；不可用时使用独立后台。") {
                ChoiceSetting(
                    "连接方式",
                    draft["connectionMode"].text(),
                    listOf("auto" to "自动", "shared" to "共享后台", "managed" to "独立后台"),
                ) {
                    field("connectionMode", it)
                }
                SettingDivider()
                TextSetting("默认项目", draft["workDir"].text(), "输入 PC 项目路径", tag = "workDir") {
                    field("workDir", it)
                }
            }
        }
        item {
            SettingsGroup("高级选项", footer = "切换连接设置前，需要先结束工作台正在运行的任务。") {
                TextSetting(
                    "Codex 配置目录",
                    draft["codexHome"].text(),
                    "继承 PC 环境",
                    subtitle = "CODEX_HOME",
                    tag = "codexHome",
                ) {
                    field("codexHome", it)
                }
                SettingDivider()
                TextSetting("Codex 程序", draft["executable"].text(), "自动查找", tag = "executable") {
                    field("executable", it)
                }
                SettingDivider()
                TextSetting("共享 Socket", draft["socketPath"].text(), "自动发现", tag = "socketPath") {
                    field("socketPath", it)
                }
            }
        }
        state.adminError?.let { item { Banner(it, true) } }
        item {
            Button(
                onClick = { actions.workbenchAction("configure", draft) },
                enabled = enabled && draft != settings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) {
                Text("保存并重新连接")
            }
        }
    }
}
