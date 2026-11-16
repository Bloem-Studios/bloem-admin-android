package com.bloemstudios.admin.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.AutomationPages

/**
 * The web admin's sidebar sections, in the order the drawer lists them: Platform, Monitor, Manage, Configure
 * (web/src/lib/adminNavigation.ts ADMIN_NAV_SECTIONS and bloemAdminNavigation.ts), or Organization inside an
 * organization. A group with no pages is not shown.
 */
enum class AdminGroup(@StringRes val labelRes: Int) {
    Platform(R.string.veil_group_platform),
    Monitor(R.string.veil_group_monitor),
    Manage(R.string.veil_group_manage),
    Configure(R.string.veil_group_configure),
    Organization(R.string.organization_group_label),
}

data class AdminDestination(
    val id: String,
    val group: AdminGroup,
    @StringRes val labelRes: Int,
    /** A Lucide icon from res/drawable (lucide_<name>). */
    @DrawableRes val icon: Int,
    /** The web's description of the page, shown in page search. 0 = none. */
    @StringRes val descriptionRes: Int = 0,
    /** The web's English search synonyms for the page; never shown. */
    val keywords: List<String> = emptyList(),
)

data class AdminShellContext(
    val displayName: String,
    val isAggregate: Boolean,
)

// Icons follow the concept names in docs/design.md: layout-dashboard, radio (live),
// history, scroll-text, library, inbox, calendar-clock, users, building-2, settings, shield-check.
private fun dashboard() = AdminDestination(
    "overview.dashboard", AdminGroup.Monitor, R.string.tab_dashboard, R.drawable.lucide_layout_dashboard,
    R.string.shell_page_dashboard, listOf("overview", "stats", "health", "scan all"),
)
private fun streams() = AdminDestination(
    "live.streams", AdminGroup.Monitor, R.string.tab_streams, R.drawable.lucide_radio,
    R.string.shell_page_streams, listOf("streams", "sessions", "now playing", "transcode"),
)
private fun activity() = AdminDestination(
    "live.activity", AdminGroup.Manage, R.string.tab_activity, R.drawable.lucide_history,
    R.string.shell_page_activity, listOf("history", "watched", "progress", "plays"),
)
private fun logs() = AdminDestination(
    "system.logs", AdminGroup.Monitor, R.string.tab_logs, R.drawable.lucide_scroll_text,
    R.string.shell_page_logs, listOf("server logs", "debug", "tail", "events"),
)
private fun libraries() = AdminDestination(
    "content.libraries", AdminGroup.Manage, R.string.tab_libraries, R.drawable.lucide_library,
    R.string.shell_page_libraries, listOf("library", "paths", "scan", "catalog", "seed", "libraries", "mount", "refresh metadata"),
)
private fun requests() = AdminDestination(
    "work.requests", AdminGroup.Manage, R.string.tab_requests, R.drawable.lucide_inbox,
    R.string.shell_page_requests, listOf("requested media", "approvals", "request queue", "decline"),
)
private fun jobs() = AdminDestination(
    "work.jobs", AdminGroup.Configure, R.string.tab_jobs, R.drawable.lucide_calendar_clock,
    R.string.shell_page_jobs, listOf("tasks", "jobs", "scheduler", "sync"),
)
private fun users() = AdminDestination(
    "people.users", AdminGroup.Manage, R.string.tab_users, R.drawable.lucide_users,
    R.string.shell_page_users, listOf("accounts", "profiles", "roles", "permissions"),
)
private fun tenants() = AdminDestination(
    "people.tenants", AdminGroup.Platform, R.string.tab_tenants, R.drawable.lucide_building_2,
    R.string.shell_page_tenants, listOf("organizations", "tenants", "memberships"),
)
private fun serverSettings() = AdminDestination(
    "system.server", AdminGroup.Configure, R.string.tab_server, R.drawable.lucide_sliders_horizontal,
    R.string.shell_page_server,
    // The web's search words for its settings pages: General, Sign-in, Playback, Logging, Email, Notifications,
    // Downloads, Network access and Requests.
    listOf("settings", "configuration", "server name", "login subtitle", "public url", "reverse proxy",
        "trusted proxies", "redis", "database", "sso", "single sign-on", "password sign-in", "signup", "break-glass",
        "transcode", "hardware acceleration", "4k", "hevc", "tone mapping", "markers", "log level", "debug",
        "retention", "smtp", "email", "mail server", "test email", "push", "discord", "webhooks", "server channels",
        "downloads", "offline", "bandwidth", "tailscale", "netbird", "network access", "remote access",
        "request limit", "approval", "auto approve", "watchlist", "restart", "uptime"),
)
private fun audit() = AdminDestination(
    "system.audit", AdminGroup.Monitor, R.string.tab_audit, R.drawable.lucide_shield_check,
    R.string.shell_page_audit, listOf("audit", "changes", "events", "who changed"),
)

