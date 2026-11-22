@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codexlauncher.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
fun UsagePage(
    state: CompanionState,
    enabled: Boolean,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    var catalog by remember { mutableStateOf(JsonObject(emptyMap())) }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var app by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    fun filter(start: Int = offset) = buildJsonObject {
        put("limit", 30)
        put("offset", start)
        if (days > 0)
            put("startMs", Instant.now().minusSeconds(days.toLong() * 86400).toEpochMilli())
        if (app.isNotBlank()) put("app", app)
        if (model.isNotBlank()) put("model", model.trim())
        if (provider.isNotBlank()) put("provider", provider.trim())
    }
    val refresh = { actions.adminQuery("usage", filter()) }
    LaunchedEffect(state.adminResult) {
        state.adminResult?.takeIf { it["summary"] is JsonObject }?.let { catalog = it }
    }
    val running =
        catalog["sync"].obj()["running"].text() == "true" ||
            catalog["reprice"].obj()["running"].text() == "true"
    val currentRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(running, state.online, state.busy) {
        if (running && state.online && !state.busy)
            while (true) {
                delay(3000)
                currentRefresh()
            }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AdminRefresh(state, refresh) }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "全部", 1 to "24 小时", 7 to "7 天", 30 to "30 天").forEach {
                        (count, label) ->
                        FilterChip(
                            days == count,
                            {
                                days = count
                                offset = 0
                                actions.adminQuery("usage", filter(0))
                            },
                            label = { Text(label) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "全部应用", "codex" to "Codex", "claude" to "Claude").forEach {
                        (id, label) ->
                        FilterChip(
                            app == id,
                            {
                                app = id
                                offset = 0
                                actions.adminQuery("usage", filter(0))
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    model,
                    { model = it },
                    label = { Text("筛选模型（留空为全部）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    provider,
                    { provider = it },
                    label = { Text("筛选供应商（留空为全部）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        offset = 0
                        actions.adminQuery("usage", filter(0))
                    }
                ) {
                    Text("应用筛选")
                }
            }
        }
        if (state.adminLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.adminError?.let { item { Banner(it, true) } }
        listOf("collectionWarning", "providerWarning").forEach { key ->
            catalog[key].text().takeIf { it.isNotBlank() }?.let { item { Banner(it) } }
        }
        item {
            val summary = catalog["summary"].obj()
            Panel {
                SectionTitle("用量总览")
                KeyValue("请求次数", summary["requests"].text("0"))
                KeyValue(
                    "成功 / 失败",
                    "${summary["successes"].text("0")} / ${summary["failures"].text("0")}",
                )
                KeyValue("输入 Token", summary["input"].text("0"))
                KeyValue("输出 Token", summary["output"].text("0"))
                KeyValue(
                    "已计费金额",
                    "$" + "%.4f".format(summary["cost"].text().toDoubleOrNull() ?: 0.0),
                )
                KeyValue("未定价请求", summary["unpriced"].text("0"))
            }
        }
        item {
            Panel {
                SectionTitle("账单维护")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.adminCommand("usage.sync", JsonObject(emptyMap())) },
                        enabled = enabled && catalog["sync"].obj()["running"].text() != "true",
                    ) {
                        Text("采集历史用量")
                    }
                    TextButton(
                        onClick = {
                            confirm(
                                ConfirmAction("重算筛选范围费用", "用 PC 当前单价和计费规则重新计算本页筛选范围的历史账单。") {
                                    actions.adminCommand("usage.reprice", filter(0))
                                }
                            )
                        },
                        enabled = enabled && !running,
                    ) {
                        Text("重算费用")
                    }
                }
                val sync = catalog["sync"].obj()
                if (sync["id"].text().isNotBlank()) {
                    KeyValue("已扫描文件", sync["filesScanned"].text("0"))
                    KeyValue("新增记录", sync["added"].text("0"))
                    if (sync["running"].text() == "true")
                        TextButton(
                            onClick = {
                                actions.adminCommand(
                                    "usage.cancelSync",
                                    buildJsonObject { put("id", sync["id"]!!) },
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("停止采集")
                        }
                    sync["errors"].list().take(3).forEach {
                        Text(
                            it.text(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val reprice = catalog["reprice"].obj()
                if (reprice["id"].text().isNotBlank()) {
                    KeyValue(
                        "重算进度",
                        "${reprice["processed"].text("0")} / ${reprice["total"].text("0")}",
                    )
                    if (reprice["running"].text() == "true")
                        TextButton(
                            onClick = {
                                actions.adminCommand(
                                    "usage.cancelReprice",
                                    buildJsonObject { put("id", reprice["id"]!!) },
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("停止重算")
                        }
                }
            }
        }
        item { SectionTitle("模型分布") }
        items(catalog["models"].list()) { value ->
            val row = value.obj()
            Panel {
                Text(row["name"].text(), style = MaterialTheme.typography.titleMedium)
                KeyValue("请求", row["requests"].text())
                KeyValue("费用", "$" + "%.4f".format(row["cost"].text().toDoubleOrNull() ?: 0.0))
            }
        }
        item { SectionTitle("请求记录", "第 ${offset / 30 + 1} 页") }
        items(catalog["records"].list()) { value ->
            val row = value.obj()
            Panel {
                Text(row["model"].text(), style = MaterialTheme.typography.titleMedium)
                KeyValue("状态", statusTitle(row["outcome"].text()))
                KeyValue("供应商", row["provider"].text())
                KeyValue(
                    "Token",
                    row["tokens"].obj().let {
                        "${it["input"].text("0")} 输入 / ${it["output"].text("0")} 输出"
                    },
                )
                KeyValue("费用", row["cost"].text("未定价"))
            }
        }
        if (catalog["records"].list().isEmpty() && !state.adminLoading)
            item { EmptyState(Icons.Outlined.BarChart, "没有匹配的用量记录", "调整筛选范围，或采集 PC 已有会话的用量。") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        offset = (offset - 30).coerceAtLeast(0)
                        refresh()
                    },
                    enabled = offset > 0 && !state.adminLoading,
                ) {
                    Text("上一页")
                }
                OutlinedButton(
                    onClick = {
                        offset += 30
                        refresh()
                    },
                    enabled = catalog["records"].list().size == 30 && !state.adminLoading,
                ) {
                    Text("下一页")
                }
            }
        }
    }
}

@Composable
fun MaintenancePage(
    state: CompanionState,
    enabled: Boolean,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    var catalog by remember { mutableStateOf(JsonObject(emptyMap())) }
    var search by rememberSaveable { mutableStateOf("") }
    var archive by rememberSaveable { mutableStateOf("all") }
    var storageFilter by rememberSaveable { mutableStateOf("available") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<JsonObject?>(null) }
    var handledOutcome by rememberSaveable { mutableStateOf(state.lastAdminOutcome?.requestId) }
    var export by remember { mutableStateOf(false) }
    var storageDialog by remember { mutableStateOf(false) }
    val page = catalog["page"].obj()
    val jobs = catalog["jobs"].list()
    fun query(refresh: Boolean = false) = buildJsonObject {
        put("query", search)
        put("archiveFilter", archive)
        put("storageFilter", storageFilter)
        put("offset", offset)
        put("limit", 50)
        put("refresh", refresh)
    }
    val refresh = { actions.adminQuery("maintenance", query(true)) }
    LaunchedEffect(state.adminResult) {
        state.adminResult
            ?.takeIf { it.containsKey("storage") && it.containsKey("jobs") }
            ?.let { catalog = it }
    }
    LaunchedEffect(page["revision"], offset, search, archive, storageFilter) {
        selected = emptySet()
    }
    LaunchedEffect(state.lastAdminOutcome?.requestId) {
        state.lastAdminOutcome
            ?.takeIf { it.action == "maintenance.prepare" && it.requestId != handledOutcome }
            ?.let {
                pending = it.value
                handledOutcome = it.requestId
            }
    }
    val activeJob = jobs.any {
        it.obj()["state"].text() in listOf("running", "pausing", "cancelling")
    }
    val currentRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(activeJob, state.online, state.busy) {
        if (activeJob && state.online && !state.busy)
            while (true) {
                delay(3000)
                currentRefresh()
            }
    }
    val writable =
        enabled && state.snapshot?.status?.active != true && page["readOnlyReason"].text().isBlank()
    fun prepare(action: String, destination: String? = null) {
        actions.adminCommand(
            "maintenance.prepare",
            buildJsonObject {
                put("action", action)
                put("ids", JsonArray(selected.map(::JsonPrimitive)))
                put("revision", page["revision"] ?: JsonNull)
                destination?.let { put("destination", it) }
            },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AdminRefresh(state, refresh) }
        if (state.adminLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        (state.adminError ?: catalog["error"].text().ifBlank { null })?.let {
            item { Banner(it, true) }
        }
        item {
            Panel {
                SectionTitle("PC 会话存储")
                Text(
                    catalog["storage"].obj()["directory"].text("尚未设置"),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { storageDialog = true }, enabled = enabled && !activeJob) {
                    Text("设置维护数据目录")
                }
                if (state.snapshot?.status?.active == true)
                    Text("ANY 运行中：可浏览和导出；停止后可维护会话。", style = MaterialTheme.typography.bodySmall)
                page["readOnlyReason"]
                    .text()
                    .takeIf { it.isNotBlank() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { SectionTitle("维护任务") }
        if (jobs.isEmpty())
            item {
                Text(
                    "暂时没有后台维护任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        items(jobs, key = { it.obj()["id"].text() }) { value ->
            val job = value.obj()
            val kind = job["state"].text()
            val idInput = buildJsonObject { put("id", job["id"]!!) }
            Panel {
                SectionTitle(maintenanceAction(job["action"].text()))
                KeyValue("状态", statusTitle(kind))
                KeyValue(
                    "进度",
                    "${job["counts"].obj()["completed"].text("0")} / ${job["total"].text("0")}",
                )
                if (job["message"].text().isNotBlank())
                    Text(job["message"].text(), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (kind in listOf("ready", "paused", "cancelled"))
                        TextButton(onClick = { pending = job }, enabled = enabled) { Text("继续") }
                    if (kind == "running")
                        TextButton(
                            onClick = {
                                actions.adminCommand(
                                    "manager",
                                    managerInput(
                                        "sessions.jobs.control",
                                        buildJsonObject {
                                            put("id", job["id"]!!)
                                            put("desired", "pause")
                                        },
                                    ),
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("暂停")
                        }
                    if (kind in listOf("running", "paused", "ready"))
                        TextButton(
                            onClick = {
                                confirm(
                                    ConfirmAction("取消维护任务", "停止后续批次，已处理的批次保持原状。") {
                                        actions.adminCommand(
                                            "manager",
                                            managerInput(
                                                "sessions.jobs.control",
                                                buildJsonObject {
                                                    put("id", job["id"]!!)
                                                    put("desired", "cancel")
                                                },
                                            ),
                                        )
                                    }
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("取消任务")
                        }
                    if (job["canRemove"].text() == "true")
                        TextButton(
                            onClick = {
                                confirm(
                                    ConfirmAction("移除任务记录", "移除这条维护任务记录及其可清理的报告。") {
                                        actions.adminCommand(
                                            "manager",
                                            managerInput("sessions.jobs.remove", idInput),
                                        )
                                    }
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("移除记录")
                        }
                }
            }
        }
        item {
            SectionTitle("会话历史", "筛选 ${page["matched"].text("0")} 条，共 ${page["total"].text("0")} 条")
        }
        item {
            OutlinedTextField(
                search,
                { search = it.take(1024) },
                label = { Text("搜索标题、项目或模型") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            offset = 0
                            refresh()
                        }
                    ) {
                        Icon(Icons.Outlined.Search, "搜索历史")
                    }
                },
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "全部状态", "active" to "未归档", "archived" to "已归档").forEach {
                    (id, label) ->
                    FilterChip(
                        archive == id,
                        {
                            archive = id
                            offset = 0
                            refresh()
                        },
                        label = { Text(label) },
                    )
                }
                listOf("available" to "有内容", "missing" to "内容缺失").forEach { (id, label) ->
                    FilterChip(
                        storageFilter == id,
                        {
                            storageFilter = id
                            offset = 0
                            refresh()
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        item {
            Panel {
                Text("已选 ${selected.size} 条", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            selected = page["sessions"].list().map { it.obj()["id"].text() }.toSet()
                        },
                        enabled = !state.busy,
                    ) {
                        Text("全选本页")
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    OutlinedButton(
                        onClick = { export = true },
                        enabled = enabled && selected.isNotEmpty(),
                    ) {
                        Text("导出到 PC")
                    }
                    OutlinedButton(
                        onClick = { prepare("archive") },
                        enabled = writable && selected.isNotEmpty(),
                    ) {
                        Text("归档")
                    }
                    TextButton(
                        onClick = {
                            prepare(if (storageFilter == "missing") "cleanMissing" else "delete")
                        },
                        enabled = writable && selected.isNotEmpty(),
                    ) {
                        Text(if (storageFilter == "missing") "清理索引" else "删除")
                    }
                }
            }
        }
        items(page["sessions"].list(), key = { it.obj()["id"].text() }) { value ->
            val row = value.obj()
            val id = row["id"].text()
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        id in selected,
                        { checked -> selected = if (checked) selected + id else selected - id },
                        enabled = !state.busy,
                    )
                    Text(
                        row["title"].text("未命名会话"),
                        Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    row["cwd"].text(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                KeyValue("模型", row["model"].text("未知"))
                KeyValue("内容", row["storage"].text("未知"))
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        offset = (offset - 50).coerceAtLeast(0)
                        refresh()
                    },
                    enabled = offset > 0 && !state.adminLoading,
                ) {
                    Text("上一页")
                }
                OutlinedButton(
                    onClick = {
                        offset += 50
                        refresh()
                    },
                    enabled = page["hasMore"].text() == "true" && !state.adminLoading,
                ) {
                    Text("下一页")
                }
            }
        }
    }
    pending?.let { job ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认${maintenanceAction(job["action"].text())}") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("PC 已检查任务，实际涉及 ${job["total"].text("0")} 条会话（含相关子会话）。确认后在 PC 后台分批执行。")
                    job["names"].list().take(10).forEach {
                        Text(
                            it.obj()["title"].text(it.obj()["id"].text()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    job["destination"].text().takeIf { it.isNotBlank() }?.let { Text("导出位置：$it") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.adminCommand(
                            "manager",
                            managerInput(
                                "sessions.jobs.start",
                                buildJsonObject { put("id", job["id"]!!) },
                            ),
                        )
                        pending = null
                        selected = emptySet()
                    },
                    enabled = enabled,
                ) {
                    Text("确认执行")
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("稍后执行") } },
        )
    }
    if (export || storageDialog) {
        var path by
            remember(export, storageDialog) {
                mutableStateOf(
                    if (storageDialog)
                        catalog["storage"]
                            .obj()["suggestedDirectory"]
                            .text(catalog["storage"].obj()["directory"].text())
                    else ""
                )
            }
        AlertDialog(
            onDismissRequest = {
                export = false
                storageDialog = false
            },
            title = { Text(if (storageDialog) "PC 维护数据目录" else "导出 ZIP 到 PC") },
            text = {
                OutlinedTextField(
                    path,
                    { path = it },
                    label = { Text("PC 上的绝对路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (storageDialog)
                            actions.adminCommand(
                                "manager",
                                managerInput(
                                    "sessions.storage.configure",
                                    buildJsonObject { put("directory", path.trim()) },
                                ),
                            )
                        else prepare("export", path.trim())
                        export = false
                        storageDialog = false
                    },
                    enabled = enabled && path.isNotBlank(),
                ) {
                    Text(if (storageDialog) "保存目录" else "准备导出")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        export = false
                        storageDialog = false
                    }
                ) {
                    Text("取消")
                }
            },
        )
    }
}

private fun maintenanceAction(action: String) =
    when (action) {
        "delete" -> "删除会话"
        "archive" -> "归档会话"
        "cleanMissing" -> "清理缺失索引"
        "export" -> "导出会话"
        else -> "会话维护"
    }
