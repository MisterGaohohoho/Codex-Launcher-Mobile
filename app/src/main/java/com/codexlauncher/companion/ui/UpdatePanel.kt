package com.codexlauncher.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codexlauncher.companion.BuildConfig
import com.codexlauncher.companion.data.UpdateState

@Composable
fun UpdatePanel(state: UpdateState, actions: LauncherActions) {
    Panel {
        SectionTitle("Launcher Mobile", "当前版本 ${BuildConfig.VERSION_NAME}")
        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.manifest?.let { manifest ->
            if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                Text("新版本 ${manifest.versionName}", style = MaterialTheme.typography.titleMedium)
                Text(
                    manifest.releaseNotes.ifBlank { "改进与修复" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.progress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloaded != null)
                    Button(onClick = actions.installUpdate) { Text("安装更新") }
                else
                    Button(onClick = actions.downloadUpdate, enabled = state.progress == null) {
                        Text("下载更新 · ${manifest.size / 1024 / 1024} MB")
                    }
            } else Text("已是最新版本", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            onClick = actions.checkUpdate,
            enabled = !state.checking && state.progress == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("检查更新")
        }
        Text(
            "从 launcher.laogaohohoho.me 获取更新，下载完成后由 Android 确认安装。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
