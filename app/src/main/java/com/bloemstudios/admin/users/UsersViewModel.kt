package com.bloemstudios.admin.users

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createUser
import com.bloemstudios.admin.network.api.deleteUser
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.network.api.updateUser
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Pure, UI-free filter used by UsersScreen's search field. The user list is
// read in full (every cursor page), so this only narrows the already-loaded
// list client-side; it never triggers a new request. Kept as
// a top-level function (not a suspend ViewModel method) so it's trivially
// unit-testable without a Compose/ViewModel harness.
fun filterUsers(users: List<AdminUser>, query: String): List<AdminUser> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return users
    return users.filter { user ->
        user.username.contains(trimmed, ignoreCase = true) || user.email.contains(trimmed, ignoreCase = true)
    }
}

sealed class CreateUserResult {
    object Success : CreateUserResult()
    object Busy : CreateUserResult()
    data class Failure(val message: ErrorText) : CreateUserResult()
}

sealed class UpdateUserResult {
    object Success : UpdateUserResult()
    object Busy : UpdateUserResult()
    data class Failure(val message: ErrorText) : UpdateUserResult()
}

class UsersViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val _users = MutableStateFlow<List<AdminUser>>(emptyList())
    val users: StateFlow<List<AdminUser>> = _users
    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    val loadError = feedback.loadError
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val users = client.listUsers()
            if (!latestLoad.isCurrent(generation)) return
            _users.value = users
            feedback.load(null)
        } catch (e: ApiError) {
            if (!latestLoad.isCurrent(generation)) return
            feedback.load(e.toErrorText(R.string.error_could_not_load_users))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestLoad.isCurrent(generation)) return
            feedback.load(ErrorText.Res(R.string.error_could_not_load_users))
        }
    }

    // Every user write reads the user fresh and sends that read's ETag as If-Match.
    suspend fun setEnabled(enabled: Boolean, user: AdminUser, client: ApiClient) = act(user.id, R.string.error_could_not_update_user, client) {
        client.withFreshUserVersion(user.id) { validator -> client.updateUser(user.id, enabledBody(enabled), validator) }
    }
    suspend fun delete(user: AdminUser, client: ApiClient) = act(user.id, R.string.error_could_not_delete_user, client) {
        client.withFreshUserVersion(user.id) { validator -> client.deleteUser(user.id, validator) }
    }
    private suspend fun act(key: String, errorResource: Int, client: ApiClient, write: suspend () -> Unit): ActionResult =
        operations.runResult(key, ActionResult.Busy) {
            val generation = feedback.begin()
            // A conflict means the list on screen is out of date, so it reloads; the write is never retried.
            var reload = false
            val result = try {
                write()
                feedback.publish(generation, null)
                reload = true
                ActionResult.Success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                reload = e.isUserConflict()
                feedback.publish(generation, userWriteFailure(e, errorResource))
                ActionResult.Failure
            }
            if (reload) load(client)
            result
        }

    // The create response shape was never confirmed live, so this does not
    // decode a typed body -- Unit as the reified success type makes
    // ApiClient.request skip response.body() entirely (see ApiClient.kt).
    // Returns an explicit CreateUserResult rather than relying on the
    // caller to infer success/failure from errorMessage: that shared
    // StateFlow can race with a concurrent load() (e.g. from polling)
    // touching the same flow, so the dialog needs its own signal carrying
    // the server's actual validation message (e.g. on a 400).
    //
    // Deliberately does NOT also write a create failure into _errorMessage:
    // the dialog already renders the returned Failure.message inline, and
    // routing the same failure through the shared banner too meant it would
    // show twice (dialog + screen banner) and then linger on the screen
    // banner until the next successful poll overwrote it.
    suspend fun createUser(username: String, email: String, password: String, role: String, client: ApiClient): CreateUserResult {
        return operations.runResult("create", CreateUserResult.Busy) { try {
            client.createUser(CreateUserRequest(username, email, password, role, create_default_profile = true))
            load(client)
            CreateUserResult.Success
        } catch (e: ApiError) {
            CreateUserResult.Failure(e.toErrorText(R.string.error_could_not_create_user))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CreateUserResult.Failure(ErrorText.Res(R.string.error_could_not_create_user))
        } }
    }

    /** Reads [id] for an edit dialog; the dialog keeps the result's validator with its draft. */
    internal suspend fun loadUserForEdit(id: String, client: ApiClient): UserEditLoad = client.readUserForEdit(id)

    // Sends only changed fields. Clearing an override is represented by an
    // explicit JSON null while untouched fields remain absent.
    // Returns an explicit result rather than routing through _errorMessage
    // for the same race/double-display reason as createUser above -- the
    // edit dialog needs its own signal so a concurrent poll's load() can't
    // stomp it, and a validation failure should only render inline in the
    // dialog, not also in the screen's shared banner.
    //
    // [validator] is the ETag read when the edit dialog opened (see [loadUserForEdit]); it is not re-read here, so an
    // edit someone else made after the dialog opened is refused with a conflict instead of being overwritten.
    suspend fun updateQuotas(user: AdminUser, request: UpdateUserQuotasRequest, validator: String, client: ApiClient): UpdateUserResult {
        return operations.runResult(user.id, UpdateUserResult.Busy) {
            val body = request.toJsonObject()
            try {
                client.updateUser(user.id, body, validator)
                load(client)
                UpdateUserResult.Success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e.isUserConflict()) load(client)
                UpdateUserResult.Failure(userWriteFailure(e, R.string.error_could_not_update_user))
            }
        }
    }

    // Sets the account's password against a fresh read, so the ETag is current. The result is the page's
    // own signal for the same reason as updateQuotas. A password is a credential: it goes only into the
    // request body, never into an error text or a log.
    suspend fun setPassword(user: AdminUser, password: String, requireChange: Boolean, client: ApiClient): UpdateUserResult {
        return operations.runResult(user.id, UpdateUserResult.Busy) {
            try {
                client.withFreshUserVersion(user.id) { validator -> client.updateUser(user.id, passwordBody(password, requireChange), validator) }
                load(client)
                UpdateUserResult.Success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e.isUserConflict()) load(client)
                UpdateUserResult.Failure(userWriteFailure(e, R.string.error_could_not_update_user))
            }
        }
    }

}
