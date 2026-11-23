package com.codexlauncher.companion.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

fun JsonElement?.text(fallback: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull ?: fallback

fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

fun jsonObject(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*values))

fun jsonText(value: String) = JsonPrimitive(value)

@Serializable
data class AdminCommand(
    val requestId: String,
    val action: String,
    val input: JsonObject,
    val commandEpoch: String = "",
    val issuedAt: Long = 0,
)

@Serializable
data class ConfigurationEnvelope(
    val revision: String,
    val value: JsonObject,
    val secretFields: List<String> = emptyList(),
    val applyMode: String = "nextRun",
    val message: String = "",
)

@Serializable
data class StreamBatch(
    val epoch: String = "",
    val cursor: Long = 0,
    val reset: Boolean = false,
    val events: List<JsonObject> = emptyList(),
    val approvals: List<JsonObject> = emptyList(),
    val connected: Boolean = true,
)

data class WorkThread(
    val id: String,
    val title: String,
    val cwd: String,
    val status: String,
    val control: String,
    val updatedAt: Long,
    val provider: String,
    val source: String,
    val model: String = "",
) {
    val canContinueOriginal: Boolean
        get() = control in setOf("managed", "attach")

    val project: String
        get() = cwd.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { "未命名项目" }

    companion object {
        fun parse(value: JsonObject, control: String = value["control"].text("readOnly")) =
            WorkThread(
                value["id"].text(),
                value["name"].text().ifBlank {
                    value["preview"].text().take(100).ifBlank { "新会话" }
                },
                value["cwd"].text(),
                value["status"].obj()["type"].text("notLoaded"),
                control,
                value["updatedAt"].text().toLongOrNull() ?: 0,
                value["modelProvider"].text(),
                value["source"].text("Codex"),
                value["model"].text(),
            )
    }
}

@Serializable
data class WorkItem(
    val id: String,
    val turnId: String,
    val kind: String,
    val title: String,
    val text: String,
    val status: String = "",
    val hasMore: Boolean = false,
    val clientId: String = "",
    val localEcho: Boolean = false,
    val attachments: List<AttachmentMeta> = emptyList(),
) {
    companion object {
        fun parse(item: JsonObject, turn: String): WorkItem? {
            val kind = item["type"].text()
            if (kind == "reasoning") return null
            val text =
                when (kind) {
                    "userMessage" ->
                        item["displayText"]?.text()
                            ?: item["content"]
                                .list()
                                .map { it.obj()["text"].text() }
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                    "agentMessage" -> item["text"].text()
                    "commandExecution" ->
                        listOf(item["command"].text(), item["aggregatedOutput"].text())
                            .filter { it.isNotBlank() }
                            .joinToString("\n\n")
                    "fileChange" ->
                        item["changes"].list().joinToString("\n\n") { change ->
                            val c = change.obj()
                            c["path"].text() + "\n" + c["diff"].text()
                        }
                    "plan" -> item["text"].text()
                    "contextCompaction" -> "历史上下文已整理。"
                    "mcpToolCall" ->
                        listOf(
                                item["server"].text() + "/" + item["tool"].text(),
                                item["arguments"]?.toString().orEmpty(),
                                item["result"]?.toString().orEmpty(),
                                item["error"]?.toString().orEmpty(),
                            )
                            .filter { it.isNotBlank() && it != "null" }
                            .joinToString("\n\n")
                    "dynamicToolCall" ->
                        listOf(
                                item["tool"].text(),
                                item["arguments"]?.toString().orEmpty(),
                                item["contentItems"]?.toString().orEmpty(),
                            )
                            .filter { it.isNotBlank() && it != "null" }
                            .joinToString("\n\n")
                    "webSearch" -> item["query"].text()
                    else -> item["text"].text().ifBlank { item["status"].text() }
                }
            return WorkItem(
                item["id"].text("$turn-$kind"),
                turn,
                kind,
                when (kind) {
                    "userMessage" -> "你"
                    "agentMessage" -> "Codex"
                    "commandExecution" -> "执行命令"
                    "fileChange" -> "文件变更"
                    "plan" -> "开发计划"
                    "contextCompaction" -> "整理上下文"
                    else -> kind
                },
                text,
                item["status"].text(),
                clientId = item["clientId"].text(),
                attachments =
                    item["attachments"]
                        .list()
                        .mapNotNull {
                            runCatching { WireJson.decodeFromJsonElement<AttachmentMeta>(it) }
                                .getOrNull()
                        }
                        .ifEmpty {
                            item["content"].list().mapIndexedNotNull { index, value ->
                                if (value.obj()["type"].text() in listOf("image", "localImage"))
                                    AttachmentMeta(
                                        "external-${item["id"].text()}-$index",
                                        "PC 图片",
                                        kind = "image",
                                        readStatus = "unavailable",
                                        readMessage = "此图片来自其他客户端，未登记到附件服务，手机暂不可预览",
                                    )
                                else null
                            }
                        },
            )
        }
    }
}

