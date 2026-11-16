package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The instant "now" for the support banner. Tests provide their own to move the count-down without waiting. */
val LocalSupportClock: ProvidableCompositionLocal<() -> Instant> = staticCompositionLocalOf { { Instant.now() } }

/** Whole hours and minutes left, rounded up to the next minute so "0 min" is never shown while time remains. */
data class SupportTimeLeft(val hours: Int, val minutes: Int) {
    val isOver: Boolean get() = hours == 0 && minutes == 0
}

fun supportTimeLeft(now: Instant, end: Instant?): SupportTimeLeft {
    if (end == null) return SupportTimeLeft(0, 0)
    val seconds = Duration.between(now, end).seconds
    if (seconds <= 0) return SupportTimeLeft(0, 0)
    val totalMinutes = (seconds + 59) / 60
    return SupportTimeLeft((totalMinutes / 60).toInt(), (totalMinutes % 60).toInt())
}

@Composable
private fun SupportTimeLeft.label(): String = when {
    isOver -> stringResource(R.string.organization_support_banner_ended)
    hours > 0 && minutes == 0 -> stringResource(R.string.organization_support_banner_left, stringResource(R.string.organization_support_time_hours, hours))
    hours > 0 -> stringResource(R.string.organization_support_banner_left, stringResource(R.string.organization_support_time_hours_minutes, hours, minutes))
    else -> stringResource(R.string.organization_support_banner_left, stringResource(R.string.organization_support_time_minutes, minutes))
}

/**
 * Shown above every page of an organization the Owner opened under support access: what this is, how long is left (it ends by
 * itself four hours after opening) and a way to end it now. It is not a notice that goes away. The count updates once a minute
 * while the app is in front.
 */
@Composable
fun SupportAccessBanner(
    endsAt: Instant?,
    ending: Boolean,
    error: ErrorText?,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = LocalSupportClock.current
    var now by remember { mutableStateOf(clock()) }
    // Real time on the main dispatcher while the app is in front (not the composition's clock, which a test's would race ahead).
    ForegroundEffect(endsAt) {
        while (isActive) {
            now = clock()
            delay(TICK_MILLIS)
        }
    }
    val left = supportTimeLeft(now, endsAt)
    val title = stringResource(R.string.organization_support_banner_title)
    val leftText = left.label()
    var confirming by remember { mutableStateOf(false) }
    val description = stringResource(R.string.organization_support_banner_description, leftText)
    Column(
        modifier
            .fillMaxWidth()
            .background(VeilColors.Card, RoundedCornerShape(VeilShapes.CompactCardCorner))
            .border(BorderStroke(1.dp, VeilColors.Warning), RoundedCornerShape(VeilShapes.CompactCardCorner))
            .padding(horizontal = VeilSpacing.Medium, vertical = VeilSpacing.Small)
            .testTag("organization-support-banner"),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                Text(leftText, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, modifier = Modifier.testTag("organization-support-time-left"))
            }
            if (!left.isOver) OutlinedButton(
                onClick = { confirming = true }, enabled = !ending,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-end"),
            ) { Text(stringResource(if (ending) R.string.organization_people_working else R.string.organization_support_end)) }
        }
        Text(stringResource(R.string.organization_support_banner_hint), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        error?.let {
            Text(
                it.resolve(), style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("organization-support-end-error"),
            )
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.organization_support_end_title)) },
        text = { Text(stringResource(R.string.organization_support_end_body)) },
        confirmButton = {
            TextButton(
                onClick = { confirming = false; onEnd() },
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-end-confirm"),
            ) { Text(stringResource(R.string.organization_support_end_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.organization_cancel))
            }
        },
    )
}

/** A banner is a minute-level count: once a minute is enough, and cheap. */
private const val TICK_MILLIS = 30_000L