// The web's Nodes (Manage, Playback), Backups (Configure, Server) and Plugins (Configure, Integrations), with its descriptions
// and search words (web/src/lib/adminNavigation.ts PAGES.nodes, .backups and .plugins). Icons: cpu, archive and puzzle.
private fun nodes() = AdminDestination(
    "playback.nodes", AdminGroup.Manage, R.string.tab_nodes, R.drawable.lucide_cpu,
    R.string.shell_page_nodes,
    listOf("stream nodes", "workers", "transcode nodes", "proxy nodes", "routing", "egress", "gpu offload", "central egress"),
)
private fun backups() = AdminDestination(
    "system.backups", AdminGroup.Configure, R.string.tab_backups, R.drawable.lucide_archive,
    R.string.shell_page_backups,
    listOf("backup", "back up now", "restore", "recovery", "database dump", "pg_dump", "disaster recovery", "copies to keep", "retention"),
)
private fun plugins() = AdminDestination(
    "system.plugins", AdminGroup.Configure, R.string.tab_plugins, R.drawable.lucide_puzzle,
    R.string.shell_page_plugins, listOf("extensions", "plugin catalog", "repositories"),
)
/** Configure, Sign-in and security: the web's API Keys page (list and revoke here; creating keys stays on the web). */
private fun apiKeys() = AdminDestination(
    "system.api-keys", AdminGroup.Configure, R.string.tab_api_keys, R.drawable.lucide_key_round,
    R.string.shell_page_api_keys, listOf("tokens", "keys", "access", "scopes", "rate limit tier"),
)
/**
 * Platform › Organizations (web: platformOrganizations, bloemAdminNavigation.ts:134): every organization on the server, its
 * members, its status and its plan. Offered to a platform administrator of a server that has organizations.
 */
private fun platformOrganizations() = AdminDestination(
    "platform.organizations", AdminGroup.Platform, R.string.platform_orgs_nav, R.drawable.lucide_building_2,
    R.string.shell_page_platform_orgs, listOf("organizations", "tenants", "memberships"),
)

/** The server Owner's way into a customer's organization (break-glass); offered only to the Owner of a server that has it. */
private fun supportAccess() = AdminDestination(
    "platform.support-access", AdminGroup.Platform, R.string.organization_support_nav, R.drawable.lucide_life_buoy,
    R.string.shell_page_org_support, listOf("support", "break glass", "organization", "customer", "access"),
)

/** The page a server or All servers opens on: what is playing right now, then the rest. */
const val START_DESTINATION_ID = "live.streams"

/**
 * A server's pages. [organizationsVisible] offers Platform > Organizations, [automation] says which of Nodes, Backups and
 * Plugins this server can serve, and [apiKeysVisible] whether it has API key administration; a page the server cannot serve
 * is left out, so an older server never shows one that would not load.
 */
fun singleServerDestinations(
    tenantsVisible: Boolean,
    supportAccessVisible: Boolean = false,
    organizationsVisible: Boolean = false,
    automation: AutomationPages = AutomationPages.None,
    /** True where the server offers API key administration (its capability says so); an older server hides the page. */
    apiKeysVisible: Boolean = false,
): List<AdminDestination> = buildList {
    // The web's order (adminNavigation.ts ADMIN_NAV_SECTIONS, bloemAdminNavigation.ts): Platform; Monitor (Dashboard, Now
    // playing, Logs, with the audit log that the web keeps as a tab of Logs); Manage (Libraries, Requests, Users, Playback with
    // History and Nodes); Configure (Settings, Scheduled tasks, Backups, API keys, Plugins).
    if (tenantsVisible) add(tenants())
    if (organizationsVisible) add(platformOrganizations())
    if (supportAccessVisible) add(supportAccess())
    add(dashboard())
    add(streams())
    add(logs())
    add(audit())
    add(libraries())
    add(requests())
    add(users())
    add(activity())
    if (automation.nodes) add(nodes())
    add(serverSettings())
    add(jobs())
    if (automation.backups) add(backups())
    if (apiKeysVisible) add(apiKeys())
    if (automation.plugins) add(plugins())
}

