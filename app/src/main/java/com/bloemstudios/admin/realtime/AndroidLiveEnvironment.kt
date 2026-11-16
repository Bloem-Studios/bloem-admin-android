package com.bloemstudios.admin.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The real device: the app is in front while any of its activities is started, and the default network is watched only
 * while someone collects [networkChanges] (a socket is open), so nothing listens in the background.
 */
class AndroidLiveEnvironment(context: Context) : LiveEnvironment {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val front = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = front

    init {
        // Lifecycle observers are added on the main thread: Application.onCreate is that.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { front.value = true }
            override fun onStop(owner: LifecycleOwner) { front.value = false }
        })
    }

    override val networkChanges: Flow<Unit> = callbackFlow {
        var current: Network? = null
        var lost = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The first call names the network the app is already on. A different one, or the same one back after a
                // loss, is a change.
                val changed = (current != null && current != network) || lost
                current = network
                lost = false
                if (changed) trySend(Unit)
            }
            override fun onLost(network: Network) {
                if (network == current) { current = null; lost = true }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(callback) } catch (_: RuntimeException) { /* no network state: nothing to react to */ }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
}
