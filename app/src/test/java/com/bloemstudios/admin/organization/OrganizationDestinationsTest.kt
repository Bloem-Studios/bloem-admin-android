package com.bloemstudios.admin.organization

import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.shell.AdminGroup
import com.bloemstudios.admin.ui.shell.aggregateDestinations
import com.bloemstudios.admin.ui.shell.organizationDestinations
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationDestinationsTest {
    @Test
    fun `the menu is the web's organization menu in its order`() {
        assertEquals(
            listOf(
                R.string.organization_nav_overview, R.string.organization_nav_people, R.string.organization_nav_cohorts,
                R.string.organization_nav_groups, R.string.organization_nav_libraries, R.string.organization_nav_invitations,
                R.string.organization_nav_decisions, R.string.organization_nav_activity,
            ),
            organizationDestinations().map { it.labelRes },
        )
        assertTrue(organizationDestinations().all { it.group == AdminGroup.Organization })
    }

    @Test
    fun `an organization context never reaches a platform destination`() {
        val organization = organizationDestinations().map { it.id }.toSet()
        val platform = (singleServerDestinations(tenantsVisible = true) + aggregateDestinations()).map { it.id }.toSet()
        assertTrue("no shared ids", organization.intersect(platform).isEmpty())
        assertTrue(organization.all { it.startsWith("organization.") })
        assertEquals("ids are unique", organization.size, organizationDestinations().size)
    }
}
