package com.shelf.reader.designsystem.theme

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4A5568),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1E8),
    onPrimaryContainer = Color(0xFF1A202C),
    secondary = Color(0xFF8B6F47),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DDCB),
    onSecondaryContainer = Color(0xFF3A2A15),
    tertiary = Color(0xFF4A7C59),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4E5DA),
    onTertiaryContainer = Color(0xFF1E3B29),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = ShelfColors.PaperWarm,
    onBackground = ShelfColors.InkPrimary,
    surface = Color(0xFFFFFBF6),
    onSurface = ShelfColors.InkPrimary,
    surfaceVariant = Color(0xFFF0EADF),
    onSurfaceVariant = ShelfColors.InkSecondary,
    surfaceTint = Color(0xFF8B6F47),
    outline = Color(0xFF8A8178),
    outlineVariant = Color(0xFFD0C8BC),
    scrim = Color(0x88000000),
    inverseSurface = Color(0xFF312D28),
    inverseOnSurface = Color(0xFFF7EFE2),
    inversePrimary = Color(0xFFC4A57B)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0BBC9),
    onPrimary = Color(0xFF1A202C),
    primaryContainer = Color(0xFF334155),
    onPrimaryContainer = Color(0xFFCDE1F7),
    secondary = Color(0xFFC4A57B),
    onSecondary = Color(0xFF3A2A15),
    secondaryContainer = Color(0xFF5A4428),
    onSecondaryContainer = Color(0xFFF5E6CC),
    tertiary = Color(0xFF88B89A),
    onTertiary = Color(0xFF0E3020),
    tertiaryContainer = Color(0xFF2D5240),
    onTertiaryContainer = Color(0xFFC7E9D6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141210),
    onBackground = Color(0xFFE8E0D5),
    surface = Color(0xFF1A1815),
    onSurface = Color(0xFFE8E0D5),
    surfaceVariant = Color(0xFF2E2A24),
    onSurfaceVariant = Color(0xFFC8BFAE),
    surfaceTint = Color(0xFFC4A57B),
    outline = Color(0xFF928A7C),
    outlineVariant = Color(0xFF4B463D),
    scrim = Color(0xCC000000),
    inverseSurface = Color(0xFFE8E0D5),
    inverseOnSurface = Color(0xFF312D28),
    inversePrimary = Color(0xFF4A5568)
)

object ShelfElevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp

    val BookStanding = 2.dp
    val BookLeaning = 3.dp
    val BookPressed = 0.dp
    val ShelfBoard = 4.dp
}

object ShelfMotion {
    val Fast = 120
    val Normal = 250
    val Slow = 400
    val Deliberate = 600
    val SpringDamping = 0.75f
    val SpringStiffness = 350f
    val LowStiffness = 220f
}

object ShelfSpacing {
    val Quarter = 2.dp
    val Half = 4.dp
    val Default = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Extra = 24.dp
    val Double = 32.dp
    val Triple = 48.dp
    val Quadruple = 64.dp

    val ShelfGap = 16.dp
    val ShelfPaddingH = 20.dp
    val ShelfPaddingTop = 28.dp
    val ShelfPaddingBottom = 12.dp
    val BookGap = 3.dp
}

val LocalWoodPalette = staticCompositionLocalOf<WoodPalette> {
    error("No WoodPalette provided. Wrap with ShelfTheme.")
}

val LocalShelfSpacing = staticCompositionLocalOf<ShelfSpacing> { ShelfSpacing }

object ShelfTheme {
    val wood: WoodPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalWoodPalette.current

    val spacing: ShelfSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalShelfSpacing.current
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    trueBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val baseScheme = when {
        dynamicColor && darkTheme -> runCatching { dynamicDarkColorScheme(ctx) }.getOrNull() ?: DarkColorScheme
        dynamicColor && !darkTheme -> runCatching { dynamicLightColorScheme(ctx) }.getOrNull() ?: LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (trueBlack && darkTheme) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF0A0A0A)
        )
    } else baseScheme

    val woodPalette = if (darkTheme) DarkWoodPalette else LightWoodPalette

    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        runCatching {
            val activity = view.context as? android.app.Activity
                ?: (view.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
            val window = activity?.window ?: return@runCatching
            if (view.windowToken == null) {
                view.post {
                    runCatching {
                        val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                        ic.isAppearanceLightStatusBars = !darkTheme
                        ic.isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            } else {
                val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                ic.isAppearanceLightStatusBars = !darkTheme
                ic.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalWoodPalette provides woodPalette,
        LocalShelfSpacing provides ShelfSpacing
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShelfMaterialTypography,
            shapes = ShelfMaterialShapes,
            content = content
        )
    }
}
