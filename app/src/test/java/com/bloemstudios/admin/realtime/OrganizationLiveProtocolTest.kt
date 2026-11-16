package com.bloemstudios.admin.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrganizationLiveProtocolTest {
    private fun decode(text: String) = OrganizationLiveProtocol.decode(text)

    @Test fun `subscribe and unsubscribe are the contract's frames`() {
        assertEquals("""{"op":"subscribe","channels":["org.streams","org.people"],"resume":null}""",
            OrganizationLiveProtocol.subscribe(listOf("org.streams", "org.people")))
        assertEquals("""{"op":"unsubscribe","channels":["org.people"]}""", OrganizationLiveProtocol.unsubscribe(listOf("org.people")))
    }

    @Test fun `a change names its channel and nothing else is kept`() {
        assertEquals(OrganizationLiveFrame.Change("org.streams"),
            decode("""{"op":"change","seq":17,"channel":"org.streams","id":"s_9f2","rev":"4","kind":"updated"}"""))
        assertEquals(OrganizationLiveFrame.Change("org.activity"),
            decode("""{"op":"change","seq":3,"channel":"org.activity","id":"lifecycle:12","rev":"lifecycle:12","kind":"created"}"""))
    }

    @Test fun `a resync and a heartbeat`() {
        assertEquals(OrganizationLiveFrame.Resync("org.people"), decode("""{"op":"resync","seq":19,"channel":"org.people"}"""))
        assertEquals(OrganizationLiveFrame.Heartbeat, decode("""{"op":"heartbeat","seq":18}"""))
    }

    @Test fun `an error frame carries its code and channel`() {
        assertEquals(OrganizationLiveFrame.Error("unknown_channel", "org.nope"), decode("""{"op":"error","code":"unknown_channel","channel":"org.nope"}"""))
        assertEquals(OrganizationLiveFrame.Error("unavailable", "org.streams"), decode("""{"op":"error","code":"unavailable","channel":"org.streams"}"""))
        assertEquals(OrganizationLiveFrame.Error("bad", null), decode("""{"op":"error","code":"bad"}"""))
    }

    @Test fun `an op this client does not know is ignored, a broken frame is a protocol failure`() {
        assertEquals(OrganizationLiveFrame.Ignored, decode("""{"op":"future","seq":1}"""))
        for (broken in listOf("not json", "[]", """{"channel":"org.streams"}""", """{"op":"change","channel":"org.streams","id":"1","kind":"moved"}""", """{"op":"change","id":"1","kind":"created"}""", """{"op":"resync"}""")) {
            try { decode(broken); fail("should not decode: $broken") }
            catch (failure: SessionTransportException) { assertEquals(SessionFailure.Protocol, failure.failure) }
        }
    }

    @Test fun `the close codes of the contract decide the next step`() {
        for (code in listOf(4401, 4403, 4408, 1012)) assertEquals("$code", SessionFailure.AccessChanged, OrganizationLiveTransport.failureForClose(code))
        for (code in listOf(4429, 1013, 1008, 1009, 1006, 1000, null)) assertEquals("$code", SessionFailure.Connection, OrganizationLiveTransport.failureForClose(code))
        assertTrue(true)
    }
}
