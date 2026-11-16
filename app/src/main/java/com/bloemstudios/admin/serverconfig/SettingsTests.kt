package com.bloemstudios.admin.serverconfig

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.AuthTestResult
import com.bloemstudios.admin.network.api.ChannelTestResult
import com.bloemstudios.admin.network.api.EmailTestResult
import com.bloemstudios.admin.network.api.SettingsCheckResult
import com.bloemstudios.admin.network.api.checkSettingsConnection
import com.bloemstudios.admin.network.api.sendTestEmail
import com.bloemstudios.admin.network.api.testServerChannel
import com.bloemstudios.admin.network.api.testSignInProvider
import com.bloemstudios.admin.network.serverSentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TestStep(val label: String, val passed: Boolean, val message: String)

/** The answer to one "is it broken?" test: a clear pass or fail, with the server's own reason. */
sealed interface TestState {
    data object Running : TestState

    /**
     * [reason] is the server's own sentence (null when it sent none). [error] is true when the test itself could not run
     * (no connection, an error answer) as opposed to the thing under test failing.
     */
    data class Finished(
        val passed: Boolean,
        val reason: String? = null,
        val durationMs: Long? = null,
        val steps: List<TestStep> = emptyList(),
        val httpStatus: Int? = null,
        val couldNotRun: Boolean = false,
    ) : TestState
}

private fun String?.sentence(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun EmailTestResult.toTestState() = TestState.Finished(ok, message.sentence(), duration_ms.takeIf { ok })
fun SettingsCheckResult.toTestState() = TestState.Finished(success, message.sentence())
fun ChannelTestResult.toTestState() = TestState.Finished(ok, message.sentence(), duration_ms.takeIf { ok }, httpStatus = http_status.takeIf { it > 0 })
fun AuthTestResult.toTestState() = TestState.Finished(
    ok, reason = steps.firstOrNull { !it.ok }?.message.sentence(),
    steps = steps.map { TestStep(it.label, it.ok, it.message) },
)

/** The test never ran: the server refused or could not be reached. The server's sentence is kept when it sent one. */
fun Throwable.toTestState(): TestState.Finished =
    TestState.Finished(passed = false, reason = (this as? ApiError)?.serverSentence, httpStatus = (this as? ApiError)?.statusCode, couldNotRun = true)

fun isPlausibleEmail(text: String): Boolean {
    val t = text.trim()
    val at = t.indexOf('@')
    return at > 0 && at == t.lastIndexOf('@') && t.substring(at + 1).contains('.') && !t.contains(' ') && !t.endsWith('.')
}

/** Keys of [SettingsTestsViewModel.results]. */
object TestKeys {
    const val EMAIL = "email"
    fun check(kind: String) = "check:$kind"
    fun channel(id: String) = "channel:$id"
    fun signIn(installationId: String) = "signin:$installationId"
}

/** Runs the test actions and holds each one's last answer. One test of a kind runs at a time; none is ever replayed. */
class SettingsTestsViewModel : ViewModel() {
    private val _results = MutableStateFlow<Map<String, TestState>>(emptyMap())
    val results: StateFlow<Map<String, TestState>> = _results

    private suspend fun run(key: String, call: suspend () -> TestState) {
        if (_results.value[key] == TestState.Running) return
        _results.update { it + (key to TestState.Running) }
        val answer = try {
            call()
        } catch (e: CancellationException) {
            _results.update { it - key }
            throw e
        } catch (e: Throwable) {
            e.toTestState()
        }
        _results.update { it + (key to answer) }
    }

    suspend fun sendTestEmail(client: ApiClient, to: String) = run(TestKeys.EMAIL) { client.sendTestEmail(to.trim()).toTestState() }

    /** Checks the server's saved settings for [kind]: nothing is sent as a change. */
    suspend fun checkConnection(client: ApiClient, kind: String) =
        run(TestKeys.check(kind)) { client.checkSettingsConnection(kind, emptyMap(), emptyList()).toTestState() }

    suspend fun testChannel(client: ApiClient, id: String) = run(TestKeys.channel(id)) { client.testServerChannel(id).toTestState() }

    suspend fun testSignIn(client: ApiClient, installationId: String, capabilityId: String) =
        run(TestKeys.signIn(installationId)) { client.testSignInProvider(installationId, capabilityId).toTestState() }

    fun clear(key: String) = _results.update { it - key }
}
