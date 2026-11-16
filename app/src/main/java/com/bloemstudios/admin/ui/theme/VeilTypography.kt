package com.bloemstudios.admin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bloemstudios.admin.R

/**
 * Plus Jakarta Sans (SIL OFL 1.1), the web admin's typeface, bundled as one variable font.
 * The app uses three weights only: 400 for reading text, 500 for labels and row titles, 600 for headings.
 */
@OptIn(ExperimentalTextApi::class)
val PlusJakartaSans: FontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** Turns on the font's tabular (equal-width) figures, so clocks, counts, bitrates and sizes do not jitter as they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun style(weight: FontWeight, size: Int, line: Int) =
    TextStyle(fontFamily = PlusJakartaSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

@Immutable
object VeilTypography {
    val material3 = Typography(
        displayLarge = style(FontWeight.SemiBold, 45, 52),
        displayMedium = style(FontWeight.SemiBold, 36, 44),
        displaySmall = style(FontWeight.SemiBold, 32, 40),
        headlineLarge = style(FontWeight.SemiBold, 28, 36),
        headlineMedium = style(FontWeight.SemiBold, 24, 32),
        headlineSmall = style(FontWeight.SemiBold, 22, 28),
        titleLarge = style(FontWeight.SemiBold, 20, 28),
        titleMedium = style(FontWeight.Medium, 16, 24),
        titleSmall = style(FontWeight.Medium, 14, 20),
        bodyLarge = style(FontWeight.Normal, 16, 24),
        bodyMedium = style(FontWeight.Normal, 14, 20),
        bodySmall = style(FontWeight.Normal, 12, 16),
        labelLarge = style(FontWeight.Medium, 14, 20),
        labelMedium = style(FontWeight.Medium, 12, 16),
        labelSmall = style(FontWeight.Medium, 11, 16),
    )
}