data class ThreadDetail(
    val thread: WorkThread,
    val items: List<WorkItem>,
    val activeTurn: String? = null,
    val nextCursor: String? = null,
    val diff: String = "",
    val backendScopeId: String = "",
) {
    companion object {
        fun parse(value: JsonObject): ThreadDetail {
            val thread = WorkThread.parse(value["thread"].obj(), value["control"].text("readOnly"))
            val page = value["turnsPage"].obj()
            val turns =
                if (page["data"] is JsonArray) page["data"].list().reversed()
                else value["thread"].obj()["turns"].list()
            val itemPage = value["itemsPage"] as? JsonObject
            val pagedItems =
                itemPage?.get("data").list().reversed().mapNotNull { entry ->
                    val row = entry.obj()
                    WorkItem.parse(row["item"].obj(), row["turnId"].text())
                }
            val turnItems = turns.flatMap { turn ->
                val t = turn.obj()
                val id = t["id"].text()
                val messages = t["items"].list().mapNotNull { WorkItem.parse(it.obj(), id) }
                val error = t["error"].obj()["message"].text()
                if (
                    error.isBlank() ||
                        t["status"].text() != "failed" ||
                        (itemPage != null &&
                            pagedItems.isNotEmpty() &&
                            pagedItems.none { it.turnId == id })
                )
                    messages
                else messages + WorkItem("error-$id", id, "error", "执行失败", error, "failed")
            }
            val items =
                if (itemPage == null) turnItems
                else pagedItems + turnItems.filter { it.kind == "error" }
            return ThreadDetail(
                thread,
                items,
                value["activeTurnId"].text().ifBlank { null }
                    ?: turns
                        .lastOrNull { it.obj()["status"].text() == "inProgress" }
                        ?.obj()
                        ?.get("id")
                        ?.text(),
                (itemPage ?: page)["nextCursor"].text().ifBlank { null },
                backendScopeId = value["backendScopeId"].text(),
            )
        }
    }
}

data class WorkbenchState(
    val threads: List<WorkThread> = emptyList(),
    val nextCursor: String? = null,
    val detail: ThreadDetail? = null,
    val capabilities: JsonObject? = null,
    val settings: JsonObject? = null,
    val approvals: List<JsonObject> = emptyList(),
    val models: List<JsonObject> = emptyList(),
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val openingThreadId: String? = null,
    val error: String? = null,
    val streaming: Boolean = false,
    val search: String = "",
    val archived: Boolean = false,
)

// Server item ids differ from the caller's clientUserMessageId. Replace the local
// echo in place when the real user item arrives, including after compaction.
fun upsertWorkItem(items: List<WorkItem>, item: WorkItem): List<WorkItem> {
    val existing = items.indexOfFirst {
        it.id == item.id ||
            (it.kind == "userMessage" &&
                item.kind == "userMessage" &&
                item.clientId.isNotBlank() &&
                it.clientId == item.clientId)
    }
    if (existing < 0) return items + item
    if (item.localEcho && !items[existing].localEcho) return items
    return items.toMutableList().apply { set(existing, item) }
}

