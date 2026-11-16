package com.bloemstudios.admin.organization

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationAuditEventTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(body: String) = json.decodeFromString<OrganizationAuditPage>(body).events.single()

    @Test fun `an event written under support access carries its authority, reason and access id`() {
        val event = decode(OrganizationFixtures.audit(OrganizationFixtures.auditEvent(
            action = "support_access.opened", actor = "null", authority = "platform_support",
            reason = "Customer asked for help with a stuck scan", supportAccessId = "5a1c0000-0000-0000-0000-000000000009",
        )))
        assertEquals("platform_support", event.actor_authority)
        assertEquals("Customer asked for help with a stuck scan", event.reason)
        assertEquals("5a1c0000-0000-0000-0000-000000000009", event.support_access_id)
        assertNull(event.actor_account_id)
        assertEquals(AuditAttribution.PlatformSupport, event.attribution())
    }

    @Test fun `an event from a server that predates organization support still decodes`() {
        val event = decode(OrganizationFixtures.audit(OrganizationFixtures.auditEvent()))
        assertNull(event.actor_authority); assertNull(event.reason); assertNull(event.support_access_id)
        assertEquals(AuditAttribution.Account(4), event.attribution())
    }

    @Test fun `platform support wins over an account id and an unattributed event is by Bloem`() {
        val support = OrganizationAuditEvent(1, action = "a", actor_account_id = 7, actor_authority = "platform_support")
        assertEquals(AuditAttribution.PlatformSupport, support.attribution())
        assertTrue(support.isPlatformSupport)
        val orgAdmin = OrganizationAuditEvent(2, action = "a", actor_account_id = 7, actor_authority = "organization_admin")
        assertEquals(AuditAttribution.Account(7), orgAdmin.attribution())
        assertFalse(orgAdmin.isPlatformSupport)
        assertEquals(AuditAttribution.Bloem, OrganizationAuditEvent(3, action = "a").attribution())
    }

    @Test fun `a blank reason is not shown`() {
        assertNull(OrganizationAuditEvent(1, action = "a", reason = "  ").reasonToShow)
        assertEquals("Needed to check a scan", OrganizationAuditEvent(1, action = "a", reason = " Needed to check a scan ").reasonToShow)
        assertNull(OrganizationAuditEvent(1, action = "a").reasonToShow)
    }
}
