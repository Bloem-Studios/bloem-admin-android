package com.bloemstudios.admin.servers

import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.AuthProviders
import com.bloemstudios.admin.auth.isAuthentik
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * The message for a rejected add-server attempt, or null when nothing should be shown (a cancelled
 * sign-in). [detail] of an OAuth failure is server-supplied text: only known reasons are mapped, and
 * anything else gets the generic copy; the raw text is never rendered.
 */
@Composable
internal fun addServerFailureMessage(reason: AddServerFailure, detail: String?, providerName: String?): String? {
    val provider = providerName.orEmpty()
    return when (reason) {
        AddServerFailure.UNSAFE_ORIGIN -> stringResource(R.string.add_server_unsafe_origin)
        AddServerFailure.HEALTH_CHECK_FAILED -> stringResource(R.string.add_server_health_check_failed)
        AddServerFailure.INVALID_CREDENTIALS -> stringResource(R.string.add_server_invalid_credentials)
        AddServerFailure.ADMIN_REQUIRED -> stringResource(R.string.add_server_admin_required)
        AddServerFailure.INCOMPATIBLE_SERVER -> stringResource(R.string.add_server_incompatible_server)
        AddServerFailure.UNREACHABLE -> stringResource(R.string.add_server_unreachable)
        AddServerFailure.STORAGE_FAILED -> stringResource(R.string.add_server_storage_failed)
        AddServerFailure.OAUTH_CANCELLED -> null
        AddServerFailure.CLIENT_UPGRADE_REQUIRED -> stringResource(R.string.error_client_upgrade_required)
        AddServerFailure.OAUTH_UNTRUSTED -> stringResource(R.string.oauth_untrusted)
        AddServerFailure.NO_BROWSER -> stringResource(R.string.oauth_no_browser, provider)
        AddServerFailure.NO_SUPPORTED_SIGN_IN -> stringResource(R.string.add_server_no_sign_in_options)
        AddServerFailure.OAUTH_FAILED -> when (detail) {
            "not_permitted" -> stringResource(R.string.oauth_not_permitted)
            "account_required" -> stringResource(R.string.oauth_account_required)
            "email_in_use" -> stringResource(R.string.oauth_email_in_use)
            "identity_linked_elsewhere" -> stringResource(R.string.oauth_identity_linked_elsewhere)
            "account_disabled" -> stringResource(R.string.oauth_account_disabled)
            "provider_unavailable" -> stringResource(R.string.oauth_provider_unavailable, provider)
            "state_invalid", "session_expired" -> stringResource(R.string.oauth_expired)
            else -> stringResource(R.string.oauth_failed)
        }
    }
}

@Composable
private fun StepShell(
    compact: Boolean,
    title: String,
    guidance: String,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    // The whole step scrolls, footer included: on a short window (a phone in landscape, the keyboard up, a large
    // font) a footer that stayed put would leave the address field a sliver. When everything fits, the footer is
    // pushed to the bottom edge, so a tall window looks as it did.
    BoxWithConstraints(
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .imePadding()
            .padding(
                horizontal = if (compact) VeilSpacing.Large else VeilSpacing.ExtraLarge,
                vertical = VeilSpacing.ExtraLarge,
            ),
    ) {
        val viewportHeight = if (constraints.hasBoundedHeight) maxHeight else Dp.Unspecified
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .padding(top = VeilSpacing.Small)
                    .semantics { heading() },
            )
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodyMedium,
                color = VeilColors.Muted,
                modifier = Modifier.padding(top = VeilSpacing.Small),
            )
            Spacer(Modifier.height(VeilSpacing.Large))
            content()
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = VeilSpacing.Large),
            ) {
                footer()
                Text(
                    text = stringResource(R.string.add_server_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = VeilColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = VeilSpacing.Large),
                )
            }
        }
    }
}

@Composable
private fun ErrorText(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = VeilColors.Danger,
            modifier = Modifier.padding(top = VeilSpacing.CompactHorizontal),
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        shape = MaterialTheme.shapes.small,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VeilSpacing.MinimumTouchTarget),
        colors = ButtonDefaults.buttonColors(
            containerColor = VeilColors.Accent,
            contentColor = VeilColors.OnAccent,
        ),
    ) { Text(text = text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        shape = MaterialTheme.shapes.small,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = VeilSpacing.Medium)
            .heightIn(min = VeilSpacing.MinimumTouchTarget),
    ) { Text(text) }
}

/**
 * Step one of Add Server: the server address. The field stays editable while the address is checked
 * (an automatic check starts when typing pauses, and an edit cancels it), so checking never takes the
 * keyboard away.
 */
