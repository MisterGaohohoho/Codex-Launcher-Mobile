@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexlauncher.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.codexlauncher.companion.BuildConfig
import com.codexlauncher.companion.CompanionState
import com.codexlauncher.companion.data.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString

@Composable
fun rememberAttachmentPicker(
    imported: (DraftTarget, List<Uri>, Boolean) -> Unit
): (DraftTarget, String) -> Unit {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    // Activity results may return after rotation or after selecting another thread.
    var destination by rememberSaveable { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf("") }
    fun selected(uris: List<Uri>, photo: Boolean) {
        val frozen =
            runCatching { WireJson.decodeFromString<DraftTarget>(destination) }.getOrNull()
                ?: return
        if (uris.isNotEmpty()) imported(frozen, uris, photo)
    }
    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
            selected(it, true)
        }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            selected(it, false)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraUri.isNotBlank()) selected(listOf(Uri.parse(cameraUri)), true)
            else if (cameraUri.isNotBlank())
                runCatching { context.contentResolver.delete(Uri.parse(cameraUri), null, null) }
            cameraUri = ""
        }
    val launchCamera = {
        try {
            val folder = File(context.cacheDir, "attachment-camera").apply { mkdirs() }
            val file = File(folder, UUID.randomUUID().toString() + ".jpg")
            val uri =
                FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".attachments",
                    file,
                )
            cameraUri = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) {
            error = "无法打开相机，请使用照片或文件选择器"
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) launchCamera() else error = "未获得相机权限，仍可选择照片或文件"
        }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("附件选择") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
    return { target, kind ->
        destination = WireJson.encodeToString(target)
        try {
            when (kind) {
                "photos" ->
                    photos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                "camera" ->
                    if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                        launchCamera()
                    else permission.launch(Manifest.permission.CAMERA)
                else -> files.launch(arrayOf("*/*"))
            }
        } catch (_: Exception) {
            error = "无法打开系统选择器，请稍后重试"
        }
    }
}

@Composable
fun AttachmentPickerButton(
    target: DraftTarget,
    state: CompanionState,
    actions: LauncherActions,
    enabled: Boolean,
) {
    var sheet by remember { mutableStateOf(false) }
    IconButton(
        onClick = { sheet = true },
        enabled = enabled && target.key !in state.importingAttachments,
        modifier = Modifier.size(44.dp).testTag("add-attachment"),
    ) {
        Icon(Icons.Outlined.Add, "添加图片或文件", Modifier.size(24.dp))
    }
    if (sheet)
        EditorSheet("添加附件", { sheet = false }) {
            val supported = "workbench.attachments.v1" in state.snapshot?.capabilities.orEmpty()
            if (!supported) {
                Banner("图片和文件附件需要 PC 2.8.0-beta.2 或更新版本。文字聊天仍可使用。")
            } else {
                SettingsGroup {
                    SettingRow(
                        "照片",
                        subtitle = "从系统相册选择图片",
                        icon = Icons.Outlined.PhotoLibrary,
                        onClick = {
                            sheet = false
                            actions.pickAttachment(target, "photos")
                        },
                    )
                    SettingDivider()
                    SettingRow(
                        "拍照",
                        subtitle = "用相机拍摄后添加",
                        icon = Icons.Outlined.PhotoCamera,
                        onClick = {
                            sheet = false
                            actions.pickAttachment(target, "camera")
                        },
                    )
                    SettingDivider()
                    SettingRow(
                        "文件",
                        subtitle = "代码、日志、文档及其他资料",
                        icon = Icons.Outlined.AttachFile,
                        onClick = {
                            sheet = false
                            actions.pickAttachment(target, "files")
                        },
                    )
                }
                val policy =
                    AttachmentPolicy.parse(state.workbench.capabilities?.get("attachments"))
                Text(
                    "最多 ${policy.maxCount} 个附件。图片 ${attachmentSize(policy.maxImageBytes)}，单文件 ${attachmentSize(policy.maxFileBytes)}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "发送后，资料会上传到已配对电脑，并交给当前配置的模型处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
}
