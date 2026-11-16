package com.bloemstudios.admin.servers

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

class SensitiveViewModelScope internal constructor(
    val owner: ViewModelStoreOwner,
    val principalGeneration: Long
)

/**
 * Retains feature state across configuration recreation without retaining it
 * across a lock or credential-principal change.
 */
class SensitiveViewModelStoreRegistry {
    private data class Entry(val generation: Long, val store: ViewModelStore)

    private class Owner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun owner(scopeKey: String, principalGeneration: Long): ViewModelStoreOwner {
        val existing = entries[scopeKey]
        if (existing?.generation == principalGeneration) {
            return Owner(existing.store)
        }
        existing?.store?.clear()
        val created = Entry(principalGeneration, ViewModelStore())
        entries[scopeKey] = created
        return Owner(created.store)
    }

    /**
     * Couples the ViewModel owner to the generation Compose must use when
     * restarting effects that populate those ViewModels. Clearing only the
     * store is insufficient: an effect keyed on a stable server/client would
     * otherwise stay running at the same call site and leave the replacement
     * ViewModel in its initial state.
     */
    fun scope(scopeKey: String, principalGeneration: Long): SensitiveViewModelScope =
        SensitiveViewModelScope(owner(scopeKey, principalGeneration), principalGeneration)

    @Synchronized
    fun clearAll() {
        entries.values.forEach { it.store.clear() }
        entries.clear()
    }
}
