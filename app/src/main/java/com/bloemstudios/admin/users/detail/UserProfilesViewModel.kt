package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createUserProfile
import com.bloemstudios.admin.network.api.deleteUserProfile
import com.bloemstudios.admin.network.api.listUserProfiles
import com.bloemstudios.admin.network.api.removeUserProfilePin
import com.bloemstudios.admin.network.api.renameUserProfile
import com.bloemstudios.admin.network.api.setUserProfilePin
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A profile's display name: 1 to 64 characters once trimmed (the server's own rule); null when it is not one. */
internal fun cleanProfileName(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= PROFILE_NAME_MAX }

internal const val PROFILE_NAME_MAX = 64

/** The web admin's PIN rule: exactly four ASCII digits. */
internal fun isValidProfilePin(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

/** What the last change did, for the line above the list. */
internal enum class ProfileNotice { Added, Renamed, PinSet, PinRemoved, Deleted }

/**
 * The Profiles tab of one account in platform mode: the list, and add, rename, set or remove a PIN and delete through
 * `/api/v2/admin/users/{id}/profiles`. A fresh one is remembered per account and server. A write is read back by
 * reading the list again; a refusal leaves the list as it was and keeps the server's own words.
 */
internal class UserProfilesViewModel(private val serverName: String?) {
    private val _profiles = MutableStateFlow<Section<List<UserProfile>>>(Section.Loading)
    val profiles: StateFlow<Section<List<UserProfile>>> = _profiles

    private val _busy = MutableStateFlow(false)

    /** True while a write runs; the tab's buttons wait. */
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val _notice = MutableStateFlow<ProfileNotice?>(null)
    val notice: StateFlow<ProfileNotice?> = _notice

    fun dismissMessages() {
        _error.value = null
        _notice.value = null
    }

    /** Reads the list. A failed read leaves a list already shown. */
    suspend fun load(client: ApiClient, userId: String) {
        try {
            _profiles.value = Section.Loaded(client.listUserProfiles(userId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_profiles.value !is Section.Loaded) _profiles.value = Section.Failed(failure(e, R.string.user_section_failed))
        }
    }

    suspend fun add(client: ApiClient, userId: String, name: String): Boolean {
        val clean = cleanProfileName(name) ?: return false
        return write(client, userId, ProfileNotice.Added) { client.createUserProfile(userId, clean) }
    }

    suspend fun rename(client: ApiClient, userId: String, profileId: String, name: String): Boolean {
        val clean = cleanProfileName(name) ?: return false
        return write(client, userId, ProfileNotice.Renamed) { client.renameUserProfile(userId, profileId, clean) }
    }

    suspend fun setPin(client: ApiClient, userId: String, profileId: String, pin: String): Boolean {
        if (!isValidProfilePin(pin)) return false
        return write(client, userId, ProfileNotice.PinSet) { client.setUserProfilePin(userId, profileId, pin) }
    }

    suspend fun removePin(client: ApiClient, userId: String, profileId: String): Boolean =
        write(client, userId, ProfileNotice.PinRemoved) { client.removeUserProfilePin(userId, profileId) }

    suspend fun delete(client: ApiClient, userId: String, profileId: String): Boolean =
        write(client, userId, ProfileNotice.Deleted) { client.deleteUserProfile(userId, profileId) }

    private suspend fun write(client: ApiClient, userId: String, done: ProfileNotice, send: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        _busy.value = true
        _error.value = null
        _notice.value = null
        try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = failure(e, R.string.user_profiles_action_failed)
            return false
        } finally {
            _busy.value = false
        }
        _notice.value = done
        load(client, userId)
        return true
    }

    private fun failure(e: Exception, fallback: Int): ErrorText =
        if (e is ApiError) (if (serverName == null) e.toErrorText(fallback) else e.toErrorText(serverName, fallback))
        else ErrorText.Res(fallback)
}
