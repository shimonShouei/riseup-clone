package com.riseup.clone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tabular figures ("tnum") everywhere money appears, so columns of shekel
 * amounts align and the headline number doesn't jitter.
 */
const val TABULAR_FIGURES = "tnum"

val MoneyLarge = TextStyle(
    fontSize = 40.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

val MoneyMedium = TextStyle(
    fontSize = 20.sp,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = TABULAR_FIGURES,
)

val MoneySmall = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = TABULAR_FIGURES,
)

val RiseUpTypography = Typography()
