package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.AggregateUsersViewModel
import com.bloemstudios.admin.users.UpdateUserQuotasRequest
import com.bloemstudios.admin.users.UpdateUserResult
import com.bloemstudios.admin.users.UserEditLoad
import com.bloemstudios.admin.users.UsersViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * What the User page needs from the list that opened it. The single-server list and the All servers list
 * each write through their own view model, which keeps their in-flight guards, per-server locks and list
 * reloads; the page itself never knows which server it is on beyond [serverName].
 */
internal interface UserAdmin {
    /** The server's name in the All servers view; null in a single-server view. */
    val serverName: String?

    /** The client of the one server this user lives on, for reads. */
    val client: ApiClient

    /** The list's action failure banner; the page shows it too and clears it when it closes. */
    val actionError: StateFlow<ErrorText?>
    fun dismissActionError()

    suspend fun readUser(id: String): UserEditLoad
    suspend fun setEnabled(user: AdminUser, enabled: Boolean): ActionResult
    suspend fun delete(user: AdminUser): ActionResult
    suspend fun updateAccess(user: AdminUser, request: UpdateUserQuotasRequest, validator: String): UpdateUserResult
    suspend fun setPassword(user: AdminUser, password: String, requireChange: Boolean): UpdateUserResult
}

internal class SingleServerUserAdmin(
    private val viewModel: UsersViewModel,
    override val client: ApiClient
) : UserAdmin {
    override val serverName: String? = null
    override val actionError: StateFlow<ErrorText?> = viewModel.actionError
    override fun dismissActionError() = viewModel.dismissActionError()
    override suspend fun readUser(id: String) = viewModel.loadUserForEdit(id, client)
    override suspend fun setEnabled(user: AdminUser, enabled: Boolean) = viewModel.setEnabled(enabled, user, client)
    override suspend fun delete(user: AdminUser) = viewModel.delete(user, client)
    override suspend fun updateAccess(user: AdminUser, request: UpdateUserQuotasRequest, validator: String) =
        viewModel.updateQuotas(user, request, validator, client)
    override suspend fun setPassword(user: AdminUser, password: String, requireChange: Boolean) =
        viewModel.setPassword(user, password, requireChange, client)
}

internal class AggregateUserAdmin(
    private val viewModel: AggregateUsersViewModel,
    private val server: AdminServer,
    private val clientFor: (AdminServer) -> ApiClient
) : UserAdmin {
    override val serverName: String = server.displayName
    override val client: ApiClient get() = clientFor(server)
    override val actionError: StateFlow<ErrorText?> = viewModel.actionError
    override fun dismissActionError() = viewModel.dismissActionError()
    override suspend fun readUser(id: String) = viewModel.loadUserForEdit(server, id, clientFor)
    override suspend fun setEnabled(user: AdminUser, enabled: Boolean) = viewModel.setEnabled(server, enabled, user, clientFor)
    override suspend fun delete(user: AdminUser) = viewModel.delete(server, user, clientFor)
    override suspend fun updateAccess(user: AdminUser, request: UpdateUserQuotasRequest, validator: String) =
        viewModel.updateQuotas(server, user, request, validator, clientFor)
    override suspend fun setPassword(user: AdminUser, password: String, requireChange: Boolean) =
        viewModel.setPassword(server, user, password, requireChange, clientFor)
}
