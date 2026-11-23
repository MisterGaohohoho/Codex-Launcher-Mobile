package com.codexlauncher.companion

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codexlauncher.companion.data.*
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class DetailTarget(val runId: String, val worker: WorkerSummary)

data class ComputerOverview(
    val snapshot: Snapshot? = null,
    val checkedAt: Long = 0,
    val error: String? = null,
    val checking: Boolean = false,
)

data class AdminOutcome(
    val requestId: String,
    val action: String,
    val operation: String,
    val value: JsonObject,
    val messageId: String = "",
)

data class CompanionState(
    val computer: PairedComputer? = null,
    val computers: List<PairedComputer> = emptyList(),
    val computerStatuses: Map<String, ComputerOverview> = emptyMap(),
    val switchingComputer: Boolean = false,
    val connecting: Boolean = false,
    val connectionFailures: Int = 0,
    val connectionInfo: ConnectionInfo = ConnectionInfo(),
    val snapshot: Snapshot? = null,
    val online: Boolean = false,
    val lastSyncElapsed: Long = 0,
    val lastSyncAt: Long = 0,
    val busy: Boolean = false,
    val pairing: Boolean = false,
    val pairingGeneration: Long = 0,
    val pairingError: String? = null,
    val connectionError: String? = null,
    val notice: String? = null,
    val theme: String = "system",
    val detailTarget: DetailTarget? = null,
    val details: WorkerDetails? = null,
    val detailsLoading: Boolean = false,
    val detailsError: String? = null,
    val workbench: WorkbenchState = WorkbenchState(),
    val configuration: ConfigurationEnvelope? = null,
    val configurationLoading: Boolean = false,
    val adminResult: JsonObject? = null,
    val adminLoading: Boolean = false,
    val adminError: String? = null,
    val lastAdminOutcome: AdminOutcome? = null,
    val uncertainCommand: AdminCommand? = null,
    val update: UpdateState = UpdateState(),
    val attachmentDrafts: Map<String, ComposerDraft> = emptyMap(),
    val pendingMessages: Map<String, PendingMessage> = emptyMap(),
    val importingAttachments: Set<String> = emptySet(),
    val attachmentFiles: Map<String, File> = emptyMap(),
    val attachmentErrors: Map<String, String> = emptyMap(),
    val lastDraftSubmissionId: String = "",
) {
    // A network result can be newer than the UI's one-second clock tick.
    fun fresh(now: Long): Boolean =
        online && lastSyncElapsed > 0 && (now - lastSyncElapsed).coerceAtLeast(0) <= 8000

    fun canControl(now: Long): Boolean = fresh(now) && computer?.canControl == true && !busy

    fun connectionLabel(now: Long): String =
        when {
            fresh(now) -> "在线"
            connectionFailures >= 3 || !online && !connecting && connectionError != null -> "连接中断"
            lastSyncElapsed > 0 &&
                (now - lastSyncElapsed).coerceAtLeast(0) > 30_000 &&
                connectionFailures > 0 -> "连接中断"
            lastSyncElapsed > 0 -> "正在同步"
            else -> "连接中"
        }
}

