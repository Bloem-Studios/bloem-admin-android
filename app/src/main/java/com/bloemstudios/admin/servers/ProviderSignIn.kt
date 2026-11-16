package com.bloemstudios.admin.servers

import com.bloemstudios.admin.ui.components.TextButton
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.OAuthBrowser
import com.bloemstudios.admin.auth.OAuthRedirects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Which screen a provider sign-in belongs to. */
internal sealed interface SignInTarget {
    data object AddServer : SignInTarget
    data class ReLogin(val serverId: String) : SignInTarget
}

/** A provider sign-in that is waiting for the browser or redeeming its code. */
internal data class RunningSignIn(val target: SignInTarget, val providerId: String, val providerName: String)

/**
 * How a sign-in ended. [rejection] is null on success (the server is saved / the credentials are
 * stored); otherwise it is the failure to show. [providerName] is for the failure copy.
 */
internal class SignInOutcome(
    val id: Long,
    val target: SignInTarget,
    val rejection: AddServerResult.Rejected?,
    val providerName: String?
)

/**
 * Owns the browser sign-in of Add Server and re-login: at most one at a time, started by a screen but
 * living in the activity's scope, so that the screen leaving composition (the app lock engaging while
 * the browser is open, a configuration change) does not cancel it. It runs start -> browser ->
 * [onResume] (the activity-level ON_RESUME) -> redeem -> persist, and publishes an outcome.
 *
 * Only [cancel] (Cancel, Change server, dismiss, leaving the gate) or a newer [start] stops one. An
 * outcome goes to the screen of its [SignInTarget] when one is [attach]ed; otherwise a failure is
 * published as [notice] for the activity to show once, and a success needs no notice (the server
 * appears, the gate becomes authorized). [OAuthRedirects.begin] and [OAuthRedirects.end] are paired
 * around each attempt, and the redirect is taken before `end`.
 *
 * The lambdas given to [start] outlive the screen: they must not capture composition state.
 */
internal class ProviderSignInOwner(private val scope: CoroutineScope) {
    /** Opens the browser for a start URL; device tests replace it so no real browser opens. */
    var openBrowser: (host: Context?, url: String) -> Boolean = { host, url ->
        host != null && OAuthBrowser.open(host, url)
    }

    private val _running = MutableStateFlow<RunningSignIn?>(null)
    val running: StateFlow<RunningSignIn?> = _running

    /**
     * An Add Server sign-in saved its server while its screen was gone (behind the app lock): the Add
     * Server dialog the lock took away is closed by whoever composes next, then [consumeAddServerSucceeded].
     */
    private val _addServerSucceeded = MutableStateFlow(false)
    val addServerSucceeded: StateFlow<Boolean> = _addServerSucceeded

    fun consumeAddServerSucceeded() {
        _addServerSucceeded.value = false
    }

    /** An outcome (a success has no rejection) for an attached screen to act on and [consumeScreenOutcome]. */
    private val _screenOutcome = MutableStateFlow<SignInOutcome?>(null)
    val screenOutcome: StateFlow<SignInOutcome?> = _screenOutcome

    /** A failure that landed while its screen was gone, for the activity to show once. */
    private val _notice = MutableStateFlow<SignInOutcome?>(null)
    val notice: StateFlow<SignInOutcome?> = _notice

    private var job: Job? = null
    private var jobTarget: SignInTarget? = null
    private var redirectWaiter: CompletableDeferred<String?>? = null
    private val attached = mutableMapOf<SignInTarget, Int>()
    private var nextOutcomeId = 0L

    /** The screen of [target] is composed. Main thread only. */
    fun attach(target: SignInTarget) {
        attached[target] = (attached[target] ?: 0) + 1
    }

    /** The screen of [target] left composition. Its unshown failure moves to the activity notice. */
    fun detach(target: SignInTarget) {
        val remaining = (attached[target] ?: 1) - 1
        if (remaining > 0) attached[target] = remaining else attached.remove(target)
        if (remaining <= 0) {
            _screenOutcome.value?.takeIf { it.target == target }?.let {
                _screenOutcome.value = null
                if (it.rejection != null) _notice.value = it
            }
        }
    }

