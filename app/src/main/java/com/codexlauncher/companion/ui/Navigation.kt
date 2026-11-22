@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexlauncher.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexlauncher.companion.CompanionState
import com.codexlauncher.companion.R
import com.codexlauncher.companion.data.WorkThread

@Composable
fun BottomDock(page: Int, navigate: (Int) -> Unit, approvals: Int) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 2.dp,
        ) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                destinations.forEachIndexed { index, (label, icon) ->
                    val selected = page == index
                    Surface(
                        Modifier.weight(1f)
                            .clip(MaterialTheme.shapes.large)
                            .selectable(selected, role = Role.Tab, onClick = { navigate(index) })
                            .testTag("tab-$index"),
                        shape = MaterialTheme.shapes.large,
                        color =
                            if (selected) MaterialTheme.colorScheme.surfaceContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            Modifier.heightIn(min = 56.dp).padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            BadgedBox(
                                badge = {
                                    if (index == 1 && approvals > 0)
                                        Badge { Text(approvals.toString()) }
                                }
                            ) {
                                Icon(
                                    icon,
                                    null,
                                    Modifier.size(21.dp),
                                    tint =
                                        if (selected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight =
                                    if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color =
                                    if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LauncherSidebar(
    state: CompanionState,
    page: Int,
    canControl: Boolean,
    actions: LauncherActions,
    close: () -> Unit,
    navigate: (Int) -> Unit,
    openThread: (String) -> Unit,
    create: () -> Unit,
    connectionSettings: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.workbench.search) { search = state.workbench.search }
    var contextThread by remember { mutableStateOf<WorkThread?>(null) }
    val threads =
        state.workbench.threads.filter {
            search.isBlank() || it.title.contains(search, true) || it.project.contains(search, true)
        }
    ModalDrawerSheet(
        Modifier.width(minOf(340.dp, LocalConfiguration.current.screenWidthDp.dp * 0.88f))
            .testTag("session-sidebar"),
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerTonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.launcher_artwork),
                null,
                Modifier.size(32.dp).clip(MaterialTheme.shapes.small),
            )
            Text(
                "Launcher",
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = close) { Icon(Icons.Outlined.MenuOpen, "收起侧栏") }
        }
        SearchField(
            search,
            { search = it },
            "搜索会话",
            Modifier.padding(horizontal = 16.dp).testTag("drawer-search"),
        ) {
            actions.loadWorkbench(search, state.workbench.archived, false)
        }
        SettingRow(
            "新会话",
            icon = Icons.Outlined.Create,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("drawer-new-thread"),
            enabled = canControl,
            onClick = create,
            trailing = {},
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.workbench.archived) "已归档会话" else "最近会话",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { navigate(1) }, modifier = Modifier.testTag("drawer-page-1")) {
                Text("全部")
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (state.workbench.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.workbench.error?.let { item { Banner(it, true) } }
            if (threads.isEmpty() && !state.workbench.loading)
                item {
                    Text(
                        if (search.isBlank()) "会话会显示在这里" else "未找到匹配会话",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            items(threads, key = { it.id }) { thread ->
                ThreadRow(
                    thread,
                    selected = page == 1 && state.workbench.detail?.thread?.id == thread.id,
                    compact = true,
                    onLongClick = { contextThread = thread },
                ) {
                    openThread(thread.id)
                }
            }
            if (state.workbench.nextCursor != null)
                item {
                    TextButton(
                        onClick = { actions.loadWorkbench(search, state.workbench.archived, true) },
                        enabled = !state.workbench.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更多")
                    }
                }
        }
        HorizontalDivider(
            Modifier.padding(horizontal = 20.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            listOf(0, 2, 3).forEach { index ->
                Column(
                    Modifier.weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { navigate(index) }
                        .padding(vertical = 12.dp)
                        .testTag("drawer-page-$index"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(destinations[index].second, null, Modifier.size(20.dp))
                    Text(destinations[index].first, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Icon(Icons.Outlined.Computer, null, Modifier.padding(9.dp).size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    state.computer?.computerName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.online) "电脑已连接" else "等待电脑连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    contextThread?.let { thread ->
        SessionChoiceSheet(
            thread,
            canControl,
            { contextThread = null },
            original = {
                contextThread = null
                openThread(thread.id)
                actions.workbenchAction(
                    "attach",
                    kotlinx.serialization.json.buildJsonObject {
                        put("threadId", kotlinx.serialization.json.JsonPrimitive(thread.id))
                    },
                )
            },
            branch = {
                contextThread = null
                navigate(1)
                actions.workbenchAction(
                    "fork",
                    kotlinx.serialization.json.buildJsonObject {
                        put("threadId", kotlinx.serialization.json.JsonPrimitive(thread.id))
                    },
                )
            },
            connectionSettings = {
                contextThread = null
                connectionSettings()
            },
        )
    }
}
