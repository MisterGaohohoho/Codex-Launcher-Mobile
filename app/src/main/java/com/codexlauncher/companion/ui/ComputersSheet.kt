package com.codexlauncher.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.CompanionState
import com.codexlauncher.companion.data.PairedComputer
import com.codexlauncher.companion.data.validateEndpoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ComputersSheet(
    state: CompanionState,
    now: Long,
    actions: LauncherActions,
    add: () -> Unit,
    dismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<PairedComputer?>(null) }
    var removing by remember { mutableStateOf<PairedComputer?>(null) }
    val target = editing
    if (target != null) {
        var name by rememberSaveable(target.id) { mutableStateOf(target.displayName) }
        var address by rememberSaveable(target.id) { mutableStateOf("") }
        val validAddress =
            address.isBlank() || runCatching { validateEndpoint(address.trim()) }.isSuccess
        EditorSheet("电脑信息", { editing = null }) {
            OutlinedTextField(
                name,
                { name = it.take(64) },
                label = { Text("备注名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("computer-name"),
            )
            OutlinedTextField(
                address,
                { address = it.take(256) },
                label = { Text("备用连接地址（可选）") },
                placeholder = { Text("https://192.168.1.2:39173") },
                singleLine = true,
                isError = !validAddress,
                supportingText = { Text("可填入这台电脑手机连接页的本地地址，用于同一 Wi-Fi 或热点连接。") },
                modifier = Modifier.fillMaxWidth().testTag("computer-address"),
            )
            Text(
                target.endpoint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    actions.editComputer(target.id, name, address)
                    editing = null
                },
                enabled = !state.busy && validAddress && name.none { it.isISOControl() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    } else
        EditorSheet("我的电脑", dismiss) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "切换后，仅控制当前选中的电脑。",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = actions.refreshComputers, enabled = !state.busy) {
                    Icon(Icons.Outlined.Refresh, "刷新电脑状态")
                }
            }
            val computers = state.computers.ifEmpty { listOfNotNull(state.computer) }
            computers.forEach { computer ->
                val selected = computer.id == state.computer?.id
                val overview = state.computerStatuses[computer.id]
                val snapshot = if (selected) state.snapshot else overview?.snapshot
                val label =
                    when {
                        selected -> state.connectionLabel(now)
                        overview?.checking == true -> "检测中"
                        overview?.error != null -> "暂不可达"
                        overview?.checkedAt == null || overview.checkedAt == 0L -> "未检测"
                        System.currentTimeMillis() - overview.checkedAt > 30_000 -> "最近状态"
                        else -> "在线"
                    }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color =
                        if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().testTag("computer-${computer.id}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f)
                                .clickable(enabled = !state.busy && !state.switchingComputer) {
                                    actions.selectComputer(computer.id)
                                    dismiss()
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                if (selected) Icons.Outlined.CheckCircle
                                else Icons.Outlined.Computer,
                                if (selected) "当前电脑" else null,
                                tint =
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    computer.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    label +
                                        if (selected && state.connectionInfo.label.isNotBlank())
                                            " · ${state.connectionInfo.label}"
                                        else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                snapshot?.let {
                                    Text(
                                        (if (label in listOf("暂不可达", "最近状态", "连接中断", "正在同步")) "上次："
                                        else "") +
                                            (if (it.status?.active == true) "任务运行中" else "空闲") +
                                            it.configuration.model
                                                .takeIf(String::isNotBlank)
                                                ?.let { model -> " · $model" }
                                                .orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!selected && overview != null && overview.checkedAt > 0)
                                    Text(
                                        "更新于 " +
                                            DateTimeFormatter.ofPattern("HH:mm:ss")
                                                .withZone(ZoneId.systemDefault())
                                                .format(Instant.ofEpochMilli(overview.checkedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                        var menu by remember(computer.id) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !state.busy) {
                                Icon(Icons.Outlined.MoreVert, "管理${computer.displayName}")
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("编辑名称与地址") },
                                    onClick = {
                                        menu = false
                                        editing = computer
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("移除电脑") },
                                    onClick = {
                                        menu = false
                                        removing = computer
                                    },
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = add,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("add-computer"),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加电脑")
            }
        }
    removing?.let { computer ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除${computer.displayName}？") },
            text = { Text("移除此手机保存的授权。电脑上的任务会继续运行，之后可重新扫码添加。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.removeComputer(computer.id)
                        removing = null
                    }
                ) {
                    Text("移除")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}
