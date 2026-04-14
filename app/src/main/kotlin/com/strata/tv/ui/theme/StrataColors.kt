package com.strata.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Strata brand palette — ported from
 * `lib/core/theme/strata_colors.dart` in the Flutter v1 app so the
 * visual identity carries across.
 *
 * Greys named by *role* (void / base / raised / float / overlay)
 * not by intensity, so dark-mode-only environments don't have to
 * fight inherited "lightX" semantics.
 */
object StrataColors {
    // Surfaces — darkest at the back, brightest at the front.
    val SurfaceVoid = Color(0xFF0A0A0F)
    val SurfaceBase = Color(0xFF111118)
    val SurfaceRaised = Color(0xFF1A1A24)
    val SurfaceFloat = Color(0xFF22222E)
    val SurfaceOverlay = Color(0xFF2A2A38)

    // Brand accents.
    val AccentPrimary = Color(0xFF7B5CF0)         // Electric Violet
    val AccentPrimaryBright = Color(0xFFA888FF)   // Hover / focus emphasis
    val AccentPrimaryGlow = Color(0x807B5CF0)     // Slider track / shadow
    val AccentSecondary = Color(0xFF00D4AA)       // Electric Teal

    // Status.
    val StatusLive = Color(0xFFFF3B5C)
    val StatusError = Color(0xFFFF6B8A)
    val StatusSuccess = Color(0xFF34D399)

    // Text.
    val TextPrimary = Color(0xFFF0EFF4)
    val TextSecondary = Color(0xFF9A98A6)
    val TextTertiary = Color(0xFF66647A)

    // ── Phase 9 extended tokens ─────────────────────────────────────
    // Gradient stops for cinematic backdrop overlays.
    val GradientVoidStart = Color(0x00000000)      // transparent
    val GradientVoidMid = Color(0xB3000000)         // 70 %
    val GradientVoidEnd = Color(0xFF0A0A0F)         // SurfaceVoid opaque

    // Focus ring / glow.
    val FocusRing = AccentPrimary
    val FocusGlow = Color(0x407B5CF0)               // 25 % violet glow

    // Shimmer placeholder colours.
    val ShimmerBase = SurfaceRaised
    val ShimmerHighlight = Color(0xFF2A2A3C)

    // Certification / badge chip background.
    val ChipSurface = Color(0xFF1E1E2E)
    val ChipBorder = Color(0xFF3A3A4E)
}