/**
 * The web's organization menu, in its order. These ids never overlap the platform destinations. Now Playing is added only
 * where the server can list the organization's streams ([streamsVisible]).
 */
fun organizationDestinations(streamsVisible: Boolean = false): List<AdminDestination> = listOfNotNull(
    AdminDestination("organization.overview", AdminGroup.Organization, R.string.organization_nav_overview,
        R.drawable.lucide_layout_dashboard, R.string.shell_page_org_overview, listOf("organization", "tenant", "health")),
    if (streamsVisible) AdminDestination("organization.streams", AdminGroup.Organization, R.string.organization_nav_streams,
        R.drawable.lucide_radio, R.string.shell_page_org_streams, listOf("streams", "sessions", "now playing", "watching")) else null,
    AdminDestination("organization.people", AdminGroup.Organization, R.string.organization_nav_people,
        R.drawable.lucide_users, R.string.shell_page_org_people, listOf("people", "memberships", "profiles")),
    AdminDestination("organization.cohorts", AdminGroup.Organization, R.string.organization_nav_cohorts,
        R.drawable.lucide_network, R.string.shell_page_org_cohorts,
        listOf("entitlements", "cohorts", "policy", "revisions")),
    AdminDestination("organization.groups", AdminGroup.Organization, R.string.organization_nav_groups,
        R.drawable.lucide_users_round, R.string.shell_page_org_groups, listOf("groups", "roles", "permissions")),
    AdminDestination("organization.libraries", AdminGroup.Organization, R.string.organization_nav_libraries,
        R.drawable.lucide_library, R.string.shell_page_org_libraries,
        listOf("libraries", "entitlements", "media ceiling", "grants")),
    AdminDestination("organization.invitations", AdminGroup.Organization, R.string.organization_nav_invitations,
        R.drawable.lucide_mail, R.string.shell_page_org_invitations, listOf("invitations", "invite", "members")),
    AdminDestination("organization.decisions", AdminGroup.Organization, R.string.organization_nav_decisions,
        R.drawable.lucide_scale, R.string.shell_page_org_decisions,
        listOf("policy", "decisions", "authorization", "access", "denied")),
    AdminDestination("organization.activity", AdminGroup.Organization, R.string.organization_nav_activity,
        R.drawable.lucide_scroll_text, R.string.shell_page_org_activity, listOf("activity", "audit", "events")),
)

/** The pages All servers shows. Organizations joins them, first, when at least one saved server has organizations. */
fun aggregateDestinations(organizationsVisible: Boolean = false): List<AdminDestination> = buildList {
    if (organizationsVisible) add(platformOrganizations())
    addAll(listOf(dashboard(), streams(), audit(), libraries(), requests(), users(), activity(), nodes(), serverSettings(), jobs(), backups(), plugins()))
}

/**
 * The drawer's sections: every group that has destinations, in enum order, each with its
 * destinations in the order given. A group added to [AdminGroup] shows up with no further wiring.
 */
fun groupedForDrawer(destinations: List<AdminDestination>): List<Pair<AdminGroup, List<AdminDestination>>> =
    AdminGroup.entries.mapNotNull { group ->
        destinations.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }

/**
 * Selects a supported destination for a group without owning any UI or persistence state.
 *
 * Callers provide the current selection and their locally remembered child; unsupported or
 * missing values safely fall back to the first supported destination for the requested group.
 */
fun selectSupportedDestination(
    group: AdminGroup,
    destinations: List<AdminDestination>,
    currentDestinationId: String?,
    rememberedDestinationId: String?,
): AdminDestination? {
    val groupDestinations = destinations.filter { it.group == group }
    return groupDestinations.firstOrNull { it.id == currentDestinationId }
        ?: groupDestinations.firstOrNull { it.id == rememberedDestinationId }
        ?: groupDestinations.firstOrNull()
}
