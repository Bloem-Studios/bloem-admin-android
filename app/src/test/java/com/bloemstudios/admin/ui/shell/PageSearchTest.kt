package com.bloemstudios.admin.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSearchTest {
    private val pages = singleServerDestinations(tenantsVisible = true).map { destination ->
        // The text the app shows, as the web words it.
        val (label, description) = when (destination.id) {
            "overview.dashboard" -> "Dashboard" to "Live sessions, content health and server activity"
            "live.streams" -> "Now playing" to "Live streams and current playback sessions"
            "live.activity" -> "Playback history" to "Historical playback events across users and profiles"
            "system.logs" -> "Logs" to "Server log stream and operational output"
            "work.requests" -> "Requests" to "Review, approve, and decline media requests"
            "work.jobs" -> "Scheduled tasks" to "Background task schedules, runs, and job history"
            "people.users" -> "Users" to "Accounts, roles, profile settings, and access"
            "people.tenants" -> "Organizations" to "Every organization on this server, and its members"
            "system.server" -> "Server settings" to "The server's name and public address, the proxies in front of it, and Redis"
            else -> "Audit" to "Who changed what on this server, and when"
        }
        val group = when (destination.group) {
            AdminGroup.Platform -> "Platform"
            AdminGroup.Monitor -> "Monitor"
            AdminGroup.Manage -> "Manage"
            AdminGroup.Configure -> "Configure"
            AdminGroup.Organization -> "Organization"
        }
        SearchablePage(destination, group, label, description)
    }

    private fun ids(query: String) = filterPages(pages, query).flatMap { it.second }.map { it.destination.id }

    @Test
    fun aBlankQueryListsEveryPageInDrawerOrder() {
        val expected = groupedForDrawer(pages.map { it.destination }).flatMap { it.second }.map { it.id }
        assertEquals(expected, ids(""))
        assertEquals(expected, ids("   "))
    }

    @Test
    fun matchesOnLabelDescriptionAndKeywordsAsTheWebDoes() {
        assertEquals(listOf("work.jobs"), ids("scheduled"))
        // A keyword the label and description lack.
        assertEquals(listOf("work.jobs"), ids("scheduler"))
        // Streams show transcodes, and the Settings pages hold the transcoding switches.
        assertEquals(listOf("live.streams", "system.server"), ids("transcode"))
        assertEquals(listOf("system.server"), ids("smtp"))
        // A word of the description.
        // (The Settings pages hold "auto approve" for the request approval default.)
        assertEquals(listOf("work.requests", "system.server"), ids("approve"))
        assertEquals(listOf("system.server"), ids("redis"))
        assertEquals(listOf("people.tenants"), ids("memberships"))
    }

    @Test
    fun everyWordMustMatchAndCaseAndAccentsDoNotMatter() {
        assertEquals(listOf("work.requests"), ids("DECLINE requests"))
        assertEquals(listOf("work.requests"), ids("Décline"))
        assertTrue(ids("decline jobs").isEmpty())
        assertTrue(ids("zzz").isEmpty())
    }

    @Test
    fun aWordStartsWithTheTokenOrContainsItFromFourLetters() {
        // "ers" starts no word but is only three letters: no match inside a word.
        assertTrue(ids("ers").none { it == "people.users" })
        // Four letters may sit inside a word ("profiles" in "Historical playback events across users and profiles").
        assertTrue("live.activity" in ids("file"))
        assertTrue("live.activity" !in ids("ile"))
    }

    @Test
    fun aGroupsNameKeepsAllItsPages() {
        val grouped = groupedForDrawer(pages.map { it.destination })
        assertEquals(grouped.first { it.first == AdminGroup.Configure }.second.map { it.id }, ids("configure"))
        assertEquals(grouped.first { it.first == AdminGroup.Monitor }.second.map { it.id }, ids("monitor"))
        assertEquals(grouped.first { it.first == AdminGroup.Platform }.second.map { it.id }, ids("platform"))
    }

    @Test
    fun resultsStayGroupedInDrawerOrder() {
        val groups = filterPages(pages, "live").map { it.first }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
    }

    @Test
    fun organizationPagesAreFoundByTheirOwnKeywords() {
        val org = organizationDestinations().map { destination ->
            SearchablePage(destination, "Organization", "Page", "")
        }
        assertEquals(listOf("organization.decisions"),
            filterPages(org, "denied").flatMap { it.second }.map { it.destination.id })
        assertEquals(listOf("organization.cohorts"),
            filterPages(org, "revisions").flatMap { it.second }.map { it.destination.id })
        // The group's name matches every organization page.
        assertEquals(org.size, filterPages(org, "organization").sumOf { it.second.size })
    }
}
