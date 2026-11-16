package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.deleteUserSettingValue
import com.bloemstudios.admin.network.api.getSettingsContract
import com.bloemstudios.admin.network.api.listLibraries
import com.bloemstudios.admin.network.api.listUserProfiles
import com.bloemstudios.admin.network.api.listUserDevices
import com.bloemstudios.admin.network.api.listUserSettingValues
import com.bloemstudios.admin.network.api.setUserSettingValue
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.users.UserDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

internal sealed interface PreferencesState {
    data object Loading : PreferencesState
    data class Failed(val message: ErrorText) : PreferencesState

    /**
     * [definitions] is null when the server's settings contract could not be read; every value is then shown but
     * none is edited, because without its type a save could not keep the value's JSON type.
     */
    data class Ready(
        val groups: List<PreferenceLevelGroup>,
        val definitions: List<SettingDefinition>?
    ) : PreferencesState {
        val levels: List<PreferenceLevel> get() = groups.flatMap { it.levels }
        fun definition(key: String): SettingDefinition? = definitions?.firstOrNull { it.key == key }
    }
}

/**
 * State of one open Preferences tab: what the account's people saved in their apps, by level, and the writes
 * that change it. Every write addresses one row by its full identity and goes through the page's own client,
 * so in All servers it reaches only the server the page names. After a write the settings are read again.
 */
internal class UserPreferencesViewModel(
    private val admin: UserAdmin,
    private val userId: String,
    /** Whether the server lists the account's devices (names for the device levels). */
    private val devicesAvailable: Boolean
) {
    private val _state = MutableStateFlow<PreferencesState>(PreferencesState.Loading)
    val state: StateFlow<PreferencesState> = _state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error

    fun dismissError() { _error.value = null }

    private var profiles: List<UserProfile> = emptyList()
    private var devices: List<UserDevice> = emptyList()
    private var libraryNames: Map<String, String> = emptyMap()
    private var definitions: List<SettingDefinition>? = null
    private var entries: List<UserSettingEntry> = emptyList()

    /** Reads everything; the stored settings are required, the names and the contract are best effort. */
    suspend fun load() {
        val client = admin.client
        try {
            entries = client.listUserSettingValues(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value !is PreferencesState.Ready) _state.value = PreferencesState.Failed(failure(e, R.string.user_prefs_load_failed))
            return
        }
        profiles = best { client.listUserProfiles(userId) } ?: profiles
        if (devicesAvailable) devices = best { client.listUserDevices(userId) } ?: devices
        libraryNames = best { client.listLibraries() }?.associate { it.id to it.name } ?: libraryNames
        definitions = best { client.getSettingsContract().definitions } ?: definitions
        publish()
    }

    private suspend fun <T> best(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun publish() {
        val order = definitions.orEmpty().withIndex().associate { (index, def) -> def.key to index }
        _state.value = PreferencesState.Ready(buildPreferenceLevels(entries, profiles, devices, libraryNames, order), definitions)
    }

    private fun failure(e: Exception, fallback: Int): ErrorText = when {
        e is ApiError -> admin.serverName?.let { e.toErrorText(it, fallback) } ?: e.toErrorText(fallback)
        else -> ErrorText.Res(fallback)
    }

    private suspend fun write(block: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        _busy.value = true
        _error.value = null
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = failure(e, R.string.user_prefs_write_failed)
            return false
        } finally {
            // Whatever happened, the list shows what the server has.
            try {
                entries = admin.client.listUserSettingValues(userId)
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            _busy.value = false
        }
    }

    /** Stores [value] for [key] at the level's own identity (or the row's, when editing a stored one). */
    suspend fun set(key: String, identity: SettingIdentity, value: JsonElement): Boolean =
        write { admin.client.setUserSettingValue(userId, key, identity, value) }

    /** Adds [keys] to [level], each starting at the value it replaces. A retry never overwrites what already landed. */
    suspend fun add(level: PreferenceLevel, keys: List<String>): Boolean {
        val ready = _state.value as? PreferencesState.Ready ?: return false
        val profileEntries = ready.levels.firstOrNull { it.kind == LevelKind.Profile && it.profileId == level.profileId }?.entries.orEmpty()
        val stored = level.entries.map { it.key }.toSet()
        return write {
            for (key in keys.filter { it !in stored }) {
                val definition = ready.definition(key) ?: continue
                val start = startingValue(replacedValue(level, key, profileEntries, definition), definition)
                admin.client.setUserSettingValue(userId, key, level.identity, start)
            }
        }
    }

    suspend fun remove(entry: UserSettingEntry): Boolean =
        write { admin.client.deleteUserSettingValue(userId, entry.key, entry.identity()) }
}
