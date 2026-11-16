package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AuthProviders
import com.bloemstudios.admin.network.ServerOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step one checks a finished address by itself after a pause in typing. */
class AddressCheckerTest {
    private val asked = mutableListOf<String>()
    private val results = mutableListOf<DiscoveryResult>()
    private var answer: CompletableDeferred<DiscoveryResult>? = null

    private fun discovered(address: String) = DiscoveryResult.Discovered(
        ServerOrigin.parseTyped(address), "Bloem West", "server-id", AuthProviders(true, emptyList())
    )

    private fun TestScope.checker() = AddressChecker(
        scope = backgroundScope,
        discover = { address ->
            asked += address
            answer?.await() ?: discovered(address)
        },
        onResult = { results += it },
    )

    @Test
    fun aFinishedAddressIsCheckedAfterThePauseWithoutATap() = runTest {
        val checker = checker()
        checker.addressChanged("lib.example.test")

        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), asked)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("lib.example.test"), asked)
        assertEquals("https://lib.example.test", (results.single() as DiscoveryResult.Discovered).origin.value)
    }

    @Test
    fun typingDuringThePauseRestartsIt() = runTest {
        val checker = checker()
        checker.addressChanged("lib.example.te")
        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS / 2)
        checker.addressChanged("lib.example.test")
        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), asked)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("lib.example.test"), asked)
    }

    @Test
    fun anUnfinishedAddressIsNotCheckedByItself() = runTest {
        val checker = checker()
        listOf("", "lib", "lib.example.t", "localhost", "10.0.0.5", "http://lib.example.test", "lib.example.test/admin")
            .forEach { checker.addressChanged(it); advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS * 3); runCurrent() }
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<DiscoveryResult>(), results)
    }

    @Test
    fun continueChecksAtOnceEvenAnAddressThatIsNotCheckedByItself() = runTest {
        val checker = checker()
        checker.checkNow("10.0.0.5")
        runCurrent()
        assertEquals(listOf("10.0.0.5"), asked)
        assertEquals(1, results.size)
    }

    @Test
    fun editingDuringACheckDropsItsAnswer() = runTest {
        val slow = CompletableDeferred<DiscoveryResult>()
        answer = slow
        val checker = checker()
        checker.addressChanged("lib.example.test")
        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS + 1)
        runCurrent()
        assertTrue(checker.isChecking)

        checker.addressChanged("lib.example.t")
        assertFalse(checker.isChecking)
        slow.complete(discovered("lib.example.test"))
        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS * 3)
        runCurrent()

        assertEquals(listOf("lib.example.test"), asked)
        assertEquals(emptyList<DiscoveryResult>(), results)
        assertFalse(checker.isChecking)
    }

    @Test
    fun checkingShowsOnlyWhileTheServerIsAsked() = runTest {
        val slow = CompletableDeferred<DiscoveryResult>()
        answer = slow
        val checker = checker()
        checker.addressChanged("lib.example.test")
        runCurrent()
        assertFalse("not during the pause", checker.isChecking)

        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS + 1)
        runCurrent()
        assertTrue(checker.isChecking)

        slow.complete(DiscoveryResult.Rejected(AddServerFailure.UNREACHABLE))
        runCurrent()
        assertFalse(checker.isChecking)
        assertEquals(listOf<DiscoveryResult>(DiscoveryResult.Rejected(AddServerFailure.UNREACHABLE)), results)
    }

    @Test
    fun cancelStopsAPendingCheck() = runTest {
        val checker = checker()
        checker.addressChanged("lib.example.test")
        checker.cancel()
        advanceTimeBy(AUTO_CHECK_PAUSE_MILLIS * 3)
        runCurrent()
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun finishedAddressesNeedAHostWithALetteredTopLevelLabel() {
        listOf("lib.example.test", "Example.Test", "example.test:8443", "https://example.test", " example.test ")
            .forEach { assertTrue(it, isFinishedAddress(it)) }
        listOf("", "example", "example.t", "example.test.", "localhost", "10.0.0.5", "[::1]", "http://example.test",
            "example.test/admin", "user@example.test")
            .forEach { assertFalse(it, isFinishedAddress(it)) }
    }
}
