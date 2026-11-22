@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codexlauncher.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.*
import com.codexlauncher.companion.data.*
import kotlinx.serialization.json.*

private val fieldLabels =
    mapOf(
        "provider" to "服务类型",
        "executionMode" to "执行方式",
        "command" to "Codex CLI 命令",
        "workDir" to "项目目录",
        "httpConfig" to "Codex HTTP 参数",
        "claudeCommand" to "Claude CLI 命令",
        "claudeWorkDir" to "Claude 项目目录",
        "claudeHttpConfig" to "Claude HTTP 参数",
        "interval" to "重试间隔（秒）",
        "intervalSeconds" to "请求间隔（秒）",
        "maxTries" to "最大尝试次数",
        "concurrency" to "并发数量",
        "deleteFailedSessions" to "清理失败会话",
        "taskName" to "Windows 任务名称",
        "schedule" to "调度规则",
        "allowedBaseUrls" to "允许的服务地址",
        "keepAlive" to "成功后自动保活",
        "keepAliveIntervalMinutes" to "保活间隔（分钟）",
        "connectionGuard" to "连接守护",
        "crashRecoveryEnabled" to "崩溃恢复",
        "autoSyncUrl" to "自动同步服务地址",
        "cloudHandoff" to "云端接力",
        "dynamicPrompt" to "动态提示词",
        "requestContent" to "请求题库",
        "keepAliveContentMode" to "保活内容类型",
        "desktopNotification" to "桌面通知",
        "serverChan" to "Server 酱通知",
        "email" to "邮件通知",
        "proxy" to "网络代理",
        "useProviderCredentials" to "使用供应商凭据",
        "codexHome" to "Codex 配置目录",
        "claudeHome" to "Claude 配置目录",
        "claudeAutoSyncUrl" to "自动同步 Claude 地址",
        "claudeAllowedBaseUrls" to "允许的 Claude 地址",
        "claudeDeleteFailedSessions" to "清理失败的 Claude 会话",
        "enabled" to "启用",
        "url" to "服务地址",
        "baseUrl" to "服务地址",
        "model" to "模型",
        "prompt" to "请求内容",
        "timeoutSeconds" to "超时（秒）",
        "reasoningEffort" to "推理强度",
        "healthCheckEnabled" to "健康检查",
        "healthIntervalSeconds" to "检查间隔（秒）",
        "autoReacquire" to "断线后重新接入",
        "failureThreshold" to "连续失败阈值",
        "probeTimeoutSeconds" to "探测超时（秒）",
        "observeCodexActivity" to "观察 Codex 活动",
        "workerUrl" to "Cloudflare Worker 地址",
        "apiKey" to "API 密钥",
        "metaPrompt" to "动态内容生成指令",
        "targetWord" to "目标词",
        "categories" to "题目类型",
        "smtpHost" to "SMTP 主机",
        "smtpPort" to "SMTP 端口",
        "smtpUsername" to "SMTP 用户名",
        "toAddress" to "收件地址",
        "resend" to "Resend 邮件",
        "domain" to "邮件域名",
        "fromName" to "发件人名称",
        "fromAddress" to "发件地址",
        "fromLocalPart" to "发件人前缀",
        "subject" to "主题",
        "kind" to "计划类型",
        "time" to "执行时间",
        "everyDays" to "每隔天数",
        "everyWeeks" to "每隔周数",
        "days" to "星期",
        "months" to "月份",
        "day" to "日期",
        "unit" to "时间单位",
        "every" to "间隔数值",
        "startTime" to "开始时间",
        "delaySeconds" to "延迟（秒）",
        "expression" to "Cron 表达式",
        "name" to "名称",
        "input" to "输入单价",
        "output" to "输出单价",
        "cacheRead" to "缓存读取",
        "cacheWrite" to "缓存写入",
        "cacheWrite1h" to "一小时缓存写入",
        "providerId" to "供应商",
        "group" to "计费分组",
        "mode" to "计费方式",
        "prices" to "直接单价",
        "multipliers" to "倍率",
        "websiteUrl" to "网站地址",
        "protocol" to "接口协议",
        "tomlConfig" to "高级 TOML 配置",
        "providerName" to "供应商名称",
        "wireApi" to "Codex 接口",
        "upstreamApi" to "上游接口",
        "requiresOpenaiAuth" to "使用 API 认证",
        "modelMappings" to "模型映射",
        "displayName" to "显示名称",
        "contextWindow" to "上下文窗口",
    )
