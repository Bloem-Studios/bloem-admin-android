package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing

internal enum class VeilStateSurfaceKind { Loading, Empty, Failure, PartialFailure }

internal data class VeilStateSurfaceAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
internal fun VeilStateSurface(
    kind: VeilStateSurfaceKind,
    title: String,
    message: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    action: VeilStateSurfaceAction? = null,
) {
    if (kind == VeilStateSurfaceKind.Loading) {
        LoadingStateSurface(
            stateDescription = stateDescription,
            modifier = modifier,
        )
        return
    }

    val tag = when (kind) {
        VeilStateSurfaceKind.Empty -> "state-empty"
        VeilStateSurfaceKind.Failure -> "state-failure"
        VeilStateSurfaceKind.PartialFailure -> "state-partialfailure"
        VeilStateSurfaceKind.Loading -> "state-loading"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = VeilColors.Card,
                shape = RoundedCornerShape(VeilShapes.PrimaryCardCorner),
            )
            .border(BorderStroke(1.dp, VeilColors.Hairline), RoundedCornerShape(VeilShapes.PrimaryCardCorner))
            .semantics { this.stateDescription = stateDescription }
            .testTag(tag)
            .padding(VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        // A failure names itself in the danger color, a partial failure in the warning color; an empty state is plain.
        Text(
            text = title,
            color = when (kind) {
                VeilStateSurfaceKind.Failure -> VeilColors.Danger
                VeilStateSurfaceKind.PartialFailure -> VeilColors.Warning
                else -> VeilColors.Text
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (message.isNotBlank()) Text(
            text = message,
            color = VeilColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        action?.let {
            // Try again is a secondary button (raised fill), not a bare word: the one accent fill is the page's primary action.
            OutlinedButton(
                onClick = it.onClick,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
            ) {
                Text(it.label)
            }
        }
    }
}

@Composable
private fun LoadingStateSurface(
    stateDescription: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(192.dp)
            .background(
                color = VeilColors.Card,
                shape = RoundedCornerShape(VeilShapes.PrimaryCardCorner),
            )
            .border(BorderStroke(1.dp, VeilColors.Hairline), RoundedCornerShape(VeilShapes.PrimaryCardCorner))
            .semantics { this.stateDescription = stateDescription }
            .testTag("state-loading")
            .padding(VeilSpacing.Large),
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingBar(tag = "state-loading-bar-1", widthFraction = 0.84f)
        Spacer(Modifier.height(VeilSpacing.Medium))
        LoadingBar(tag = "state-loading-bar-2", widthFraction = 0.58f)
        Spacer(Modifier.height(VeilSpacing.Medium))
        LoadingBar(tag = "state-loading-bar-3", widthFraction = 0.72f)
    }
}

@Composable
private fun LoadingBar(
    tag: String,
    widthFraction: Float,
) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(VeilSpacing.CompactHorizontal)
            .background(
                color = VeilColors.Raised,
                shape = RoundedCornerShape(VeilShapes.InnerCorner),
            )
            .testTag(tag),
    )
}
