@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexlauncher.companion.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.codexlauncher.companion.BuildConfig
import com.codexlauncher.companion.CompanionState
import com.codexlauncher.companion.data.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun attachmentSize(bytes: Long): String =
    when {
        bytes >= ATTACHMENT_MIB ->
            String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / ATTACHMENT_MIB)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }

@Composable
fun ComposerAttachments(
    state: CompanionState,
    target: DraftTarget,
    actions: LauncherActions,
    confirm: (ConfirmAction) -> Unit,
) {
    val draft = state.attachmentDrafts[target.key]
    val pending =
        state.pendingMessages.values.filter {
            it.target == target && it.state !in listOf("sent", "discarded")
        }
    val context = LocalContext.current
    var selected by remember { mutableStateOf<LocalAttachment?>(null) }
    if (target.key in state.importingAttachments) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "正在准备附件…",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (draft?.attachments?.isNotEmpty() == true) {
        LazyRow(
            Modifier.fillMaxWidth().testTag("attachment-drafts"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(draft.attachments, key = { it.id }) { attachment ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.width(204.dp)
                            .clickable { selected = attachment }
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachmentThumbnail(
                            File(context.filesDir, "attachments/objects/${attachment.id}.bin"),
                            attachment.mediaType.startsWith("image/"),
                            Modifier.size(44.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                attachment.fileName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                attachmentSize(attachment.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { actions.removeAttachment(target, attachment.id) },
                            modifier =
                                Modifier.size(40.dp).testTag("remove-attachment-${attachment.id}"),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                "移除 " + attachment.fileName,
                                Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    pending.forEach { message ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("pending-message-${message.messageId}"),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when (message.state) {
                        "waitingForUploads" -> "正在上传附件"
                        "submitting" -> "正在提交消息"
                        "unknown" -> "消息发送结果待核对"
                        "needsConfirmation" -> "会话状态已变化"
                        "paused" -> "已保存待发送消息"
                        else -> "消息尚未发送"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (message.text.isNotBlank())
                    Text(
                        message.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (message.attachments.isNotEmpty()) {
                    val total = message.attachments.sumOf { it.size }
                    val received = message.attachments.sumOf { it.receivedBytes }
                    Text(
                        "${message.attachments.size} 个附件 · ${attachmentSize(received)} / ${attachmentSize(total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.state == "waitingForUploads")
                        LinearProgressIndicator(
                            progress = { if (total == 0L) 0f else received.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
                message.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (message.state == "waitingForUploads") {
                        TextButton(onClick = { actions.pauseMessage(message.messageId) }) {
                            Text("暂停")
                        }
                    } else {
                        if (message.state !in listOf("submitting", "unknown"))
                            TextButton(
                                onClick = {
                                    confirm(
                                        ConfirmAction("移除待发送消息", "移除本机的这份待发送内容；手机原始照片和文件会保留。") {
                                            actions.discardMessage(message.messageId)
                                        }
                                    )
                                }
                            ) {
                                Text("移除")
                            }
                        TextButton(
                            enabled = state.online && !state.busy,
                            onClick = {
                                if (message.state == "needsConfirmation")
                                    confirm(
                                        ConfirmAction(
                                            "按当前会话状态发送",
                                            "继续发送这份资料。当前有任务时补充到当前任务，否则开始新的一轮。",
                                        ) {
                                            actions.resumeMessage(message.messageId, true)
                                        }
                                    )
                                else actions.resumeMessage(message.messageId, false)
                            },
                        ) {
                            Text(
                                when (message.state) {
                                    "unknown",
                                    "submitting" -> "核对结果"
                                    "needsConfirmation" -> "确认发送方式"
                                    else -> "继续发送"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    selected?.let { local ->
        AttachmentDetails(
            local.displayMeta(),
            target,
            state,
            actions,
            File(context.filesDir, "attachments/objects/${local.id}.bin"),
        ) {
            selected = null
        }
    }
}

@Composable
fun AttachmentMessageCard(
    meta: AttachmentMeta,
    target: DraftTarget,
    state: CompanionState,
    actions: LauncherActions,
) {
    var details by remember { mutableStateOf(false) }
    val key = attachmentCacheKey(target, meta, true)
    LaunchedEffect(key) {
        if (meta.previewAvailable && state.attachmentErrors[key] == null)
            actions.loadAttachment(target, meta, true)
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { details = true }
            .testTag("attachment-card-${meta.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (meta.kind == "image")
            AttachmentThumbnail(
                state.attachmentFiles[key],
                true,
                Modifier.fillMaxWidth().height(148.dp),
            )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (meta.kind != "image") Icon(Icons.Outlined.Description, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    attachmentSize(meta.size) +
                        if (meta.readStatus == "unsupported") " · 未解析" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.OpenInFull, "查看附件", Modifier.size(16.dp))
        }
    }
    if (details) AttachmentDetails(meta, target, state, actions) { details = false }
}

@Composable
private fun AttachmentThumbnail(
    file: File?,
    image: Boolean,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
) {
    val bitmap by
        produceState<Bitmap?>(null, file?.path, file?.lastModified()) {
            value =
                if (image && file?.isFile == true)
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val options =
                                BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, options)
                            options.inSampleSize = 1
                            while (
                                maxOf(options.outWidth, options.outHeight) / options.inSampleSize >
                                    1024
                            ) options.inSampleSize *= 2
                            options.inJustDecodeBounds = false
                            BitmapFactory.decodeFile(file.path, options)
                        }
                            .getOrNull()
                    }
                else null
        }
    var scale by remember(file?.path) { mutableFloatStateOf(1f) }
    var offset by remember(file?.path) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Surface(
        modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        val current = bitmap
        if (current == null)
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (image) Icons.Outlined.Image else Icons.Outlined.Description,
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else
            Image(
                current.asImageBitmap(),
                "附件图片",
                Modifier.fillMaxSize()
                    .then(
                        if (zoomable)
                            Modifier.transformable(transform).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                        else Modifier
                    ),
                contentScale = if (zoomable) ContentScale.Fit else ContentScale.Crop,
            )
    }
}

@Composable
private fun AttachmentDetails(
    meta: AttachmentMeta,
    target: DraftTarget,
    state: CompanionState,
    actions: LauncherActions,
    local: File? = null,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    var openWhenReady by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val previewKey = attachmentCacheKey(target, meta, true)
    val fileKey = attachmentCacheKey(target, meta, false)
    val downloaded = local ?: state.attachmentFiles[fileKey]
    fun open(file: File) {
        try {
            val uri =
                FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".attachments",
                    file,
                )
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, meta.mediaType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "打开附件",
                )
            )
        } catch (_: Exception) {
            openError = "没有可打开此类型文件的应用"
        }
    }
    LaunchedEffect(downloaded?.path, openWhenReady) {
        if (openWhenReady && downloaded != null) {
            openWhenReady = false
            open(downloaded)
        }
    }
    EditorSheet(meta.fileName, dismiss) {
        if (meta.kind == "image") {
            AttachmentThumbnail(
                local ?: state.attachmentFiles[previewKey],
                true,
                Modifier.fillMaxWidth().height(320.dp),
                zoomable = true,
            )
            Text(
                "双指放大查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            attachmentSize(meta.size) + " · " + meta.mediaType,
            style = MaterialTheme.typography.bodySmall,
        )
        if (meta.readMessage.isNotBlank())
            Text(meta.readMessage, style = MaterialTheme.typography.bodyMedium)
        val error = state.attachmentErrors[if (openWhenReady) fileKey else previewKey] ?: openError
        error?.let { Banner(it, true) }
        if (meta.id.startsWith("att_") || local != null) {
            Button(
                onClick = {
                    if (downloaded != null) open(downloaded)
                    else {
                        openWhenReady = true
                        actions.loadAttachment(target, meta, false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = local != null || state.online,
            ) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (openWhenReady && downloaded == null && error == null) "正在下载…" else "在其他应用打开"
                )
            }
            if (error != null && meta.previewAvailable)
                TextButton(onClick = { actions.loadAttachment(target, meta, true) }) {
                    Text("重试预览")
                }
        }
    }
}
