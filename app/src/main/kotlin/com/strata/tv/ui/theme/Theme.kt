package com.strata.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Strata's Compose theme.
 *
 * Strata is dark-only by design (we're a TV app, light mode would be
 * actively painful at night).  We map the brand palette into a
 * Material 3 [androidx.tv.material3.ColorScheme] so out-of-the-box
 * components — buttons, focus rings, surfaces — get the right colours
 * without per-call overrides.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StrataTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StrataColors.AccentPrimary,
            onPrimary = StrataColors.TextPrimary,
            primaryContainer = StrataColors.AccentPrimaryGlow,
            secondary = StrataColors.AccentSecondary,
            onSecondary = StrataColors.SurfaceVoid,
            background = StrataColors.SurfaceVoid,
            onBackground = StrataColors.TextPrimary,
            surface = StrataColors.SurfaceRaised,
            onSurface = StrataColors.TextPrimary,
            surfaceVariant = StrataColors.SurfaceFloat,
            onSurfaceVariant = StrataColors.TextSecondary,
            error = StrataColors.StatusError,
            border = StrataColors.SurfaceOverlay,
        ),
        content = content,
    )
}
