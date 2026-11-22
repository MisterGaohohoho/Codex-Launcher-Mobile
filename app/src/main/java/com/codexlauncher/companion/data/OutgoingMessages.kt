package com.codexlauncher.companion.data

import kotlinx.serialization.json.JsonObject

/** Keep submitted text visible while the PC compacts history or retries upstream. */
class OutgoingMessages {
    private data class Entry(val threadId: String, val item: WorkItem)

    private val entries = linkedMapOf<String, Entry>()

    fun clear() = entries.clear()

    private fun messageId(request: AdminCommand) =
        request.input["messageId"].text().ifBlank { request.requestId }

    fun begin(request: AdminCommand) {
        if (request.action !in listOf("workbench.send", "workbench.steer")) return
        val thread = request.input["threadId"].text()
        val text = request.input["text"].text()
        if (thread.isBlank() || text.isBlank()) return
        val id = messageId(request)
        entries[id] =
            Entry(
                thread,
                entries[id]?.item?.copy(status = "sending")
                    ?: WorkItem(
                        "local-$id",
                        request.input["turnId"].text(),
                        "userMessage",
                        "你",
                        text,
                        status = "sending",
                        clientId = id,
                        localEcho = true,
                    ),
            )
        while (entries.size > 128 || entries.values.sumOf { it.item.text.length } > 512 * 1024) {
            entries.remove(entries.keys.first())
        }
    }

    fun settle(request: AdminCommand, status: String, result: JsonObject? = null) {
        val id = messageId(request)
        val entry = entries[id] ?: return
        val turn =
            result
                ?.get("turn")
                .obj()["id"]
                .text()
                .ifBlank { result?.get("turnId").text() }
                .ifBlank { entry.item.turnId }
        entries[id] = entry.copy(item = entry.item.copy(status = status, turnId = turn))
    }

    fun decorate(detail: ThreadDetail): ThreadDetail {
        val actual = detail.items.filterNot { it.localEcho }
        val unmatched = actual.filter { it.kind == "userMessage" }.toMutableList()
        entries.entries.removeAll { (_, entry) ->
            if (entry.threadId != detail.thread.id) false
            else {
                val found = unmatched.indexOfFirst {
                    // Repeated instructions can have identical text in the same turn.
                    // Only the server's client id confirms this particular submission.
                    it.clientId == entry.item.clientId
                }
                if (found >= 0) {
                    unmatched.removeAt(found)
                    true
                } else false
            }
        }
        val outgoing = entries.values.filter { it.threadId == detail.thread.id }.map { it.item }
        // Retain each echo's original timeline position until its server item arrives.
        val byId = outgoing.associateBy { it.id }
        val retained = detail.items.mapNotNull { if (it.localEcho) byId[it.id] else it }
        return detail.copy(items = outgoing.fold(retained, ::upsertWorkItem))
    }
}