private fun uniqueWorkItems(items: List<WorkItem>) =
    items.fold(emptyList<WorkItem>(), ::upsertWorkItem)

// A history request can finish after a newer stream delta. Preserve newer partial
// output and loaded earlier pages, while completed server items remain authoritative.
fun mergeThreadRefresh(fresh: ThreadDetail, live: ThreadDetail): ThreadDetail {
    if (fresh.thread.id != live.thread.id) return fresh
    val freshById = fresh.items.associateBy { it.id }
    val liveById = live.items.associateBy { it.id }
    val firstShared = live.items.indexOfFirst { it.id in freshById }
    // A long offline period can leave a gap between history windows. Re-anchor
    // pagination at the server's latest page instead of appending old turns last.
    if (firstShared < 0 && fresh.items.isNotEmpty()) {
        val active =
            if (fresh.activeTurn != null && fresh.activeTurn == live.activeTurn)
                live.items.filter { it.turnId == fresh.activeTurn }
            else emptyList()
        return fresh.copy(
            items =
                uniqueWorkItems(fresh.items + active).filter {
                    it.kind != "retry" || it.turnId == fresh.activeTurn
                },
            diff = if (fresh.activeTurn == live.activeTurn) live.diff else fresh.diff,
        )
    }
    val earlier = if (firstShared >= 0) live.items.take(firstShared) else emptyList()
    val newerOnly = live.items.drop(firstShared.coerceAtLeast(0)).filter { it.id !in freshById }
    val ordered =
        (earlier + fresh.items + newerOnly).map { item ->
            val newer = liveById[item.id]
            if (
                item.status !in listOf("completed", "failed", "declined") &&
                    newer != null &&
                    newer.text.length > item.text.length
            )
                newer
            else item
        }
    return fresh.copy(
        items =
            uniqueWorkItems(ordered).filter {
                it.kind != "retry" || it.turnId == fresh.activeTurn
            },
        nextCursor = if (earlier.isNotEmpty()) live.nextCursor else fresh.nextCursor,
        diff = fresh.diff.ifBlank { if (fresh.activeTurn == live.activeTurn) live.diff else "" },
    )
}