private val optionLabels =
    mapOf(
        "codex" to "Codex",
        "claude" to "Claude",
        "cli" to "CLI",
        "http" to "HTTP",
        "configured" to "原始内容",
        "questionBank" to "随机题库",
        "ascii" to "ASCII 短码",
        "daily" to "每天",
        "weekly" to "每周",
        "monthly" to "每月",
        "interval" to "固定间隔",
        "atLogon" to "登录时",
        "atStartup" to "开机时",
        "cron" to "Cron",
        "minutes" to "分钟",
        "hours" to "小时",
        "direct" to "直接单价",
        "multiplier" to "倍率",
        "day" to "指定日期",
        "lastDay" to "每月最后一天",
        "xhigh" to "超高",
        "high" to "高",
        "medium" to "中",
        "low" to "低",
        "minimal" to "极简",
        "none" to "关闭",
    )
private val groups =
    listOf(
        "接入与执行" to
            listOf(
                "provider",
                "executionMode",
                "command",
                "workDir",
                "httpConfig",
                "claudeCommand",
                "claudeWorkDir",
                "claudeHttpConfig",
                "interval",
                "maxTries",
                "concurrency",
            ),
        "保活与恢复" to
            listOf(
                "keepAlive",
                "keepAliveIntervalMinutes",
                "keepAliveContentMode",
                "connectionGuard",
                "crashRecoveryEnabled",
                "requestContent",
                "dynamicPrompt",
                "deleteFailedSessions",
                "claudeDeleteFailedSessions",
            ),
        "供应商与网络" to
            listOf(
                "codexHome",
                "claudeHome",
                "useProviderCredentials",
                "autoSyncUrl",
                "allowedBaseUrls",
                "claudeAutoSyncUrl",
                "claudeAllowedBaseUrls",
                "proxy",
                "cloudHandoff",
            ),
        "调度与通知" to listOf("taskName", "schedule", "desktopNotification", "serverChan", "email"),
    )

fun configurationDiff(base: JsonObject, draft: JsonObject): JsonObject = buildJsonObject {
    draft.forEach { (key, value) ->
        if (key != "configVersion" && base[key] != value)
            put(
                key,
                if (value is JsonObject && base[key] is JsonObject && !value.containsKey("kind"))
                    configurationDiff(base[key].obj(), value)
                else value,
            )
    }
}

@Composable
fun ConfigurationPage(state: CompanionState, enabled: Boolean, actions: LauncherActions) {
    val config = state.configuration
    if (config == null) {
        Column {
            if (state.configurationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            EmptyState(Icons.Outlined.Tune, "PC 实时配置", state.adminError ?: "正在读取电脑保存的配置…") {
                OutlinedButton(onClick = actions.loadConfiguration) { Text("重新读取") }
            }
        }
        return
    }
    var draft by remember(config.revision) { mutableStateOf(config.value) }
    val patch = configurationDiff(config.value, draft)
    val assigned = groups.flatMap { it.second }.toSet() + "configVersion"
    val displayedGroups = groups + listOf("其他选项" to draft.keys.filter { it !in assigned })
    val conflict =
        state.snapshot?.configurationRevision?.let { it.isNotEmpty() && it != config.revision } ==
            true
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).testTag("configuration-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Text("运行配置", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "设置自动同步到电脑，下次启动时生效。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (conflict) item { Banner("PC 上已有新配置。保存时会检查冲突，也可以重新读取。", true) }
            state.adminError?.let { item { Banner(it, true) } }
            displayedGroups.forEach { (title, keys) ->
                val fields = keys.filter { draft.containsKey(it) }
                if (fields.isNotEmpty())
                    item(key = title) {
                        SettingsGroup(title) {
                            fields.forEachIndexed { index, key ->
                                if (index > 0) SettingDivider()
                                ConfigField(
                                    key,
                                    draft[key]!!,
                                    config.secretFields,
                                    changed = { next -> draft = JsonObject(draft + (key to next)) },
                                )
                            }
                        }
                    }
            }
            item {
                Text(
                    "运行中的保活开关和内容类型，可在“运行”页即时调整。",
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (patch.isNotEmpty())
                    Text(
                        "有未保存的修改",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (patch.isEmpty()) actions.loadConfiguration() else discard = true
                        },
                        enabled = !state.busy && !state.configurationLoading,
                    ) {
                        Text("重新读取")
                    }
                    Button(
                        onClick = { actions.saveConfiguration(config.revision, patch) },
                        enabled = enabled && patch.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    ) {
                        Text(if (state.busy) "正在保存…" else "保存到 PC")
                    }
                }
            }
        }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("重新读取配置") },
            text = { Text("这会放弃本页尚未保存的修改，读取电脑当前配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        draft = config.value
                        actions.loadConfiguration()
                    }
                ) {
                    Text("重新读取")
                }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
        )
}

