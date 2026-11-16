package com.bloemstudios.admin.ui.shell

import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.AutomationPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDestinationTest {
    @Test
    fun groupsFollowTheWebAdminOrder() {
        assertEquals(
            listOf("Platform", "Monitor", "Manage", "Configure", "Organization"),
            AdminGroup.entries.map { it.name },
        )
    }

    @Test
    fun singleServerDestinationsFollowTheWebGroupsAndConditionalOrganizations() {
        val withoutTenants = singleServerDestinations(tenantsVisible = false)
        val withTenants = singleServerDestinations(tenantsVisible = true)

        assertEquals(
            listOf(
                "overview.dashboard" to AdminGroup.Monitor,
                "live.streams" to AdminGroup.Monitor,
                "system.logs" to AdminGroup.Monitor,
                "system.audit" to AdminGroup.Monitor,
                "content.libraries" to AdminGroup.Manage,
                "work.requests" to AdminGroup.Manage,
                "people.users" to AdminGroup.Manage,
                "live.activity" to AdminGroup.Manage,
                "system.server" to AdminGroup.Configure,
                "work.jobs" to AdminGroup.Configure,
            ),
            withoutTenants.map { it.id to it.group },
        )
        // Organizations lead the Platform group, before every other page.
        assertEquals(listOf("people.tenants") + withoutTenants.map { it.id }, withTenants.map { it.id })
        assertEquals(AdminGroup.Platform, withTenants.first { it.id == "people.tenants" }.group)
    }

    /** Every optional page on: the whole menu, as the web groups and orders it (web/src/lib/adminNavigation.ts ADMIN_NAV_SECTIONS). */
    private fun fullMenu() = singleServerDestinations(
        tenantsVisible = false, supportAccessVisible = true, organizationsVisible = true,
        automation = AutomationPages(nodes = true, backups = true, plugins = true), apiKeysVisible = true,
    )

    @Test
    fun theFullMenuHasTheWebsGroupsAndPagesInTheWebsOrder() {
        assertEquals(
            listOf(
                AdminGroup.Platform to listOf("platform.organizations", "platform.support-access"),
                // The web keeps the audit log as a tab of Logs, so it sits next to Logs.
                AdminGroup.Monitor to listOf("overview.dashboard", "live.streams", "system.logs", "system.audit"),
                // Libraries, Requests, Users, then Playback's History and Nodes.
                AdminGroup.Manage to listOf("content.libraries", "work.requests", "people.users", "live.activity", "playback.nodes"),
                // Server (Settings, Scheduled tasks, Backups), Access (API keys), Integrations (Plugins).
                AdminGroup.Configure to listOf("system.server", "work.jobs", "system.backups", "system.api-keys", "system.plugins"),
            ),
            groupedForDrawer(fullMenu()).map { (group, pages) -> group to pages.map { it.id } },
        )
    }

    @Test
    fun theFullMenuHasNoDuplicatePages() {
        val ids = fullMenu().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("sixteen pages", 16, ids.size)
    }

    @Test
    fun supportAccessSitsInThePlatformGroupAfterOrganizations() {
        val menu = singleServerDestinations(tenantsVisible = true, supportAccessVisible = true)
        assertEquals(listOf("people.tenants", "platform.support-access"), menu.take(2).map { it.id })
        assertEquals(AdminGroup.Platform, menu.first { it.id == "platform.support-access" }.group)
    }

    @Test
    fun aGroupWithNoPagesIsNotShown() {
        val grouped = groupedForDrawer(singleServerDestinations(tenantsVisible = false))
        assertEquals(listOf(AdminGroup.Monitor, AdminGroup.Manage, AdminGroup.Configure), grouped.map { it.first })
    }

    @Test
    fun destinationsUseTheWebNames() {
        val byId = singleServerDestinations(tenantsVisible = true).associate { it.id to it.labelRes }
        assertEquals(R.string.tab_dashboard, byId["overview.dashboard"])
        assertEquals(R.string.tab_streams, byId["live.streams"])
        assertEquals(R.string.tab_activity, byId["live.activity"])
        assertEquals(R.string.tab_jobs, byId["work.jobs"])
        assertEquals(R.string.tab_tenants, byId["people.tenants"])
    }

    @Test
    fun aggregateDestinationsAreTheApprovedOrderedTwelveDestinationSubset() {
        val destinations = aggregateDestinations()

        assertEquals(
            listOf(
                "overview.dashboard" to AdminGroup.Monitor,
                "live.streams" to AdminGroup.Monitor,
                "system.audit" to AdminGroup.Monitor,
                "content.libraries" to AdminGroup.Manage,
                "work.requests" to AdminGroup.Manage,
                "people.users" to AdminGroup.Manage,
                "live.activity" to AdminGroup.Manage,
                "playback.nodes" to AdminGroup.Manage,
                "system.server" to AdminGroup.Configure,
                "work.jobs" to AdminGroup.Configure,
                "system.backups" to AdminGroup.Configure,
                "system.plugins" to AdminGroup.Configure,
            ),
            destinations.map { it.id to it.group },
        )
        assertFalse(
            destinations.any {
                it.id in setOf("system.logs", "people.tenants", "people.invitations")
            },
        )
    }

    @Test
    fun groupedForDrawerKeepsEnumOrderAndSkipsEmptyGroups() {
        val extra = AdminDestination("x.y", AdminGroup.Configure, R.string.tab_audit, R.drawable.lucide_layout_dashboard)
        val grouped = groupedForDrawer(aggregateDestinations() + extra)
        assertEquals(listOf(AdminGroup.Monitor, AdminGroup.Manage, AdminGroup.Configure), grouped.map { it.first })
        assertEquals(listOf("system.server", "work.jobs", "system.backups", "system.plugins", "x.y"), grouped.last().second.map { it.id })
        assertEquals(
            listOf(AdminGroup.Monitor),
            groupedForDrawer(aggregateDestinations().filter { it.group <= AdminGroup.Monitor }).map { it.first },
        )
    }

    @Test
    fun selectionRetainsTheCurrentSupportedChildInTheRequestedGroup() {
        val result = selectSupportedDestination(
            group = AdminGroup.Manage,
            destinations = singleServerDestinations(tenantsVisible = false),
            currentDestinationId = "live.activity",
            rememberedDestinationId = "work.requests",
        )

        assertEquals("live.activity", result?.id)
    }

    @Test
    fun selectionRestoresTheRememberedSupportedChildWhenCurrentIsOutsideTheGroup() {
        val result = selectSupportedDestination(
            group = AdminGroup.Manage,
            destinations = singleServerDestinations(tenantsVisible = false),
            currentDestinationId = "work.jobs",
            rememberedDestinationId = "live.activity",
        )

        assertEquals("live.activity", result?.id)
    }

    @Test
    fun selectionFallsBackToTheFirstSupportedChildWhenRememberedChildIsRemoved() {
        val result = selectSupportedDestination(
            group = AdminGroup.Manage,
            destinations = singleServerDestinations(tenantsVisible = false),
            currentDestinationId = "work.jobs",
            rememberedDestinationId = "people.tenants",
        )

        assertEquals("content.libraries", result?.id)
    }

    @Test
    fun aggregateOverviewSelectsNowPlaying() {
        val result = selectSupportedDestination(
            group = AdminGroup.Monitor,
            destinations = aggregateDestinations(),
            currentDestinationId = null,
            rememberedDestinationId = null,
        )

        // Monitor lists the dashboard first; the app still opens on Now playing.
        assertEquals("overview.dashboard", result?.id)
        assertEquals("live.streams", START_DESTINATION_ID)
    }

    @Test
    fun everyDestinationHasItsOwnIconTheWebsDescriptionAndKeywords() {
        listOf(
            singleServerDestinations(tenantsVisible = true),
            fullMenu(),
            aggregateDestinations(organizationsVisible = true),
            organizationDestinations(),
        ).forEach { menu ->
            // The same page keeps its icon across menus, but no two pages of one menu share one.
            assertEquals("icons are distinct within a menu", menu.size, menu.map { it.icon }.toSet().size)
            menu.forEach {
                assertTrue("${it.id} has a description", it.descriptionRes != 0)
                assertTrue("${it.id} has keywords", it.keywords.isNotEmpty())
            }
        }
        val byId = singleServerDestinations(tenantsVisible = true).associateBy { it.id }
        assertEquals("the same page has the same icon in the All servers menu",
            byId.getValue("work.requests").icon,
            aggregateDestinations().first { it.id == "work.requests" }.icon)
    }

    @Test
    fun nodesBackupsAndPluginsAppearOnlyWhereTheServerHasThem() {
        val none = singleServerDestinations(tenantsVisible = false).map { it.id }
        assertFalse(none.any { it in setOf("playback.nodes", "system.backups", "system.plugins") })

        val all = singleServerDestinations(tenantsVisible = false, automation = AutomationPages(nodes = true, backups = true, plugins = true))
        assertEquals(AdminGroup.Manage, all.first { it.id == "playback.nodes" }.group)
        assertEquals(AdminGroup.Configure, all.first { it.id == "system.backups" }.group)
        assertEquals(AdminGroup.Configure, all.first { it.id == "system.plugins" }.group)

        val onlyBackups = singleServerDestinations(tenantsVisible = false, automation = AutomationPages(backups = true)).map { it.id }
        assertTrue("system.backups" in onlyBackups)
        assertFalse("playback.nodes" in onlyBackups || "system.plugins" in onlyBackups)
    }

    @Test
    fun theNewPagesCarryTheWebsDescriptionAndSearchWords() {
        val pages = aggregateDestinations().associateBy { it.id }
        assertEquals(R.string.shell_page_nodes, pages.getValue("playback.nodes").descriptionRes)
        assertEquals(R.string.shell_page_backups, pages.getValue("system.backups").descriptionRes)
        assertEquals(R.string.shell_page_plugins, pages.getValue("system.plugins").descriptionRes)
        assertTrue("transcode nodes" in pages.getValue("playback.nodes").keywords)
        assertTrue("copies to keep" in pages.getValue("system.backups").keywords)
        assertTrue("plugin catalog" in pages.getValue("system.plugins").keywords)
    }

    @Test
    fun librariesSitsFirstInManageWithTheWebDescriptionAndKeywords() {
        for (menu in listOf(singleServerDestinations(tenantsVisible = false), aggregateDestinations())) {
            val libraries = menu.first { it.id == "content.libraries" }
            assertEquals(AdminGroup.Manage, libraries.group)
            assertEquals(R.string.tab_libraries, libraries.labelRes)
            assertEquals(R.string.shell_page_libraries, libraries.descriptionRes)
            // The web's keywords (adminNavigation.ts PAGES.libraries) stay; the page is also found by what it does.
            assertTrue(libraries.keywords.containsAll(listOf("library", "paths", "scan", "catalog", "seed")))
            assertEquals("content.libraries", menu.first { it.group == AdminGroup.Manage }.id)
        }
    }

    @Test
    fun organizationMenuHasNoPlatformLibrariesPage() {
        assertFalse(organizationDestinations(streamsVisible = true).any { it.id == "content.libraries" })
    }

    @Test
    fun apiKeysIsAConfigurePageOnlyWhereTheServerOffersKeyAdministration() {
        assertFalse(singleServerDestinations(tenantsVisible = false).any { it.id == "system.api-keys" })
        val page = singleServerDestinations(tenantsVisible = false, apiKeysVisible = true).single { it.id == "system.api-keys" }
        assertEquals(AdminGroup.Configure, page.group)
        assertEquals(R.string.tab_api_keys, page.labelRes)
        assertEquals("the web's description is used for page search", R.string.shell_page_api_keys, page.descriptionRes)
        assertTrue(page.keywords.containsAll(listOf("tokens", "keys", "scopes")))
        assertFalse("not an All servers page", aggregateDestinations().any { it.id == "system.api-keys" })
    }

    @Test
    fun organizationsLeadThePlatformGroupForAPlatformAdminOfAServerThatHasThem() {
        val menu = singleServerDestinations(tenantsVisible = false, supportAccessVisible = true, organizationsVisible = true)
        assertEquals(listOf("platform.organizations", "platform.support-access"), menu.filter { it.group == AdminGroup.Platform }.map { it.id })
        val organizations = menu.first { it.id == "platform.organizations" }
        assertEquals(R.string.platform_orgs_nav, organizations.labelRes)
        assertEquals(R.string.shell_page_platform_orgs, organizations.descriptionRes)
        assertEquals(listOf("organizations", "tenants", "memberships"), organizations.keywords)
        assertEquals(AdminGroup.Platform, groupedForDrawer(menu).first().first)
    }

    @Test
    fun organizationsAreHiddenUntilTheServerHasThem() {
        assertFalse(singleServerDestinations(tenantsVisible = false).any { it.id == "platform.organizations" })
        assertFalse(singleServerDestinations(tenantsVisible = false, supportAccessVisible = true).any { it.id == "platform.organizations" })
        assertFalse(aggregateDestinations().any { it.id == "platform.organizations" })
    }

    @Test
    fun allServersListsOrganizationsFirstWhenASavedServerHasThem() {
        val menu = aggregateDestinations(organizationsVisible = true)
        assertEquals("platform.organizations", menu.first().id)
        assertEquals(AdminGroup.Platform, menu.first().group)
        assertEquals("the other pages are unchanged", aggregateDestinations().map { it.id }, menu.drop(1).map { it.id })
        assertEquals(AdminGroup.Platform, groupedForDrawer(menu).first().first)
    }
}
