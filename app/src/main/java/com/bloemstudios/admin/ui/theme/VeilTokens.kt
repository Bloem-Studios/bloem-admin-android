package com.bloemstudios.admin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object VeilSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val CompactHorizontal = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
    val MinimumTouchTarget = 48.dp
}

object VeilShapes {
    /** Buttons, fields, chips and nav rows: the web's 10. */
    val ControlCorner = 10.dp
    val CompactCardCorner = 12.dp
    val PrimaryCardCorner = 12.dp
    val LargeSurfaceCorner = 16.dp
    /** Marks inside a card, such as a health cell or a keycap: card radius minus its 8dp padding. */
    val InnerCorner = 4.dp

    val material3 = Shapes(
        extraSmall = RoundedCornerShape(InnerCorner),
        small = RoundedCornerShape(ControlCorner),
        medium = RoundedCornerShape(CompactCardCorner),
        large = RoundedCornerShape(PrimaryCardCorner),
        extraLarge = RoundedCornerShape(LargeSurfaceCorner),
    )
}
