package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ui.theme.VeilColors
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.TextButton as M3TextButton

// The app's buttons. Material 3 1.2 draws buttons as full pills; the Bloem look uses the theme's 10dp control
// corner everywhere. Screens import these in place of the Material ones, so the names and parameters match.

/** The one filled button on a screen: the primary action, in Bloem orange. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) = M3Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/** A secondary action: a raised fill with text-color text, no outline. The one accent fill on a screen is [Button]. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = VeilColors.Raised,
        contentColor = VeilColors.Text,
        disabledContainerColor = VeilColors.Raised,
        disabledContentColor = VeilColors.Muted,
    ),
    elevation: ButtonElevation? = null,
    // A hairline edge, as on cards: on the light page the raised fill alone is too close to the canvas to read as a button.
    border: BorderStroke? = BorderStroke(1.dp, VeilColors.Hairline),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/**
 * A quiet text action. It is set in the primary text color, not orange: orange marks the one primary action,
 * the selection and live things, so 160 text buttons do not each compete for it.
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = VeilColors.Text),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/** The confirm button of an action that cannot be undone: terminate, remove, forget, restart, revoke, suspend, decline, delete. */
@Composable
fun DestructiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.textButtonColors(contentColor = VeilColors.Danger, disabledContentColor = VeilColors.Muted),
    content = content,
)

/** The confirm button of a dialog: danger-colored when what it confirms cannot be undone, otherwise a plain text button. */
@Composable
fun ConfirmButton(
    onClick: () -> Unit,
    destructive: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = if (destructive) DestructiveTextButton(onClick, modifier, enabled, content) else TextButton(onClick, modifier, enabled, content = content)
