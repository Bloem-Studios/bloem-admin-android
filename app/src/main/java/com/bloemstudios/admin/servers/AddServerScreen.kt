package com.bloemstudios.admin.servers

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.FullScreenDialogSystemBars
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bloemstudios.admin.auth.OAuthBrowser
import com.bloemstudios.admin.auth.OAuthRedirects

@Composable
fun AddServerScreen(store: ServerStore, onDismiss: () -> Unit) {
    AddServerForm(store, onDismissRequest = onDismiss)
}

@Composable
private fun AddServerForm(store: ServerStore, onDismissRequest: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf<DiscoveryResult.Discovered?>(null) }
    var rejection by remember { mutableStateOf<AddServerResult.Rejected?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connector = remember { AddServerConnector() }
    var providerName by remember { mutableStateOf<String?>(null) }
    // The provider sign-in lives in the activity, not here: this screen leaving composition (the app
    // lock engaging while the browser is open) does not cancel it. It reports back while composed.
    val providerSignIn = rememberProviderSignIn(
        target = SignInTarget.AddServer,
        onSucceeded = onDismissRequest,
        onRejected = { failure, name -> rejection = failure; providerName = name },
    )
    // Cancel, Change server and dismiss end a waiting provider sign-in; success calls onDismissRequest directly.
    val onDismiss = { providerSignIn.cancel(); onDismissRequest() }
    val busy = isConnecting || providerSignIn.isConnecting
    // Step one moves on by itself once a finished-looking address is typed; Continue checks at once.
    val addressChecker = remember(connector, scope) {
        AddressChecker(scope, connector::discover, onResult = { result ->
            when (result) {
                is DiscoveryResult.Discovered -> {
                    username = ""
                    password = ""
                    discovered = result
                }
                is DiscoveryResult.Rejected -> rejection = AddServerResult.Rejected(result.reason)
            }
        })
    }
    val errorMessage = rejection?.let { addServerFailureMessage(it.reason, it.detail, providerName) }

    suspend fun finish(result: AddServerResult) {
        when (result) {
            is AddServerResult.Connected -> {
                when (val persisted = persistConnectedServer(result) { server, accessToken, refreshToken ->
                    store.addServer(server, accessToken, refreshToken)
                }) {
                    is AddServerResult.Connected -> onDismissRequest()
                    is AddServerResult.Rejected -> rejection = persisted
                }
            }
            is AddServerResult.Rejected -> rejection = result
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenDialogSystemBars()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(VeilColors.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxWidth < 600.dp
            val wideSurfaceWidth = minOf(
                560.dp,
                maxWidth - (VeilSpacing.ExtraLarge * 2),
            )
            // The page is the canvas edge to edge, behind the system bars; what is on it stays inside them.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (compact) {
                            Modifier
                        } else {
                            Modifier.pointerInput(onDismiss) {
                                detectTapGestures { onDismiss() }
                            }
                        },
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = (if (compact) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .width(wideSurfaceWidth)
                            // All the room inside the system bars and a margin, up to 720 dp: on a short landscape window
                            // the card would otherwise run under the navigation bar.
                            .padding(vertical = VeilSpacing.Large)
                            .fillMaxHeight()
                            .heightIn(max = 720.dp)
                    }).then(
                        if (compact) {
                            Modifier
                        } else {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures { /* Consume taps inside the bounded sheet. */ }
                            }
                        },
                    ),
                    color = if (compact) VeilColors.Canvas else VeilColors.Raised,
                    contentColor = VeilColors.Text,
                    shape = if (compact) RectangleShape else RoundedCornerShape(VeilShapes.LargeSurfaceCorner),
                ) {
                    val current = discovered
                    if (current == null) {
                        AddServerAddressStep(
                            url = url,
                            onUrlChange = {
                                url = it
                                rejection = null
                                addressChecker.addressChanged(it)
                            },
                            errorMessage = errorMessage,
                            isChecking = addressChecker.isChecking,
                            compact = compact,
                            onDismiss = onDismiss,
                            onContinue = {
                                rejection = null
                                addressChecker.checkNow(url)
                            },
                        )
                    } else {
                        SignInOptionsStep(
                            serverName = current.displayName,
                            providers = current.providers,
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            errorMessage = errorMessage,
                            isConnecting = busy,
                            connectingProviderId = providerSignIn.providerId,
                            compact = compact,
                            onChangeServer = {
                                providerSignIn.cancel()
                                discovered = null
                                rejection = null
                                username = ""
                                password = ""
                            },
                            onDismiss = onDismiss,
                            onPasswordSignIn = {
                                scope.launch {
                                    isConnecting = true
                                    rejection = null
                                    try {
                                        finish(connector.signInWithPassword(current, username, password))
                                    } finally {
                                        isConnecting = false
                                    }
                                }
                            },
                            onProviderSignIn = { provider ->
                                rejection = null
                                providerName = provider.display_name
                                providerSignIn.start(
                                    connector = connector,
                                    discovered = current,
                                    provider = provider,
                                    probeAdmin = true,
                                    persist = { server, accessToken, refreshToken ->
                                        store.addServer(server, accessToken, refreshToken)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
