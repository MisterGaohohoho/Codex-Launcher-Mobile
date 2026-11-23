package com.codexlauncher.companion.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val ATTACHMENT_MIB = 1024L * 1024

fun bytesSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Serializable
data class AttachmentPolicy(
    val protocolVersion: Int = 1,
    val upload: Boolean = false,
    val maxCount: Int = 10,
    val maxImageBytes: Long = 20 * ATTACHMENT_MIB,
    val maxFileBytes: Long = 50 * ATTACHMENT_MIB,
    val maxMessageBytes: Long = 100 * ATTACHMENT_MIB,
    val chunkSizeBytes: Int = ATTACHMENT_MIB.toInt(),
    val imageInput: String = "unknown",
) {
    companion object {
        fun parse(value: JsonElement?): AttachmentPolicy = runCatching {
            WireJson.decodeFromJsonElement<AttachmentPolicy>(value ?: JsonNull)
        }
            .getOrDefault(AttachmentPolicy())
    }
}

@Serializable
data class AttachmentMeta(
    val id: String,
    val fileName: String,
    val mediaType: String = "application/octet-stream",
    val size: Long = 0,
    val sha256: String = "",
    val kind: String = "file",
    val readStatus: String = "unsupported",
    val readMessage: String = "",
    val previewAvailable: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class UploadRequest(
    val clientUploadId: String,
    val threadId: String,
    val messageId: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class AttachmentUpload(
    val uploadId: String,
    val state: String,
    val receivedBytes: Long,
    val chunkSizeBytes: Int,
    val expiresAt: String = "",
    val attachment: AttachmentMeta? = null,
)

@Serializable
data class DraftTarget(val pc: String, val scope: String, val thread: String) {
    val key: String
        get() = bytesSha256("$pc\n$scope\n$thread".toByteArray())
}

@Serializable
data class LocalAttachment(
    val id: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val state: String = "selected",
    val receivedBytes: Long = 0,
    val uploadId: String? = null,
    val attachment: AttachmentMeta? = null,
    val error: String? = null,
    val clientUploadId: String = id,
) {
    fun displayMeta() =
        attachment
            ?: AttachmentMeta(
                id = id,
                fileName = fileName,
                mediaType = mediaType,
                size = size,
                sha256 = sha256,
                kind = if (mediaType.startsWith("image/")) "image" else "file",
                readMessage = "尚未发送",
            )
}

@Serializable
data class ComposerDraft(
    val target: DraftTarget,
    val text: String = "",
    val attachments: List<LocalAttachment> = emptyList(),
)

@Serializable
data class PendingMessage(
    val target: DraftTarget,
    val messageId: String,
    val action: String,
    val text: String,
    val attachments: List<LocalAttachment>,
    val turnId: String? = null,
    val state: String = "waitingForUploads",
    val error: String? = null,
    val request: AdminCommand? = null,
)

fun ThreadDetail.draftTarget(computer: PairedComputer?) =
    DraftTarget(computer?.certificateSha256.orEmpty(), backendScopeId, thread.id)

fun attachmentStateTitle(state: String): String =
    when (state) {
        "selected" -> "待发送"
        "preparing" -> "准备中"
        "uploading" -> "正在上传"
        "verifying" -> "正在校验"
        "ready" -> "已上传"
        "paused" -> "已暂停"
        "failed" -> "上传失败"
        else -> state
    }
