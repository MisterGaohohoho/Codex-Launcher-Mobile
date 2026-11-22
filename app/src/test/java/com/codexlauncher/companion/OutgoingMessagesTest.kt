package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutgoingMessagesTest {
    private val thread =
        WorkThread("fork", "Fork", "C:/Project", "idle", "managed", 0, "fixture", "appServer")
    private val empty = ThreadDetail(thread, emptyList())

    private fun command(
        id: String = "client-1",
        action: String = "send",
        text: String = "Continue development",
    ) =
        AdminCommand(
            "receipt-$id",
            "workbench.$action",
            buildJsonObject {
                put("threadId", thread.id)
                put("messageId", id)
                put("text", text)
                if (action == "steer") put("turnId", "turn-1")
            },
        )

    private fun json(value: String) = WireJson.parseToJsonElement(value).obj()

    @Test
    fun submittedTextSurvivesDelayedHistoryAndReopeningTheFork() {
        val pending = OutgoingMessages()
        val request = command()
        pending.begin(request)
        val sending = pending.decorate(empty)
        assertEquals("Continue development", sending.items.single().text)
        assertEquals("sending", sending.items.single().status)

        pending.settle(request, "sent", json("""{"turn":{"id":"turn-1"}}"""))
        val compaction =
            applyWorkbenchEvent(
                empty.copy(activeTurn = "turn-1"),
                json(
                    """{"method":"item/started","params":{"threadId":"fork","turnId":"turn-1","item":{"id":"compact","type":"contextCompaction"}}}"""
                ),
            )
        val refreshed = pending.decorate(mergeThreadRefresh(compaction, sending))
        val echoed = refreshed.items.single { it.kind == "userMessage" }
        assertEquals("sent", echoed.status)
        assertEquals("turn-1", echoed.turnId)
        assertEquals(2, refreshed.items.size)
        assertTrue(pending.decorate(empty.copy(thread = thread.copy(id = "other"))).items.isEmpty())
        assertEquals(echoed, pending.decorate(empty).items.single())
    }

    @Test
    fun serverClientIdentityReplacesEchoEvenWhenTheReplyArrivesBeforeTheReceipt() {
        val pending = OutgoingMessages()
        val request = command()
        pending.begin(request)
        val echo = pending.decorate(empty)
        val confirmed =
            pending.decorate(
                applyWorkbenchEvent(
                    echo,
                    json(
                        """{"method":"item/completed","params":{"threadId":"fork","turnId":"turn-1","item":{"id":"server-1","clientId":"client-1","type":"userMessage","content":[{"type":"text","text":"Continue development"}]}}}"""
                    ),
                )
            )
        assertEquals("server-1", confirmed.items.single().id)
        assertFalse(confirmed.items.single().localEcho)
        pending.settle(request, "sent", json("""{"turn":{"id":"turn-1"}}"""))
        assertEquals(confirmed, pending.decorate(confirmed))
        assertTrue(pending.decorate(empty).items.isEmpty())
    }

    @Test
    fun historyReconcilesByClientIdWithoutDuplicatingTheMessage() {
        val pending = OutgoingMessages()
        pending.begin(command())
        val echo = pending.decorate(empty)
        val real = echo.items.single().copy(id = "server-1", localEcho = false, status = "")
        val combined = mergeThreadRefresh(empty.copy(items = listOf(real)), echo)
        val confirmed = pending.decorate(combined)
        assertEquals(listOf(real), confirmed.items)
        assertEquals(confirmed, pending.decorate(confirmed))
    }

    @Test
    fun identicalSteeringMessagesAreNotMistakenForAnOlderSubmission() {
        val pending = OutgoingMessages()
        val older = WorkItem("old", "turn-1", "userMessage", "You", "Continue")
        val history = empty.copy(items = listOf(older), activeTurn = "turn-1")
        val first = command("client-1", "steer", "Continue")
        val second = command("client-2", "steer", "Continue")
        pending.begin(first)
        pending.settle(first, "sent", json("""{"turnId":"turn-1"}"""))
        pending.begin(second)
        val local = pending.decorate(history)
        assertEquals(3, local.items.size)
        val actual = local.items[1].copy(id = "server-1", localEcho = false)
        val confirmed = pending.decorate(local.copy(items = upsertWorkItem(local.items, actual)))
        assertEquals(3, confirmed.items.size)
        assertEquals("client-2", confirmed.items.single { it.localEcho }.clientId)
    }

    @Test
    fun lostReceiptKeepsTextAndRetryUpdatesOneEcho() {
        val pending = OutgoingMessages()
        val request = command()
        pending.begin(request)
        pending.settle(request, "unconfirmed")
        val uncertain = pending.decorate(empty)
        assertEquals("unconfirmed", uncertain.items.single().status)
        pending.begin(request)
        val retrying = pending.decorate(uncertain)
        assertEquals("sending", retrying.items.single().status)
        pending.settle(request, "sent", json("""{"turn":{"id":"turn-1"}}"""))
        assertEquals("sent", pending.decorate(retrying).items.single().status)
    }

    @Test
    fun rejectedMessagesRemainVisibleUntilTheConnectionIsReset() {
        val pending = OutgoingMessages()
        val request = command()
        pending.begin(request)
        pending.settle(request, "notSent")
        val rejected = pending.decorate(empty)
        assertEquals("notSent", rejected.items.single().status)
        pending.clear()
        assertTrue(pending.decorate(rejected).items.isEmpty())
    }
}
