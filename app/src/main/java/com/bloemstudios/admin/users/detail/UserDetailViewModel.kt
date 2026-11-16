package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.dashboard.DashboardLibrary
import com.bloemstudios.admin.network.api.getAccountMe
import com.bloemstudios.admin.network.api.getUserPolicyDefaults
import com.bloemstudios.admin.network.api.listAccessGroups
import com.bloemstudios.admin.network.api.listLibraries
import com.bloemstudios.admin.network.api.getUserCapabilities
import com.bloemstudios.admin.network.api.getUserWithValidator
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.UpdateUserQuotasRequest
import com.bloemstudios.admin.users.UpdateUserResult
import com.bloemstudios.admin.users.UserEditLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the User page shows in place of its content until the account is read. */
internal sealed interface UserPageState {
    data object Loading : UserPageState

    /** [validator] is the ETag of this read; the Access tab's form keeps it until it is saved or reloaded. */
    data class Ready(val user: AdminUser, val validator: String?) : UserPageState
    data class Failed(val message: ErrorText) : UserPageState

    /** Another admin deleted the account after the list was read. */
    data object Gone : UserPageState
}

/**
 * State of one open User page. Created per (server, user) with `remember`, like UserSessionsViewModel,
 * so a different user never shows the previous one's data. Reads the account fresh when it opens, and
 * again after every write, so each guarded write carries the version the admin last saw.
 */
internal class UserDetailViewModel(
    private val admin: UserAdmin,
    private val userId: String
) {
    private val _state = MutableStateFlow<UserPageState>(UserPageState.Loading)
    val state: StateFlow<UserPageState> = _state

    private val _viewer = MutableStateFlow<Viewer?>(null)
    val viewer: StateFlow<Viewer?> = _viewer

    private val _capabilities = MutableStateFlow<AdminUserCapabilities?>(null)
    val capabilities: StateFlow<AdminUserCapabilities?> = _capabilities

    /** True while an action of this page is running; buttons that start another one wait. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    // What the Access & limits tab chooses from. Read when the tab is first shown, not when the page opens.
    private val groupsLoader = SectionLoader(admin.serverName) { admin.client.listAccessGroups() }
    private val librariesLoader = SectionLoader(admin.serverName) { admin.client.listLibraries() }
    private val defaultsLoader = SectionLoader(admin.serverName) { admin.client.getUserPolicyDefaults() }
    val groups: StateFlow<Section<List<AccessGroup>>> = groupsLoader.state
    val libraries: StateFlow<Section<List<DashboardLibrary>>> = librariesLoader.state
    val policyDefaults: StateFlow<Section<UserPolicyDefaults>> = defaultsLoader.state

    /** Opens the page: the account, who is looking at it, and what the server supports. */
    suspend fun open() {
        reload()
        loadViewer()
        loadCapabilities()
    }

    /** Reads the account again. A failed re-read leaves the account already on screen. */
    suspend fun reload() {
        when (val read = admin.readUser(userId)) {
            is UserEditLoad.Ready -> _state.value = UserPageState.Ready(read.user, read.validator)
            is UserEditLoad.Failed -> when {
                read.notFound -> _state.value = UserPageState.Gone
                _state.value !is UserPageState.Ready -> _state.value = UserPageState.Failed(read.message)
            }
        }
    }

    // Owner status is part of the viewer's own account, so it is read from there, as the web does.
    private suspend fun loadViewer() {
        try {
            val me = admin.client.getAccountMe()
            val shown = current()
            val owner = try {
                if (shown != null && shown.id == me.id) shown.is_owner
                else admin.client.getUserWithValidator(me.id).first.is_owner
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _viewer.value = Viewer(me.id, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Without a viewer no action is offered; the next open tries again.
        }
    }

    private suspend fun loadCapabilities() {
        try {
            _capabilities.value = admin.client.getUserCapabilities()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unknown capabilities leave core actions available and hide the optional sections.
        }
    }

    /** Reads the access groups, the libraries and the server's policy defaults the Access & limits form chooses from. */
    suspend fun loadAccessChoices() = coroutineScope {
        launch { groupsLoader.load() }
        launch { librariesLoader.load() }
        launch { defaultsLoader.load() }
    }

    private suspend fun <T> busyWhile(block: suspend () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
        }
    }

    private fun current(): AdminUser? = (state.value as? UserPageState.Ready)?.user

    suspend fun setEnabled(enabled: Boolean): ActionResult {
        val user = current() ?: return ActionResult.Busy
        return busyWhile {
            admin.setEnabled(user, enabled).also { reload() }
        }
    }

    /** On success the account is gone, so the page is not read again. */
    suspend fun delete(): ActionResult {
        val user = current() ?: return ActionResult.Busy
        return busyWhile {
            admin.delete(user).also { if (it == ActionResult.Failure) reload() }
        }
    }

    /**
     * Saves the changes against the ETag the page was read with, so an edit someone else made since is
     * refused with a conflict instead of overwritten. After a save or a conflict the account is read
     * again: the form then shows the server's current values and holds its new ETag.
     */
    suspend fun saveAccess(request: UpdateUserQuotasRequest): UpdateUserResult {
        val ready = state.value as? UserPageState.Ready ?: return UpdateUserResult.Busy
        val validator = ready.validator ?: return UpdateUserResult.Busy
        return busyWhile {
            val result = admin.updateAccess(ready.user, request, validator)
            if (result is UpdateUserResult.Success || (result is UpdateUserResult.Failure && result.isConflict)) reload()
            result
        }
    }

    suspend fun setPassword(password: String, requireChange: Boolean): UpdateUserResult {
        val user = current() ?: return UpdateUserResult.Busy
        return busyWhile {
            val result = admin.setPassword(user, password, requireChange)
            if (result is UpdateUserResult.Success) reload()
            result
        }
    }

    fun dismissActionError() = admin.dismissActionError()
}

private val UpdateUserResult.Failure.isConflict: Boolean
    get() = message.isUsersConflict()

private fun ErrorText.isUsersConflict(): Boolean = when (this) {
    is ErrorText.Res -> id == R.string.users_conflict
    is ErrorText.Prefixed -> inner.isUsersConflict()
    else -> false
}
