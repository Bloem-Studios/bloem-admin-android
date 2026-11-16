package com.bloemstudios.admin.users

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listUserProfiles
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.users.detail.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An account as a filter offers it: the name to pick by and the email that tells two similar names apart. */
data class UserChoice(val id: String, val username: String, val email: String)

/**
 * The accounts and one account's profiles, for the filters that pick a person by name instead of by number
 * (Playback history, Audit). The account list is read once per server and kept; a different server drops it first, so
 * one server's names are never offered for another.
 */
class UserDirectoryViewModel : ViewModel() {
    private val _users = MutableStateFlow<List<UserChoice>>(emptyList())
    val users: StateFlow<List<UserChoice>> = _users
    private val _usersError = MutableStateFlow<ErrorText?>(null)
    val usersError: StateFlow<ErrorText?> = _usersError

    private val _profiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val profiles: StateFlow<List<UserProfile>> = _profiles
    private val _profilesError = MutableStateFlow<ErrorText?>(null)
    val profilesError: StateFlow<ErrorText?> = _profilesError

    private val mutex = Mutex()
    private var usersLoadedFor: ApiClient? = null
    private var profilesFor: Pair<ApiClient, String>? = null

    /** Reads every account (all pages) unless this client's list is already here; a failed read is tried again on the next call. */
    suspend fun loadUsers(client: ApiClient) = mutex.withLock {
        if (usersLoadedFor === client) return@withLock
        if (usersLoadedFor != null) _users.value = emptyList()
        try {
            _users.value = client.listUsers()
                .map { UserChoice(it.id, it.username, it.email) }
                .sortedBy { it.username.lowercase() }
            _usersError.value = null
            usersLoadedFor = client
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            usersLoadedFor = null
            _users.value = emptyList()
            _usersError.value = if (e is ApiError) e.toErrorText(R.string.error_could_not_load_users) else ErrorText.Res(R.string.error_could_not_load_users)
        }
    }

    /** Reads [userId]'s profiles; null clears them. */
    suspend fun loadProfiles(client: ApiClient, userId: String?) = mutex.withLock {
        if (userId == null) {
            profilesFor = null
            _profiles.value = emptyList()
            _profilesError.value = null
            return@withLock
        }
        if (profilesFor?.first === client && profilesFor?.second == userId) return@withLock
        _profiles.value = emptyList()
        try {
            _profiles.value = client.listUserProfiles(userId)
            _profilesError.value = null
            profilesFor = client to userId
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            profilesFor = null
            _profilesError.value = if (e is ApiError) e.toErrorText(R.string.error_could_not_load_profiles) else ErrorText.Res(R.string.error_could_not_load_profiles)
        }
    }
}
