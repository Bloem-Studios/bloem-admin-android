package com.bloemstudios.admin.automation

import androidx.annotation.StringRes
import com.bloemstudios.admin.InFlightOperations
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A write the page refused before or after sending, with the words to show. */
class ActionRefused(val text: ErrorText) : Exception()

/**
 * Runs one write at a time per key (a second tap on the same action is dropped while the first is unresolved), turns a
 * failure into text the page shows, and reports whether the write worked. The Nodes, Backups and Plugins pages, single
 * server and All servers, share it.
 */
class ActionRunner {
    private val operations = InFlightOperations()
    val busy: StateFlow<Set<String>> = operations.keys
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error

    fun dismiss() { _error.value = null }

    /** [serverName] prefixes the error in All servers, where several servers share one banner. */
    suspend fun run(key: String, @StringRes failure: Int, serverName: String? = null, write: suspend () -> Unit): Boolean =
        operations.runResult(key, false) {
            try {
                write()
                _error.value = null
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionRefused) {
                _error.value = e.text.withServer(serverName)
                false
            } catch (e: ApiError) {
                _error.value = if (serverName != null) e.toErrorText(serverName, failure) else e.toErrorText(failure)
                false
            } catch (_: Exception) {
                _error.value = ErrorText.Res(failure).withServer(serverName)
                false
            }
        }

    private fun ErrorText.withServer(serverName: String?): ErrorText = if (serverName == null) this else ErrorText.Prefixed(serverName, this)
}
