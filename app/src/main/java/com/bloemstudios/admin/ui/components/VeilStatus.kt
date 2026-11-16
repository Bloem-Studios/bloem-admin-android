package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/** How loud a status is. Only exceptions get color; the normal state is plain text or no label at all. */
enum class StatusTone {
    /** The normal state (Playing, Active, Enabled): secondary text. */
    Quiet,

    /** Needs a look but is not broken: Paused, Transcode, Suspended, Signed out. */
    Warning,

    /** A failure: Failed, Stopped by an error. Red means failure and nothing else. */
    Danger,

    /** Live right now (a playing stream, a live socket, a running job): the orange tally dot. */
    Live,
}

/**
 * The orange dot that means live right now. It marks the live stream count, the live socket and a running job,
 * and nothing else in the app. 8dp.
 */
@Composable
fun TallyDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(VeilColors.Tally, CircleShape))
}

/**
 * The one status component: secondary text with an optional 8dp dot. Color marks exceptions only
 * ([StatusTone.Warning], [StatusTone.Danger]); [StatusTone.Live] shows the tally dot beside quiet text.
 * [dotColor] overrides the dot for categories that carry their own color, such as a play method.
 */
@Composable
fun StatusLabel(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Quiet,
    dot: Boolean = tone == StatusTone.Live,
    dotColor: Color? = null,
    /** One line by default; a status that names several things (a restart waiting on three settings) may take more. */
    maxLines: Int = 1,
) {
    val textColor = when (tone) {
        StatusTone.Warning -> VeilColors.Warning
        StatusTone.Danger -> VeilColors.Danger
        else -> VeilColors.Muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (dot) {
            val color = dotColor ?: when (tone) {
                StatusTone.Live -> VeilColors.Tally
                StatusTone.Warning -> VeilColors.Warning
                StatusTone.Danger -> VeilColors.Danger
                StatusTone.Quiet -> VeilColors.Muted
            }
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
