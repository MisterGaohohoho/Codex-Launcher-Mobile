package com.codexlauncher.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.data.*
import com.codexlauncher.companion.ui.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun configurationEditorKeepsDecimalInputAndSendsOnlyChangedFields() {
        val configuration =
            ConfigurationEnvelope(
                "revision1",
                WireJson.parseToJsonElement(
                        """{"configVersion":2,"httpConfig":{"model":"fixture-model","prompt":"Reply when ready.","intervalSeconds":1.0,"concurrency":2},"dynamicPrompt":{"enabled":false,"apiKey":""}}"""
                    )
                    .obj(),
                secretFields = listOf("dynamicPrompt.apiKey"),
            )
        var patch: JsonObject? = null
        compose.setContent {
            LauncherTheme(false) {
                ConfigurationPage(
                    fixture().copy(configuration = configuration),
                    true,
                    LauncherActions(
                        saveConfiguration = { revision, change ->
                            assertEquals("revision1", revision)
                            patch = change
                        }
                    ),
                )
            }
        }
        compose.onNodeWithText("请求间隔（秒）").performScrollTo().performTextReplacement("0.")
        compose.onNodeWithText("0.").assertExists()
        compose.onNodeWithText("请求间隔（秒）").performTextReplacement("0.5")
        capture("configuration-light")
        compose.onNodeWithText("保存到 PC").performClick()
        assertEquals(setOf("httpConfig"), patch?.keys)
        assertEquals("0.5", patch?.get("httpConfig").obj()["intervalSeconds"].text())
    }

    @Test
    fun maintenancePreparesTheSelectionBeforeItsExplicitConfirmation() {
        val state =
            mutableStateOf(
                fixture()
                    .copy(
                        snapshot = fixture().snapshot?.copy(status = null),
                        adminResult =
                            WireJson.parseToJsonElement(
                                    """{"storage":{"directory":"D:/LauncherWork"},"jobs":[],"page":{"revision":"catalog1","total":1,"matched":1,"sessions":[{"id":"session1","title":"已完成的测试会话","cwd":"C:/Projects/demo","model":"fixture-model","storage":"available"}]}}"""
                                )
                                .obj(),
                    )
            )
        val commands = mutableListOf<Pair<String, JsonObject>>()
        compose.setContent {
            LauncherTheme(false) {
                MaintenancePage(
                    state.value,
                    true,
                    LauncherActions(
                        adminCommand = { action, input -> commands += action to input }
                    ),
                    {},
                )
            }
        }
        capture("maintenance-light")
        compose.onNodeWithText("全选本页").performScrollTo().performClick()
        compose.onNodeWithText("归档").performScrollTo().performClick()
        assertEquals("maintenance.prepare", commands.single().first)
        assertEquals("catalog1", commands.single().second["revision"].text())
        assertEquals(listOf("session1"), commands.single().second["ids"].list().map { it.text() })
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    lastAdminOutcome =
                        AdminOutcome(
                            "receipt1",
                            "maintenance.prepare",
                            "",
                            WireJson.parseToJsonElement(
                                    """{"id":"job1","action":"archive","total":1,"state":"ready","names":[{"title":"已完成的测试会话"}]}"""
                                )
                                .obj(),
                        )
                )
        }
        compose.onNodeWithText("确认归档会话").assertIsDisplayed()
        assertEquals(1, commands.size)
        capture("maintenance-confirmation-light")
        compose.onNodeWithText("确认执行").performClick()
        assertEquals("sessions.jobs.start", commands.last().second["operation"].text())
        assertEquals("job1", commands.last().second["input"].obj()["id"].text())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val destination = File("build/reports/mobile-ui/$name.png")
        checkNotNull(destination.parentFile).mkdirs()
        // PixelCopy's forced redraw waits for a real window frame that is not
        // delivered by Robolectric. Draw the measured Android view instead.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            destination.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
    }

    private fun fixture() =
        CompanionState(
            computer =
                PairedComputer(
                    "https://192.168.1.2:39173",
                    "a".repeat(64),
                    "fixture",
                    "b".repeat(64),
                    "工作电脑",
                    true,
                    "2030-01-01T00:00:00Z",
                ),
            online = true,
            lastSyncElapsed = 1000,
            snapshot =
                Snapshot(
                    1,
                    "2026-11-20T01:00:00Z",
                    "工作电脑",
                    "2.6.0",
                    RunSummary(
                        "fixture-run",
                        "running",
                        "keepAlive",
                        42,
                        36,
                        1,
                        6,
                        "2026-11-20T00:00:00Z",
                        "2026-11-20T01:00:00Z",
                        3600,
                        true,
                    ),
                    RunConfiguration(
                        provider = "Codex",
                        executionMode = "cli",
                        model = "由电脑决定",
                        concurrency = 6,
                        keepAliveEnabled = true,
                        contentMode = "ascii",
                        contentModeSupported = true,
                        questionBankAvailable = true,
                        startAvailable = true,
                    ),
                    workers =
                        listOf(
                            WorkerSummary(
                                2,
                                7,
                                "keepAliveWait",
                                "2026-11-20T01:00:00Z",
                                "2026-11-20T01:00:00Z",
                                "本轮已完成，等待下次保活",
                            )
                        ),
                    logs = listOf(LogLine(1, "2026-11-20T01:00:00Z", "success", 2, "任务已成功")),
                ),
        )

    @Test
    fun navigationAndDetailsExposeRealRequestAndReply() {
        val state = mutableStateOf(fixture())
        compose.setContent {
            LauncherTheme(dark = true) {
                LauncherScreen(
                    state.value,
                    2000,
                    actions =
                        LauncherActions(
                            openDetails = { worker ->
                                state.value =
                                    state.value.copy(
                                        detailTarget = DetailTarget("fixture-run", worker),
                                        details =
                                            WorkerDetails(
                                                "fixture-run",
                                                2,
                                                7,
                                                "prompt",
                                                "ASCII 短码",
                                                "fixture-model",
                                                "只回复 ABC",
                                                "ABC",
                                                "",
                                                true,
                                                false,
                                            ),
                                    )
                            },
                            closeDetails = { state.value = state.value.copy(detailTarget = null) },
                        ),
                )
            }
        }
        compose.onAllNodesWithText("保活中").assertCountEquals(1)
        capture("monitor-dark")
        compose.onNodeWithTag("tab-2").performClick()
        compose.onNodeWithTag("run-tab-1").performClick()
        capture("processes-dark")
        compose.onNodeWithTag("worker-2").performClick()
        compose.onNodeWithText("只回复 ABC").assertIsDisplayed()
        compose.onNodeWithText("模型回复").performClick()
        compose.onNodeWithText("ABC").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭请求详情").performClick()
        compose.onNodeWithTag("run-tab-2").performClick()
        compose.onNodeWithText("任务已成功").assertExists()
        capture("logs-dark")
        compose.onNodeWithTag("tab-3").performClick()
        compose.onNodeWithText("完整管理").assertExists()
        capture("settings-dark")
    }

    @Test
    fun stopRequiresConfirmationAndKeepsOriginalRunIdentity() {
        var stopped: String? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(fixture(), 2000, actions = LauncherActions(stop = { stopped = it }))
            }
        }
        capture("monitor-light")
        compose.onNodeWithTag("run-control").performClick()
        assertNull(stopped)
        compose.onNodeWithText("取消").performClick()
        assertNull(stopped)
        compose.onNodeWithTag("run-control").performClick()
        compose.onNodeWithText("确认").performClick()
        assertEquals("fixture-run", stopped)
    }

    @Test
    fun narrowLargeTextStillHasNavigationAndOfflineDisablesActions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                LauncherTheme(false) {
                    Box(Modifier.width(320.dp).fillMaxSize()) {
                        LauncherScreen(fixture().copy(online = false), 2000)
                    }
                }
            }
        }
        compose.onNodeWithTag("run-control").assertIsDisplayed().assertIsNotEnabled()
        for (index in 0..3) compose.onNodeWithTag("tab-$index").assertIsDisplayed().performClick()
        compose.onNodeWithText("完整管理").assertExists()
        capture("narrow-large-font-offline")
    }

    @Test
    fun pairingOffersCameraAndPasteWithoutSendingBlankInput() {
        var scans = 0
        var submitted: String? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    CompanionState(),
                    2000,
                    actions = LauncherActions(scan = { scans++ }, pair = { submitted = it }),
                )
            }
        }
        compose.onNodeWithText("验证并配对").assertIsNotEnabled()
        compose.onNodeWithText("扫码连接电脑").performClick()
        assertEquals(1, scans)
        val content = "{\"type\":\"codex-launcher-pair\",\"fixture\":true}"
        compose.onNodeWithText("电脑复制的配对内容").performTextInput(content)
        compose.onNodeWithText("验证并配对").performScrollTo().performClick()
        assertEquals(content, submitted)
        capture("pairing-light")
    }

    @Test
    fun successfulPollingKeepsOnlineControlsAndContentPositionBetweenClockTicks() {
        val state = mutableStateOf(fixture())
        val now = mutableLongStateOf(2000)
        compose.setContent { LauncherTheme(false) { LauncherScreen(state.value, now.longValue) } }
        val position = compose.onNodeWithTag("monitor-list").fetchSemanticsNode().positionInRoot
        repeat(8) { poll ->
            compose.runOnIdle {
                now.longValue = 2000L + poll * 2000
                state.value = state.value.copy(lastSyncElapsed = now.longValue + 600)
            }
            compose.onNodeWithText("在线", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("正在连接电脑…").assertDoesNotExist()
            compose.onNodeWithTag("run-control").assertIsEnabled()
            assertEquals(
                position,
                compose.onNodeWithTag("monitor-list").fetchSemanticsNode().positionInRoot,
            )
        }
        capture("polling-stable")
        compose.runOnIdle {
            state.value = state.value.copy(online = false, connectionError = "连接已中断")
        }
        compose.onNodeWithText("连接已中断").assertIsDisplayed()
        compose.onNodeWithTag("run-control").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(online = true, connectionError = null) }
        compose.onNodeWithTag("run-control").assertIsEnabled()
    }

    @Test
    fun pairingProgressAndFirstSyncFailureStayInsideTheApp() {
        val state = mutableStateOf(CompanionState(busy = true, pairing = true))
        compose.setContent { LauncherTheme(false) { LauncherScreen(state.value, 2000) } }
        compose.onNodeWithText("正在验证并连接电脑…").assertIsDisplayed()
        compose.onNodeWithText("扫码连接电脑").assertIsNotEnabled()
        compose.runOnIdle {
            state.value =
                fixture().copy(online = false, snapshot = null, connectionError = "连接已中断，请检查网络")
        }
        compose.onNodeWithTag("tab-0").assertIsSelected()
        compose.onNodeWithText("连接已中断，请检查网络").assertIsDisplayed()
        compose.onNodeWithTag("run-control").assertIsNotEnabled()
        assertFalse(compose.activity.isFinishing)
        capture("paired-first-sync-failed")
        compose.runOnIdle { state.value = fixture() }
        compose.onNodeWithTag("run-control").assertIsEnabled()
    }

    @Test
    fun workbenchSteeringAndApprovalUseTheSelectedSessionAndConnection() {
        val thread =
            WorkThread(
                "thread-fixture",
                "完成移动端配置同步",
                "C:/Projects/launcher",
                "active",
                "managed",
                0,
                "Codex",
                "appServer",
            )
        val approval =
            WireJson.parseToJsonElement(
                    """{"id":"approval1","epoch":"connection1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-fixture","command":"npm test","reason":"验证配置同步"}}"""
                )
                .obj()
        val detail =
            ThreadDetail(
                thread,
                listOf(
                    WorkItem("u1", "turn1", "userMessage", "你", "让手机修改的配置实时显示在电脑端。"),
                    WorkItem("a1", "turn1", "agentMessage", "Codex", "已接入配置版本校验，正在验证同时编辑时的冲突处理。"),
                    WorkItem(
                        "c1",
                        "turn1",
                        "commandExecution",
                        "执行命令",
                        "npm test\n\n2 tests passed",
                        "completed",
                    ),
                ),
                activeTurn = "turn1",
            )
        var command: Pair<String, JsonObject>? = null
        compose.setContent {
            LauncherTheme(true) {
                LauncherScreen(
                    fixture()
                        .copy(
                            workbench =
                                WorkbenchState(
                                    threads = listOf(thread),
                                    detail = detail,
                                    approvals = listOf(approval),
                                    streaming = true,
                                )
                        ),
                    2000,
                    actions =
                        LauncherActions(
                            workbenchAction = { action, input -> command = action to input }
                        ),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        capture("workbench-chat-dark")
        compose.onNodeWithTag("message-input").performTextInput("增加断线重连测试")
        compose.onNodeWithTag("send-message").assertIsEnabled().performClick()
        assertEquals("steer", command?.first)
        assertEquals("thread-fixture", command?.second?.get("threadId").text())
        assertEquals("turn1", command?.second?.get("turnId").text())
        compose.onNodeWithText("1 项等待你确认").performClick()
        compose.onNodeWithText("npm test").assertIsDisplayed()
        capture("workbench-approval-dark")
        compose.onNodeWithText("拒绝").performClick()
        assertEquals("answer", command?.first)
        assertEquals("connection1", command?.second?.get("epoch").text())
        assertEquals("decline", command?.second?.get("answer").obj()["decision"].text())
        compose.onNodeWithTag("thread-tab-1").performClick()
        compose.onNodeWithText("npm test\n\n2 tests passed").assertIsDisplayed()
        capture("workbench-execution-dark")
    }

    @Test
    fun independentHistoryHasAnExplicitForkInsteadOfASendButton() {
        val thread =
            WorkThread(
                "independent",
                "桌面开发会话",
                "C:/Projects/site",
                "notLoaded",
                "readOnly",
                0,
                "Codex",
                "cli",
            )
        var action: String? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    fixture()
                        .copy(
                            workbench = WorkbenchState(detail = ThreadDetail(thread, emptyList()))
                        ),
                    2000,
                    actions = LauncherActions(workbenchAction = { kind, _ -> action = kind }),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithTag("send-message").assertDoesNotExist()
        compose.onNodeWithText("创建分支继续").performClick()
        assertNull(action)
        compose.onNodeWithText("确认").performClick()
        assertEquals("fork", action)
        capture("workbench-readonly-light")
    }

    @Test
    fun forkChatKeepsSentTextVisibleDuringCompactionAndTransientRetry() {
        val thread =
            WorkThread(
                "fork",
                "继续开发移动端",
                "C:/Projects/launcher",
                "idle",
                "managed",
                0,
                "Codex",
                "appServer",
            )
        val older = (1..8).map { WorkItem("old-$it", "old-turn", "userMessage", "你", "已有上下文 $it") }
        val state =
            mutableStateOf(
                fixture()
                    .copy(
                        workbench =
                            WorkbenchState(detail = ThreadDetail(thread, older), streaming = true)
                    )
            )
        val dark = mutableStateOf(false)
        val outgoing = OutgoingMessages()
        var submitted: AdminCommand? = null
        val text = "继续检查分支会话的消息显示"
        fun event(value: String) {
            val detail =
                applyWorkbenchEvent(
                    state.value.workbench.detail!!,
                    WireJson.parseToJsonElement(value).obj(),
                )
            state.value =
                state.value.copy(
                    workbench = state.value.workbench.copy(detail = outgoing.decorate(detail))
                )
        }
        compose.setContent {
            LauncherTheme(dark.value) {
                LauncherScreen(
                    state.value,
                    2000,
                    actions =
                        LauncherActions(
                            workbenchAction = { action, input ->
                                val request = AdminCommand("receipt", "workbench.$action", input)
                                submitted = request
                                outgoing.begin(request)
                                state.value =
                                    state.value.copy(
                                        busy = true,
                                        workbench =
                                            state.value.workbench.copy(
                                                detail =
                                                    outgoing.decorate(
                                                        state.value.workbench.detail!!
                                                    )
                                            ),
                                    )
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithTag("thread-timeline").performScrollToIndex(0)
        compose.onNodeWithTag("message-input").performTextInput(text)
        compose.onNodeWithTag("send-message").performClick()
        compose.onNodeWithText("正在发送").assertIsDisplayed()
        compose
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag("thread-timeline")))
            .assertIsDisplayed()
        compose.runOnIdle {
            event(
                """{"method":"turn/started","params":{"threadId":"fork","turn":{"id":"turn1"}}}"""
            )
            event(
                """{"method":"item/started","params":{"threadId":"fork","turnId":"turn1","item":{"id":"compact","type":"contextCompaction"}}}"""
            )
        }
        compose.onNodeWithText("正在整理上下文，随后处理消息").assertIsDisplayed()
        capture("workbench-compacting-light")
        compose.runOnIdle {
            val request = submitted!!
            val result = buildJsonObject { put("turn", buildJsonObject { put("id", "turn1") }) }
            outgoing.settle(request, "sent", result)
            state.value =
                state.value.copy(
                    busy = false,
                    lastAdminOutcome =
                        AdminOutcome(
                            "receipt",
                            "workbench.send",
                            "",
                            result,
                            request.input["messageId"].text(),
                        ),
                    workbench =
                        state.value.workbench.copy(
                            detail = outgoing.decorate(state.value.workbench.detail!!)
                        ),
                )
        }
        assertEquals(
            "",
            compose
                .onNodeWithTag("message-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        compose.onAllNodesWithText(text).assertCountEquals(1)
        compose.onNodeWithText("已发送").assertIsDisplayed()
        compose.runOnIdle {
            dark.value = true
            event(
                """{"method":"error","params":{"threadId":"fork","turnId":"turn1","willRetry":true,"error":{"message":"Reconnecting… 1/5"}}}"""
            )
        }
        compose.onNodeWithText("连接重试中，任务仍在继续").assertIsDisplayed()
        compose.onNodeWithText("执行失败").assertDoesNotExist()
        compose.onNodeWithText("Reconnecting… 1/5").assertIsDisplayed()
        capture("workbench-retry-dark")
        compose.runOnIdle {
            val clientId = submitted!!.input["messageId"].text()
            event(
                """{"method":"item/completed","params":{"threadId":"fork","turnId":"turn1","item":{"id":"compact","type":"contextCompaction"}}}"""
            )
            event(
                """{"method":"item/completed","params":{"threadId":"fork","turnId":"turn1","item":{"id":"server-user","clientId":"$clientId","type":"userMessage","content":[{"type":"text","text":"$text"}]}}}"""
            )
            event(
                """{"method":"turn/completed","params":{"threadId":"fork","turn":{"id":"turn1","status":"completed","items":[{"id":"answer","type":"agentMessage","text":"已完成分支会话检查。"}]}}}"""
            )
        }
        compose.onNodeWithText("已就绪").assertIsDisplayed()
        compose.onNodeWithText("Reconnecting… 1/5").assertDoesNotExist()
        compose.onNodeWithText("停止").assertDoesNotExist()
        compose.onNodeWithTag("thread-timeline").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text).assertCountEquals(1)
        assertFalse(state.value.workbench.detail!!.items.any { it.localEcho })
    }
}
