package com.github.damontecres.wholphin.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

// Sleek, modern geometric sans-serif font family
val sleekFontFamily = FontFamily.SansSerif

// Typeface for use in non-Compose views (like ExoPlayer Subtitles)
val sleekTypeface: Typeface = Typeface.SANS_SERIF

val AppTypography = Typography(
    // Headlines: Strong, impactful, and slightly tightened for a cinematic look
    headlineLarge = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).em
    ),
    headlineMedium = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.02).em
    ),

    // Titles: Clean and modern for your movie and show cards
    titleLarge = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.01).em
    ),
    titleMedium = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = 0.sp
    ),

    // Body: Optimized for readability on a TV screen from the couch
    bodyLarge = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    ),

    // Labels: Small but crisp
    labelLarge = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.em
    ),
    labelMedium = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = sleekFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp // Extra spacing for very small text to keep it sleek
    )
)