@Composable
internal fun AddServerAddressStep(
    url: String,
    onUrlChange: (String) -> Unit,
    errorMessage: String?,
    isChecking: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val canContinue = !isChecking && url.isNotBlank()
    fun submit() { if (canContinue) { focusManager.clearFocus(); onContinue() } }
    StepShell(
        compact = compact,
        title = stringResource(R.string.add_server_title),
        guidance = stringResource(R.string.add_server_guidance),
        content = {
            OutlinedTextField(
                value = url, onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.add_server_url_label)) },
                placeholder = { Text(stringResource(R.string.add_server_url_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            ErrorText(errorMessage)
        },
        footer = {
            PrimaryButton(
                text = stringResource(if (isChecking) R.string.add_server_checking else R.string.add_server_continue),
                enabled = canContinue,
                onClick = ::submit,
            )
            SecondaryButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/**
 * Step two: the sign-in options of one server. Reused for re-login, so the title and guidance are
 * parameters and "Change server" and "Cancel" are optional.
 */
@Composable
internal fun SignInOptionsStep(
    serverName: String,
    providers: AuthProviders,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    errorMessage: String?,
    isConnecting: Boolean,
    /** The provider a waiting sign-in uses; null while connecting means the password. */
    connectingProviderId: String?,
    compact: Boolean,
    onPasswordSignIn: () -> Unit,
    onProviderSignIn: (AuthProvider) -> Unit,
    title: String = stringResource(R.string.add_server_title),
    guidance: String = stringResource(R.string.add_server_choose_sign_in, serverName),
    onChangeServer: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    /** Re-login: the provider this server was last signed in with is listed (and primary) first. */
    rememberedProviderId: String? = null,
    passwordActionLabel: String = stringResource(R.string.add_server_connect),
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val oauthProviders = providers.nativeOAuthFor(rememberedProviderId)
    val hasOptions = providers.password_login || oauthProviders.isNotEmpty()
    val canSubmitPassword = !isConnecting && username.isNotBlank() && password.isNotBlank()
    fun submitPassword() { if (canSubmitPassword) { focusManager.clearFocus(); onPasswordSignIn() } }
    // The remembered provider (re-login), else Authentik, else the default provider is the primary action when it is
    // an OAuth provider; otherwise the password button is.
    val primaryProviderId = (oauthProviders.firstOrNull { it.id == rememberedProviderId }
        ?: oauthProviders.firstOrNull { it.isAuthentik }
        ?: oauthProviders.firstOrNull { it.default })?.id
    StepShell(
        compact = compact,
        title = title,
        guidance = guidance,
        content = {
            oauthProviders.forEachIndexed { index, provider ->
                val label = if (isConnecting && connectingProviderId == provider.id) {
                    stringResource(R.string.login_signing_in)
                } else {
                    stringResource(R.string.oauth_sign_in_with, provider.display_name)
                }
                if (provider.id == primaryProviderId) {
                    PrimaryButton(label, enabled = !isConnecting) { onProviderSignIn(provider) }
                } else {
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        enabled = !isConnecting,
                        onClick = { onProviderSignIn(provider) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else VeilSpacing.Medium)
                            .heightIn(min = VeilSpacing.MinimumTouchTarget),
                    ) { Text(label) }
                }
            }
            if (providers.password_login) {
                OutlinedTextField(
                    value = username, onValueChange = onUsernameChange, enabled = !isConnecting,
                    label = { Text(stringResource(R.string.action_username)) },
                    modifier = Modifier.fillMaxWidth().padding(top = VeilSpacing.Large), singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password, onValueChange = onPasswordChange, enabled = !isConnecting,
                    label = { Text(stringResource(R.string.action_password)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !isConnecting) {
                            Text(stringResource(if (passwordVisible) R.string.login_hide_password else R.string.login_show_password))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = VeilSpacing.Medium), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                )
            }
            if (!hasOptions) {
                Text(
                    text = stringResource(R.string.add_server_no_sign_in_options),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ErrorText(errorMessage)
        },
        footer = {
            if (providers.password_login) {
                val text = if (isConnecting && connectingProviderId == null) stringResource(R.string.login_signing_in) else passwordActionLabel
                if (primaryProviderId == null) {
                    PrimaryButton(text, enabled = canSubmitPassword, onClick = ::submitPassword)
                } else {
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        enabled = canSubmitPassword,
                        onClick = ::submitPassword,
                        modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
                    ) { Text(text) }
                }
            }
            onChangeServer?.let { SecondaryButton(stringResource(R.string.add_server_change_server), enabled = !isConnecting, onClick = it) }
            onDismiss?.let { SecondaryButton(stringResource(R.string.action_cancel), onClick = it) }
        },
    )
}
