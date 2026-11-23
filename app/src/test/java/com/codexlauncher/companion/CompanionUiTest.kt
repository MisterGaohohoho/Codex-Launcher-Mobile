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
    fun computerSwitcherShowsSeparateStatusAndEditsTheSelectedIdentity() {
        val base = fixture()
        val first = base.computer!!
        val second = first.copy(certificateSha256 = "c".repeat(64), computerName = "旅行笔记本")
        val offline = first.copy(certificateSha256 = "d".repeat(64), computerName = "家里的电脑")
        val state =
            base.copy(
                computers = listOf(first, second, offline),
                connectionInfo = ConnectionInfo(first.endpoint, 12),
                computerStatuses =
                    mapOf(
                        second.id to
                            ComputerOverview(
                                base.snapshot?.copy(status = null),
                                System.currentTimeMillis(),
                            ),
                        offline.id to
                            ComputerOverview(
                                base.snapshot,
                                System.currentTimeMillis() - 120_000,
                                error = "连接超时",
                            ),
                    ),
            )
        var selected: String? = null
        var edited: Triple<String, String, String>? = null
        var checks = 0
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    state,
                    2000,
                    actions =
                        LauncherActions(
                            refreshComputers = { checks++ },
                            selectComputer = { selected = it },
                            editComputer = { id, name, address ->
                                edited = Triple(id, name, address)
                            },
                        ),
                )
            }
        }
        compose.onNodeWithTag("conversation-title").performClick()
        compose.onNodeWithText("我的电脑").assertIsDisplayed()
        compose.onNodeWithText("暂不可达").assertIsDisplayed()
        assertEquals(1, checks)
        capture("computers-light")
        compose.onNodeWithContentDescription("管理旅行笔记本").performClick()
        compose.onNodeWithText("编辑名称与地址").performClick()
        compose.onNodeWithTag("computer-name").performTextReplacement("随身电脑")
        compose.onNodeWithTag("computer-address").performTextReplacement("http://8.8.8.8:39173")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose
            .onNodeWithTag("computer-address")
            .performTextReplacement("https://192.168.2.3:39173")
        capture("computer-editor-light")
        compose.onNodeWithText("保存").performClick()
        assertEquals(Triple(second.id, "随身电脑", "https://192.168.2.3:39173"), edited)
        compose.onNodeWithText("旅行笔记本").performClick()
        assertEquals(second.id, selected)
        compose.onNodeWithTag("add-computer").assertDoesNotExist()
    }

    @Test
    fun switchingComputersClosesTheOldControlConfirmation() {
        val first = fixture()
        val second =
            first.computer!!.copy(certificateSha256 = "e".repeat(64), computerName = "另一台电脑")
        val state = mutableStateOf(first.copy(computers = listOf(first.computer!!, second)))
        var stopped: String? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    state.value,
                    2000,
                    actions = LauncherActions(stop = { stopped = it }),
                )
            }
        }
        compose.onNodeWithTag("run-control").performClick()
        compose.onNodeWithText("确认").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(computer = second, snapshot = first.snapshot?.copy(status = null))
        }
        compose.onNodeWithText("确认").assertDoesNotExist()
        assertNull(stopped)
        compose.onNodeWithTag("conversation-title").performClick()
        capture("computers-after-switch")
    }

    @Test
    fun aSlowConversationKeepsThePcOnlineAndProvidesAnExitAndRetry() {
        val state =
            mutableStateOf(
                fixture()
                    .copy(
                        workbench =
                            WorkbenchState(openingThreadId = "fixture-chat", detailLoading = true)
                    )
            )
        var closed = false
        var retry: String? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    state.value,
                    2000,
                    actions =
                        LauncherActions(
                            closeThread = { closed = true },
                            openThread = { retry = it },
                        ),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithText("正在加载会话…").assertIsDisplayed()
        compose.onNodeWithText("正在连接电脑").assertDoesNotExist()
        capture("conversation-loading")
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    workbench =
                        state.value.workbench.copy(detailLoading = false, error = "网络波动，请重试")
                )
        }
        compose.onNodeWithText("重新加载").performClick()
        assertEquals("fixture-chat", retry)
        compose.onNodeWithText("返回会话列表").performClick()
        assertTrue(closed)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h800dp-port")
    @Suppress("DEPRECATION")
    fun narrowLargeTextKeepsComputerSwitchingAccessible() {
        val configuration =
            android.content.res.Configuration(compose.activity.resources.configuration)
        configuration.fontScale = 1.6f
        compose.activity.resources.updateConfiguration(
            configuration,
            compose.activity.resources.displayMetrics,
        )
        val base = fixture()
        val first = base.computer!!
        val second = first.copy(certificateSha256 = "f".repeat(64), computerName = "我的第二台开发电脑")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                LauncherTheme(true) {
                    Box(Modifier.width(320.dp).fillMaxSize()) {
                        ComputersSheet(
                            base.copy(computers = listOf(first, second)),
                            2000,
                            LauncherActions(),
                            {},
                            {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("我的电脑").assertIsDisplayed()
        compose.onNodeWithTag("add-computer").performScrollTo().assertIsDisplayed()
        capture("computers-narrow-large-dark")
    }

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
        capture("configuration-light")
        compose.onNodeWithTag("config-httpConfig").performClick()
        compose.onNodeWithTag("config-httpConfig.intervalSeconds").performClick()
        compose
            .onNodeWithTag("config-input-httpConfig.intervalSeconds")
            .performTextReplacement("0.")
        compose.onNodeWithText("0.").assertExists()
        compose
            .onNodeWithTag("config-input-httpConfig.intervalSeconds")
            .performTextReplacement("0.5")
        capture("configuration-number-light")
        compose.onNodeWithTag("sheet-save-请求间隔（秒）").performClick()
        capture("configuration-fields-light")
        compose.onNodeWithTag("sheet-save-Codex HTTP 参数").performClick()
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
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            // Sheets and dialogs own separate Android windows. Include each
            // visible window so screenshots verify the editor, not only its host.
            org.robolectric.shadows.ShadowDialog.getShownDialogs()
                .filter { it.isShowing }
                .forEach { dialog ->
                    val window = dialog.window ?: return@forEach
                    val decor = window.decorView
                    if (decor.width <= 0 || decor.height <= 0) return@forEach
                    val attrs = window.attributes
                    if (
                        attrs.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                    )
                        canvas.drawColor(
                            android.graphics.Color.argb((attrs.dimAmount * 255).toInt(), 0, 0, 0)
                        )
                    val location = IntArray(2)
                    decor.getLocationOnScreen(location)
                    canvas.save()
                    canvas.translate(
                        (location[0] - origin[0]).toFloat(),
                        (location[1] - origin[1]).toFloat(),
                    )
                    decor.draw(canvas)
                    canvas.restore()
                }
            destination.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
    }

    @Test
    fun conversationDefaultsToChatShowsModelAndKeepsToolsInMenu() {
        val thread =
            WorkThread(
                "fixture",
                "为移动工作台添加图片与文件",
                "C:/Projects/launcher",
                "idle",
                "managed",
                1,
                "provider-name",
                "cli",
                "gpt-5.4",
            )
        val detail =
            ThreadDetail(
                thread,
                listOf(
                    WorkItem("u", "turn", "userMessage", "你", "请根据截图优化会话页面布局。"),
                    WorkItem(
                        "a",
                        "turn",
                        "agentMessage",
                        "Codex",
                        "我会保留清晰的对话区域，把执行记录和文件变更收进会话菜单，并让附件紧邻输入框。",
                    ),
                    WorkItem(
                        "c",
                        "turn",
                        "commandExecution",
                        "执行命令",
                        "npm test\nAll tests passed",
                        "completed",
                    ),
                ),
            )
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(fixture().copy(workbench = WorkbenchState(detail = detail)), 2000)
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithText("gpt-5.4 · 工作电脑 · launcher").assertIsDisplayed()
        compose.onNodeWithTag("thread-tab-1").assertDoesNotExist()
        compose.onNodeWithText("npm test\nAll tests passed").assertDoesNotExist()
        capture("conversation-clean-model-light")
        compose.onNodeWithContentDescription("会话操作").performClick()
        compose.onNodeWithTag("show-executions").performClick()
        compose.onNodeWithText("npm test\nAll tests passed").assertIsDisplayed()
        compose.onNodeWithTag("back-to-conversation").performClick()
        compose.onNodeWithText("请根据截图优化会话页面布局。").assertIsDisplayed()
    }

    @Test
    fun titleOpensRenameAndSendsTheSelectedSessionIdentity() {
        val thread =
            WorkThread(
                "rename-me",
                "新会话",
                "C:/Projects/demo",
                "idle",
                "managed",
                1,
                "",
                "cli",
                "fixture-model",
            )
        var command: Pair<String, JsonObject>? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    fixture()
                        .copy(
                            workbench = WorkbenchState(detail = ThreadDetail(thread, emptyList()))
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
        compose.onNodeWithTag("conversation-title").performClick()
        compose.onNodeWithTag("rename-thread-input").performTextReplacement("修复上传进度")
        compose.onNodeWithTag("sheet-save-重命名会话").performClick()
        assertEquals("rename", command?.first)
        assertEquals("rename-me", command?.second?.get("threadId").text())
        assertEquals("修复上传进度", command?.second?.get("name").text())
    }

    @Test
    fun pullDownLoadsEarlierHistoryAndKeepsTheManualButton() {
        val thread =
            WorkThread("history", "历史加载", "C:/Projects/demo", "idle", "managed", 1, "", "cli")
        var earlier = 0
        compose.setContent {
            LauncherTheme(false) {
                WorkbenchDetailPage(
                    fixture()
                        .copy(
                            workbench =
                                WorkbenchState(
                                    detail =
                                        ThreadDetail(
                                            thread,
                                            listOf(
                                                WorkItem(
                                                    "message",
                                                    "turn",
                                                    "agentMessage",
                                                    "Codex",
                                                    "最近的一条回复",
                                                )
                                            ),
                                            nextCursor = "page-2",
                                        )
                                )
                        ),
                    true,
                    LauncherActions(olderMessages = { earlier++ }),
                    confirm = {},
                )
            }
        }
        compose.onNodeWithTag("thread-timeline").performScrollToIndex(0)
        compose.onNodeWithTag("thread-timeline").performTouchInput {
            swipeDown(durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(1, earlier)
        compose.onNodeWithText("加载更早记录").performClick()
        assertEquals(2, earlier)
    }

    @Test
    fun attachmentOnlyComposerCanSendAndAnOldPcShowsTheUpgradeExplanation() {
        val thread =
            WorkThread(
                "files",
                "发送附件",
                "C:/Projects/demo",
                "idle",
                "managed",
                1,
                "",
                "cli",
                "fixture-model",
            )
        val detail = ThreadDetail(thread, emptyList(), backendScopeId = "scope")
        val base = fixture()
        val target = detail.draftTarget(base.computer)
        val local =
            LocalAttachment(
                "00000000-0000-4000-8000-000000000010",
                "error.log",
                "text/plain",
                256,
                "a".repeat(64),
            )
        var command: JsonObject? = null
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    base.copy(
                        workbench = WorkbenchState(detail = detail),
                        attachmentDrafts =
                            mapOf(target.key to ComposerDraft(target, attachments = listOf(local))),
                    ),
                    2000,
                    actions = LauncherActions(workbenchAction = { _, input -> command = input }),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithText("error.log").assertIsDisplayed()
        compose.onNodeWithTag("send-message").assertIsEnabled().performClick()
        assertEquals("", command?.get("text").text())
        assertEquals(local.id, command?.get("localAttachmentIds").list().single().text())
        capture("conversation-attachment-draft-light")
        compose.onNodeWithTag("add-attachment").performClick()
        compose.onNodeWithText("图片和文件附件需要 PC 2.8.0-beta.2 或更新版本。文字聊天仍可使用。").assertIsDisplayed()
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
                        model = "gpt-5.4",
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
        compose.onNodeWithText("gpt-5.4").assertIsDisplayed()
        capture("run-model-dark")
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
        compose.onNodeWithText("扫码连接电脑").performClick()
        assertEquals(1, scans)
        compose.onNodeWithText("粘贴配对内容").performClick()
        compose.onNodeWithText("验证并配对").assertIsNotEnabled()
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
            compose.onNodeWithText("工作电脑 · 在线", useUnmergedTree = true).assertIsDisplayed()
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
        compose.onNodeWithContentDescription("会话操作").performClick()
        compose.onNodeWithTag("show-executions").performClick()
        compose.onNodeWithText("npm test\n\n2 tests passed").assertIsDisplayed()
        capture("workbench-execution-dark")
    }

    @Test
    fun independentHistoryExplainsBothContinuationChoicesAndNeverAttaches() {
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
        compose.onNodeWithTag("choose-session-mode").performClick()
        compose.onNodeWithTag("continue-original").assertIsNotEnabled()
        compose.onNodeWithText("连接原会话后台").assertIsDisplayed()
        capture("workbench-session-choice-readonly")
        assertNull(action)
        compose.onNodeWithTag("continue-fork").performClick()
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

    @Test
    fun sharedSessionOffersOriginalIdentityAndExplicitIndependentFork() {
        val thread =
            WorkThread(
                "desktop-original",
                "完善移动工作台",
                "C:/Projects/launcher",
                "idle",
                "attach",
                0,
                "Codex",
                "desktop",
            )
        val commands = mutableListOf<Pair<String, JsonObject>>()
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    fixture()
                        .copy(
                            workbench =
                                WorkbenchState(
                                    threads = listOf(thread),
                                    detail =
                                        ThreadDetail(
                                            thread,
                                            listOf(
                                                WorkItem(
                                                    "u1",
                                                    "t1",
                                                    "userMessage",
                                                    "你",
                                                    "让手机也能接着电脑上的开发。",
                                                ),
                                                WorkItem(
                                                    "a1",
                                                    "t1",
                                                    "agentMessage",
                                                    "Codex",
                                                    "已经整理好了。\n\n- 配置改为分组列表\n- 可以从侧栏切换会话\n- 开发进展会实时同步",
                                                ),
                                            ),
                                        ),
                                    streaming = true,
                                )
                        ),
                    2000,
                    actions =
                        LauncherActions(
                            workbenchAction = { action, input -> commands += action to input }
                        ),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        capture("workbench-chat-light")
        compose.onNodeWithTag("session-mode").performClick()
        compose.onNodeWithTag("continue-original").assertIsEnabled()
        capture("workbench-session-choice-light")
        compose.onNodeWithTag("continue-original").performClick()
        assertEquals("attach", commands.single().first)
        assertEquals("desktop-original", commands.single().second["threadId"].text())
        compose.onNodeWithTag("message-input").performTextInput("继续检查")
        compose.onNodeWithTag("send-message").performClick()
        assertEquals("send", commands.last().first)
        assertEquals("desktop-original", commands.last().second["threadId"].text())
        compose.onNodeWithTag("session-mode").performClick()
        compose.onNodeWithTag("continue-fork").performClick()
        assertEquals("fork", commands.last().first)
        assertEquals("desktop-original", commands.last().second["threadId"].text())
    }

    @Test
    fun sidebarSwitchingPreservesSeparateDraftsAndSearchesLocally() {
        val first =
            WorkThread(
                "alpha",
                "重构手机工作台",
                "C:/Projects/launcher",
                "idle",
                "managed",
                0,
                "Codex",
                "appServer",
            )
        val second =
            WorkThread(
                "beta",
                "检查配置同步",
                "C:/Projects/website",
                "idle",
                "attach",
                0,
                "Codex",
                "desktop",
            )
        val state =
            mutableStateOf(
                fixture()
                    .copy(
                        workbench =
                            WorkbenchState(
                                threads = listOf(first, second),
                                detail = ThreadDetail(first, emptyList()),
                                streaming = true,
                            )
                    )
            )
        compose.setContent {
            LauncherTheme(false) {
                LauncherScreen(
                    state.value,
                    2000,
                    actions =
                        LauncherActions(
                            openThread = { id ->
                                state.value =
                                    state.value.copy(
                                        workbench =
                                            state.value.workbench.copy(
                                                detail =
                                                    ThreadDetail(
                                                        if (id == first.id) first else second,
                                                        emptyList(),
                                                    )
                                            )
                                    )
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithTag("message-input").performTextInput("先保存这段未发送的要求")
        compose.onNodeWithTag("open-sidebar").performClick()
        capture("sidebar-light")
        compose.onNodeWithTag("drawer-search").performTextInput("配置")
        compose.onNodeWithTag("thread-alpha").assertDoesNotExist()
        compose.onNodeWithTag("thread-beta").performClick()
        assertEquals(
            "",
            compose
                .onNodeWithTag("message-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        compose.onNodeWithTag("message-input").performTextInput("另一个项目的草稿")
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("drawer-search").performTextReplacement("")
        compose.onNodeWithTag("thread-alpha").performClick()
        compose.onNodeWithTag("message-input").assertTextEquals("先保存这段未发送的要求")
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("thread-beta").performClick()
        compose.onNodeWithTag("message-input").assertTextEquals("另一个项目的草稿")
    }

    @Test
    fun cancelledSettingEditsNeverBecomeAPcPatchAndInvalidNumbersCannotCommit() {
        val configuration =
            ConfigurationEnvelope(
                "r1",
                buildJsonObject {
                    put("interval", 2.0)
                    put("keepAlive", true)
                },
            )
        var saved: JsonObject? = null
        compose.setContent {
            LauncherTheme(false) {
                ConfigurationPage(
                    fixture().copy(configuration = configuration),
                    true,
                    LauncherActions(saveConfiguration = { _, patch -> saved = patch }),
                )
            }
        }
        compose.onNodeWithText("保存到 PC").assertIsNotEnabled()
        compose.onNodeWithTag("config-interval").performClick()
        compose.onNodeWithTag("config-input-interval").performTextReplacement("NaN")
        compose.onNodeWithTag("sheet-save-重试间隔（秒）").assertIsNotEnabled()
        compose.onNodeWithTag("sheet-cancel-重试间隔（秒）").performClick()
        compose.onNodeWithText("保存到 PC").assertIsNotEnabled()
        compose.onNodeWithTag("config-interval").performClick()
        compose.onNodeWithTag("config-input-interval").assertTextContains("2.0")
        compose.onNodeWithTag("config-input-interval").performTextReplacement("3.5")
        compose.onNodeWithTag("sheet-save-重试间隔（秒）").assertIsNotEnabled()
        compose.onNodeWithTag("config-input-interval").performTextReplacement("3")
        compose.onNodeWithTag("sheet-save-重试间隔（秒）").performClick()
        compose.onNodeWithText("保存到 PC").performClick()
        assertEquals(setOf("interval"), saved?.keys)
        assertEquals("3", saved?.get("interval").text())
    }
}
