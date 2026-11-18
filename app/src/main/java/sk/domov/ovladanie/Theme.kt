package sk.domov.ovladanie

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Farby, ktoré Material 3 schéma nemá: dlaždice, jantárová žiara, zelená „beží“. */
@Immutable
data class DomovColors(
    val dark: Boolean,
    val tileOn: Brush,
    val tileOnBorder: Color,
    val tileOff: Color,
    val tileOffBorder: Color,
    val onTileOn: Color,
    val onTileOnMuted: Color,
    val iconOn: Color,
    val onIconOn: Color,
    val iconOff: Color,
    val onIconOff: Color,
    val good: Color,
    val goodContainer: Color,
    val glow: Color,
    val orbGradient: List<Color>,
    val orbOff: List<Color>,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB35A),
    onPrimary = Color(0xFF2A1700),
    primaryContainer = Color(0xFFFFC878),
    onPrimaryContainer = Color(0xFF2A1700),
    secondary = Color(0xFFDFC2A2),
    onSecondary = Color(0xFF3F2D17),
    secondaryContainer = Color(0xFF2A251F),
    onSecondaryContainer = Color(0xFFF4EBE1),
    tertiary = Color(0xFF8FD694),
    onTertiary = Color(0xFF053910),
    tertiaryContainer = Color(0xFF1B2E1E),
    onTertiaryContainer = Color(0xFFBAF4BA),
    background = Color(0xFF0F0E0D),
    onBackground = Color(0xFFF4EBE1),
    surface = Color(0xFF0F0E0D),
    onSurface = Color(0xFFF4EBE1),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFB3A596),
    surfaceTint = Color(0xFFFFB35A),
    inverseSurface = Color(0xFFF4EBE1),
    inverseOnSurface = Color(0xFF2A2622),
    inversePrimary = Color(0xFF855400),
    error = Color(0xFFFF9E8F),
    onError = Color(0xFF3A0A05),
    errorContainer = Color(0xFF3A1C18),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F8378),
    outlineVariant = Color(0xFF3A342E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF332E29),
    surfaceDim = Color(0xFF0F0E0D),
    surfaceContainerLowest = Color(0xFF0B0A09),
    surfaceContainerLow = Color(0xFF1A1816),
    surfaceContainer = Color(0xFF1F1C19),
    surfaceContainerHigh = Color(0xFF25211D),
    surfaceContainerHighest = Color(0xFF2A2622),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF855400),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFC27A),
    onPrimaryContainer = Color(0xFF2A1700),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E7DA),
    onSecondaryContainer = Color(0xFF241B13),
    tertiary = Color(0xFF2E6A35),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB1F1B2),
    onTertiaryContainer = Color(0xFF00210A),
    background = Color(0xFFFBF5EE),
    onBackground = Color(0xFF241B13),
    surface = Color(0xFFFBF5EE),
    onSurface = Color(0xFF241B13),
    surfaceVariant = Color(0xFFEADBCB),
    onSurfaceVariant = Color(0xFF66554A),
    surfaceTint = Color(0xFF855400),
    inverseSurface = Color(0xFF372F28),
    inverseOnSurface = Color(0xFFFCEEE2),
    inversePrimary = Color(0xFFFFB35A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF837468),
    outlineVariant = Color(0xFFD9C7B6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF5EE),
    surfaceDim = Color(0xFFE6D7CC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF8EEE3),
    surfaceContainerHigh = Color(0xFFF5EADD),
    surfaceContainerHighest = Color(0xFFF1E4D6),
)

private val DarkExtra = DomovColors(
    dark = true,
    tileOn = Brush.verticalGradient(listOf(Color(0xFFFFCF8F), Color(0xFFFFB963))),
    tileOnBorder = Color(0xFFFFCF8F),
    tileOff = Color(0xFF1A1816),
    tileOffBorder = Color(0xFF2A2622),
    onTileOn = Color(0xFF2A1700),
    onTileOnMuted = Color(0xFF5B3A0E),
    iconOn = Color(0xFF2A1700),
    onIconOn = Color(0xFFFFCF8F),
    iconOff = Color(0xFF27231F),
    onIconOff = Color(0xFFC4B5A6),
    good = Color(0xFF8FD694),
    goodContainer = Color(0xFF1B2E1E),
    glow = Color(0xFFFFB962),
    orbGradient = listOf(Color(0xFFFFE2B8), Color(0xFFFFB95C), Color(0xFFE08A1E)),
    orbOff = listOf(Color(0xFF36302B), Color(0xFF2B2622), Color(0xFF231F1C)),
)

private val LightExtra = DomovColors(
    dark = false,
    tileOn = Brush.verticalGradient(listOf(Color(0xFFFFD9A0), Color(0xFFFFC27A))),
    tileOnBorder = Color(0xFFFFC27A),
    tileOff = Color(0xFFFFFFFF),
    tileOffBorder = Color(0xFFE9DAC9),
    onTileOn = Color(0xFF2A1700),
    onTileOnMuted = Color(0xFF5B3A0E),
    iconOn = Color(0xFF2A1700),
    onIconOn = Color(0xFFFFD9A0),
    iconOff = Color(0xFFF3E7DA),
    onIconOff = Color(0xFF5A4A3A),
    good = Color(0xFF2E6A35),
    goodContainer = Color(0xFFD6F2D3),
    glow = Color(0xFFFFA530),
    orbGradient = listOf(Color(0xFFFFF0D6), Color(0xFFFFC172), Color(0xFFF09A2A)),
    orbOff = listOf(Color(0xFFF3E6DA), Color(0xFFEADBCD), Color(0xFFE0CFBF)),
)

val LocalDomovColors = staticCompositionLocalOf { DarkExtra }

/** Skratka: `Domov.colors.tileOn`. */
object Domov {
    val colors: DomovColors @Composable get() = LocalDomovColors.current
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun t(size: Int, weight: FontWeight, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val DomovTypography = Typography(
    displayLarge = t(57, FontWeight.SemiBold, 64, -1.0),
    displayMedium = t(45, FontWeight.SemiBold, 52, -1.0),
    displaySmall = t(36, FontWeight.SemiBold, 44, -0.5),
    headlineLarge = t(32, FontWeight.Bold, 40, -0.5),
    headlineMedium = t(28, FontWeight.SemiBold, 36, -0.3),
    headlineSmall = t(24, FontWeight.SemiBold, 32),
    titleLarge = t(20, FontWeight.SemiBold, 28),
    titleMedium = t(16, FontWeight.SemiBold, 24, 0.1),
    titleSmall = t(14, FontWeight.SemiBold, 20, 0.1),
    bodyLarge = t(16, FontWeight.Normal, 24, 0.2),
    bodyMedium = t(14, FontWeight.Normal, 20, 0.2),
    bodySmall = t(12, FontWeight.Normal, 16, 0.3),
    labelLarge = t(14, FontWeight.Medium, 20, 0.1),
    labelMedium = t(12, FontWeight.Medium, 16, 0.4),
    labelSmall = t(11, FontWeight.Medium, 16, 0.5),
)

val DomovShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Vlastná téma appky: tmavá s jantárovým akcentom, svetlá varianta podľa systému. */
@Composable
fun HomeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDomovColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = DomovTypography,
            shapes = DomovShapes,
            content = content,
        )
    }
}
