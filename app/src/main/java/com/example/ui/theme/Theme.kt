package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElegantDarkPrimary,
    onPrimary = ElegantDarkOnPrimary,
    secondary = ElegantDarkSecondary,
    onSecondary = ElegantDarkOnSecondary,
    tertiary = ElegantDarkPrimary,
    background = ElegantDarkBg,
    onBackground = ElegantDarkOnBackground,
    surface = ElegantDarkSurface,
    onSurface = ElegantDarkOnSurface,
    surfaceVariant = ElegantDarkSurfaceVariant,
    onSurfaceVariant = ElegantDarkPrimary,
    outline = ElegantDarkOutline
  )

private val LightColorScheme = DarkColorScheme // Keep Elegant Dark cohesive in light mode too

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme by default for Elegant Dark feel
  dynamicColor: Boolean = false, // Disable dynamic colors to protect the handcrafted theme
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
