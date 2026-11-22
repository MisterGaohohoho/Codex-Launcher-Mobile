@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.codexlauncher.companion.ui

import androidx.compose.animation.AnimatedVisibility
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
    val errors = remember(config.revision) { mutableStateMapOf<String, Boolean>() }
    var group by rememberSaveable { mutableIntStateOf(0) }
    val patch = configurationDiff(config.value, draft)
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { index, value ->
                FilterChip(group == index, { group = index }, label = { Text(value.first) })
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Banner(
                    if (
                        state.snapshot?.configurationRevision?.let {
                            it.isNotEmpty() && it != config.revision
                        } == true
                    )
                        "PC 配置已有新版本。保存时会检查冲突，也可以重新读取。"
                    else "修改会保存到 PC，并用于下次启动。运行页可即时调整本轮保活。"
                )
            }
            state.adminError?.let { item { Banner(it, true) } }
            groups[group]
                .second
                .filter { draft.containsKey(it) }
                .forEach { key ->
                    item(key = "${config.revision}-$key") {
                        Panel {
                            ConfigField(
                                key,
                                draft[key]!!,
                                config.secretFields,
                                { next -> draft = JsonObject(draft + (key to next)) },
                            ) { path, invalid ->
                                errors[path] = invalid
                            }
                        }
                    }
                }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = actions.loadConfiguration,
                    enabled = !state.busy && !state.configurationLoading,
                ) {
                    Text("重新读取")
                }
                Button(
                    onClick = { actions.saveConfiguration(config.revision, patch) },
                    enabled = enabled && patch.isNotEmpty() && errors.values.none { it },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.busy) "正在保存…" else "保存到 PC")
                }
            }
        }
    }
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
    val label = fieldLabels[key] ?: key
    when (value) {
        is JsonObject -> {
            var expanded by rememberSaveable(path) { mutableStateOf(path.count { it == '.' } == 0) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    value.forEach { (child, nested) ->
                        if (child != "configVersion" && child != "updatedMs")
                            ConfigField(
                                "$path.$child",
                                nested,
                                secrets,
                                { next ->
                                    if (path == "schedule" && child == "kind")
                                        changed(scheduleDefaults(next.text()))
                                    else if (path == "schedule.day" && child == "kind")
                                        changed(
                                            if (next.text() == "lastDay")
                                                buildJsonObject { put("kind", "lastDay") }
                                            else
                                                buildJsonObject {
                                                    put("kind", "day")
                                                    put("day", 1)
                                                }
                                        )
                                    else changed(JsonObject(value + (child to next)))
                                },
                                invalid,
                            )
                    }
                }
            }
        }
        is JsonArray -> {
            if (key == "modelMappings" || value.any { it is JsonObject }) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                value.forEachIndexed { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfigField(
                            "$path.$index",
                            item,
                            secrets,
                            { next ->
                                changed(JsonArray(value.toMutableList().apply { set(index, next) }))
                            },
                            invalid,
                        )
                        TextButton(
                            onClick = {
                                changed(JsonArray(value.filterIndexed { i, _ -> i != index }))
                            }
                        ) {
                            Text("移除此映射")
                        }
                    }
                }
                if (key == "modelMappings")
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
                        }
                    ) {
                        Text("添加模型映射")
                    }
            } else if (path in listOf("schedule.days", "schedule.months")) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                val choices =
                    if (path == "schedule.days")
                        listOf(
                            "monday" to "周一",
                            "tuesday" to "周二",
                            "wednesday" to "周三",
                            "thursday" to "周四",
                            "friday" to "周五",
                            "saturday" to "周六",
                            "sunday" to "周日",
                        )
                    else
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { (id, title) ->
                        val selected = value.any { it.text() == id }
                        FilterChip(
                            selected,
                            {
                                changed(
                                    JsonArray(
                                        if (selected) value.filterNot { it.text() == id }
                                        else value + JsonPrimitive(id)
                                    )
                                )
                            },
                            label = { Text(title) },
                        )
                    }
                }
            } else {
                var text by
                    rememberSaveable(path) {
                        mutableStateOf(value.joinToString("\n") { it.text() })
                    }
                var emitted by rememberSaveable(path) { mutableStateOf(value.toString()) }
                LaunchedEffect(value) {
                    if (value.toString() != emitted) text = value.joinToString("\n") { it.text() }
                    emitted = value.toString()
                }
                OutlinedTextField(
                    text,
                    {
                        text = it
                        val parsed =
                            JsonArray(
                                it.lines()
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .map(::JsonPrimitive)
                            )
                        emitted = parsed.toString()
                        changed(parsed)
                    },
                    label = { Text(label) },
                    supportingText = { Text("每行一项") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is JsonPrimitive ->
            when {
                !value.isString && value.booleanOrNull != null ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(value.boolean, { changed(JsonPrimitive(it)) })
                    }
                else -> {
                    val choices =
                        when (key) {
                            "provider" -> listOf("codex", "claude")
                            "executionMode" -> listOf("cli", "http")
                            "keepAliveContentMode" -> listOf("configured", "questionBank", "ascii")
                            "kind" ->
                                if (path == "schedule.kind")
                                    listOf(
                                        "daily",
                                        "weekly",
                                        "monthly",
                                        "interval",
                                        "atLogon",
                                        "atStartup",
                                        "cron",
                                    )
                                else if (path == "schedule.day.kind") listOf("day", "lastDay")
                                else emptyList()
                            "unit" -> listOf("minutes", "hours")
                            "mode" ->
                                if (path == "mode") listOf("direct", "multiplier") else emptyList()
                            "upstreamApi" ->
                                listOf(
                                    "responses",
                                    "chat_completions",
                                    "anthropic_messages",
                                    "gemini",
                                )
                            else -> emptyList()
                        }
                    if (choices.isNotEmpty()) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { choice ->
                                FilterChip(
                                    value.text() == choice,
                                    { changed(JsonPrimitive(choice)) },
                                    label = { Text(optionLabels[choice] ?: choice) },
                                )
                            }
                        }
                    } else {
                        var text by
                            rememberSaveable(path) { mutableStateOf(value.contentOrNull.orEmpty()) }
                        var emitted by rememberSaveable(path) { mutableStateOf(value.toString()) }
                        var bad by rememberSaveable(path) { mutableStateOf(false) }
                        LaunchedEffect(value) {
                            if (value.toString() != emitted) {
                                text = value.contentOrNull.orEmpty()
                                bad = false
                                invalid(path, false)
                            }
                            emitted = value.toString()
                        }
                        val numeric =
                            (!value.isString && value != JsonNull) || key == "contextWindow"
                        val secret = key in listOf("apiKey", "password", "token", "authJson")
                        OutlinedTextField(
                            text,
                            { raw ->
                                text = raw
                                val parsed: JsonElement? =
                                    if (key == "contextWindow" && raw.isBlank()) JsonNull
                                    else if (numeric)
                                        raw.toLongOrNull()?.let(::JsonPrimitive)
                                            ?: raw.toDoubleOrNull()
                                                ?.takeIf { it.isFinite() }
                                                ?.let(::JsonPrimitive)
                                    else JsonPrimitive(raw)
                                bad = parsed == null
                                invalid(path, bad)
                                if (parsed != null) {
                                    emitted = parsed.toString()
                                    changed(parsed)
                                }
                            },
                            label = { Text(label) },
                            supportingText =
                                if (path in secrets) ({ Text("PC 已保存密钥；不修改则保留") }) else null,
                            modifier = Modifier.fillMaxWidth(),
                            isError = bad,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        if (numeric) KeyboardType.Decimal else KeyboardType.Text
                                ),
                            visualTransformation =
                                if (secret) PasswordVisualTransformation()
                                else VisualTransformation.None,
                            minLines =
                                if (key in listOf("prompt", "metaPrompt", "command", "tomlConfig"))
                                    3
                                else 1,
                            maxLines = if (secret) 1 else 8,
                        )
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
