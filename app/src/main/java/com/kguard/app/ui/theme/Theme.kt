package com.kguard.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * K-Guard is light-only by product decision. Fixed scheme, no dynamic colour — the palette is
 * the same on every device, which matters for a child-safety product parents must recognise
 * at a glance. Structure follows DESIGN.md with the canvas/ink pairing inverted for daylight.
 */
private val KGuardColorScheme = lightColorScheme(
    primary = Ink,                 // black: the primary CTA pill fill
    onPrimary = Canvas,            // white text on black pills
    primaryContainer = Surface2,
    onPrimaryContainer = Ink,
    secondary = Surface1,          // light gray: the secondary pill fill
    onSecondary = Ink,
    secondaryContainer = Surface2,
    onSecondaryContainer = Ink,
    tertiary = AccentBlue,
    onTertiary = Canvas,
    tertiaryContainer = Color(0xFFE1F3FF),
    onTertiaryContainer = Color(0xFF0073B8),
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = Surface1,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    surfaceBright = Canvas,
    surfaceDim = Color(0xFFEDEDED),
    inverseSurface = Ink,
    inverseOnSurface = Canvas,
    inversePrimary = Canvas,
    outline = Hairline,
    outlineVariant = HairlineSoft,
    error = SignalDanger,
    onError = Canvas,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFFB91C1C),
    scrim = Color(0x66000000)
)

@Composable
fun KGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KGuardColorScheme,
        typography = Typography,
        shapes = KGShapesMaterial,
        content = content
    )
}

/** Extended palette beyond the Material scheme, addressed through the theme for consistency. */
object KGColors {
    val canvas: Color @Composable @ReadOnlyComposable get() = Canvas
    val inkMuted: Color @Composable @ReadOnlyComposable get() = InkMuted
    val accent: Color @Composable @ReadOnlyComposable get() = AccentBlue
    val success: Color @Composable @ReadOnlyComposable get() = SignalSuccess
    val warning: Color @Composable @ReadOnlyComposable get() = SignalWarning
    val danger: Color @Composable @ReadOnlyComposable get() = SignalDanger
    val info: Color @Composable @ReadOnlyComposable get() = SignalInfo
    val hairline: Color @Composable @ReadOnlyComposable get() = Hairline
    val gradientViolet: Color get() = GradientViolet
    val gradientMagenta: Color get() = GradientMagenta
    val gradientOrange: Color get() = GradientOrange
    val gradientCoral: Color get() = GradientCoral
}

/** Radius vocabulary from DESIGN.md: inputs 10, cards 20, spotlight panels 30, pills 100, circles full. */
object KGShapes {
    val card = RoundedCornerShape(20.dp)
    val cardSmall = RoundedCornerShape(12.dp)
    val input = RoundedCornerShape(10.dp)
    val spotlight = RoundedCornerShape(30.dp)
    val pill = RoundedCornerShape(100.dp)
    val circle = CircleShape
}

internal val KGShapesMaterial = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(30.dp)
)
