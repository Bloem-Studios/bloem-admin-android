package com.bloemstudios.admin.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OAuthRedirectsTest {
    @Before fun reset() { OAuthRedirects.end(); OAuthRedirects.takeOrphan(); OAuthRedirects.begin(); OAuthRedirects.end() }
    @After fun clean() { OAuthRedirects.end(); OAuthRedirects.takeOrphan() }

    @Test fun deliveryAfterBeginIsReturnedOnceAndIsNotAnOrphan() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        assertEquals("a", OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeOrphan())
    }

    @Test fun orphanTakeWhileWaitingKeepsTheDeliveryForTheSignIn() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        assertNull(OAuthRedirects.takeOrphan())
        assertEquals("a", OAuthRedirects.takeForSignIn())
    }

    @Test fun takeBeforeAnyDeliveryIsNull() {
        OAuthRedirects.begin()
        assertNull(OAuthRedirects.takeForSignIn())
    }

    @Test fun secondDeliveryReplacesTheFirst() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        OAuthRedirects.deliver("b")
        assertEquals("b", OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeForSignIn())
    }

    @Test fun beginDiscardsAnOlderDelivery() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        OAuthRedirects.begin()
        assertNull(OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeOrphan())
    }

    @Test fun deliveryWithoutBeginIsAnOrphan() {
        OAuthRedirects.deliver("a")
        assertNull(OAuthRedirects.takeForSignIn())
        assertEquals("a", OAuthRedirects.takeOrphan())
        assertNull(OAuthRedirects.takeOrphan())
    }

    @Test fun deliveryAfterEndIsAnOrphan() {
        OAuthRedirects.begin()
        OAuthRedirects.end()
        OAuthRedirects.deliver("a")
        assertNull(OAuthRedirects.takeForSignIn())
        assertEquals("a", OAuthRedirects.takeOrphan())
    }

    @Test fun endKeepsAnUnconsumedWaitingDeliveryAsAnOrphan() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        OAuthRedirects.end()
        // It is never redeemed, but the person is told the sign-in was interrupted.
        assertNull(OAuthRedirects.takeForSignIn())
        assertEquals("a", OAuthRedirects.takeOrphan())
        assertNull(OAuthRedirects.takeOrphan())
    }

    @Test fun endAfterTheDeliveryWasTakenLeavesNoOrphan() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        assertEquals("a", OAuthRedirects.takeForSignIn())
        OAuthRedirects.end()
        assertNull(OAuthRedirects.takeOrphan())
    }

    @Test fun aNewBeginDiscardsTheOrphanAnEarlierEndKept() {
        OAuthRedirects.begin()
        OAuthRedirects.deliver("a")
        OAuthRedirects.end()
        OAuthRedirects.begin()
        assertNull(OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeOrphan())
    }
}
