package com.bloemstudios.admin

import android.app.Application
import android.content.Context
import com.bloemstudios.admin.navigation.ActivityScopePreferences
import com.bloemstudios.admin.lock.AppLockPreferences
import com.bloemstudios.admin.ui.theme.ThemePreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.servers.ServerStoreHolder
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CloseOnceOwner<T : Closeable> : Closeable {
    private val resource = AtomicReference<T?>()
    private val closed = AtomicBoolean(false)

    fun attach(value: T): Boolean {
        check(resource.compareAndSet(null, value)) { "A resource is already attached" }
        if (!closed.get()) return true
        resource.getAndSet(null)?.close()
        return false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) resource.getAndSet(null)?.close()
    }
}

class AdminRootViewModel(application: Application) : AndroidViewModel(application) {
    val appLockPreferences = AppLockPreferences(application.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
    val themePreferences = ThemePreferences(application.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
    val activityScopePreferences = ActivityScopePreferences(application.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
    val sensitiveViewModelStores = SensitiveViewModelStoreRegistry()
    // The store is shared with the background check (see ServerStoreHolder), so this view model holds a lease on it.
    private val owner = CloseOnceOwner<ServerStoreHolder.Lease>()
    private val _store = MutableStateFlow<ServerStore?>(null)
    val store: StateFlow<ServerStore?> = _store

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val lease = ServerStoreHolder.acquire(application.applicationContext)
            if (owner.attach(lease)) _store.value = lease.store
        }
    }

    override fun onCleared() {
        sensitiveViewModelStores.clearAll()
        owner.close()
        super.onCleared()
    }

    fun clearSensitiveState() {
        sensitiveViewModelStores.clearAll()
    }
}