internal fun configChoices(path: String): List<String> =
    when (path.substringAfterLast('.')) {
        "provider" -> listOf("codex", "claude")
        "executionMode" -> listOf("cli", "http")
        "reasoningEffort" -> listOf("xhigh", "high", "medium", "low", "minimal", "none")
        "keepAliveContentMode" -> listOf("configured", "questionBank", "ascii")
        "kind" ->
            when (path) {
                "schedule.kind" ->
                    listOf("daily", "weekly", "monthly", "interval", "atLogon", "atStartup", "cron")
                "schedule.day.kind" -> listOf("day", "lastDay")
                else -> emptyList()
            }
        "unit" -> listOf("minutes", "hours")
        "mode" -> if (path == "mode") listOf("direct", "multiplier") else emptyList()
        "upstreamApi" -> listOf("responses", "chat_completions", "anthropic_messages", "gemini")
        else -> emptyList()
    }

internal fun configurationSummary(value: JsonElement, secret: Boolean = false): String =
    when {
        secret -> if (value.text().isBlank()) "未设置" else "已填写"
        value is JsonObject -> {
            val enabled = value["enabled"]?.text()
            val model = value["model"].text()
            when {
                enabled == "true" -> "已开启"
                enabled == "false" -> "已关闭"
                model.isNotBlank() -> model
                else -> "${value.size} 项设置"
            }
        }
        value is JsonArray -> "${value.size} 项"
        value == JsonNull -> "使用默认值"
        else -> optionLabels[value.text()] ?: value.text().ifBlank { "未设置" }
    }

private data class NumberRule(
    val min: Double,
    val max: Double,
    val integer: Boolean = true,
    val step: String = "1",
) {
    val hint: String
        get() {
            fun display(value: Double) = value.toBigDecimal().stripTrailingZeros().toPlainString()
            return "请输入 ${display(min)} 至 ${display(max)} 的${if (integer) "整数" else "数值"}"
        }
}

private fun numberRule(path: String): NumberRule? =
    when (path) {
        "interval" -> NumberRule(1.0, 86400.0)
        "maxTries" -> NumberRule(0.0, 100000.0)
        "concurrency" -> NumberRule(1.0, 99.0)
        "httpConfig.concurrency",
        "claudeHttpConfig.concurrency" -> NumberRule(1.0, 256.0)
        "httpConfig.intervalSeconds",
        "claudeHttpConfig.intervalSeconds" -> NumberRule(0.05, 86400.0, false, "0.05")
        "httpConfig.timeoutSeconds",
        "claudeHttpConfig.timeoutSeconds" -> NumberRule(1.0, 600.0)
        "keepAliveIntervalMinutes" -> NumberRule(1.0, 1440.0)
        "connectionGuard.healthIntervalSeconds" -> NumberRule(15.0, 86400.0)
        "connectionGuard.failureThreshold" -> NumberRule(1.0, 10.0)
        "connectionGuard.probeTimeoutSeconds" -> NumberRule(10.0, 3600.0)
        "schedule.everyDays" -> NumberRule(1.0, 365.0)
        "schedule.everyWeeks" -> NumberRule(1.0, 52.0)
        "schedule.day.day" -> NumberRule(1.0, 31.0)
        "schedule.delaySeconds" -> NumberRule(0.0, 599999.0)
        else -> null
    }

internal fun parseConfigNumber(raw: String, nullable: Boolean, path: String = ""): JsonElement? {
    if (nullable && raw.isBlank()) return JsonNull
    val rule = numberRule(path)
    val parsed =
        if (rule?.integer == true || path.substringAfterLast('.') == "contextWindow")
            raw.toBigDecimalOrNull()?.let {
                runCatching { JsonPrimitive(it.longValueExact()) }.getOrNull()
            }
        else
            raw.toLongOrNull()?.let(::JsonPrimitive)
                ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
    val number = parsed?.doubleOrNull ?: return null
    if (rule != null && number !in rule.min..rule.max) return null
    if (path.substringAfterLast('.') == "contextWindow" && number < 1) return null
    return parsed
}

private fun stepConfigNumber(path: String, raw: String, direction: Int): String {
    val rule = numberRule(path)
    val step = (rule?.step ?: "1").toBigDecimal() * direction.toBigDecimal()
    var value = (raw.toBigDecimalOrNull() ?: (rule?.min ?: 0.0).toBigDecimal()) + step
    if (rule != null) value = value.coerceIn(rule.min.toBigDecimal(), rule.max.toBigDecimal())
    return value.stripTrailingZeros().toPlainString()
}

