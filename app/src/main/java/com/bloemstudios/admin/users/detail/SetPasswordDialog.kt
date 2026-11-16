package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.UpdateUserResult
import kotlinx.coroutines.launch

/**
 * Sets a password on an account with no password sign-in, such as one a sign-in provider manages (the web's
 * Set password). The server turns the account's password sign-in back on when an admin writes one. The
 * password is held only in this dialog's state and sent in the request body; it is never shown in an error.
 */
@Composable
internal fun SetPasswordDialog(
    user: AdminUser,
    onSubmit: suspend (password: String, requireChange: Boolean) -> UpdateUserResult,
    onDone: (requireChange: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var temporary by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<ErrorText?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ShellInteractionLockEffect(true)
    val problem = passwordProblem(password)

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.user_setpw_title_temporary)) },
        text = {
            Column {
                Text(stringResource(if (user.password_login) R.string.user_setpw_body_password else R.string.user_setpw_body, user.username))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.user_setpw_field)) },
                    supportingText = { Text(stringResource(R.string.user_setpw_hint, user.username)) },
                    singleLine = true,
                    isError = password.isNotEmpty() && problem != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)
                )
                if (password.isNotEmpty()) when (problem) {
                    PasswordProblem.TooShort -> Text(stringResource(R.string.user_setpw_too_short), color = MaterialTheme.colorScheme.error)
                    PasswordProblem.TooLong -> Text(stringResource(R.string.user_setpw_too_long), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                LabeledCheckbox(temporary, stringResource(R.string.user_setpw_temporary), { temporary = it }, enabled = !submitting)
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && !submitting,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        when (val result = onSubmit(password, temporary)) {
                            UpdateUserResult.Busy -> error = ErrorText.Res(R.string.action_in_progress)
                            is UpdateUserResult.Success -> onDone(temporary)
                            is UpdateUserResult.Failure -> error = result.message
                        }
                        submitting = false
                    }
                }
            ) { Text(stringResource(if (submitting) R.string.user_setpw_working else R.string.user_setpw_submit)) }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
