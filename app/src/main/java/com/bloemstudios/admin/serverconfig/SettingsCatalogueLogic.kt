package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.ui.shell.matchesTokens
import com.bloemstudios.admin.ui.shell.normalizeSearchText
import com.bloemstudios.admin.ui.shell.searchTokens

/** A page is offered when the server reports at least one of its keys: a server that predates the page does not. */
fun SettingsPage.isAvailable(reported: Set<String>): Boolean = settings.any { it.key in reported }

/**
 * The controls to draw: those whose key the server reports, and the ones the server has no default for (an SMTP host is
 * absent from the effective settings until someone sets it) once the page itself is available.
 */
fun SettingsPage.shownSettings(reported: Set<String>): List<SettingDef> =
    if (!isAvailable(reported)) emptyList() else settings.filter { it.key in reported || it.noServerDefault }

/** Same rule, group by group, dropping groups with nothing to show. */
fun SettingsPage.shownGroups(reported: Set<String>): List<SettingGroup> {
    val shown = shownSettings(reported).mapTo(HashSet()) { it.key }
    return groups.mapNotNull { group ->
        group.settings.filter { it.key in shown }.takeIf { it.isNotEmpty() }?.let { group.copy(settings = it) }
    }
}

/** Reported keys no catalogue page covers, sorted: the "Other settings" list. */
fun otherSettingKeys(reported: Set<String>): List<String> {
    val covered = SETTINGS_PAGES.flatMapTo(HashSet()) { it.keys }
    return reported.filter { it !in covered }.sorted()
}

/** The catalogue's label for [key], or null when no page covers it. */
fun labelForKey(key: String, labelOf: (SettingDef) -> String): String? =
    SETTINGS_PAGES.asSequence().flatMap { it.settings.asSequence() }.firstOrNull { it.key == key }?.let(labelOf)

fun pageForKey(key: String): SettingsPage? = SETTINGS_PAGES.firstOrNull { key in it.keys }

data class SettingHit(val page: SettingsPage, val def: SettingDef)
data class SettingsSearchResult(val pages: List<SettingsPage>, val settings: List<SettingHit>)

/**
 * The pages and the individual settings [query] finds. A page matches on its name and the web's keywords; a setting on its
 * label and its key in words ("playback.transcode_enabled" is found by "transcode enabled"). Only pages and settings the
 * server reports are returned. A blank query finds nothing.
 */
fun searchSettings(
    query: String,
    pages: List<SettingsPage>,
    titleOf: (SettingsPage) -> String,
    labelOf: (SettingDef) -> String,
    reported: Set<String>,
): SettingsSearchResult {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return SettingsSearchResult(emptyList(), emptyList())
    val available = pages.filter { it.isAvailable(reported) }
    val matchedPages = available.filter { page ->
        matchesTokens(normalizeSearchText((listOf(titleOf(page)) + page.keywords).joinToString(" ")), tokens)
    }
    val hits = available.flatMap { page ->
        page.shownSettings(reported).filter { def ->
            matchesTokens(normalizeSearchText(labelOf(def) + " " + def.key), tokens)
        }.map { SettingHit(page, it) }
    }
    return SettingsSearchResult(matchedPages, hits)
}