@Composable
fun ConfigField(
    path: String,
    value: JsonElement,
    secrets: List<String> = emptyList(),
    changed: (JsonElement) -> Unit,
    invalid: (String, Boolean) -> Unit = { _, _ -> },
) {
    val key = path.substringAfterLast('.')
    val label = fieldLabels[key] ?: if (key.toIntOrNull() != null) "第 ${key.toInt() + 1} 项" else key
    val secret = key in listOf("apiKey", "password", "token", "authJson") || path in secrets
    if (value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
        ToggleSetting(label, value.boolean, modifier = Modifier.testTag("config-$path")) {
            changed(JsonPrimitive(it))
        }
        return
    }
    val choices = configChoices(path)
    if (choices.isNotEmpty()) {
        ChoiceSetting(
            label,
            value.text(),
            choices.map { it to (optionLabels[it] ?: it) },
            modifier = Modifier.testTag("config-$path"),
        ) {
            changed(JsonPrimitive(it))
        }
        return
    }
    var editing by remember { mutableStateOf(false) }
    SettingRow(
        label,
        if (path in secrets && value.text().isBlank()) "PC 已保存"
        else configurationSummary(value, secret),
        modifier = Modifier.testTag("config-$path"),
        onClick = { editing = true },
    )
    if (editing) {
        var draft by remember(path) { mutableStateOf(value) }
        var raw by rememberSaveable(path) { mutableStateOf(value.text()) }
        val numeric =
            value is JsonPrimitive &&
                ((!value.isString && value != JsonNull) || key == "contextWindow")
        val parsed =
            if (numeric) parseConfigNumber(raw, key == "contextWindow", path)
            else if (value == JsonNull && raw.isEmpty()) JsonNull else JsonPrimitive(raw)
        EditorSheet(
            label,
            {
                editing = false
                invalid(path, false)
            },
            save = {
                changed(if (value is JsonObject || value is JsonArray) draft else parsed!!)
                invalid(path, false)
                editing = false
            },
            saveEnabled =
                when (value) {
                    is JsonObject -> true
                    is JsonArray ->
                        path !in
                            listOf(
                                "schedule.days",
                                "schedule.months",
                                "requestContent.categories",
                            ) || draft.list().isNotEmpty()
                    else -> parsed != null
                },
        ) {
            when (value) {
                is JsonObject -> ConfigObjectEditor(path, draft.obj(), secrets) { draft = it }
                is JsonArray -> ConfigArrayEditor(path, draft as JsonArray, secrets) { draft = it }
                else -> {
                    if (numeric) {
                        Text(
                            numberRule(path)?.hint ?: "输入数值，或使用下方按钮微调。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircleAction(
                                Icons.Outlined.Remove,
                                "减少$label",
                                { raw = stepConfigNumber(path, raw, -1) },
                            )
                            OutlinedTextField(
                                raw,
                                { raw = it },
                                Modifier.weight(1f).testTag("config-input-$path"),
                                label = { Text("数值") },
                                singleLine = true,
                                isError = parsed == null,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            CircleAction(
                                Icons.Outlined.Add,
                                "增加$label",
                                { raw = stepConfigNumber(path, raw, 1) },
                            )
                        }
                        if (parsed == null)
                            Text(
                                numberRule(path)?.hint ?: "请输入有效数值",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                    } else {
                        val multiline =
                            key in
                                listOf(
                                    "prompt",
                                    "metaPrompt",
                                    "command",
                                    "claudeCommand",
                                    "tomlConfig",
                                    "authJson",
                                    "expression",
                                )
                        OutlinedTextField(
                            raw,
                            { raw = it },
                            Modifier.fillMaxWidth().testTag("config-input-$path"),
                            label = { Text(label) },
                            minLines = if (multiline && !secret) 4 else 1,
                            maxLines = if (multiline && !secret) 12 else 2,
                            singleLine = secret,
                            visualTransformation =
                                if (secret) PasswordVisualTransformation()
                                else VisualTransformation.None,
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                    if (path in secrets)
                        Text(
                            "留空保留电脑已保存的凭据；删除请在凭据管理中操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

@Composable
private fun ConfigObjectEditor(
    path: String,
    value: JsonObject,
    secrets: List<String>,
    changed: (JsonObject) -> Unit,
) {
    SettingsGroup {
        value
            .filterKeys { it !in listOf("configVersion", "updatedMs") }
            .entries
            .forEachIndexed { index, (child, nested) ->
                if (index > 0) SettingDivider()
                ConfigField(
                    "$path.$child",
                    nested,
                    secrets,
                    changed = { next ->
                        changed(
                            if (path == "schedule" && child == "kind") scheduleDefaults(next.text())
                            else if (path == "schedule.day" && child == "kind") {
                                if (next.text() == "lastDay")
                                    buildJsonObject { put("kind", "lastDay") }
                                else
                                    buildJsonObject {
                                        put("kind", "day")
                                        put("day", 1)
                                    }
                            } else JsonObject(value + (child to next))
                        )
                    },
                )
            }
    }
}

@Composable
private fun ConfigArrayEditor(
    path: String,
    value: JsonArray,
    secrets: List<String>,
    changed: (JsonArray) -> Unit,
) {
    val key = path.substringAfterLast('.')
    val choices =
        when (path) {
            "requestContent.categories" ->
                listOf(
                    "aiQuestion" to "AI 问答",
                    "codeExplanation" to "代码讲解",
                    "boundaryCheck" to "边界检查",
                    "bugAnalysis" to "故障分析",
                )
            "schedule.days" ->
                listOf(
                    "monday" to "周一",
                    "tuesday" to "周二",
                    "wednesday" to "周三",
                    "thursday" to "周四",
                    "friday" to "周五",
                    "saturday" to "周六",
                    "sunday" to "周日",
                )
            "schedule.months" ->
                listOf(
                        "january",
                        "february",
                        "march",
                        "april",
                        "may",
                        "june",
                        "july",
                        "august",
                        "september",
                        "october",
                        "november",
                        "december",
                    )
                    .mapIndexed { index, month -> month to "${index + 1} 月" }
            else -> emptyList()
        }
    if (choices.isNotEmpty()) {
        Text(
            "至少选择一项。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup {
            choices.forEachIndexed { index, (id, title) ->
                if (index > 0) SettingDivider()
                val selected = value.any { it.text() == id }
                SettingRow(
                    title,
                    onClick = {
                        changed(
                            JsonArray(
                                if (selected) value.filterNot { it.text() == id }
                                else value + JsonPrimitive(id)
                            )
                        )
                    },
                    trailing = { Checkbox(selected, null) },
                )
            }
        }
    } else {
        if (value.isNotEmpty())
            SettingsGroup {
                value.forEachIndexed { index, element ->
                    if (index > 0) SettingDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            androidx.compose.runtime.key(element.toString(), index) {
                                ConfigField(
                                    "$path.$index",
                                    element,
                                    secrets,
                                    changed = { next ->
                                        changed(
                                            JsonArray(
                                                value.toMutableList().apply { set(index, next) }
                                            )
                                        )
                                    },
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                changed(JsonArray(value.filterIndexed { i, _ -> i != index }))
                            }
                        ) {
                            Icon(Icons.Outlined.RemoveCircleOutline, "移除第 ${index + 1} 项")
                        }
                    }
                }
            }
        if (key == "modelMappings") {
            OutlinedButton(
                onClick = {
                    changed(
                        JsonArray(
                            value +
                                buildJsonObject {
                                    put("model", "")
                                    put("displayName", "")
                                    put("contextWindow", JsonNull)
                                }
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Text("添加模型映射")
            }
        } else if (value.none { it is JsonObject }) {
            var entry by rememberSaveable { mutableStateOf("") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    entry,
                    { entry = it },
                    Modifier.weight(1f),
                    label = { Text("新增项目") },
                )
                IconButton(
                    onClick = {
                        changed(JsonArray(value + JsonPrimitive(entry.trim())))
                        entry = ""
                    },
                    enabled = entry.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.AddCircleOutline, "添加项目")
                }
            }
        }
    }
}

private fun scheduleDefaults(kind: String): JsonObject =
    WireJson.parseToJsonElement(
            when (kind) {
                "daily" -> """{"kind":"daily","time":"09:00","everyDays":1}"""
                "weekly" -> """{"kind":"weekly","time":"09:00","everyWeeks":1,"days":["monday"]}"""
                "monthly" ->
                    """{"kind":"monthly","time":"09:00","day":{"kind":"day","day":1},"months":["january","february","march","april","may","june","july","august","september","october","november","december"]}"""
                "interval" ->
                    """{"kind":"interval","unit":"minutes","every":30,"startTime":"09:00"}"""
                "atLogon",
                "atStartup" -> """{"kind":"$kind","delaySeconds":30}"""
                else -> """{"kind":"cron","expression":"0 9 * * *"}"""
            }
        )
        .obj()
