package com.codexlauncher.companion.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AttachmentState(
    val drafts: Map<String, ComposerDraft> = emptyMap(),
    val pending: Map<String, PendingMessage> = emptyMap(),
)

/** Private copies survive URI permission loss. No token or PC path is stored here. */
class AttachmentRepository(private val context: Context) {
    private val root = File(context.filesDir, "attachments").apply { mkdirs() }
    private val objects = File(root, "objects").apply { mkdirs() }
    private val index = File(root, "drafts.json")
    @Volatile
    private var data =
        if (index.exists()) {
            val bytes = index.inputStream().use { it.readBytesBounded(16 * ATTACHMENT_MIB) }
            WireJson.decodeFromString<AttachmentState>(bytes.toString(Charsets.UTF_8))
        } else AttachmentState()

    init {
        data =
            data.copy(
                pending =
                    data.pending.mapValues { (_, message) ->
                        when (message.state) {
                            "submitting" ->
                                message.copy(state = "unknown", error = "上次发送结果待核对，请先刷新历史")
                            "waitingForUploads" ->
                                message.copy(state = "paused", error = "上传已暂停，可继续发送")
                            else -> message
                        }
                    }
            )
    }

    fun state(): AttachmentState = data

    @Synchronized
    fun edit(target: DraftTarget, text: String) {
        require(text.toByteArray().size <= 65536)
        val draft = data.drafts[target.key] ?: ComposerDraft(target)
        commit(data.copy(drafts = data.drafts + (target.key to draft.copy(text = text))))
    }

