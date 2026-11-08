package com.kguard.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * K-Guard brand palette. Light-only by product decision (overrides DESIGN.md's dark-canvas
 * spec — a parental-control app must be readable in daylight, at a glance, by parents).
 * Same structure as the Framer system, inverted: white canvas, charcoal-black ink,
 * light-gray surfaces, black pill CTAs, one accent blue used strictly for signals.
 * Everything else in the app must reference these or MaterialTheme — never inline hex.
 */

// Anchor surfaces
val Canvas = Color(0xFFFFFFFF)      // background
val Surface1 = Color(0xFFF5F5F5)    // cards, secondary pill fill
val Surface2 = Color(0xFFEBEBEB)    // featured cards, selected states
val Hairline = Color(0xFFE5E5E5)    // 1dp borders, dividers
val HairlineSoft = Color(0xFFF0F0F0)

// Text
val Ink = Color(0xFF0A0A0A)
val InkMuted = Color(0xFF666666)

// The single chromatic accent: links, focus rings, selection. Never a fill.
val AccentBlue = Color(0xFF0099FF)

// Gradient spotlight cards — scarce by design (see DESIGN.md); hero moments only.
val GradientViolet = Color(0xFF6A4CF5)
val GradientMagenta = Color(0xFFD44DF0)
val GradientOrange = Color(0xFFFF7A3D)
val GradientCoral = Color(0xFFFF5577)

// Semantic signals (safety-app needs, used as glyph/badge color, not surface)
val SignalSuccess = Color(0xFF16A34A)
val SignalWarning = Color(0xFFD97706)
val SignalDanger = Color(0xFFDC2626)
val SignalInfo = AccentBlue

// Google mark colours for the auth button
val GoogleBlue = Color(0xFF4285F4)
