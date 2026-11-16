package com.bloemstudios.admin.users

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.createUser
import com.bloemstudios.admin.network.api.deleteUser
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.network.api.updateUser
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import com.bloemstudios.admin.servers.ServerRequestLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aggregate ("All Servers") counterpart to UsersViewModel: one user list per
 * server. Every mutation (enable/disable, delete, create, quota edit)
 * targets exactly one server's client and, like Streams/Jobs/Requests,
 * reloads only that server's section afterward -- never a full re-fan-out --
 * so an action on server A's users can never touch or refresh server B's
 * section.
 *
 * Aggregate mode intentionally does not expose impersonation. Its sibling
 * tabs are administrator-only surfaces, so showing an effective-user banner
 * while those tabs continue to execute administrator calls would create an
 * authority ambiguity.
 */
class AggregateUsersViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<List<AdminUser>>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<List<AdminUser>>>> = _sections

    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    val loadError = feedback.loadError
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    // Polls and actions share a lock only within their own server.
    private val latestLoad = com.bloemstudios.admin.LatestRequestGate()
    private val locks = ServerRequestLocks()

    @Synchronized
    private fun publishSections(sections: List<ServerSectionResult<List<AdminUser>>>) {
        _sections.value = sections
        feedback.load(sections.firstOrNull { it.result.isFailure }?.let { ErrorText.Res(R.string.error_could_not_load_users) })
    }
    @Synchronized
    private fun publishResult(result: ServerSectionResult<List<AdminUser>>, servers: List<AdminServer>? = null) {
        val current = _sections.value
        if (servers == null) publishSections(current.map { if (it.server.id == result.server.id) result else it })
        else {
            val byId = current.associateBy { it.server.id } + (result.server.id to result)
            publishSections(servers.mapNotNull { byId[it.id] })
        }
    }

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = latestLoad.begin()
        publishSections(_sections.value.filter { row -> servers.any { it.id == row.server.id } })
        fanOutToServers(servers, clientFor, locks = locks, onResult = { result ->
            if (latestLoad.isCurrent(generation)) publishResult(result, servers)
        }) { client -> client.listUsers() }
    }

    fun pendingKey(server: AdminServer, user: AdminUser) = "${server.id}:${user.id}"
    suspend fun setEnabled(server: AdminServer, enabled: Boolean, user: AdminUser, clientFor: (AdminServer) -> ApiClient) =
        act(server, pendingKey(server, user), R.string.error_could_not_update_user, clientFor) { client ->
            client.withFreshUserVersion(user.id) { validator -> client.updateUser(user.id, enabledBody(enabled), validator) }
        }
    suspend fun delete(server: AdminServer, user: AdminUser, clientFor: (AdminServer) -> ApiClient) =
        act(server, pendingKey(server, user), R.string.error_could_not_delete_user, clientFor) { client ->
            client.withFreshUserVersion(user.id) { validator -> client.deleteUser(user.id, validator) }
        }
    private suspend fun act(server: AdminServer, key: String, errorResource: Int, clientFor: (AdminServer) -> ApiClient, write: suspend (ApiClient) -> Unit): ActionResult =
        operations.runResult(key, ActionResult.Busy) {
            val generation = feedback.begin()
            locks.forServer(server).withLock {
                var client: ApiClient? = null
                val result = try {
                    client = clientFor(server)
                    write(client)
                    feedback.publish(generation, null)
                    ActionResult.Success
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    feedback.publish(generation, userWriteFailure(e, errorResource, serverName = server.displayName))
                    ActionResult.Failure
                }
                client?.let { reloadOneLocked(server, it) }
                result
            }
        }

    /** Dialog-local result, same reasoning as UsersViewModel.createUser -- avoids racing the shared errorMessage/double display. */
    suspend fun createUser(
        server: AdminServer,
        username: String,
        email: String,
        password: String,
        role: String,
        clientFor: (AdminServer) -> ApiClient
    ): CreateUserResult = operations.runResult("${server.id}:create", CreateUserResult.Busy) { locks.forServer(server).withLock {
        var client: ApiClient? = null
        val result = try {
            client = clientFor(server)
            client.createUser(CreateUserRequest(username, email, password, role, create_default_profile = true))
            CreateUserResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            CreateUserResult.Failure(e.toErrorText(server.displayName, R.string.error_could_not_create_user))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CreateUserResult.Failure(ErrorText.Res(R.string.error_could_not_create_user))
        }
        if (result is CreateUserResult.Success) client?.let { reloadOneLocked(server, it) }
        result
    } }

    /** Reads [id] on [server] for an edit dialog; the dialog keeps the result's validator with its draft. */
    internal suspend fun loadUserForEdit(server: AdminServer, id: String, clientFor: (AdminServer) -> ApiClient): UserEditLoad =
        try { clientFor(server).readUserForEdit(id, server.displayName) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { UserEditLoad.Failed(ErrorText.Res(R.string.error_could_not_load_user)) }

    /** Dialog-local result, same reasoning as UsersViewModel.updateQuotas. The validator is the one the dialog opened with. */
    suspend fun updateQuotas(
        server: AdminServer,
        user: AdminUser,
        request: UpdateUserQuotasRequest,
        validator: String,
        clientFor: (AdminServer) -> ApiClient
    ): UpdateUserResult = operations.runResult(pendingKey(server, user), UpdateUserResult.Busy) { locks.forServer(server).withLock {
        var client: ApiClient? = null
        var conflict = false
        val result = try {
            client = clientFor(server)
            val body = request.toJsonObject()
            client.updateUser(user.id, body, validator)
            UpdateUserResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conflict = e.isUserConflict()
            UpdateUserResult.Failure(userWriteFailure(e, R.string.error_could_not_update_user, serverName = server.displayName))
        }
        if (result is UpdateUserResult.Success || conflict) client?.let { reloadOneLocked(server, it) }
        result
    } }

    /** Same reasoning as UsersViewModel.setPassword: a fresh ETag, a dialog-local result, and the password only in the body. */
    suspend fun setPassword(
        server: AdminServer,
        user: AdminUser,
        password: String,
        requireChange: Boolean,
        clientFor: (AdminServer) -> ApiClient
    ): UpdateUserResult = operations.runResult(pendingKey(server, user), UpdateUserResult.Busy) { locks.forServer(server).withLock {
        var client: ApiClient? = null
        var conflict = false
        val result = try {
            client = clientFor(server)
            client.withFreshUserVersion(user.id) { validator -> client.updateUser(user.id, passwordBody(password, requireChange), validator) }
            UpdateUserResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conflict = e.isUserConflict()
            UpdateUserResult.Failure(userWriteFailure(e, R.string.error_could_not_update_user, serverName = server.displayName))
        }
        if (result is UpdateUserResult.Success || conflict) client?.let { reloadOneLocked(server, it) }
        result
    } }

    fun clearErrorMessage() = dismissActionError()

    /** Retries just [server]'s section without touching any other section. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadOneLocked(server, clientFor(server))
    }

    // Callers must already hold the server lock.
    private suspend fun reloadOneLocked(server: AdminServer, client: ApiClient) {
        val result = runCatching { client.listUsers() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        publishResult(ServerSectionResult(server, result))
    }
}