fun applyWorkbenchEvent(detail: ThreadDetail, event: JsonObject): ThreadDetail {
    val params = event["params"].obj()
    if (params["threadId"].text() != detail.thread.id) return detail
    val method = event["method"].text()
    val turn = params["turnId"].text(detail.activeTurn.orEmpty())
    return when (method) {
        "thread/name/updated" ->
            detail.copy(
                thread = detail.thread.copy(title = params["name"].text(detail.thread.title))
            )
        "turn/started" -> {
            val started = params["turn"].obj()
            val id = started["id"].text()
            detail.copy(
                items =
                    started["items"]
                        .list()
                        .mapNotNull { WorkItem.parse(it.obj(), id) }
                        .fold(detail.items.filterNot { it.kind == "retry" }, ::upsertWorkItem),
                activeTurn = id,
                thread = detail.thread.copy(status = "active"),
                diff = "",
            )
        }
        "turn/completed" -> {
            val completed = params["turn"].obj()
            val id = completed["id"].text()
            val current = detail.activeTurn == null || detail.activeTurn == id
            var items =
                completed["items"]
                    .list()
                    .mapNotNull { WorkItem.parse(it.obj(), id) }
                    .fold(
                        detail.items.filterNot { it.kind == "retry" && it.turnId == id },
                        ::upsertWorkItem,
                    )
                    .map {
                        if (
                            it.kind == "contextCompaction" &&
                                it.turnId == id &&
                                it.status == "inProgress"
                        )
                            if (completed["status"].text() == "completed")
                                it.copy(status = "completed", text = "历史上下文已整理。")
                            else it.copy(status = "interrupted", text = "上下文整理已中断。")
                        else it
                    }
            val error = completed["error"].obj()["message"].text()
            if (completed["status"].text() == "failed" && error.isNotBlank())
                items =
                    upsertWorkItem(
                        items,
                        WorkItem("error-$id", id, "error", "执行失败", error, "failed"),
                    )
            detail.copy(
                items = items,
                activeTurn = if (current) null else detail.activeTurn,
                thread =
                    if (current)
                        detail.thread.copy(
                            status =
                                when (completed["status"].text()) {
                                    "failed" -> "failed"
                                    "interrupted" -> "interrupted"
                                    else -> "idle"
                                }
                        )
                    else detail.thread,
            )
        }
        "thread/status/changed" ->
            detail.copy(thread = detail.thread.copy(status = params["status"].obj()["type"].text()))
        "turn/diff/updated" -> detail.copy(diff = params["diff"].text())
        "turn/plan/updated" -> {
            val text =
                listOf(
                        params["explanation"].text(),
                        params["plan"].list().joinToString("\n") { step ->
                            val s = step.obj()
                            "${if(s["status"].text() == "completed") "✓" else "○"} ${s["step"].text()}"
                        },
                    )
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            detail.copy(
                items =
                    detail.items.filterNot { it.id == "plan-$turn" } +
                        WorkItem("plan-$turn", turn, "plan", "开发计划", text)
            )
        }
        "error" -> {
            val retry = params["willRetry"].text() == "true"
            val error = params["error"].obj()
            val message =
                listOf(
                        error["message"].text(params["message"].text()),
                        error["additionalDetails"].text(),
                    )
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            detail.copy(
                items =
                    upsertWorkItem(
                        detail.items.filterNot {
                            !retry && it.kind == "retry" && it.turnId == turn
                        },
                        WorkItem(
                            if (retry) "retry-$turn" else "error-$turn",
                            turn,
                            if (retry) "retry" else "error",
                            if (retry) "连接重试中" else "执行失败",
                            message,
                            if (retry) "retrying" else "failed",
                        ),
                    )
            )
        }
        "item/started",
        "item/completed" -> {
            val progressing =
                detail.copy(
                    items = detail.items.filterNot { it.kind == "retry" && it.turnId == turn }
                )
            var item = WorkItem.parse(params["item"].obj(), turn) ?: return progressing
            if (item.kind == "contextCompaction")
                item =
                    item.copy(
                        text =
                            if (method == "item/started") "正在整理较长的历史上下文，完成后会处理你的消息。"
                            else "历史上下文已整理。",
                        status = if (method == "item/started") "inProgress" else "completed",
                    )
            if (item.kind == "agentMessage" && method == "item/completed")
                item = item.copy(status = "completed")
            progressing.copy(items = upsertWorkItem(progressing.items, item))
        }
        "item/agentMessage/delta",
        "item/commandExecution/outputDelta" -> {
            val id = params["itemId"].text()
            val existing = detail.items.indexOfFirst { it.id == id }
            val kind = if (method.contains("agentMessage")) "agentMessage" else "commandExecution"
            val before =
                detail.items.getOrNull(existing)
                    ?: WorkItem(id, turn, kind, if (kind == "agentMessage") "Codex" else "执行命令", "")
            val item =
                before.copy(text = (before.text + params["delta"].text()).takeLast(128 * 1024))
            detail
                .copy(
                    items =
                        if (existing < 0) detail.items + item
                        else detail.items.toMutableList().apply { set(existing, item) }
                )
                .let { next ->
                    next.copy(
                        items = next.items.filterNot { it.kind == "retry" && it.turnId == turn }
                    )
                }
        }
        else ->
            if (method.startsWith("item/reasoning/"))
                detail.copy(
                    items = detail.items.filterNot { it.kind == "retry" && it.turnId == turn }
                )
            else detail
    }
}