class CompanionViewModel
@JvmOverloads
constructor(
    application: Application,
    private val store: SessionStore = DeviceStore(application),
    private val makeClient: (String, String, String?) -> CompanionApi = { endpoint, pin, token ->
        LauncherApi(endpoint, pin, token)
    },
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CompanionState(theme = store.theme))
    val state = mutable.asStateFlow()
    private var api: CompanionApi? = null
    private var profiles = ComputerProfiles()
    private val computerChanges = Mutex()
    private val computerCache = linkedMapOf<String, CompanionState>()
    private val threadCache = linkedMapOf<String, ThreadDetail>()
    private var computerOverviewJob: Job? = null
    private var configurationJob: Job? = null
    private var foreground = false
    private var polling: Job? = null
    private var detailsJob: Job? = null
    private val refreshLock = Mutex()
    private var lastDetailRead = 0L
    private var streamJob: Job? = null
    private var workbenchJob: Job? = null
    private var threadJob: Job? = null
    private var streamEpoch = ""
    private var streamCursor = 0L
    private var lastThreadRefresh = 0L
    private var lastWorkbenchRefresh = 0L
    private var workbenchGeneration = 0L
    private var threadGeneration = 0L
    private var catalogGeneration = 0L
    private var adminQueryJob: Job? = null
    private var latestAdminQuery: Pair<String, JsonObject>? = null
    private val updater = AppUpdater(application)
    private var outgoingMessages = OutgoingMessages()
    private val messages =
        MessageController(
            application,
            viewModelScope,
            { api },
            { mutable.value },
            { update -> mutable.update(update) },
            { request, attachments -> executeAdmin(request, attachments) },
            { message, status, result ->
                message.request?.let { outgoingMessages.settle(it, status, result) }
                mutable.update {
                    it.copy(
                        uncertainCommand =
                            it.uncertainCommand?.takeUnless { command ->
                                command.input["messageId"].text() == message.messageId
                            },
                        adminError = null,
                        workbench =
                            it.workbench.copy(
                                detail = it.workbench.detail?.let(outgoingMessages::decorate)
                            ),
                    )
                }
                mutable.value.workbench.detail?.thread?.id?.let { openThread(it, quiet = true) }
            },
        )

    private suspend fun outgoingFor(computer: PairedComputer): OutgoingMessages =
        withContext(Dispatchers.IO) {
            OutgoingMessages().apply {
                bind(
                    File(
                        getApplication<Application>().filesDir,
                        "outgoing/" +
                            bytesSha256(computer.certificateSha256.toByteArray()) +
                            ".json",
                    )
                )
            }
        }

    fun editDraft(target: DraftTarget, text: String) = messages.edit(target, text)

    fun importAttachments(target: DraftTarget, uris: List<Uri>, photos: Boolean) {
        viewModelScope.launch {
            initialization.join()
            messages.import(target, uris, photos)
        }
    }

    fun removeAttachment(target: DraftTarget, id: String) = messages.remove(target, id)

    fun resumeMessage(id: String, acceptCurrentTurn: Boolean = false) =
        messages.resume(id, acceptCurrentTurn)

    fun pauseMessage(id: String) = messages.pause(id)

    fun discardMessage(id: String) = messages.discard(id)

    fun loadAttachment(target: DraftTarget, meta: AttachmentMeta, preview: Boolean) =
        messages.load(target, meta, preview)

    private val initialization = viewModelScope.launch {
        try {
            profiles = withContext(Dispatchers.IO) { store.loadProfiles() }
            val computer = profiles.active
            mutable.update { it.copy(computers = profiles.computers) }
            if (computer != null) {
                validateEndpoint(computer.endpoint)
                api =
                    makeClient(computer.endpoint, computer.certificateSha256, computer.deviceToken)
                api?.updateEndpoints(computer.connectionEndpoints)
                outgoingMessages = outgoingFor(computer)
                mutable.update { it.copy(computer = computer) }
                startPolling()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.update { it.copy(connectionError = "无法读取这台手机保存的授权，请重新配对电脑") }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) startPolling()
        else {
            messages.pause()
            computerOverviewJob?.cancel()
            polling?.cancel()
            polling = null
            streamJob?.cancel()
            streamJob = null
            detailsJob?.cancel()
            detailsJob = null
            mutable.update {
                it.copy(
                    online = false,
                    detailsLoading = false,
                    workbench = it.workbench.copy(streaming = false),
                )
            }
        }
    }

    private fun startPolling() {
        if (!foreground || api == null || polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive && foreground) {
                refreshNow()
                delay(
                    if (mutable.value.connectionFailures == 0) 2000
                    else (1000L shl mutable.value.connectionFailures.coerceAtMost(3))
                )
            }
        }
    }

    fun refresh() {
        if (!foreground || mutable.value.busy) return
        polling?.cancel()
        polling = null
        startPolling()
    }

    private suspend fun refreshNow() = refreshLock.withLock {
        val source = api ?: return@withLock
        mutable.update { it.copy(connecting = true) }
        try {
            val value = source.snapshot()
            if (source !== api) return@withLock
            mutable.update {
                it.copy(
                    snapshot = value,
                    online = foreground,
                    lastSyncElapsed = SystemClock.elapsedRealtime(),
                    lastSyncAt = System.currentTimeMillis(),
                    connectionError = null,
                    connecting = false,
                    connectionFailures = 0,
                    connectionInfo = source.connectionInfo,
                    computerStatuses =
                        it.computerStatuses +
                            (it.computer!!.id to
                                ComputerOverview(value, System.currentTimeMillis())),
                )
            }
            rememberEndpoints(source, value)
            if (
                foreground &&
                    mutable.value.computer?.canControl == true &&
                    "workbench" in value.capabilities
            ) {
                startStream()
                if (
                    (mutable.value.workbench.detail == null ||
                        mutable.value.workbench.capabilities == null) &&
                        !mutable.value.workbench.loading &&
                        (lastWorkbenchRefresh == 0L ||
                            (mutable.value.workbench.threads.size <= 30 &&
                                SystemClock.elapsedRealtime() - lastWorkbenchRefresh > 15000))
                )
                    loadWorkbench()
                if (
                    (!mutable.value.workbench.streaming ||
                        mutable.value.workbench.detail?.thread?.control == "readOnly") &&
                        SystemClock.elapsedRealtime() - lastThreadRefresh > 5000
                )
                    mutable.value.workbench.detail?.thread?.id?.let { openThread(it, quiet = true) }
            }
            val target = mutable.value.detailTarget
            if (
                foreground &&
                    target != null &&
                    mutable.value.details?.completed != true &&
                    detailsJob?.isActive != true &&
                    SystemClock.elapsedRealtime() - lastDetailRead > 4000 &&
                    mutable.value.detailsError == null
            ) {
                loadDetails(target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (source === api)
                mutable.update {
                    it.copy(
                        online = if (e is ApiException && e.status == 401) false else it.online,
                        connecting = false,
                        connectionFailures = it.connectionFailures + 1,
                        connectionError = explain(e, false),
                    )
                }
            if (source === api && e is ApiException && e.status == 401) {
                polling?.cancel()
                polling = null
            }
        } finally {
            if (source === api) mutable.update { it.copy(connecting = false) }
        }
    }

    private suspend fun rememberEndpoints(source: CompanionApi, snapshot: Snapshot) {
        if (snapshot.connectionEndpoints.isEmpty()) return
        computerChanges.withLock {
            if (source !== api) return@withLock
            val computer = mutable.value.computer ?: return@withLock
            val endpoints =
                (snapshot.connectionEndpoints + computer.connectionEndpoints)
                    .filter { runCatching { validateEndpoint(it) }.isSuccess }
                    .distinct()
                    .take(16)
            if (
                endpoints == computer.connectionEndpoints &&
                    computer.computerName == snapshot.computerName
            )
                return@withLock
            val updated =
                computer.copy(
                    connectionEndpoints = endpoints,
                    computerName = snapshot.computerName.take(128),
                )
            val next = profiles.upsert(updated, activate = false)
            try {
                withContext(Dispatchers.IO) { store.saveProfiles(next) }
                profiles = next
                mutable.update { it.copy(computer = updated, computers = next.computers) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Keep the working session; retry persistence after the next snapshot. */
            }
        }
    }

    private fun cacheComputer() {
        val value = mutable.value
        val id = value.computer?.id ?: return
        computerCache.remove(id)
        computerCache[id] =
            value.copy(busy = false, pairing = false, online = false, connecting = false)
        while (computerCache.size > 4) {
            val evict =
                computerCache.entries.firstOrNull {
                    it.key != id && it.value.uncertainCommand == null
                } ?: break
            computerCache.remove(evict.key)
        }
    }

    // Persist a complete profile set before changing the live connection. A failed save keeps the
    // old PC usable.
    private suspend fun activate(
        next: ComputerProfiles,
        notice: String? = null,
        paired: Boolean = false,
    ) {
        val computer = next.active
        var candidate: CompanionApi? = null
        try {
            candidate = computer?.let {
                makeClient(it.endpoint, it.certificateSha256, it.deviceToken).apply {
                    updateEndpoints(it.connectionEndpoints)
                }
            }
            val outgoing = computer?.let { outgoingFor(it) } ?: OutgoingMessages()
            withContext(Dispatchers.IO) { store.saveProfiles(next) }
            polling?.cancelAndJoin()
            polling = null
            detailsJob?.cancel()
            resetWorkbenchJobs()
            messages.pauseAndJoin()
            cacheComputer()
            val previous = api
            api = candidate
            candidate = null
            outgoingMessages = outgoing
            profiles = next
            val global = mutable.value
            val cached = computer?.let { computerCache[it.id] } ?: CompanionState()
            mutable.value =
                cached.copy(
                    computer = computer,
                    computers = next.computers,
                    computerStatuses =
                        global.computerStatuses.filterKeys { key ->
                            next.computers.any { it.id == key }
                        },
                    theme = global.theme,
                    update = global.update,
                    notice = notice,
                    busy = true,
                    pairing = false,
                    pairingError = null,
                    switchingComputer = true,
                    online = false,
                    connecting = false,
                    lastSyncElapsed = 0,
                    connectionFailures = 0,
                    connectionError = null,
                    connectionInfo =
                        api?.connectionInfo ?: ConnectionInfo(computer?.endpoint.orEmpty()),
                    pairingGeneration = global.pairingGeneration + if (paired) 1 else 0,
                    configurationLoading = false,
                    adminLoading = false,
                    detailTarget = null,
                    detailsLoading = false,
                    details = null,
                    detailsError = null,
                    lastAdminOutcome = null,
                    workbench =
                        cached.workbench.copy(
                            loading = false,
                            streaming = false,
                            detailLoading = false,
                            capabilities = null,
                        ),
                )
            messages.publish()
            startPolling()
            closeClient(previous)
            mutable.update { it.copy(busy = false, switchingComputer = false) }
        } finally {
            closeClient(candidate)
        }
    }

    fun pair(text: String) {
        if (mutable.value.busy || mutable.value.switchingComputer) return
        mutable.update { it.copy(busy = true, pairing = true, pairingError = null) }
        viewModelScope.launch {
            var candidate: CompanionApi? = null
            try {
                initialization.join()
                val code = PairingCode.parse(text)
                require(
                    profiles.computers.size < ComputerProfiles.MAX_COMPUTERS ||
                        profiles.computers.any { it.id == code.certificateSha256.lowercase() }
                ) {
                    "电脑列表已满，请先移除不再使用的电脑"
                }
                candidate = makeClient(code.endpoint, code.certificateSha256, null)
                val reply = candidate.pair(code, "${Build.MANUFACTURER} ${Build.MODEL}".take(64))
                val computer =
                    PairedComputer(
                        code.endpoint,
                        code.certificateSha256.lowercase(),
                        reply.deviceId,
                        reply.deviceToken,
                        reply.computerName,
                        reply.canControl,
                        reply.expiresAt,
                        connectionEndpoints =
                            reply.connectionEndpoints
                                .filter { runCatching { validateEndpoint(it) }.isSuccess }
                                .distinct()
                                .take(16),
                    )
                computerChanges.withLock {
                    activate(profiles.upsert(computer), "电脑已添加，可在我的电脑中切换", paired = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(pairingError = explain(e, true)) }
            } finally {
                mutable.update { it.copy(busy = false, pairing = false) }
                closeClient(candidate)
            }
        }
    }

    fun selectComputer(id: String) {
        if (id == mutable.value.computer?.id) return
        if (mutable.value.busy || mutable.value.switchingComputer) {
            mutable.update { it.copy(notice = "请等待当前操作提交完成后切换电脑") }
            return
        }
        mutable.update { it.copy(switchingComputer = true, busy = true) }
        viewModelScope.launch {
            try {
                initialization.join()
                computerChanges.withLock { activate(profiles.select(id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(notice = e.message ?: "切换电脑失败") }
            } finally {
                mutable.update { it.copy(switchingComputer = false, busy = false) }
            }
        }
    }

    fun editComputer(id: String, name: String, endpoint: String) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            try {
                require(name.trim().length <= 64 && name.none { it.isISOControl() }) {
                    "电脑备注不能超过 64 个字符"
                }
                val extra =
                    endpoint
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { validateEndpoint(it).toString().trimEnd('/') }
                computerChanges.withLock {
                    val old = profiles.computers.firstOrNull { it.id == id } ?: return@withLock
                    val updated =
                        old.copy(
                            nickname = name.trim(),
                            connectionEndpoints =
                                (listOfNotNull(extra) + old.connectionEndpoints)
                                    .distinct()
                                    .take(16),
                        )
                    val next = profiles.upsert(updated, activate = false, preserveNickname = false)
                    withContext(Dispatchers.IO) { store.saveProfiles(next) }
                    profiles = next
                    if (mutable.value.computer?.id == id)
                        api?.updateEndpoints(updated.connectionEndpoints)
                    mutable.update {
                        it.copy(
                            computers = next.computers,
                            computer = next.active,
                            notice = "电脑信息已保存",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(notice = e.message ?: "保存电脑信息失败") }
            }
        }
    }

    fun removeComputer(id: String) {
        if (mutable.value.busy) return
        val cached = if (id == mutable.value.computer?.id) mutable.value else computerCache[id]
        if (cached?.uncertainCommand != null) {
            mutable.update { it.copy(notice = "这台电脑还有结果未确认的操作，请先核对后再移除") }
            return
        }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                initialization.join()
                computerChanges.withLock {
                    val next = profiles.remove(id)
                    if (id == mutable.value.computer?.id) activate(next, "已移除本机保存的电脑授权")
                    else {
                        withContext(Dispatchers.IO) { store.saveProfiles(next) }
                        profiles = next
                        mutable.update {
                            it.copy(
                                computers = next.computers,
                                computerStatuses = it.computerStatuses - id,
                            )
                        }
                    }
                    computerCache.remove(id)
                    threadCache.keys.removeAll { it.startsWith("$id:") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(notice = e.message ?: "无法移除电脑授权") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun forget() {
        mutable.value.computer?.id?.let(::removeComputer)
    }

    fun refreshComputers() {
        if (!foreground || computerOverviewJob?.isActive == true) return
        computerOverviewJob = viewModelScope.launch {
            val limiter = kotlinx.coroutines.sync.Semaphore(2)
            profiles.computers
                .filter { it.id != mutable.value.computer?.id }
                .map { computer ->
                    launch {
                        limiter.acquire()
                        var client: CompanionApi? = null
                        try {
                            if (profiles.computers.none { it.id == computer.id }) return@launch
                            mutable.update {
                                it.copy(
                                    computerStatuses =
                                        it.computerStatuses +
                                            (computer.id to
                                                (it.computerStatuses[computer.id]
                                                        ?: ComputerOverview())
                                                    .copy(checking = true))
                                )
                            }
                            client =
                                makeClient(
                                        computer.endpoint,
                                        computer.certificateSha256,
                                        computer.deviceToken,
                                    )
                                    .apply { updateEndpoints(computer.connectionEndpoints) }
                            val value = client.snapshot()
                            if (
                                profiles.computers.any { it.id == computer.id } &&
                                    mutable.value.computer?.id != computer.id
                            )
                                mutable.update {
                                    it.copy(
                                        computerStatuses =
                                            it.computerStatuses +
                                                (computer.id to
                                                    ComputerOverview(
                                                        value,
                                                        System.currentTimeMillis(),
                                                    ))
                                    )
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (
                                profiles.computers.any { it.id == computer.id } &&
                                    mutable.value.computer?.id != computer.id
                            )
                                mutable.update {
                                    it.copy(
                                        computerStatuses =
                                            it.computerStatuses +
                                                (computer.id to
                                                    (it.computerStatuses[computer.id]
                                                            ?: ComputerOverview())
                                                        .copy(
                                                            error = explain(e, false),
                                                            checking = false,
                                                        ))
                                    )
                                }
                        } finally {
                            closeClient(client)
                            mutable.update { state ->
                                state.computerStatuses[computer.id]?.let { overview ->
                                    state.copy(
                                        computerStatuses =
                                            state.computerStatuses +
                                                (computer.id to overview.copy(checking = false))
                                    )
                                } ?: state
                            }
                            limiter.release()
                        }
                    }
                }
                .joinAll()
        }
    }

    fun theme(value: String) {
        if (value !in listOf("system", "light", "dark")) return
        store.theme = value
        mutable.update { it.copy(theme = value) }
    }

    fun consumeNotice() {
        mutable.update { it.copy(notice = null) }
    }

    fun start(expectedRunId: String?) = control { remote, state ->
        val snapshot = state.snapshot ?: error("请等待电脑状态同步")
        check(snapshot.status?.runId == expectedRunId) { "电脑任务已变化，请重新确认" }
        check(snapshot.status?.active != true && snapshot.configuration.startAvailable) {
            "请先在电脑端保存有效配置"
        }
        remote.start(StartRequest(UUID.randomUUID().toString(), snapshot.status?.runId))
    }

    fun stop(runId: String) = control { remote, state ->
        val run = state.snapshot?.status?.takeIf { it.active } ?: error("当前没有运行中的任务")
        check(run.runId == runId) { "电脑任务已变化，请重新确认" }
        remote.stopRun(StopRequest(UUID.randomUUID().toString(), run.runId))
    }

    fun keepAlive(enabled: Boolean? = null, mode: String? = null, expectedRunId: String?) =
        control { remote, state ->
            check(state.snapshot?.status?.takeIf { it.active }?.runId == expectedRunId) {
                "电脑任务已变化，请刷新后调整保活"
            }
            if (mode != null) {
                require(mode in listOf("configured", "questionBank", "ascii"))
                if (mode != "configured")
                    check(state.snapshot?.configuration?.contentModeSupported == true) {
                        "当前命令不支持替换保活内容"
                    }
                if (mode == "questionBank")
                    check(state.snapshot?.configuration?.questionBankAvailable == true) {
                        "电脑本轮题库不可用"
                    }
            }
            remote.keepAlive(
                KeepAliveRequest(
                    UUID.randomUUID().toString(),
                    state.snapshot?.status?.takeIf { it.active }?.runId,
                    enabled,
                    mode,
                )
            )
        }

    private fun control(action: suspend (CompanionApi, CompanionState) -> ActionResponse) {
        val before = mutable.value
        val source = api ?: return
        if (!before.canControl(SystemClock.elapsedRealtime())) {
            mutable.update {
                it.copy(
                    notice = if (it.computer?.canControl == false) "此手机只有查看权限" else "请等待电脑状态重新同步"
                )
            }
            return
        }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val response = action(source, before)
                if (source === api) mutable.update { it.copy(notice = response.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api)
                    mutable.update {
                        it.copy(
                            notice =
                                if (
                                    e is ApiException ||
                                        e is IllegalArgumentException ||
                                        e is IllegalStateException
                                )
                                    e.message
                                else "未确认操作结果，正在刷新电脑状态；请勿连续重复操作"
                        )
                    }
            } finally {
                if (source === api) {
                    mutable.update { it.copy(busy = false) }
                    refreshNow()
                }
            }
        }
    }

    fun openDetails(worker: WorkerSummary) {
        val run = mutable.value.snapshot?.status?.runId ?: return
        if (!mutable.value.fresh(SystemClock.elapsedRealtime()) || worker.attempt <= 0) return
        val target = DetailTarget(run, worker)
        mutable.update { it.copy(detailTarget = target, details = null, detailsError = null) }
        loadDetails(target)
    }

    fun refreshDetails() {
        mutable.value.detailTarget?.let { loadDetails(it) }
    }

    private fun loadDetails(target: DetailTarget) {
        val source = api ?: return
        detailsJob?.cancel()
        mutable.update { it.copy(detailsLoading = true, detailsError = null) }
        lastDetailRead = SystemClock.elapsedRealtime()
        detailsJob = viewModelScope.launch {
            try {
                val value =
                    source.details(target.runId, target.worker.workerId, target.worker.attempt)
                if (source === api && mutable.value.detailTarget == target)
                    mutable.update { it.copy(details = value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api && mutable.value.detailTarget == target)
                    mutable.update { it.copy(detailsError = explain(e, false)) }
            } finally {
                if (source === api && mutable.value.detailTarget == target)
                    mutable.update { it.copy(detailsLoading = false) }
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        mutable.update {
            it.copy(
                detailTarget = null,
                details = null,
                detailsError = null,
                detailsLoading = false,
            )
        }
    }

    private fun resetWorkbenchJobs() {
        messages.pause()
        streamJob?.cancel()
        streamJob = null
        workbenchJob?.cancel()
        threadJob?.cancel()
        threadGeneration++
        workbenchGeneration++
        catalogGeneration++
        adminQueryJob?.cancel()
        configurationJob?.cancel()
        latestAdminQuery = null
        lastWorkbenchRefresh = 0L
        streamEpoch = ""
        streamCursor = 0
    }

    private fun startStream() {
        if (!foreground || streamJob?.isActive == true) return
        val source = api ?: return
        streamJob = viewModelScope.launch {
            var backoff = 1000L
            while (isActive && foreground && source === api) {
                try {
                    source.events(streamEpoch, streamCursor).collect { batch ->
                        if (source !== api) return@collect
                        streamEpoch = batch.epoch
                        streamCursor = batch.cursor
                        backoff = 1000
                        mutable.update { state ->
                            state.copy(
                                workbench =
                                    state.workbench.copy(
                                        streaming = batch.connected,
                                        approvals = batch.approvals,
                                        detail =
                                            state.workbench.detail?.let { detail ->
                                                outgoingMessages.decorate(
                                                    batch.events.fold(detail, ::applyWorkbenchEvent)
                                                )
                                            },
                                    )
                            )
                        }
                        if (
                            batch.reset ||
                                batch.events.any {
                                    it["method"].text() in
                                        listOf("turn/completed", "gateway/refresh")
                                }
                        ) {
                            mutable.value.workbench.detail?.thread?.id?.let {
                                openThread(it, quiet = true)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Snapshot polling remains available during stream reconnect. */
                }
                if (source === api)
                    mutable.update { it.copy(workbench = it.workbench.copy(streaming = false)) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        }
    }

    fun loadWorkbench(
        search: String = mutable.value.workbench.search,
        archived: Boolean = mutable.value.workbench.archived,
        more: Boolean = false,
    ) {
        val source = api ?: return
        workbenchJob?.cancel()
        val generation = ++workbenchGeneration
        lastWorkbenchRefresh = SystemClock.elapsedRealtime()
        val cursor = if (more) mutable.value.workbench.nextCursor else null
        mutable.update {
            it.copy(
                workbench =
                    it.workbench.copy(
                        loading = true,
                        error = null,
                        search = search,
                        archived = archived,
                    )
            )
        }
        workbenchJob = viewModelScope.launch {
            try {
                val capabilities =
                    mutable.value.workbench.capabilities ?: source.query("workbench-capabilities")
                val models =
                    if (mutable.value.workbench.capabilities == null)
                        try {
                            source.query("workbench-models")["data"].list().map { it.obj() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    else mutable.value.workbench.models
                val page =
                    source.query(
                        "workbench-threads",
                        buildJsonObject {
                            put("search", search)
                            put("archived", archived)
                            cursor?.let { put("cursor", it) }
                        },
                    )
                val threads =
                    withContext(Dispatchers.Default) {
                        page["data"].list().map { WorkThread.parse(it.obj()) }
                    }
                if (source !== api || generation != workbenchGeneration) return@launch
                val backend = capabilities["backendScopeId"].text()
                val oldDetail = mutable.value.workbench.detail
                val changedBackend = oldDetail != null && oldDetail.backendScopeId != backend
                if (changedBackend) {
                    threadJob?.cancel()
                    threadGeneration++
                }
                mutable.update {
                    it.copy(
                        workbench =
                            it.workbench.copy(
                                capabilities = capabilities,
                                settings = capabilities["settings"].obj(),
                                models = models,
                                threads =
                                    (if (more) it.workbench.threads + threads else threads)
                                        .distinctBy { t -> t.id },
                                nextCursor = page["nextCursor"].text().ifBlank { null },
                                detail = if (changedBackend) null else it.workbench.detail,
                                openingThreadId =
                                    if (changedBackend) null else it.workbench.openingThreadId,
                                detailLoading =
                                    if (changedBackend) false else it.workbench.detailLoading,
                                approvals =
                                    if (changedBackend) emptyList() else it.workbench.approvals,
                            )
                    )
                }
                if (changedBackend) mutable.update { it.copy(notice = "电脑会话后台已变化，请重新选择会话") }
                else
                    (oldDetail?.thread?.id ?: mutable.value.workbench.openingThreadId)?.let {
                        openThread(it, quiet = oldDetail != null)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api && generation == workbenchGeneration)
                    mutable.update {
                        it.copy(workbench = it.workbench.copy(error = e.message ?: "读取会话失败"))
                    }
            } finally {
                if (source === api && generation == workbenchGeneration)
                    mutable.update { it.copy(workbench = it.workbench.copy(loading = false)) }
            }
        }
    }

    private fun rememberThread(detail: ThreadDetail) {
        val pc = mutable.value.computer?.id ?: return
        val key = "$pc:${detail.backendScopeId}:${detail.thread.id}"
        threadCache.remove(key)
        threadCache[key] = detail
        while (threadCache.size > 8) threadCache.remove(threadCache.keys.first())
    }

    fun openThread(id: String, quiet: Boolean = false, older: Boolean = false) {
        val source = api ?: return
        if (
            older &&
                (threadJob?.isActive == true ||
                    mutable.value.workbench.detail?.takeIf { it.thread.id == id }?.nextCursor ==
                        null)
        )
            return
        if (quiet && threadJob?.isActive == true) return
        threadJob?.cancel()
        lastThreadRefresh = SystemClock.elapsedRealtime()
        val generation = ++threadGeneration
        val previous = mutable.value.workbench.detail?.takeIf { it.thread.id == id }
        mutable.value.workbench.detail?.let(::rememberThread)
        val pc = mutable.value.computer?.id.orEmpty()
        val backend = mutable.value.workbench.capabilities?.get("backendScopeId").text()
        val cached = previous ?: threadCache["$pc:$backend:$id"]
        mutable.update {
            it.copy(
                workbench =
                    it.workbench.copy(
                        detail = cached,
                        openingThreadId = id,
                        detailLoading = !quiet,
                        error = null,
                    )
            )
        }
        threadJob = viewModelScope.launch {
            try {
                val result =
                    source.query(
                        "workbench-thread",
                        buildJsonObject {
                            put("threadId", id)
                            if (older) previous?.nextCursor?.let { put("cursor", it) }
                        },
                    )
                var detail = withContext(Dispatchers.Default) { ThreadDetail.parse(result) }
                if (source !== api || generation != threadGeneration) return@launch
                val live =
                    mutable.value.workbench.detail?.takeIf {
                        it.thread.id == id && it.backendScopeId == detail.backendScopeId
                    }
                if (older && live != null)
                    detail =
                        live.copy(
                            items = (detail.items + live.items).distinctBy { it.id },
                            nextCursor = detail.nextCursor,
                        )
                else if (live != null) detail = mergeThreadRefresh(detail, live)
                if (live != null && live.activeTurn != previous?.activeTurn)
                    detail =
                        detail.copy(
                            activeTurn = live.activeTurn,
                            thread = detail.thread.copy(status = live.thread.status),
                        )
                rememberThread(detail)
                mutable.update {
                    it.copy(
                        workbench =
                            it.workbench.copy(
                                detail = outgoingMessages.decorate(detail),
                                openingThreadId = id,
                                approvals =
                                    result["events"].obj()["approvals"].list().map { a -> a.obj() },
                                error = null,
                            )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api && generation == threadGeneration)
                    mutable.update {
                        it.copy(workbench = it.workbench.copy(error = e.message ?: "读取会话失败"))
                    }
            } finally {
                if (source === api && generation == threadGeneration)
                    mutable.update { it.copy(workbench = it.workbench.copy(detailLoading = false)) }
            }
        }
    }

    fun closeThread() {
        mutable.value.workbench.detail?.let(::rememberThread)
        threadGeneration++
        threadJob?.cancel()
        mutable.update {
            it.copy(
                workbench =
                    it.workbench.copy(
                        detail = null,
                        openingThreadId = null,
                        detailLoading = false,
                        error = null,
                    )
            )
        }
        loadWorkbench()
    }

    fun workbenchAction(action: String, input: JsonObject) {
        if (action in listOf("send", "steer")) messages.send(action, input)
        else adminCommand("workbench.$action", input)
    }

    private fun applyWorkbenchResult(action: String, result: JsonObject) {
        when (action) {
            "create",
            "attach",
            "fork" ->
                result["thread"]
                    .obj()["id"]
                    .text()
                    .takeIf { it.isNotBlank() }
                    ?.let { openThread(it) }
            "configure" -> {
                resetWorkbenchJobs()
                mutable.update { it.copy(workbench = WorkbenchState()) }
                loadWorkbench()
                startStream()
            }
            "archive" -> closeThread()
            else -> mutable.value.workbench.detail?.thread?.id?.let { openThread(it, quiet = true) }
        }
    }

    fun loadConfiguration() {
        val source = api ?: return
        if (mutable.value.configurationLoading) return
        mutable.update { it.copy(configurationLoading = true, adminError = null) }
        configurationJob = viewModelScope.launch {
            try {
                val value =
                    WireJson.decodeFromJsonElement<ConfigurationEnvelope>(
                        source.query("configuration")
                    )
                if (source === api) mutable.update { it.copy(configuration = value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api) mutable.update { it.copy(adminError = e.message) }
            } finally {
                if (source === api) mutable.update { it.copy(configurationLoading = false) }
            }
        }
    }

    fun saveConfiguration(revision: String, patch: JsonObject) =
        adminCommand(
            "configuration.patch",
            buildJsonObject {
                put("revision", revision)
                put("patch", patch)
            },
        )

    fun adminQuery(resource: String, input: JsonObject = JsonObject(emptyMap())) {
        val source = api ?: return
        adminQueryJob?.cancel()
        val generation = ++catalogGeneration
        latestAdminQuery = resource to input
        mutable.update { it.copy(adminLoading = true, adminResult = null, adminError = null) }
        adminQueryJob = viewModelScope.launch {
            try {
                val result = source.query(resource, input)
                if (source === api && generation == catalogGeneration)
                    mutable.update { it.copy(adminResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (source === api && generation == catalogGeneration)
                    mutable.update { it.copy(adminError = e.message) }
            } finally {
                if (source === api && generation == catalogGeneration)
                    mutable.update { it.copy(adminLoading = false) }
            }
        }
    }

    fun adminCommand(action: String, input: JsonObject) {
        val snapshot = mutable.value.snapshot
        val issuedAt = runCatching {
            Instant.parse(snapshot?.serverTime).toEpochMilli()
        }
            .getOrDefault(0L)
        executeAdmin(
            AdminCommand(
                UUID.randomUUID().toString(),
                action,
                input,
                snapshot?.commandEpoch.orEmpty(),
                issuedAt,
            )
        )
    }

    fun retryUncertainCommand() {
        val request = mutable.value.uncertainCommand ?: return
        val id = request.input["messageId"].text()
        if (mutable.value.pendingMessages.containsKey(id)) messages.verify(id)
        else executeAdmin(request)
    }

    fun checkUpdate() {
        if (mutable.value.update.checking || mutable.value.update.progress != null) return
        mutable.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        viewModelScope.launch {
            try {
                val manifest = updater.check()
                mutable.update {
                    it.copy(
                        update =
                            it.update.copy(
                                manifest = manifest,
                                checked = true,
                                downloaded =
                                    it.update.downloaded.takeIf { _ ->
                                        it.update.manifest?.versionCode == manifest.versionCode
                                    },
                            )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(update = it.update.copy(error = e.message ?: "检查更新失败")) }
            } finally {
                mutable.update { it.copy(update = it.update.copy(checking = false)) }
            }
        }
    }

    fun downloadUpdate() {
        val manifest = mutable.value.update.manifest ?: return
        if (mutable.value.update.progress != null) return
        mutable.update { it.copy(update = it.update.copy(progress = 0f, error = null)) }
        viewModelScope.launch {
            try {
                val file =
                    updater.download(manifest) { progress ->
                        mutable.update { it.copy(update = it.update.copy(progress = progress)) }
                    }
                mutable.update { it.copy(update = it.update.copy(downloaded = file)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(update = it.update.copy(error = e.message ?: "下载更新失败")) }
            } finally {
                mutable.update { it.copy(update = it.update.copy(progress = null)) }
            }
        }
    }

    fun updateInstallError() {
        mutable.update { it.copy(update = it.update.copy(error = "无法打开系统安装器，请检查安装权限后重试")) }
    }

    private fun executeAdmin(
        request: AdminCommand,
        attachments: List<AttachmentMeta> = emptyList(),
    ): Boolean {
        val source = api ?: return false
        if (!mutable.value.canControl(SystemClock.elapsedRealtime())) {
            mutable.update { it.copy(notice = "请等待已授权电脑恢复在线") }
            return false
        }
        if (mutable.value.uncertainCommand?.let { it.requestId != request.requestId } == true) {
            mutable.update { it.copy(notice = "请先核对上一项操作的结果") }
            return false
        }
        outgoingMessages.begin(request, attachments)
        mutable.update {
            it.copy(
                busy = true,
                adminError = null,
                uncertainCommand = null,
                workbench =
                    it.workbench.copy(
                        detail = it.workbench.detail?.let(outgoingMessages::decorate)
                    ),
            )
        }
        viewModelScope.launch {
            try {
                val result = source.command(request)
                if (source === api) {
                    messages.settled(request, result, null)
                    if (request.action == "workbench.create") {
                        val id = result["thread"].obj()["id"].text()
                        if (id.isNotBlank())
                            messages.created(
                                mutable.value.computer?.certificateSha256.orEmpty(),
                                request.input["draftScopeId"].text(),
                                id,
                            )
                    }
                    outgoingMessages.settle(request, "sent", result)
                    mutable.update {
                        it.copy(
                            workbench =
                                it.workbench.copy(
                                    detail = it.workbench.detail?.let(outgoingMessages::decorate)
                                ),
                            notice = result["message"].text("操作已完成"),
                            lastAdminOutcome =
                                AdminOutcome(
                                    request.requestId,
                                    request.action,
                                    request.input["operation"].text(),
                                    result,
                                    request.input["messageId"].text(),
                                ),
                        )
                    }
                    if (request.action.startsWith("workbench."))
                        applyWorkbenchResult(request.action.removePrefix("workbench."), result)
                    if (request.action == "workbench.rename") {
                        val id = request.input["threadId"].text()
                        val name = request.input["name"].text()
                        mutable.update { state ->
                            state.copy(
                                workbench =
                                    state.workbench.copy(
                                        threads =
                                            state.workbench.threads.map {
                                                if (it.id == id) it.copy(title = name) else it
                                            },
                                        detail =
                                            state.workbench.detail?.let {
                                                if (it.thread.id == id)
                                                    it.copy(thread = it.thread.copy(title = name))
                                                else it
                                            },
                                    )
                            )
                        }
                    }
                    if (request.action == "configuration.patch") {
                        val config = WireJson.decodeFromJsonElement<ConfigurationEnvelope>(result)
                        mutable.update { it.copy(configuration = config, notice = config.message) }
                    }
                    if (
                        !request.action.startsWith("workbench.") &&
                            request.action != "configuration.patch"
                    ) {
                        latestAdminQuery?.let { (resource, input) ->
                            if (
                                resource == "manager" &&
                                    input["operation"].text() == "providers.detail"
                            )
                                adminQuery(
                                    "manager",
                                    buildJsonObject { put("operation", "providers.list") },
                                )
                            else adminQuery(resource, input)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val uncertain = e.commandResultUnknown()
                if (source !== api) return@launch
                messages.settled(request, null, e)
                outgoingMessages.settle(request, if (uncertain) "unconfirmed" else "notSent")
                mutable.update {
                    it.copy(
                        workbench =
                            it.workbench.copy(
                                detail = it.workbench.detail?.let(outgoingMessages::decorate)
                            ),
                        adminError = e.message,
                        notice = if (uncertain) "尚未确认结果，可重试同一请求核对；请勿重复创建任务" else e.message,
                        uncertainCommand = if (uncertain) request else null,
                    )
                }
            } finally {
                if (source === api) {
                    mutable.update { it.copy(busy = false) }
                    refreshNow()
                }
            }
        }
        return true
    }

    private suspend fun closeClient(client: CompanionApi?) {
        if (client == null) return
        // Closing an idle TLS socket can send close_notify. Doing that on Main
        // can throw NetworkOnMainThreadException after pairing was already saved.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                client.close()
            } catch (e: Exception) {
                Log.w("Launcher", "Connection cleanup failed (${e.javaClass.simpleName})")
            }
        }
    }

    override fun onCleared() {
        resetWorkbenchJobs()
        val previous = api
        api = null
        // viewModelScope has already been cancelled when this callback runs.
        if (previous != null) CoroutineScope(Dispatchers.IO).launch { closeClient(previous) }
        super.onCleared()
    }

    private fun explain(error: Exception, pairing: Boolean): String =
        when {
            error is ApiException -> error.message ?: "电脑请求失败"
            error is SSLException -> "电脑证书校验失败，请在确认电脑身份后重新扫码配对"
            error is IllegalArgumentException -> error.message ?: "连接信息无效"
            pairing -> "连接未完成，请确认电脑已开启手机连接，两端网络可达且配对码有效"
            else -> "连接已中断，请检查电脑、网络或 Tailscale；恢复后会自动同步"
        }
}
