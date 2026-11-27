package com.dafeiyu.overlay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MonoLightColors = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1C1C1E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF2C2C2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEDEF),
    onSecondaryContainer = Color(0xFF0A0A0A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF6F6F7),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEEEEF0),
    onSurfaceVariant = Color(0xFF5A5A60),
    outline = Color(0xFFDCDCE0),
    outlineVariant = Color(0xFFEAEAEF)
)

private val MonoDarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFFD1D1D6),
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Color(0xFF222226),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF2E2E34),
    outlineVariant = Color(0xFF222228)
)

private val StreamlinedShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val MonoTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun DaFeiYuTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MonoDarkColors else MonoLightColors,
        typography = MonoTypography,
        shapes = StreamlinedShapes,
        content = content
    )
}
