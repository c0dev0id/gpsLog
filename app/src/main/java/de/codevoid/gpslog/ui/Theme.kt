package de.codevoid.gpslog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/** Tabular figures: every digit takes the same width, so a ticking value never shifts its neighbours. */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Material 3 defaults with tabular numerals on the display, headline and title roles — the roles
 * that carry live numbers (status hero, stat tiles, result count). Body and label roles keep
 * proportional figures for prose.
 */
private val AppTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.tabular(),
        displayMedium = displayMedium.tabular(),
        displaySmall = displaySmall.tabular(),
        headlineLarge = headlineLarge.tabular(),
        headlineMedium = headlineMedium.tabular(),
        headlineSmall = headlineSmall.tabular(),
        titleLarge = titleLarge.tabular(),
        titleMedium = titleMedium.tabular(),
        titleSmall = titleSmall.tabular(),
    )
}

/** Material You: at minSdk 34 the dynamic schemes are always available, so no fallback palette. */
@Composable
fun GpsLogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
