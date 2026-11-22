@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.codexlauncher.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Native Compose primitives, with original styling inspired by minimal iOS chat apps.
// No third-party application source, Apple symbols or fonts are included.
@Composable
fun CircleAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp,
        ) {
            Icon(icon, description, Modifier.padding(10.dp).size(20.dp))
        }
    }
}

@Composable
fun SettingsGroup(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank())
            Text(
                title,
                Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (!footer.isNullOrBlank())
            Text(
                footer,
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
fun SettingDivider(inset: Int = 16) {
    HorizontalDivider(
        Modifier.padding(start = inset.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val stackValue = LocalDensity.current.fontScale > 1.25f || (value?.length ?: 0) > 22
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null)
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.48f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(21.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (stackValue && !value.isNullOrBlank())
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            if (!subtitle.isNullOrBlank())
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        if (!stackValue && !value.isNullOrBlank())
            Text(
                value,
                Modifier.widthIn(max = 132.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        if (trailing != null) trailing()
        else if (onClick != null)
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
    }
}

@Composable
fun ToggleSetting(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(title, subtitle = subtitle, modifier = modifier, enabled = enabled) {
        Switch(
            checked,
            onCheckedChange,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFF25844E),
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    uncheckedBorderColor = Color.Transparent,
                ),
        )
    }
}

@Composable
fun SegmentedBar(
    labels: List<String>,
    selected: Int,
    select: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String? = null,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val color by
                    animateColorAsState(
                        if (selected == index) MaterialTheme.colorScheme.surfaceContainerLow
                        else Color.Transparent,
                        tween(180),
                        label = "segmentColor",
                    )
                Surface(
                    modifier =
                        Modifier.weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected == index,
                                role = Role.Tab,
                                onClick = { select(index) },
                            )
                            .then(
                                if (tagPrefix != null) Modifier.testTag("$tagPrefix-$index")
                                else Modifier
                            ),
                    color = color,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = if (selected == index) 1.dp else 0.dp,
                ) {
                    Box(
                        Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight =
                                if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                            color =
                                if (selected == index) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    search: () -> Unit = {},
) {
    TextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
        trailingIcon =
            if (value.isNotEmpty())
                ({
                    IconButton(
                        onClick = {
                            onValueChange("")
                            search()
                        }
                    ) {
                        Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp))
                    }
                })
            else null,
        singleLine = true,
        shape = CircleShape,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
    )
}

@Composable
fun EditorSheet(
    title: String,
    dismiss: () -> Unit,
    save: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
    saveLabel: String = "完成",
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f)
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = dismiss, modifier = Modifier.testTag("sheet-cancel-$title")) {
                    Icon(Icons.Outlined.Close, if (save != null) "取消编辑" else "关闭面板")
                }
                if (save != null)
                    TextButton(
                        onClick = save,
                        enabled = saveEnabled,
                        modifier = Modifier.testTag("sheet-save-$title"),
                    ) {
                        Text(saveLabel)
                    }
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun ChoiceSetting(
    title: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    changed: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title,
        choices.find { it.first == value }?.second ?: value,
        subtitle,
        modifier = modifier,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        EditorSheet(title, { open = false }) {
            SettingsGroup {
                choices.forEachIndexed { index, (id, label) ->
                    if (index > 0) SettingDivider()
                    SettingRow(
                        label,
                        onClick = {
                            changed(id)
                            open = false
                        },
                        trailing = {
                            if (id == value) Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp))
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun TextSetting(
    title: String,
    value: String,
    placeholder: String = "未设置",
    subtitle: String? = null,
    secret: Boolean = false,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    tag: String = title,
    changed: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title,
        if (secret && value.isNotBlank()) "已填写" else value.ifBlank { placeholder },
        subtitle,
        modifier = Modifier.testTag("setting-$tag"),
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        var draft by rememberSaveable { mutableStateOf(value) }
        EditorSheet(
            title,
            { open = false },
            save = {
                changed(draft)
                open = false
            },
        ) {
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth().testTag("setting-input-$tag"),
                label = { Text(title) },
                placeholder = { Text(placeholder) },
                minLines = if (multiline) 4 else 1,
                maxLines = if (multiline) 10 else 1,
                singleLine = !multiline,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation =
                    if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                shape = MaterialTheme.shapes.medium,
            )
            if (!subtitle.isNullOrBlank())
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
