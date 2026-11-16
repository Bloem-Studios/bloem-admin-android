package com.bloemstudios.admin.lock

import androidx.compose.foundation.layout.Arrangement
import com.bloemstudios.admin.ui.components.Button
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import android.app.Activity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.biometric.BiometricManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * Wraps [content] behind a biometric/device-credential gate. Renders a
 * simple "Unlock BloemAdmin" screen until AppLockViewModel.isLocked goes
 * false, then shows [content]. Re-locks on ON_STOP -- observed on BOTH the
 * whole process (ProcessLifecycleOwner) and this composition's own Activity
 * lifecycle, since ProcessLifecycleOwner's ON_STOP has a ~700ms debounce
 * that can miss a very fast app-switch -- with no grace period. See
 * AppLockViewModel for the pure state-transition logic this wires up, and
 * BiometricSupport.shouldBypass for the pure bypass decision.
 */
@Composable
fun AppLockScreen(enabled: Boolean = false, onLocked: () -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val viewModel = viewModel<AppLockViewModel>()
    val isLocked by viewModel.isLocked.collectAsState()
    // While the lock is on, the app switcher shows a cover, not the app; screenshots of the unlocked app stay allowed.
    WindowProtectionEffect(lockOn = enabled, locked = isLocked)
    if (!enabled) {
        // Rearm the optional gate for a later opt-in, without invoking lock callbacks,
        // observing lifecycle transitions, or requesting authentication while disabled.
        SideEffect { viewModel.onProcessStopped() }
        content()
        return
    }
    val message by viewModel.message.collectAsState()
    // Whether the device can show a prompt at all. Set by the check below, once the lock is up.
    var canPrompt by remember { mutableStateOf(false) }
    val hardwareUnavailableMessage = stringResource(R.string.app_lock_hardware_unavailable)
    val promptTitle = stringResource(R.string.app_lock_prompt_title)

    DisposableEffect(viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onProcessStopped()
        }
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
    }

    // Belt-and-suspenders alongside the ProcessLifecycleOwner observer above:
    // this Activity's own lifecycle fires ON_STOP immediately (no debounce),
    // so a background/foreground cycle too quick for ProcessLifecycleOwner
    // to register still re-locks.
    val activityLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, activityLifecycleOwner) {
        val activity = context as? FragmentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onActivityStopped(activity?.isChangingConfigurations == true)
            }
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Devices where a prompt can never succeed (no biometric/PIN/pattern/
    // password enrolled at all, or no hardware) shouldn't permanently lock
    // the app out. Keyed on `isLocked` rather than Unit: LaunchedEffect(Unit)
    // only ever runs once per composition, so after the FIRST re-lock (any
    // background/foreground cycle) it would never re-fire and the bypass
    // would only ever have applied to the very first launch -- leaving the
    // Unlock button permanently dead on an unenrolled device/emulator from
    // then on. Keying on isLocked makes it re-evaluate every time the lock
    // re-engages.
    LaunchedEffect(isLocked) {
        if (isLocked) {
            onLocked()
            val result = BiometricSupport.canAuthenticateResult(context)
            if (BiometricSupport.shouldBypass(result)) {
                canPrompt = false
                viewModel.setMessage(null)
                viewModel.onUnlocked()
            } else if (result != BiometricManager.BIOMETRIC_SUCCESS) {
                canPrompt = false
                viewModel.setMessage(hardwareUnavailableMessage)
            } else {
                canPrompt = true
            }
        }
    }

    // Created with the composition, so that after a rotation or a fold it re-attaches to a prompt that
    // is still showing (AndroidX Biometric hands the answer to the newest callback).
    val activity = context as? FragmentActivity
    val prompt = remember(activity, viewModel) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        viewModel.onUnlocked()
                    }

                    // A cancel or dismissal leaves the lock screen as it is, and the Unlock button
                    // is the retry; a lockout or other error also shows the system's own text.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        viewModel.onPromptError(errorCode, errString)
                    }

                    // A wrong finger: the prompt stays up and counts the attempts itself.
                    override fun onAuthenticationFailed() = Unit
                },
            )
        }
    }
    val showPrompt: () -> Unit = {
        prompt?.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setAllowedAuthenticators(BiometricSupport.AUTHENTICATORS)
                .build(),
        )
    }

    // The prompt opens by itself, once per lock, when the lock screen is resumed AND has window
    // focus: a prompt raised earlier is dropped or crashes on some devices. A cancel does not
    // reopen it (the tracker has already used this lock's turn), and neither does a rotation (the
    // ViewModel outlives the activity).
    val lifecycleState by activityLifecycleOwner.lifecycle.currentStateAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(isLocked, canPrompt, lifecycleState, windowFocused, prompt) {
        if (isLocked && canPrompt && prompt != null && windowFocused &&
            lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && viewModel.takeAutoPrompt()
        ) {
            showPrompt()
        }
    }

    if (isLocked) {
        LockScreen(message = message, onUnlockRequested = {
            if (prompt != null && viewModel.takeManualPrompt()) showPrompt()
        })
    } else {
        content()
    }
}

@Composable
internal fun LockScreen(message: String?, onUnlockRequested: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = VeilColors.Canvas,
        contentColor = VeilColors.Text,
    ) {
        // One group in the middle of the screen, what is locked, a hardware problem if there
        // is one, and Unlock right beneath it. The biometric prompt opens over this by itself; the button is the fallback.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VeilSpacing.Large, vertical = VeilSpacing.ExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.widget_app_mark),
                contentDescription = null,
                tint = VeilColors.Text,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = VeilSpacing.Medium).semantics { heading() },
            )
            Text(
                text = stringResource(R.string.app_lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = VeilColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = VeilSpacing.Small),
            )
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeilColors.Warning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = VeilSpacing.Medium),
                )
            }
            Button(
                onClick = onUnlockRequested,
                modifier = Modifier
                    .padding(top = VeilSpacing.Large)
                    .widthIn(min = 160.dp)
                    .heightIn(min = VeilSpacing.MinimumTouchTarget),
            ) {
                Text(
                    text = stringResource(R.string.app_lock_unlock),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** Applies [WindowProtection] as the app leaves and returns to the screen, not after: the thumbnail is taken on the way out. */
@Composable
private fun WindowProtectionEffect(lockOn: Boolean, locked: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val protection = remember(activity) { WindowProtection(activity) }
    val current by rememberUpdatedState(lockOn to locked)
    DisposableEffect(lifecycleOwner, protection) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> protection.apply(current.first, current.second, resumed = true)
                Lifecycle.Event.ON_PAUSE -> protection.apply(current.first, current.second, resumed = false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    SideEffect { protection.apply(lockOn, locked, resumed) }
}