    @Synchronized
    fun import(
        target: DraftTarget,
        uri: Uri,
        photo: Boolean,
        policy: AttachmentPolicy,
    ): LocalAttachment {
        val draft = data.drafts[target.key] ?: ComposerDraft(target)
        require(draft.attachments.size < policy.maxCount.coerceAtMost(10)) { "此消息的附件数量已达到上限" }
        var name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                ?.cleanAttachmentName()
                .orEmpty()
                .ifBlank { if (photo) "photo.jpg" else "attachment" }
        var type =
            context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val image =
            photo ||
                type in listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
        if (!image && type.startsWith("image/")) type = "application/octet-stream"
        val maximum =
            (if (image) policy.maxImageBytes else policy.maxFileBytes).coerceAtMost(
                50 * ATTACHMENT_MIB
            )
        val usage =
            objects.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0
        require(usage < 256 * ATTACHMENT_MIB) { "本机附件草稿已达到 256 MiB，请移除不需要的草稿后重试" }
        val id = UUID.randomUUID().toString()
        val file = localFile(id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件，请重新选择" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= maximum && usage + size <= 256 * ATTACHMENT_MIB) {
                            "文件超过附件大小限制"
                        }
                        require(
                            size + draft.attachments.sumOf { it.size } <= policy.maxMessageBytes
                        ) {
                            "消息附件总大小超过上限"
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            var hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (image) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outMimeType != "image/gif") { "请从文件入口上传 GIF，或选择静态图片用于识图" }
                require(
                    bounds.outWidth > 0 &&
                        bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L
                ) {
                    "图片无法读取或超过 4000 万像素"
                }
                if (bounds.outMimeType !in listOf("image/jpeg", "image/png", "image/webp")) {
                    if (Build.VERSION.SDK_INT < 28) {
                        throw IllegalArgumentException("此设备无法转换该图片格式，请选择 JPEG、PNG 或 WebP")
                    }
                    val converted = File(root, "$id.converted")
                    val bitmap =
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {
                            decoder,
                            info,
                            _ ->
                            require(info.size.width.toLong() * info.size.height <= 40_000_000L)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val sample =
                                kotlin.math
                                    .ceil(
                                        kotlin.math.sqrt(
                                            info.size.width.toDouble() * info.size.height /
                                                12_000_000
                                        )
                                    )
                                    .toInt()
                                    .coerceAtLeast(1)
                            if (sample > 1) decoder.setTargetSampleSize(sample)
                        }
                    try {
                        converted.outputStream().use { output ->
                            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                "图片转换失败"
                            }
                            output.fd.sync()
                        }
                        require(converted.length() <= maximum) { "转换后的图片超过大小限制" }
                        require(converted.renameTo(file)) { "无法保存转换后的图片" }
                    } finally {
                        bitmap.recycle()
                        converted.delete()
                    }
                    name = name.substringBeforeLast('.', name).take(170) + ".jpg"
                    type = "image/jpeg"
                    size = file.length()
                    hash = fileSha256(file)
                } else type = bounds.outMimeType
                require(bounds.outWidth <= 16384 && bounds.outHeight <= 16384) {
                    "图片边长超过 16384 像素，请分段发送长截图"
                }
            }
            val attachment = LocalAttachment(id, name, type, size, hash)
            commit(
                data.copy(
                    drafts =
                        data.drafts +
                            (target.key to draft.copy(attachments = draft.attachments + attachment))
                )
            )
            // Only remove our temporary camera output after the private copy commits.
            if (
                uri.authority == context.packageName + ".attachments" &&
                    uri.path?.startsWith("/camera/") == true
            ) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            return attachment
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    @Synchronized
    fun remove(target: DraftTarget, id: String) {
        val draft = data.drafts[target.key] ?: return
        commit(
            data.copy(
                drafts =
                    data.drafts +
                        (target.key to
                            draft.copy(attachments = draft.attachments.filterNot { it.id == id }))
            )
        )
        deleteUnused(id)
    }

    @Synchronized
    fun freeze(
        target: DraftTarget,
        id: String,
        action: String,
        text: String,
        turnId: String?,
        selected: List<String>,
    ): PendingMessage {
        data.pending[id]?.let {
            return it
        }
        require(
            data.pending.values.none {
                it.target == target && it.state !in listOf("sent", "discarded")
            }
        ) {
            "请先处理此会话中待发送或待核对的消息"
        }
        val draft = data.drafts[target.key] ?: ComposerDraft(target)
        val attachments = selected.map { selectedId ->
            draft.attachments.find { it.id == selectedId } ?: error("所选附件已变化，请重新确认")
        }
        require(text.isNotBlank() || attachments.isNotEmpty()) { "请输入消息或选择附件" }
        val message = PendingMessage(target, id, action, text, attachments, turnId)
        commit(
            data.copy(
                drafts =
                    data.drafts +
                        (target.key to
                            draft.copy(
                                text = "",
                                attachments = draft.attachments.filterNot { it.id in selected },
                            )),
                pending = data.pending + (id to message),
            )
        )
        return message
    }

    @Synchronized
    fun update(id: String, change: (PendingMessage) -> PendingMessage): PendingMessage? {
        val current = data.pending[id] ?: return null
        val updated = change(current)
        commit(data.copy(pending = data.pending + (id to updated)))
        return updated
    }

    @Synchronized
    fun discard(id: String) {
        val current = data.pending[id] ?: return
        require(current.state !in listOf("submitting", "unknown", "sent")) { "此消息已经提交，需先核对结果" }
        commit(data.copy(pending = data.pending - id))
        current.attachments.forEach { deleteUnused(it.id) }
    }

    fun localFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(objects, "$id.bin")
    }

    @Synchronized
    fun moveDraft(source: DraftTarget, target: DraftTarget) {
        val draft = data.drafts[source.key] ?: return
        require(data.drafts[target.key]?.attachments.isNullOrEmpty()) { "目标会话已有附件草稿" }
        commit(
            data.copy(
                drafts = (data.drafts - source.key) + (target.key to draft.copy(target = target))
            )
        )
    }

    @Synchronized
    fun forgetDelivered(id: String) {
        val message = data.pending[id]?.takeIf { it.state == "sent" } ?: return
        commit(data.copy(pending = data.pending - id))
        message.attachments.forEach { deleteUnused(it.id) }
    }

    fun cacheFile(target: DraftTarget, meta: AttachmentMeta, preview: Boolean): File {
        val key = bytesSha256((target.pc + target.scope + meta.id).toByteArray())
        val folder =
            File(context.cacheDir, if (preview) "attachment-previews" else "attachment-downloads")
        val directory = File(folder, key).apply { mkdirs() }
        return File(
            directory,
            if (preview) "preview.jpg"
            else meta.fileName.cleanAttachmentName().ifBlank { "attachment" },
        )
    }

    private fun commit(next: AttachmentState) {
        atomicWrite(index, WireJson.encodeToString(next).toByteArray())
        data = next
    }

    private fun deleteUnused(id: String) {
        if (
            data.drafts.values.none { draft -> draft.attachments.any { it.id == id } } &&
                data.pending.values.none { pending -> pending.attachments.any { it.id == id } }
        )
            localFile(id).delete()
    }
}

