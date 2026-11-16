package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import androidx.compose.material3.OutlinedTextField as M3OutlinedTextField

/** The theme's field colors: a 3:1 outline at rest, orange when focused, no fill. */
@Composable
fun veilFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = VeilColors.Accent,
    unfocusedIndicatorColor = VeilColors.Outline,
    disabledIndicatorColor = VeilColors.Hairline,
    errorIndicatorColor = VeilColors.Danger,
    cursorColor = VeilColors.Accent,
    focusedLabelColor = VeilColors.Muted,
    unfocusedLabelColor = VeilColors.Muted,
    errorLabelColor = VeilColors.Danger,
    focusedTextColor = VeilColors.Text,
    unfocusedTextColor = VeilColors.Text,
    errorTextColor = VeilColors.Text,
    focusedPlaceholderColor = VeilColors.Muted,
    unfocusedPlaceholderColor = VeilColors.Muted,
    focusedSupportingTextColor = VeilColors.Muted,
    unfocusedSupportingTextColor = VeilColors.Muted,
    errorSupportingTextColor = VeilColors.Danger,
)

/** The app's one text field: outlined, 10dp corners. Every field in the app uses this, never a filled one. */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = MaterialTheme.shapes.small,
    colors: TextFieldColors = veilFieldColors(),
) = M3OutlinedTextField(
    value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon,
    prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine,
    maxLines, minLines, interactionSource, shape, colors,
)

/**
 * A row of mutually exclusive choices, for a few short options (System, Light, Dark; 1h, 24h, 7d).
 * The chosen option has a filled step and the same weight as the others: no bold.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionModifier: (Int) -> Modifier = { Modifier },
) {
    val shape = RoundedCornerShape(VeilShapes.ControlCorner)
    Row(
        modifier
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            Box(
                optionModifier(index)
                    .defaultMinSize(minWidth = 48.dp, minHeight = VeilSpacing.MinimumTouchTarget)
                    .background(if (chosen) VeilColors.Selected else Color.Transparent, RoundedCornerShape(VeilShapes.ControlCorner - 2.dp))
                    .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = VeilSpacing.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) VeilColors.Text else VeilColors.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}
