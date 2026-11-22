package com.codexlauncher.companion

import com.codexlauncher.companion.data.*
import com.codexlauncher.companion.ui.configurationDiff
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkbenchDataTest {
    @Test
    fun individualHistoryPagesPreserveTurnsAndLatestReplies() {
        val detail =
            ThreadDetail.parse(
                json(
                    """{"thread":{"id":"fixture"},"control":"managed","itemsPage":{"nextCursor":"earlier-items","data":[{"turnId":"t2","item":{"id":"a2","type":"agentMessage","text":"Latest reply"}},{"turnId":"t2","item":{"id":"r2","type":"reasoning","text":"private"}},{"turnId":"t1","item":{"id":"u1","type":"userMessage","content":[{"type":"text","text":"Earlier request"}]}}]},"turnsPage":{"data":[{"id":"t2","status":"completed","items":[]}]}}"""
                )
            )
        assertEquals(listOf("u1", "a2"), detail.items.map { it.id })
        assertEquals(listOf("t1", "t2"), detail.items.map { it.turnId })
        assertEquals("earlier-items", detail.nextCursor)
    }

    @Test
    fun disconnectedHistoryReanchorsPaginationWithoutReorderingOldMessages() {
        val thread =
            WorkThread(
                "fixture",
                "Test",
                "C:/Project",
                "idle",
                "managed",
                0,
                "fixture",
                "appServer",
            )
        val before =
            ThreadDetail(thread, listOf(WorkItem("old", "t1", "agentMessage", "Codex", "Earlier")))
        val fresh =
            ThreadDetail(
                thread,
                listOf(WorkItem("new", "t9", "agentMessage", "Codex", "Latest")),
                nextCursor = "gap",
            )
        val merged = mergeThreadRefresh(fresh, before)
        assertEquals(listOf("new"), merged.items.map { it.id })
        assertEquals("gap", merged.nextCursor)
    }

    private fun json(value: String) = WireJson.parseToJsonElement(value).obj()

    private val thread =
        WorkThread(
            "fixture",
            "Implement settings",
            "C:/Projects/launcher",
            "idle",
            "managed",
            0,
            "fixture",
            "appServer",
        )

    @Test
    fun historyIsChronologicalAndDoesNotExposeReasoning() {
        val detail =
            ThreadDetail.parse(
                json(
                    """{"thread":{"id":"fixture","cwd":"C:/Projects/launcher"},"control":"readOnly","turnsPage":{"nextCursor":"older","data":[{"id":"turn2","status":"inProgress","items":[{"id":"m2","type":"agentMessage","text":"Working"},{"id":"r","type":"reasoning","text":"internal"}]},{"id":"turn1","status":"completed","items":[{"id":"m1","type":"userMessage","content":[{"type":"text","text":"Start"}]}]}]}}"""
                )
            )
        assertEquals(listOf("m1", "m2"), detail.items.map { it.id })
        assertEquals("turn2", detail.activeTurn)
        assertEquals("older", detail.nextCursor)
        assertEquals("readOnly", detail.thread.control)
        assertEquals("launcher", detail.thread.project)
    }

    @Test
    fun streamedRepliesCommandsAndTurnsStayIsolatedByThread() {
        var detail = ThreadDetail(thread, emptyList(), diff = "previous turn")
        detail =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"turn/started","params":{"threadId":"fixture","turn":{"id":"turn2"}}}"""
                ),
            )
        assertEquals("turn2", detail.activeTurn)
        assertEquals("", detail.diff)
        detail =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"item/agentMessage/delta","params":{"threadId":"fixture","turnId":"turn2","itemId":"m","delta":"Hello "}}"""
                ),
            )
        detail =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"item/agentMessage/delta","params":{"threadId":"fixture","turnId":"turn2","itemId":"m","delta":"world"}}"""
                ),
            )
        val unchanged =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"turn/completed","params":{"threadId":"another","turn":{"id":"other"}}}"""
                ),
            )
        assertEquals(detail, unchanged)
        assertEquals("Hello world", detail.items.single().text)
        detail =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"item/completed","params":{"threadId":"fixture","turnId":"turn2","item":{"id":"m","type":"agentMessage","text":"Hello world!"}}}"""
                ),
            )
        assertEquals(1, detail.items.size)
        assertEquals("Hello world!", detail.items.single().text)
        detail =
            applyWorkbenchEvent(
                detail,
                json(
                    """{"method":"turn/completed","params":{"threadId":"fixture","turn":{"id":"turn2"}}}"""
                ),
            )
        assertNull(detail.activeTurn)
    }

    @Test
    fun staleHistoryCannotEraseLiveOutputOrPreviouslyLoadedPages() {
        val older = WorkItem("old", "turn0", "userMessage", "You", "Previous page")
        val liveText = WorkItem("m", "turn1", "agentMessage", "Codex", "A newer answer")
        val command = WorkItem("cmd", "turn1", "commandExecution", "Command", "Running tests")
        val live =
            ThreadDetail(
                thread,
                listOf(older, liveText, command),
                activeTurn = "turn1",
                nextCursor = "page3",
            )
        val refreshed =
            mergeThreadRefresh(
                ThreadDetail(
                    thread,
                    listOf(liveText.copy(text = "A newer")),
                    activeTurn = "turn1",
                    nextCursor = "page2",
                ),
                live,
            )
        assertEquals(listOf("old", "m", "cmd"), refreshed.items.map { it.id })
        assertEquals("A newer answer", refreshed.items[1].text)
        assertEquals("page3", refreshed.nextCursor)
    }

    @Test
    fun retryNotificationKeepsTheTurnActiveAndDisappearsWhenOutputResumes() {
        val active = ThreadDetail(thread.copy(status = "active"), emptyList(), activeTurn = "turn1")
        val retrying =
            applyWorkbenchEvent(
                active,
                json(
                    """{"method":"error","params":{"threadId":"fixture","turnId":"turn1","willRetry":true,"error":{"message":"Reconnecting… 1/5","additionalDetails":"Temporary upstream disconnect"}}}"""
                ),
            )
        assertEquals("turn1", retrying.activeTurn)
        assertEquals("active", retrying.thread.status)
        assertEquals("retry", retrying.items.single().kind)
        assertEquals("retrying", retrying.items.single().status)
        assertTrue(retrying.items.single().text.contains("1/5"))
        val working =
            applyWorkbenchEvent(
                retrying,
                json(
                    """{"method":"item/agentMessage/delta","params":{"threadId":"fixture","turnId":"turn1","itemId":"reply","delta":"Connection recovered"}}"""
                ),
            )
        assertEquals("agentMessage", working.items.single().kind)
        assertEquals("Connection recovered", working.items.single().text)
    }

    @Test
    fun retryCompletionAndHistoryRefreshDoNotLeaveAFailureCard() {
        val active = ThreadDetail(thread.copy(status = "active"), emptyList(), activeTurn = "turn1")
        val retrying =
            applyWorkbenchEvent(
                active,
                json(
                    """{"method":"error","params":{"threadId":"fixture","turnId":"turn1","willRetry":true,"error":{"message":"Reconnecting… 1/5"}}}"""
                ),
            )
        val completed =
            applyWorkbenchEvent(
                retrying,
                json(
                    """{"method":"turn/completed","params":{"threadId":"fixture","turn":{"id":"turn1","status":"completed","items":[{"id":"reply","type":"agentMessage","text":"Done"}],"error":{"message":"Previous retry"}}}}"""
                ),
            )
        assertNull(completed.activeTurn)
        assertEquals("idle", completed.thread.status)
        assertEquals(listOf("agentMessage"), completed.items.map { it.kind })
        val fresh =
            ThreadDetail.parse(
                json(
                    """{"thread":{"id":"fixture"},"turnsPage":{"data":[{"id":"turn1","status":"completed","error":{"message":"Previous retry"},"items":[]}]}}"""
                )
            )
        assertTrue(fresh.items.isEmpty())
        assertTrue(mergeThreadRefresh(fresh, retrying).items.isEmpty())
    }

    @Test
    fun realTurnFailuresRemainVisibleAndLateCompletionDoesNotStopTheNextTurn() {
        val active = ThreadDetail(thread.copy(status = "active"), emptyList(), activeTurn = "turn2")
        val terminal =
            applyWorkbenchEvent(
                active,
                json(
                    """{"method":"error","params":{"threadId":"fixture","turnId":"turn1","willRetry":false,"error":{"message":"Provider rejected request"}}}"""
                ),
            )
        assertEquals("error", terminal.items.single().kind)
        val oldComplete =
            applyWorkbenchEvent(
                terminal,
                json(
                    """{"method":"turn/completed","params":{"threadId":"fixture","turn":{"id":"turn1","status":"failed","error":{"message":"Provider rejected request"}}}}"""
                ),
            )
        assertEquals("turn2", oldComplete.activeTurn)
        assertEquals("active", oldComplete.thread.status)
        assertEquals(1, oldComplete.items.size)
        val failed =
            applyWorkbenchEvent(
                oldComplete,
                json(
                    """{"method":"turn/completed","params":{"threadId":"fixture","turn":{"id":"turn2","status":"failed","error":{"message":"Retry limit reached"}}}}"""
                ),
            )
        assertNull(failed.activeTurn)
        assertEquals("failed", failed.thread.status)
        assertEquals("Retry limit reached", failed.items.last().text)
        val history =
            ThreadDetail.parse(
                json(
                    """{"thread":{"id":"fixture"},"turnsPage":{"data":[{"id":"turn2","status":"failed","error":{"message":"Retry limit reached"},"items":[]}]}}"""
                )
            )
        assertEquals("failed", history.items.single().status)
    }

    @Test
    fun compactionShowsProgressAndInterruptDoesNotLeaveItRunning() {
        val active = ThreadDetail(thread, emptyList(), activeTurn = "turn1")
        val compacting =
            applyWorkbenchEvent(
                active,
                json(
                    """{"method":"item/started","params":{"threadId":"fixture","turnId":"turn1","item":{"id":"compact","type":"contextCompaction"}}}"""
                ),
            )
        assertEquals("inProgress", compacting.items.single().status)
        val interrupted =
            applyWorkbenchEvent(
                compacting,
                json(
                    """{"method":"turn/completed","params":{"threadId":"fixture","turn":{"id":"turn1","status":"interrupted"}}}"""
                ),
            )
        assertEquals("interrupted", interrupted.items.single().status)
        assertFalse(interrupted.items.single().text.contains("正在"))
        val completed =
            applyWorkbenchEvent(
                compacting,
                json(
                    """{"method":"item/completed","params":{"threadId":"fixture","turnId":"turn1","item":{"id":"compact","type":"contextCompaction"}}}"""
                ),
            )
        assertEquals("completed", completed.items.single().status)
    }

    @Test
    fun hiddenReasoningProgressClearsRetryWithoutDisplayingReasoning() {
        val retry =
            WorkItem("retry-turn1", "turn1", "retry", "Retrying", "Reconnecting", "retrying")
        val detail = ThreadDetail(thread, listOf(retry), activeTurn = "turn1")
        for (event in
            listOf(
                """{"method":"item/started","params":{"threadId":"fixture","turnId":"turn1","item":{"id":"reason","type":"reasoning"}}}""",
                """{"method":"item/reasoning/textDelta","params":{"threadId":"fixture","turnId":"turn1","itemId":"reason","delta":"private"}}""",
            )) {
            assertTrue(applyWorkbenchEvent(detail, json(event)).items.isEmpty())
        }
    }

    @Test
    fun configurationPatchPreservesUnchangedSecretsAndNestedSiblings() {
        val base =
            json(
                """{"configVersion":1,"dynamicPrompt":{"enabled":false,"apiKey":""},"httpConfig":{"model":"old","prompt":"keep"},"schedule":{"kind":"daily","time":"09:00","everyDays":1}}"""
            )
        val draft =
            json(
                """{"configVersion":1,"dynamicPrompt":{"enabled":true,"apiKey":""},"httpConfig":{"model":"new","prompt":"keep"},"schedule":{"kind":"atLogon","delaySeconds":30}}"""
            )
        val patch = configurationDiff(base, draft)
        assertFalse(patch.containsKey("configVersion"))
        assertFalse(patch["dynamicPrompt"].obj().containsKey("apiKey"))
        assertEquals(json("""{"model":"new"}"""), patch["httpConfig"])
        assertEquals(draft["schedule"], patch["schedule"])
    }
}