fun String.cleanAttachmentName(): String = map {
    if (it.isISOControl() || it in "/\\:") '_' else it
}
    .joinToString("")
    .trim()
    .trim('.')
    .take(180)

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun InputStream.readBytesBounded(maximum: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "附件草稿索引超过限制，原数据已保留" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Retry file transport using the acknowledged offset. Never retries a model command. */
suspend fun uploadAttachment(
    api: CompanionApi,
    repository: AttachmentRepository,
    message: PendingMessage,
    selected: LocalAttachment,
    restartExpired: Boolean = false,
    progress: suspend (LocalAttachment) -> Unit,
): LocalAttachment =
    withContext(Dispatchers.IO) {
        var local = selected
        val file = repository.localFile(local.id)
        require(file.exists() && file.length() == local.size && fileSha256(file) == local.sha256) {
            "本地附件已变化，请重新选择"
        }
        suspend fun initialize() =
            api.createUpload(
                UploadRequest(
                    local.clientUploadId,
                    message.target.thread,
                    message.messageId,
                    local.fileName,
                    local.mediaType,
                    local.size,
                    local.sha256,
                )
            )
        var status =
            try {
                local.uploadId?.let { api.uploadStatus(it) } ?: initialize()
            } catch (error: ApiException) {
                if (!restartExpired || error.status !in listOf(404, 410)) throw error
                // Explicit resume can replace an expired transfer, while preserving
                // the frozen message identity and the phone's existing private copy.
                local =
                    local.copy(
                        clientUploadId = UUID.randomUUID().toString(),
                        uploadId = null,
                        attachment = null,
                        receivedBytes = 0,
                        state = "uploading",
                        error = null,
                    )
                progress(local)
                initialize()
            }
        require(status.chunkSizeBytes == ATTACHMENT_MIB.toInt()) { "PC 分片版本不兼容，请更新应用" }
        require(
            status.receivedBytes in 0..local.size &&
                (status.receivedBytes == local.size || status.receivedBytes % ATTACHMENT_MIB == 0L)
        ) {
            "PC 返回了无效上传进度"
        }
        local =
            local.copy(
                uploadId = status.uploadId,
                state = "uploading",
                receivedBytes = status.receivedBytes,
                error = null,
            )
        progress(local)
        RandomAccessFile(file, "r").use { input ->
            var retries = 0
            while (status.receivedBytes < local.size) {
                ensureActive()
                input.seek(status.receivedBytes)
                val bytes =
                    ByteArray(
                        (local.size - status.receivedBytes).coerceAtMost(ATTACHMENT_MIB).toInt()
                    )
                input.readFully(bytes)
                try {
                    val before = status.receivedBytes
                    status =
                        api.uploadChunk(
                            status.uploadId,
                            (before / ATTACHMENT_MIB).toInt(),
                            local.size,
                            bytes,
                        )
                    require(status.receivedBytes == before + bytes.size) { "PC 确认的上传进度不一致" }
                    retries = 0
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is ApiException && error.status !in listOf(408, 409, 429, 503))
                        throw error
                    if (++retries > 2) throw error
                    delay(1000L * retries)
                    status = api.uploadStatus(status.uploadId)
                    require(
                        status.receivedBytes in 0..local.size &&
                            (status.receivedBytes == local.size ||
                                status.receivedBytes % ATTACHMENT_MIB == 0L)
                    ) {
                        "PC 返回了无效上传进度"
                    }
                }
                local = local.copy(receivedBytes = status.receivedBytes)
                progress(local)
            }
        }
        progress(local.copy(state = "verifying"))
        val completed = if (status.state == "ready") status else api.completeUpload(status.uploadId)
        val attachment = requireNotNull(completed.attachment) { "PC 尚未确认附件完成，请重试核对" }
        require(attachment.sha256 == local.sha256 && attachment.size == local.size) { "附件完整性校验不一致" }
        local
            .copy(
                state = "ready",
                receivedBytes = local.size,
                attachment = attachment,
                error = null,
            )
            .also { progress(it) }
    }
