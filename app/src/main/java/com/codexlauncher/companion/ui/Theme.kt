package com.codexlauncher.companion.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark =
    darkColorScheme(
        primary = Color(0xFFF4F4F4),
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFF2C2C2E),
        onPrimaryContainer = Color(0xFFF3F3F3),
        background = Color(0xFF161616),
        onBackground = Color(0xFFF3F3F3),
        surface = Color(0xFF202020),
        onSurface = Color(0xFFF3F3F3),
        surfaceContainer = Color(0xFF272727),
        surfaceContainerLow = Color(0xFF202020),
        surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF3A3A3C),
        surfaceVariant = Color(0xFF292929),
        onSurfaceVariant = Color(0xFFB0B0B5),
        outline = Color(0xFF77777D),
        outlineVariant = Color(0xFF363638),
        secondary = Color(0xFFC8C8CC),
        onSecondary = Color(0xFF202020),
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFE5E5E8),
        surfaceTint = Color.Transparent,
        error = Color(0xFFFF98A8),
        errorContainer = Color(0xFF321C22),
        onErrorContainer = Color(0xFFFFCBD3),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF222222),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEEEEF0),
        onPrimaryContainer = Color(0xFF232325),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF202022),
        surface = Color.White,
        onSurface = Color(0xFF202022),
        surfaceContainer = Color(0xFFF0F0F2),
        surfaceContainerLow = Color.White,
        surfaceContainerHigh = Color(0xFFEAEAEC),
        surfaceContainerHighest = Color(0xFFE1E1E5),
        surfaceVariant = Color(0xFFF3F3F5),
        onSurfaceVariant = Color(0xFF68686E),
        outline = Color(0xFF85858B),
        outlineVariant = Color(0xFFE8E8EB),
        secondary = Color(0xFF606066),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF0F0F2),
        onSecondaryContainer = Color(0xFF3B3B40),
        surfaceTint = Color.Transparent,
        error = Color(0xFFB52F43),
        errorContainer = Color(0xFFFFF1F2),
        onErrorContainer = Color(0xFF8A2434),
    )

private val LauncherTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 39.sp,
                letterSpacing = (-0.7).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 27.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 31.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 27.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            ),
        bodyLarge =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
        bodySmall =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        labelSmall =
            TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
    )

@Composable
fun LauncherTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = LauncherTypography,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}
