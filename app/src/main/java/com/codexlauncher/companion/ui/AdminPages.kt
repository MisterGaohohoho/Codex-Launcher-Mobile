@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.codexlauncher.companion.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import java.util.UUID
import kotlinx.serialization.json.*

@Composable
fun SettingsPage(
    state: CompanionState,
    actions: LauncherActions,
    navigate: (String) -> Unit,
    pair: () -> Unit,
    confirm: (ConfirmAction) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Panel {
                SectionTitle("已连接的电脑")
                KeyValue("名称", state.computer?.computerName.orEmpty())
                KeyValue("地址", state.computer?.endpoint.orEmpty())
                KeyValue("权限", if (state.computer?.canControl == true) "完整管理" else "仅查看")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pair, enabled = !state.busy) { Text("重新配对") }
                    TextButton(
                        onClick = {
                            confirm(
                                ConfirmAction("移除此电脑", "移除手机保存的授权。重新连接时需要扫描 PC 上的配对码。") {
                                    actions.forget()
                                }
                            )
                        },
                        enabled = !state.busy,
                    ) {
                        Text("移除连接")
                    }
                }
            }
        }
        item {
            Panel {
                SectionTitle("外观")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, name)
                        ->
                        FilterChip(state.theme == id, { actions.theme(id) }, label = { Text(name) })
                    }
                }
            }
        }
        item { SectionTitle("PC 管理") }
        listOf(
                "configuration" to "完整运行配置",
                "providers" to "供应商与模型",
                "usage" to "用量与请求记录",
                "billing" to "单价与计费规则",
                "schedule" to "Windows 计划任务",
                "maintenance" to "会话历史与维护",
                "credentials" to "凭据管理",
                "workbench-settings" to "Codex 工作台连接",
            )
            .forEach { (id, label) ->
                item {
                    FilledTonalButton(
                        onClick = { navigate(id) },
                        enabled = state.computer?.canControl == true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) {
                        Text(label, Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        item { UpdatePanel(state.update, actions) }
        item {
            Text(
                "Launcher ${BuildConfig.VERSION_NAME} · Android 原生工作台",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun managerInput(operation: String, input: JsonObject = JsonObject(emptyMap())) =
    buildJsonObject {
        put("operation", operation)
        put("input", input)
    }

@Composable
fun AdminPage(
    page: String,
    state: CompanionState,
    enabled: Boolean,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    if (page == "maintenance") {
        MaintenancePage(state, enabled, actions, confirm)
        return
    }
    if (page == "usage") {
        UsagePage(state, enabled, actions, confirm)
        return
    }
    val result = state.adminResult
    var catalog by remember(page) { mutableStateOf<JsonObject?>(null) }
    var editing by remember(page) { mutableStateOf<Pair<String, JsonObject>?>(null) }
    var pendingInspection by remember { mutableStateOf<String?>(null) }
    var inspection by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    LaunchedEffect(result, state.adminLoading) {
        if (result != null) {
            val catalogResult =
                when (page) {
                    "providers" -> result["providers"] is JsonArray
                    "billing" -> result["settings"] is JsonObject
                    "credentials" -> result["http"] is JsonObject
                    else -> result.containsKey("installed")
                }
            if (catalogResult) catalog = result
            if (!state.adminLoading && pendingInspection != null) {
                if (pendingInspection == "编辑供应商" && result["providerName"] != null)
                    editing = "provider" to result
                else if (!catalogResult) inspection = pendingInspection!! to result
                pendingInspection = null
            }
        }
    }
    val data = catalog ?: JsonObject(emptyMap())
    val refresh = {
        if (page == "providers") actions.adminQuery("manager", managerInput("providers.list"))
        else actions.adminQuery(page, JsonObject(emptyMap()))
    }
    val inspect: (String, String, JsonObject) -> Unit = { title, operation, input ->
        pendingInspection = title
        actions.adminQuery("manager", managerInput(operation, input))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AdminRefresh(state, refresh) }
        if (state.adminLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.adminError?.let { item { Banner(it, true) } }
        when (page) {
            "providers" -> {
                item {
                    Panel {
                        KeyValue("当前模型", data["model"].text("等待同步"))
                        KeyValue("当前供应商", data["modelProvider"].text("等待同步"))
                        Text(
                            "切换供应商前请停止 ANY 接入和保活。工作台使用所连接目录中的 Codex 配置。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(data["providers"].list(), key = { it.obj()["id"].text() }) { value ->
                    val row = value.obj()
                    val id = row["id"].text()
                    val input = buildJsonObject { put("id", id) }
                    Panel {
                        SectionTitle(row["providerName"].text())
                        Text(row["baseUrl"].text(), style = MaterialTheme.typography.bodySmall)
                        KeyValue("模型", row["model"].text())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    confirm(
                                        ConfirmAction(
                                            "启用供应商",
                                            "将 PC 的 Codex 接入切换为 ${row["providerName"].text()}。",
                                        ) {
                                            actions.adminCommand(
                                                "manager",
                                                managerInput("providers.activate", input),
                                            )
                                        }
                                    )
                                },
                                enabled = enabled && data["activeProviderId"].text() != id,
                            ) {
                                Text(if (data["activeProviderId"].text() == id) "当前使用" else "启用")
                            }
                            OutlinedButton(
                                onClick = { inspect("编辑供应商", "providers.detail", input) },
                                enabled = enabled,
                            ) {
                                Text("编辑")
                            }
                            TextButton(
                                onClick = {
                                    confirm(
                                        ConfirmAction(
                                            "删除供应商",
                                            "删除 ${row["providerName"].text()} 的已保存配置。",
                                        ) {
                                            actions.adminCommand(
                                                "manager",
                                                managerInput("providers.delete", input),
                                            )
                                        }
                                    )
                                },
                                enabled = enabled,
                            ) {
                                Text("删除")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { inspect("连接测试", "providers.test", input) },
                                enabled = enabled && !state.adminLoading,
                            ) {
                                Text("测试连接")
                            }
                            TextButton(
                                onClick = { inspect("可用模型", "providers.models", input) },
                                enabled = enabled && !state.adminLoading,
                            ) {
                                Text("模型列表")
                            }
                            TextButton(
                                onClick = {
                                    inspect(
                                        "供应商余额",
                                        "providers.balance",
                                        buildJsonObject {
                                            put("id", id)
                                            put("source", "local")
                                        },
                                    )
                                },
                                enabled = enabled && !state.adminLoading,
                            ) {
                                Text("余额")
                            }
                            TextButton(
                                onClick = {
                                    actions.adminCommand(
                                        "manager",
                                        managerInput("providers.duplicate", input),
                                    )
                                },
                                enabled = enabled,
                            ) {
                                Text("复制")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { editing = "provider" to newProvider() },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text("添加供应商")
                    }
                }
                if (data["detected"] is JsonObject)
                    item {
                        TextButton(
                            onClick = {
                                actions.adminCommand(
                                    "manager",
                                    managerInput("providers.importCurrent"),
                                )
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("导入 PC 当前供应商")
                        }
                    }
                items(data["officialProfiles"].list()) { value ->
                    val row = value.obj()
                    val input = buildJsonObject { put("id", row["id"] ?: JsonNull) }
                    Panel {
                        SectionTitle(row["providerName"].text("官方账号"))
                        Text(
                            row["email"].text(row["planType"].text()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    confirm(
                                        ConfirmAction("切换官方账号", "切换 PC 保存的官方 Codex 账号配置。") {
                                            actions.adminCommand(
                                                "manager",
                                                managerInput("official.activate", input),
                                            )
                                        }
                                    )
                                },
                                enabled = enabled,
                            ) {
                                Text("启用此账号")
                            }
                            TextButton(
                                onClick = { inspect("账号用量限额", "official.quota", input) },
                                enabled = enabled && row["canQueryQuota"].text() == "true",
                            ) {
                                Text("查看额度")
                            }
                        }
                    }
                }
            }
            "credentials" -> {
                item { Banner("凭据通过已配对的加密连接写入 PC 凭据库，手机不保存明文密钥。") }
                listOf(
                        "http" to "HTTP API 密钥",
                        "smtp" to "SMTP 密码",
                        "resend" to "Resend API 密钥",
                        "cloud" to "云端接力令牌",
                        "serverChan" to "Server 酱 SendKey",
                        "proxy" to "网络代理密码",
                    )
                    .forEach { (kind, title) ->
                        item {
                            Panel {
                                SectionTitle(title)
                                Text(
                                    if (data[kind].obj()["configured"].text() == "true") "PC 已配置"
                                    else "尚未配置",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            editing =
                                                "credential:$kind" to
                                                    buildJsonObject { put("value", "") }
                                        },
                                        enabled = enabled,
                                    ) {
                                        Text("设置凭据")
                                    }
                                    TextButton(
                                        onClick = {
                                            confirm(
                                                ConfirmAction("删除凭据", "删除 PC 保存的$title。") {
                                                    actions.adminCommand(
                                                        "credentials.delete",
                                                        buildJsonObject { put("kind", kind) },
                                                    )
                                                }
                                            )
                                        },
                                        enabled = enabled,
                                    ) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
            }
            "billing" -> {
                val settings = data["settings"].obj()
                item { Banner("单价以每百万 Token 为单位。新规则应用于后续请求；用量页可重新计算历史费用。") }
                item {
                    SectionTitle(
                        "模型单价",
                        action = {
                            TextButton(
                                onClick = {
                                    editing =
                                        "price" to
                                            buildJsonObject {
                                                put("model", "")
                                                listOf(
                                                        "input",
                                                        "output",
                                                        "cacheRead",
                                                        "cacheWrite",
                                                        "cacheWrite1h",
                                                    )
                                                    .forEach { put(it, 0) }
                                            }
                                },
                                enabled = enabled,
                            ) {
                                Text("添加")
                            }
                        },
                    )
                }
                items(settings["prices"].list()) { value ->
                    Panel {
                        SectionTitle(value.obj()["model"].text())
                        KeyValue(
                            "输入 / 输出",
                            "${value.obj()["input"].text()} / ${value.obj()["output"].text()}",
                        )
                        FlowRow {
                            TextButton(
                                onClick = { editing = "price" to value.obj() },
                                enabled = enabled,
                            ) {
                                Text("编辑单价")
                            }
                            TextButton(
                                onClick = {
                                    confirm(
                                        ConfirmAction(
                                            "删除模型单价",
                                            "删除 ${value.obj()["model"].text()} 的单价。",
                                        ) {
                                            actions.adminCommand(
                                                "billing.deletePrice",
                                                buildJsonObject {
                                                    put("model", value.obj()["model"]!!)
                                                },
                                            )
                                        }
                                    )
                                },
                                enabled = enabled,
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
                item {
                    SectionTitle(
                        "供应商计费规则",
                        action = {
                            TextButton(
                                onClick = { editing = "rule" to newBillingRule() },
                                enabled = enabled,
                            ) {
                                Text("添加")
                            }
                        },
                    )
                }
                items(settings["rules"].list()) { value ->
                    val rule = value.obj()
                    Panel {
                        SectionTitle(rule["name"].text())
                        KeyValue("模型", rule["model"].text())
                        FlowRow {
                            TextButton(onClick = { editing = "rule" to rule }, enabled = enabled) {
                                Text("编辑")
                            }
                            TextButton(
                                onClick = {
                                    confirm(
                                        ConfirmAction(
                                            "删除计费规则",
                                            "删除规则 ${rule["name"].text()}。已有账单快照保持不变。",
                                        ) {
                                            actions.adminCommand(
                                                "billing.deleteRule",
                                                buildJsonObject {
                                                    put("id", rule["id"] ?: JsonNull)
                                                },
                                            )
                                        }
                                    )
                                },
                                enabled = enabled,
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
                item {
                    SectionTitle(
                        "供应商分组",
                        action = {
                            TextButton(
                                onClick = {
                                    editing =
                                        "group" to
                                            buildJsonObject {
                                                put("providerId", "")
                                                put("group", "default")
                                            }
                                },
                                enabled = enabled,
                            ) {
                                Text("设置")
                            }
                        },
                    )
                }
                items(settings["providerGroups"].list()) { value ->
                    val group = value.obj()
                    Panel {
                        Text(
                            data["providers"]
                                .list()
                                .find { it.obj()["id"] == group["providerId"] }
                                ?.obj()
                                ?.get("name")
                                .text(group["providerId"].text())
                        )
                        KeyValue("分组", group["group"].text())
                        TextButton(onClick = { editing = "group" to group }, enabled = enabled) {
                            Text("编辑分组")
                        }
                    }
                }
            }
            "schedule" ->
                item {
                    Panel {
                        SectionTitle(
                            if (data["installed"].text() == "true") "计划任务已安装" else "计划任务未安装"
                        )
                        SelectionContainer {
                            Text(
                                data["detail"].text("先在完整运行配置中设置调度规则。"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = {
                                confirm(
                                    ConfirmAction("安装计划任务", "使用 PC 当前保存的配置创建或更新 Windows 计划任务。") {
                                        actions.adminCommand(
                                            "schedule.install",
                                            JsonObject(emptyMap()),
                                        )
                                    }
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("安装或更新")
                        }
                        OutlinedButton(
                            onClick = {
                                confirm(
                                    ConfirmAction("移除计划任务", "移除 PC 当前配置对应的 Windows 计划任务。") {
                                        actions.adminCommand(
                                            "schedule.remove",
                                            JsonObject(emptyMap()),
                                        )
                                    }
                                )
                            },
                            enabled = enabled,
                        ) {
                            Text("移除计划任务")
                        }
                    }
                }
        }
    }
    editing?.let { (kind, initial) ->
        var draft by remember(kind, initial) { mutableStateOf(initial) }
        val errors = remember(kind, initial) { mutableStateMapOf<String, Boolean>() }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    when {
                        kind == "provider" -> "供应商配置"
                        kind.startsWith("credential:") -> "设置 PC 凭据"
                        kind == "price" -> "模型单价"
                        kind == "group" -> "供应商分组"
                        else -> "计费规则"
                    }
                )
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (kind.startsWith("credential:"))
                        OutlinedTextField(
                            draft["value"].text(),
                            { draft = buildJsonObject { put("value", it) } },
                            label = { Text("新凭据") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else
                        draft
                            .filterKeys { it !in listOf("id", "updatedMs") }
                            .forEach { (key, value) ->
                                if (key == "providerId" && data["providers"] is JsonArray) {
                                    Text("供应商", style = MaterialTheme.typography.labelLarge)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        data["providers"].list().forEach { provider ->
                                            val p = provider.obj()
                                            FilterChip(
                                                value == p["id"],
                                                { draft = JsonObject(draft + (key to p["id"]!!)) },
                                                label = { Text(p["name"].text()) },
                                            )
                                        }
                                    }
                                } else
                                    ConfigField(
                                        key,
                                        value,
                                        changed = { next ->
                                            draft = JsonObject(draft + (key to next))
                                        },
                                        invalid = { path, bad -> errors[path] = bad },
                                    )
                            }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            kind == "provider" ->
                                actions.adminCommand(
                                    "manager",
                                    managerInput(
                                        "providers.save",
                                        buildJsonObject { put("provider", draft) },
                                    ),
                                )
                            kind == "price" -> actions.adminCommand("billing.savePrice", draft)
                            kind == "rule" -> actions.adminCommand("billing.saveRule", draft)
                            kind == "group" -> actions.adminCommand("billing.saveGroup", draft)
                            kind.startsWith("credential:") ->
                                actions.adminCommand(
                                    "credentials.set",
                                    buildJsonObject {
                                        put("kind", kind.substringAfter(':'))
                                        put("value", draft["value"] ?: JsonNull)
                                    },
                                )
                        }
                        editing = null
                    },
                    enabled =
                        enabled &&
                            errors.values.none { it } &&
                            (!kind.startsWith("credential:") ||
                                draft["value"].text().isNotBlank()) &&
                            (kind !in listOf("rule", "group") ||
                                draft["providerId"].text().isNotBlank()),
                ) {
                    Text("保存到 PC")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
    inspection?.let { (title, value) ->
        AlertDialog(
            onDismissRequest = { inspection = null },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (title) {
                        "可用模型" -> {
                            value["models"].list().forEach { Text(it.obj()["id"].text()) }
                            value["warning"].text().takeIf { it.isNotBlank() }?.let { Text(it) }
                        }
                        "供应商余额" -> {
                            Text(
                                if (value["supported"].text() == "true") "余额查询完成"
                                else "此供应商暂不支持余额查询"
                            )
                            value["balances"].list().forEach {
                                Text("${it.obj()["amount"].text()} ${it.obj()["currency"].text()}")
                            }
                            value["errorCode"].text().takeIf { it.isNotBlank() }?.let { Text(it) }
                        }
                        "账号用量限额" -> {
                            Text(value["email"].text())
                            value["limits"].list().forEach { limit ->
                                Text(limit.obj()["name"].text(limit.obj()["id"].text()))
                                limit.obj()["windows"].list().forEach { window ->
                                    KeyValue(
                                        "剩余比例",
                                        "${window.obj()["remainingPercent"].text("未知")}%",
                                    )
                                    KeyValue("重置时间", window.obj()["resetsAt"].text("未知"))
                                }
                            }
                        }
                        else -> {
                            Text(value["message"].text())
                            KeyValue("结果", if (value["ok"].text() == "true") "连接成功" else "未通过")
                            KeyValue("状态码", value["status"].text("无"))
                            KeyValue("耗时", "${value["durationMs"].text("0")} ms")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inspection = null }) { Text("关闭") } },
        )
    }
}

@Composable
internal fun AdminRefresh(state: CompanionState, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("数据来自当前 PC", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = refresh, enabled = !state.adminLoading && !state.busy) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            Text("刷新")
        }
    }
}

private fun newProvider() = buildJsonObject {
    put("id", UUID.randomUUID().toString())
    put("providerName", "")
    put("baseUrl", "")
    put("model", "")
    put("apiKey", "")
    put("tomlConfig", JsonNull)
    put("wireApi", "responses")
    put("upstreamApi", "responses")
    put("requiresOpenaiAuth", true)
    put("modelMappings", JsonArray(emptyList()))
}

private fun newBillingRule() = buildJsonObject {
    put("id", UUID.randomUUID().toString())
    put("name", "")
    put("providerId", "")
    put("group", "*")
    put("model", "*")
    put("mode", "direct")
    put(
        "prices",
        buildJsonObject {
            listOf("input", "output", "cacheRead", "cacheWrite", "cacheWrite1h").forEach {
                put(it, 0)
            }
        },
    )
    put(
        "multipliers",
        buildJsonObject {
            listOf("base", "model", "output", "group", "cacheRead", "cacheWrite", "cacheWrite1h")
                .forEach { put(it, 1) }
        },
    )
}
