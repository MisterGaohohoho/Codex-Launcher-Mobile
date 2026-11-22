package com.codexlauncher.companion.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Dark =
    darkColorScheme(
        primary = Color(0xFF55DBAA),
        onPrimary = Color(0xFF003825),
        primaryContainer = Color(0xFF173026),
        onPrimaryContainer = Color(0xFFAAEFD2),
        background = Color(0xFF101312),
        onBackground = Color(0xFFE8EDE9),
        surface = Color(0xFF171C19),
        onSurface = Color(0xFFE8EDE9),
        surfaceContainer = Color(0xFF1B211E),
        surfaceContainerLow = Color(0xFF191F1C),
        surfaceContainerHigh = Color(0xFF242C27),
        surfaceContainerHighest = Color(0xFF2B342E),
        surfaceVariant = Color(0xFF19231D),
        onSurfaceVariant = Color(0xFFA1B1A7),
        outline = Color(0xFF506457),
        outlineVariant = Color(0xFF29372F),
        secondary = Color(0xFFA1C9B1),
        secondaryContainer = Color(0xFF243B2E),
        onSecondaryContainer = Color(0xFFD0E7D8),
        error = Color(0xFFFF98A8),
        errorContainer = Color(0xFF321C22),
        onErrorContainer = Color(0xFFFFCBD3),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF007B53),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE7F5ED),
        onPrimaryContainer = Color(0xFF005339),
        background = Color(0xFFF4F6F5),
        onBackground = Color(0xFF15251C),
        surface = Color.White,
        onSurface = Color(0xFF15251C),
        surfaceContainer = Color(0xFFEAF0EC),
        surfaceContainerLow = Color(0xFFFDFEFD),
        surfaceContainerHigh = Color(0xFFE4ECE6),
        surfaceContainerHighest = Color(0xFFDAE5DD),
        surfaceVariant = Color(0xFFF0F5F2),
        onSurfaceVariant = Color(0xFF596B60),
        outline = Color(0xFF73867A),
        outlineVariant = Color(0xFFE0E7E2),
        secondary = Color(0xFF466653),
        secondaryContainer = Color(0xFFE0EFE5),
        onSecondaryContainer = Color(0xFF264431),
        error = Color(0xFFB52F43),
        errorContainer = Color(0xFFFFF1F2),
        onErrorContainer = Color(0xFF8A2434),
    )

@Composable
fun LauncherTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
        content = content,
    )
}
