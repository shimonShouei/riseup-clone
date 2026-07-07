package com.riseup.clone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Chart / money semantics that Material's color roles don't cover.
 * Positive cash is teal-green, overdraft is rust, caution is amber.
 */
@Immutable
data class FlowColors(
    val positive: Color,
    val negative: Color,
    val caution: Color,
    val chartHistory: Color,
    val gridline: Color,
    val mutedText: Color,
)

val LocalFlowColors = staticCompositionLocalOf {
    FlowColors(
        positive = TealLight,
        negative = RustLight,
        caution = AmberLight,
        chartHistory = Color(0xFF9AA1AE),
        gridline = Color(0x14161A24),
        mutedText = Color(0xFF6A7180),
    )
}

private val LightColors = lightColorScheme(
    primary = TealLight,
    onPrimary = Color.White,
    secondary = AmberLight,
    error = RustLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = CardLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEBEDF1),
    onSurfaceVariant = Color(0xFF6A7180),
    outline = Color(0xFFD5D9E0),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF06281D),
    secondary = AmberDark,
    error = RustDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF232936),
    onSurfaceVariant = Color(0xFF98A0AF),
    outline = Color(0xFF39404E),
)

@Composable
fun RiseUpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val flow = if (darkTheme) {
        FlowColors(
            positive = TealDark,
            negative = RustDark,
            caution = AmberDark,
            chartHistory = Color(0xFF6F7685),
            gridline = Color(0x1FE8EAEF),
            mutedText = Color(0xFF98A0AF),
        )
    } else {
        FlowColors(
            positive = TealLight,
            negative = RustLight,
            caution = AmberLight,
            chartHistory = Color(0xFF9AA1AE),
            gridline = Color(0x14161A24),
            mutedText = Color(0xFF6A7180),
        )
    }
    CompositionLocalProvider(LocalFlowColors provides flow) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = RiseUpTypography,
            content = content,
        )
    }
}
