package com.bloemstudios.admin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one icon size per role (docs/design.md). Lucide's 2-unit stroke on its 24 grid draws as a 1.5dp line
 * at [Row], the size most icons use.
 */
object LucideSize {
    /** Inline with text, such as a chevron inside a button label. */
    val Inline: Dp = 16.dp

    /** Rows, menus, fields and buttons. */
    val Row: Dp = 18.dp

    /** Top bars. */
    val Bar: Dp = 20.dp
}

/**
 * A Lucide icon (ISC licence, lucide.dev) from `res/drawable/lucide_<name>.xml`. It takes the color of its text role
 * (the surrounding content color unless [tint] says otherwise) and never a color of its own. Add an icon with
 * `scripts/lucide-to-vectordrawable.mjs`; the app ships only the ones it uses.
 */
@Composable
fun LucideIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = LucideSize.Row,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