    fun consumeScreenOutcome(id: Long) {
        if (_screenOutcome.value?.id == id) _screenOutcome.value = null
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** The activity resumed: completes a waiting sign-in; null means the person came back without signing in. */
    fun onResume() {
        // takeForSignIn() must run before anything calls end().
        redirectWaiter?.takeIf { it.isActive }?.complete(OAuthRedirects.takeForSignIn())
    }

    /** Cancels the sign-in of [target], if that is the one in progress. */
    fun cancel(target: SignInTarget) {
        if (jobTarget == target) job?.cancel()
    }

    /** Completes when the current sign-in has ended. */
    internal suspend fun join() {
        job?.join()
    }

    /**
     * Starts a provider sign-in, first cancelling and joining any earlier one (its `finally` ends its
     * redirect wait before this one begins). [host] opens the browser and is held weakly.
     * [persist] stores a connected server and may throw; the failure becomes a STORAGE_FAILED outcome.
     */
    fun start(
        host: Context?,
        target: SignInTarget,
        connector: AddServerConnector,
        discovered: DiscoveryResult.Discovered,
        provider: AuthProvider,
        probeAdmin: Boolean,
        persist: suspend (AdminServer, String, String) -> Unit
    ) {
        val hostRef = WeakReference(host)
        val previous = job
        _screenOutcome.value = null
        jobTarget = target
        _addServerSucceeded.value = false
        job = scope.launch {
            previous?.cancelAndJoin()
            _running.value = RunningSignIn(target, provider.id, provider.display_name)
            try {
                var result = connector.signInWithProvider(
                    discovered = discovered,
                    provider = provider,
                    openBrowser = { startUrl ->
                        OAuthRedirects.begin()
                        openBrowser(hostRef.get(), startUrl)
                    },
                    probeAdmin = probeAdmin,
                    awaitRedirect = {
                        val waiter = CompletableDeferred<String?>()
                        redirectWaiter = waiter
                        try {
                            waiter.await()
                        } finally {
                            redirectWaiter = null
                        }
                    }
                )
                if (result is AddServerResult.Connected) result = persistConnectedServer(result, persist)
                publish(
                    SignInOutcome(
                        id = ++nextOutcomeId,
                        target = target,
                        rejection = result as? AddServerResult.Rejected,
                        providerName = provider.display_name
                    )
                )
            } finally {
                OAuthRedirects.end()
                _running.value = null
            }
        }
    }

    private fun publish(outcome: SignInOutcome) {
        val rejection = outcome.rejection
        val screenIsShown = (attached[outcome.target] ?: 0) > 0
        when {
            // Coming back without signing in shows nothing.
            rejection?.reason == AddServerFailure.OAUTH_CANCELLED -> Unit
            screenIsShown -> _screenOutcome.value = outcome
            rejection != null -> _notice.value = outcome
            // A success with no screen needs no notice: the gate becomes authorized, the server appears.
            outcome.target == SignInTarget.AddServer -> _addServerSucceeded.value = true
        }
    }
}

/** The activity-scoped holder, so the owner survives configuration changes. */
internal class ProviderSignInHolder : ViewModel() {
    val owner = ProviderSignInOwner(viewModelScope)
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The hosting activity's [ProviderSignInOwner]; MainActivity and every screen get the same instance. */
@Composable
internal fun rememberProviderSignInOwner(): ProviderSignInOwner {
    val activity = checkNotNull(LocalContext.current.findActivity()) { "provider sign-in needs an activity" }
    return viewModel<ProviderSignInHolder>(viewModelStoreOwner = activity).owner
}

/** One screen's view of the owner: whether its own sign-in is running, and start/cancel for it. */
internal class ProviderSignIn(
    private val owner: ProviderSignInOwner,
    private val host: Context,
    private val target: SignInTarget,
    running: RunningSignIn?
) {
    private val mine = running?.takeIf { it.target == target }
    val isConnecting: Boolean = mine != null
    /** The provider a waiting sign-in uses. */
    val providerId: String? = mine?.providerId

    fun start(
        connector: AddServerConnector,
        discovered: DiscoveryResult.Discovered,
        provider: AuthProvider,
        probeAdmin: Boolean,
        persist: suspend (AdminServer, String, String) -> Unit
    ) = owner.start(host, target, connector, discovered, provider, probeAdmin, persist)

    /** Explicit Cancel, Change server or dismiss. Leaving composition does not call this. */
    fun cancel() = owner.cancel(target)
}

/**
 * The screen of [target]'s handle on the activity's sign-in. The screen is attached while composed;
 * a result that lands then comes to [onSucceeded] or [onRejected]; a failure that lands after the screen
 * left composition is shown once by the activity after unlock.
 */
@Composable
internal fun rememberProviderSignIn(
    target: SignInTarget,
    onSucceeded: () -> Unit,
    onRejected: (AddServerResult.Rejected, providerName: String?) -> Unit
): ProviderSignIn {
    val owner = rememberProviderSignInOwner()
    val host = LocalContext.current
    val running by owner.running.collectAsState()
    val outcome by owner.screenOutcome.collectAsState()
    val latestOnSucceeded by rememberUpdatedState(onSucceeded)
    val latestOnRejected by rememberUpdatedState(onRejected)
    DisposableEffect(owner, target) {
        owner.attach(target)
        onDispose { owner.detach(target) }
    }
    LaunchedEffect(outcome) {
        outcome?.takeIf { it.target == target }?.let {
            owner.consumeScreenOutcome(it.id)
            val rejection = it.rejection
            if (rejection == null) latestOnSucceeded() else latestOnRejected(rejection, it.providerName)
        }
    }
    return ProviderSignIn(owner, host, target, running)
}

/**
 * Completes a waiting provider sign-in at the activity's ON_RESUME, taking its redirect first.
 * Composed at the top of the activity's content, outside the app lock, so it is never removed by it.
 */
@Composable
internal fun ProviderSignInResumeEffect(owner: ProviderSignInOwner) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { owner.onResume() }
}

/**
 * Shows, once, a failure that landed while its screen was gone (for example behind the app lock).
 * It must be composed where the lock lets content through, so nothing shows before unlock.
 */
@Composable
internal fun ProviderSignInNotice(owner: ProviderSignInOwner) {
    val notice by owner.notice.collectAsState()
    val current = notice ?: return
    val rejection = current.rejection ?: return
    val message = addServerFailureMessage(rejection.reason, rejection.detail, current.providerName) ?: return
    AlertDialog(
        onDismissRequest = owner::dismissNotice,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = owner::dismissNotice) { Text(stringResource(R.string.action_close)) }
        }
    )
}
