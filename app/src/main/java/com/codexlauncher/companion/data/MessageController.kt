package com.codexlauncher.companion.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.codexlauncher.companion.CompanionState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Freezes a destination before upload and never replays an uncertain model command. */
class MessageController(
    context: Context,
    private val scope: CoroutineScope,
    private val client: () -> CompanionApi?,
    private val current: () -> CompanionState,
    private val change: ((CompanionState) -> CompanionState) -> Unit,
    private val submit: (AdminCommand, List<AttachmentMeta>) -> Boolean,
    private val reconciled: (PendingMessage, String, JsonObject?) -> Unit,
) {
    private val repository = runCatching { AttachmentRepository(context) }
    private val jobs = mutableMapOf<String, Job>()
    private val previewJobs = mutableMapOf<String, Job>()
    private val uploads = Semaphore(2)
    private val downloads = Semaphore(2)
    private val edits = mutableMapOf<String, Job>()
    private val verificationJobs = mutableMapOf<String, Job>()
    private val importing = mutableSetOf<String>()

    init {
        publish()
    }

    fun publish() {
        repository.getOrNull()?.state()?.let { data ->
            change {
                it.copy(
                    attachmentDrafts = data.drafts,
                    pendingMessages = data.pending,
                    importingAttachments = importing.toSet(),
                )
            }
        }
    }

    fun edit(target: DraftTarget, text: String) {
        edits.remove(target.key)?.cancel()
        edits[target.key] = scope.launch {
            try {
                delay(120)
                withContext(Dispatchers.IO) { repository.getOrThrow().edit(target, text) }
                publish()
            } catch (error: Exception) {
                notice(error)
            }
        }
    }

    fun import(target: DraftTarget, uris: List<Uri>, photos: Boolean) {
        if (
            uris.isEmpty() ||
                target.pc != current().computer?.certificateSha256 ||
                target.key in importing
        )
            return
        // A system picker can return while connection capabilities are still restoring.
        // Import a bounded local draft now; the send path validates the PC again later.
        val policy =
            AttachmentPolicy.parse(current().workbench.capabilities?.get("attachments")).takeIf {
                it.upload
            } ?: AttachmentPolicy(upload = true)
        importing.add(target.key)
        change { it.copy(importingAttachments = importing.toSet()) }
        scope.launch {
            try {
                for (uri in uris.take(policy.maxCount.coerceAtMost(10))) {
                    withContext(Dispatchers.IO) {
                        repository.getOrThrow().import(target, uri, photos, policy)
                    }
                    publish()
                }
            } catch (error: Exception) {
                notice(error)
            } finally {
                importing.remove(target.key)
                change { it.copy(importingAttachments = it.importingAttachments - target.key) }
            }
        }
    }

    fun remove(target: DraftTarget, id: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.getOrThrow().remove(target, id) }
                publish()
            } catch (error: Exception) {
                notice(error)
            }
        }
    }

    fun send(action: String, input: JsonObject) {
        val state = current()
        val detail = state.workbench.detail ?: return
        if (!state.canControl(SystemClock.elapsedRealtime()) || state.uncertainCommand != null) {
            change { it.copy(notice = "请先等待电脑在线并核对上一项操作") }
            return
        }
        val target = detail.draftTarget(state.computer)
        if (input["threadId"].text() != target.thread) return
        val id = input["messageId"].text().ifBlank { UUID.randomUUID().toString() }
        val selected = input["localAttachmentIds"].list().map { it.text() }
        scope.launch {
            try {
                edits.remove(target.key)?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    repository
                        .getOrThrow()
                        .freeze(
                            target,
                            id,
                            action,
                            input["text"].text(),
                            input["turnId"].text().ifBlank { null },
                            selected,
                        )
                }
                publish()
                change { it.copy(lastDraftSubmissionId = id) }
                resume(id)
            } catch (error: Exception) {
                notice(error)
            }
        }
    }

    fun pause() {
        jobs.values.toList().forEach { it.cancel() }
        previewJobs.values.toList().forEach { it.cancel() }
    }

    suspend fun pauseAndJoin() {
        val active = (jobs.values + previewJobs.values + verificationJobs.values).toList()
        active.forEach { it.cancel() }
        active.joinAll()
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
    }

    fun resume(id: String, acceptCurrentTurn: Boolean = false) {
        if (jobs[id]?.isCompleted == false) return
        val repo = repository.getOrNull() ?: return
        var message = repo.state().pending[id] ?: return
        if (message.state in listOf("unknown", "submitting")) {
            verify(id)
            return
        }
        val api = client() ?: return
        if (message.target.pc != current().computer?.certificateSha256) return
        if (message.state == "sent") return
        if (acceptCurrentTurn && message.request == null) {
            val detail =
                current().workbench.detail?.takeIf { it.thread.id == message.target.thread }
                    ?: return
            message =
                repo.update(id) {
                    it.copy(
                        action = if (detail.activeTurn == null) "send" else "steer",
                        turnId = detail.activeTurn,
                    )
                } ?: return
        }
        jobs[id] =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (message.attachments.isNotEmpty()) {
                        val original =
                            ThreadDetail.parse(
                                api.query(
                                    "workbench-thread",
                                    buildJsonObject { put("threadId", message.target.thread) },
                                )
                            )
                        check(
                            api === client() &&
                                message.target.pc == current().computer?.certificateSha256
                        ) {
                            "电脑连接已变化，文件已保留"
                        }
                        check(
                            original.backendScopeId == message.target.scope &&
                                original.thread.canContinueOriginal
                        ) {
                            "请连接这份草稿原来所在的可控制后台"
                        }
                    }
                    update(id) { it.copy(state = "waitingForUploads", error = null) }
                    val completed = coroutineScope {
                        message.attachments
                            .map { attachment ->
                                async {
                                    uploads.withPermit {
                                        uploadAttachment(
                                            api,
                                            repo,
                                            message,
                                            attachment,
                                            restartExpired =
                                                message.state in listOf("failed", "paused"),
                                        ) { updated ->
                                            repo.update(id) { before ->
                                                before.copy(
                                                    attachments =
                                                        before.attachments.map {
                                                            if (it.id == updated.id) updated else it
                                                        }
                                                )
                                            }
                                            withContext(Dispatchers.Main) { publish() }
                                        }
                                    }
                                }
                            }
                            .awaitAll()
                    }
                    // Check the frozen target, not whichever conversation is now on screen.
                    val fresh =
                        ThreadDetail.parse(
                            api.query(
                                "workbench-thread",
                                buildJsonObject { put("threadId", message.target.thread) },
                            )
                        )
                    check(
                        api === client() &&
                            message.target.pc == current().computer?.certificateSha256
                    ) {
                        "已切换电脑，附件已保留"
                    }
                    check(fresh.backendScopeId == message.target.scope) { "电脑后台范围已变化，请重新连接原后台" }
                    check(fresh.thread.canContinueOriginal) { "此会话当前只能查看，请先接入原后台" }
                    if (
                        (message.action == "steer" && fresh.activeTurn != message.turnId) ||
                            (message.action == "send" && fresh.activeTurn != null)
                    ) {
                        update(id) {
                            it.copy(
                                state = "needsConfirmation",
                                error = "上传期间会话运行状态已变化，请确认后再按当前状态发送",
                            )
                        }
                        return@launch
                    }
                    check(
                        current().canControl(SystemClock.elapsedRealtime()) &&
                            current().uncertainCommand == null
                    ) {
                        "电脑状态正在同步，附件已保留，可稍后继续发送"
                    }
                    val snapshot = current().snapshot
                    val request =
                        message.request
                            ?: AdminCommand(
                                UUID.randomUUID().toString(),
                                "workbench." + message.action,
                                buildJsonObject {
                                    put("threadId", message.target.thread)
                                    put("messageId", id)
                                    put("text", message.text)
                                    put("backendScopeId", message.target.scope)
                                    message.turnId?.let { put("turnId", it) }
                                    if (completed.isNotEmpty())
                                        putJsonArray("attachmentIds") {
                                            completed.forEach {
                                                add(requireNotNull(it.attachment).id)
                                            }
                                        }
                                },
                                snapshot?.commandEpoch.orEmpty(),
                                runCatching { Instant.parse(snapshot?.serverTime).toEpochMilli() }
                                    .getOrDefault(0),
                            )
                    update(id) {
                        it.copy(state = "submitting", request = request, attachments = completed)
                    }
                    if (!submit(request, completed.mapNotNull { it.attachment })) {
                        update(id) {
                            it.copy(state = "failed", request = null, error = "电脑正在处理其他操作，可稍后继续发送")
                        }
                    }
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        if (
                            repo.state().pending[id]?.state !in
                                listOf("submitting", "sent", "unknown")
                        )
                            update(id) { it.copy(state = "paused", error = "附件上传已暂停，草稿已保存") }
                    }
                    throw error
                } catch (error: Exception) {
                    update(id) {
                        it.copy(state = "failed", error = error.message ?: "附件处理失败，草稿已保存")
                    }
                } finally {
                    jobs.remove(id)
                }
            }
        jobs[id]?.start()
    }

    fun discard(id: String) {
        scope.launch {
            try {
                jobs[id]?.cancelAndJoin()
                val repo = repository.getOrThrow()
                val message = repo.state().pending[id] ?: return@launch
                // Cancel only this device's unsubmitted objects. Referenced server files are
                // retained.
                if (
                    message.request == null &&
                        message.target.pc == current().computer?.certificateSha256
                ) {
                    message.attachments.forEach { local ->
                        local.uploadId?.let { upload ->
                            runCatching { client()?.cancelUpload(upload) }
                        }
                    }
                }
                withContext(Dispatchers.IO) { repo.discard(id) }
                publish()
            } catch (error: Exception) {
                notice(error)
            }
        }
    }

    suspend fun settled(request: AdminCommand, result: JsonObject?, error: Exception?) {
        val id = request.input["messageId"].text()
        if (repository.getOrNull()?.state()?.pending?.containsKey(id) != true) return
        val unknown = error?.commandResultUnknown() == true
        update(id) {
            it.copy(
                state = if (result != null) "sent" else if (unknown) "unknown" else "failed",
                request = if (result == null && !unknown) null else it.request,
                error = error?.message,
            )
        }
        if (result != null)
            withContext(Dispatchers.IO) { repository.getOrThrow().forgetDelivered(id) }
        publish()
    }

    suspend fun created(pc: String, backendScope: String, threadId: String) {
        withContext(Dispatchers.IO) {
            repository
                .getOrThrow()
                .moveDraft(
                    DraftTarget(pc, backendScope, "new"),
                    DraftTarget(pc, backendScope, threadId),
                )
        }
        publish()
    }

    fun verify(id: String) {
        if (verificationJobs[id]?.isCompleted == false) return
        val message = repository.getOrNull()?.state()?.pending?.get(id) ?: return
        val api = client() ?: return
        if (message.target.pc != current().computer?.certificateSha256) return
        verificationJobs[id] =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val thread =
                        ThreadDetail.parse(
                            api.query(
                                "workbench-thread",
                                buildJsonObject { put("threadId", message.target.thread) },
                            )
                        )
                    check(api === client()) { "电脑连接已变化，请重新核对" }
                    check(thread.backendScopeId == message.target.scope) { "请连接这条消息原来所在的电脑后台" }
                    val found = thread.items.any { it.clientId == id && !it.localEcho }
                    if (
                        !found &&
                            "workbench.attachments.v1" !in
                                current().snapshot?.capabilities.orEmpty()
                    ) {
                        // Old PCs only support in-memory receipts. A manual retry must keep
                        // the exact request and remain inside that process's receipt window.
                        val request = message.request ?: error("上次请求记录不完整，无法安全重试")
                        val snapshot = current().snapshot ?: error("请先刷新电脑状态")
                        val now = runCatching {
                            Instant.parse(snapshot.serverTime).toEpochMilli()
                        }
                            .getOrDefault(0)
                        check(
                            request.commandEpoch.isNotBlank() &&
                                request.commandEpoch == snapshot.commandEpoch &&
                                request.issuedAt > 0 &&
                                now - request.issuedAt in -30_000..899_000
                        ) {
                            "旧版 PC 的核对窗口已失效，请查看原会话；未重复发送开发指令"
                        }
                        if (current().canControl(SystemClock.elapsedRealtime())) {
                            update(id) { it.copy(state = "submitting", error = null) }
                            if (!submit(request, message.attachments.mapNotNull { it.attachment }))
                                update(id) { it.copy(state = "unknown", error = "请等待电脑状态同步后再核对") }
                        }
                        return@launch
                    }
                    val result =
                        if (found) buildJsonObject { put("state", "sent") }
                        else
                            api.query(
                                "workbench-message",
                                buildJsonObject {
                                    put("threadId", message.target.thread)
                                    put("messageId", id)
                                },
                            )
                    check(api === client()) { "电脑连接已变化，请重新核对" }
                    when (result["state"].text()) {
                        "sent" -> {
                            update(id) { it.copy(state = "sent", error = null) }
                            reconciled(message, "sent", result["reply"] as? JsonObject)
                            withContext(Dispatchers.IO) {
                                repository.getOrThrow().forgetDelivered(id)
                            }
                            publish()
                            change { it.copy(notice = "已确认消息发送成功") }
                        }
                        "failed" -> {
                            update(id) {
                                it.copy(
                                    state = "failed",
                                    request = null,
                                    error = "电脑确认此次提交被拒绝，可核对原因后重试",
                                )
                            }
                            reconciled(message, "notSent", result["reply"] as? JsonObject)
                        }
                        else -> change { it.copy(notice = "发送结果仍待核对，未重复发送指令") }
                    }
                } catch (error: Exception) {
                    notice(error)
                } finally {
                    verificationJobs.remove(id)
                }
            }
        verificationJobs[id]?.start()
    }

    fun load(target: DraftTarget, meta: AttachmentMeta, preview: Boolean) {
        if (!meta.id.startsWith("att_") || target.pc != current().computer?.certificateSha256)
            return
        val key = attachmentCacheKey(target, meta, preview)
        if (previewJobs[key]?.isActive == true || current().attachmentFiles.containsKey(key)) return
        val api = client() ?: return
        previewJobs[key] = scope.launch {
            try {
                val file = repository.getOrThrow().cacheFile(target, meta, preview)
                downloads.withPermit {
                    // Always re-authorize over HTTPS, even when a previous preview is cached.
                    api.downloadAttachment(target.thread, meta.id, preview, file)
                }
                if (!preview)
                    check(
                        withContext(Dispatchers.IO) {
                            file.length() == meta.size && fileSha256(file) == meta.sha256
                        }
                    ) {
                        "下载附件的完整性校验失败"
                    }
                if (api === client())
                    change {
                        it.copy(
                            attachmentFiles = it.attachmentFiles + (key to file),
                            attachmentErrors = it.attachmentErrors - key,
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                change {
                    it.copy(
                        attachmentErrors =
                            it.attachmentErrors + (key to (error.message ?: "附件暂不可用"))
                    )
                }
            } finally {
                previewJobs.remove(key)
            }
        }
    }

    private suspend fun update(id: String, edit: (PendingMessage) -> PendingMessage) {
        withContext(Dispatchers.IO) { repository.getOrThrow().update(id, edit) }
        publish()
    }

    private fun notice(error: Exception) {
        if (error is CancellationException) throw error
        change { it.copy(notice = error.message ?: "附件操作失败，原草稿已保留") }
    }
}

fun attachmentCacheKey(target: DraftTarget, meta: AttachmentMeta, preview: Boolean) =
    target.key + ":" + meta.id + ":" + preview

fun Exception.commandResultUnknown(): Boolean =
    this !is ApiException ||
        status >= 500 ||
        status == 408 ||
        code == "MESSAGE_PENDING" ||
        message.orEmpty().contains("MESSAGE_PENDING") ||
        message.orEmpty().contains("结果未知")
