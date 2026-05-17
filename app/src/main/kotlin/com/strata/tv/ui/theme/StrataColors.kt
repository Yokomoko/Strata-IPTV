package com.strata.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Strata brand palette.
 *
 * Anchors to the gradient defined in the icon pack
 * (cyan -> blue -> purple -> pink -> magenta).  Surfaces sit on a
 * deep-navy-to-deep-violet vertical gradient that matches the
 * launcher icon and TV banner so the brand carries from outside the
 * app into the UI.
 *
 * Greys named by *role* (void / base / raised / float / overlay)
 * not by intensity, so dark-mode-only environments don't have to
 * fight inherited "lightX" semantics.
 */
object StrataColors {
    // Surfaces, darkest at the back, brightest at the front.
    // Top of the launcher icon gradient is #08091A; the body of the
    // app sits on the same anchor with progressively lighter cards.
    val SurfaceVoid = Color(0xFF08091A)
    val SurfaceBase = Color(0xFF120A22)
    val SurfaceRaised = Color(0xFF1A1228)
    val SurfaceFloat = Color(0xFF231733)
    val SurfaceOverlay = Color(0xFF2D1F40)

    // Brand accents.  Primary is the gradient anchor (purple).
    // Bright is the magenta high end for hover/focus emphasis.
    // Glow + secondary lean into the cyan low end for highlights.
    val AccentPrimary = Color(0xFFA855F7)          // gradient anchor purple
    val AccentPrimaryBright = Color(0xFFEC4899)    // hover / focus emphasis (pink)
    val AccentPrimaryGlow = Color(0x80A855F7)      // slider track / shadow
    val AccentSecondary = Color(0xFF00E5FF)        // cyan accent
    val AccentMagenta = Color(0xFFFF2D92)          // magenta high-energy
    val AccentBlue = Color(0xFF3B82F6)             // blue mid

    // Brand glow, used behind hero artwork / banners.
    val BrandGlow = Color(0xFF5E2D9B)

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
    val GradientVoidEnd = Color(0xFF08091A)         // SurfaceVoid opaque

    // Focus ring / glow.
    val FocusRing = AccentPrimary
    val FocusGlow = Color(0x40A855F7)               // 25 % purple glow

    // Shimmer placeholder colours.
    val ShimmerBase = SurfaceRaised
    val ShimmerHighlight = Color(0xFF2A2040)

    // Certification / badge chip background.
    val ChipSurface = Color(0xFF1E1430)
    val ChipBorder = Color(0xFF3A2A58)
}
