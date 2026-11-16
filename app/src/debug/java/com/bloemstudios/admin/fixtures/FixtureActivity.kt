package com.bloemstudios.admin.fixtures

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.ui.theme.SystemBarsFollowTheme
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme

/**
 * Debug builds only. Draws the real shell and screens from [FixtureData], with no server and no sign-in, so the look
 * of the app can be checked and photographed. Open it with
 * `bloemfixture://shell?page=live.streams&theme=dark`, `bloemfixture://shell?page=system.server&route=page:logging` `bloemfixture://org?page=organization.people&theme=light`,
 * `bloemfixture://all?page=platform.organizations`. Platform > Organizations has three single-server pages: `platform.organizations`
 * (the list), `platform.organization` (one organization) and `platform.organization-plan` (a previewed plan change).
 * `&server=silo` draws a plain Silo server instead of a Bloem one: no Platform pages in the menu.
 * Some pages take `&variant=`, such as `page=work.requests&variant=select`.
 * Pages with sample data: live.streams, overview.dashboard, people.users, live.activity (Playback history), system.logs and
 * system.audit. Logs also takes `session=<playback session id>` and `ffmpeg=1` to open on one playback session.
 * `bloemfixture://settings?alerts=off|on|blocked&theme=dark` shows App settings with its Server alerts section.
 */
class FixtureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data
        val org = link?.host == "org"
        val all = link?.host == "all"
        val settings = link?.host == "settings"
        val page = link?.getQueryParameter("page")
        // For the Settings page: `route` is home, page:<id> (such as page:logging), requests, network, other or checks.
        val settingsRoute = link?.getQueryParameter("route") ?: "home"
        val logsFocus = link?.getQueryParameter("session")?.let { LogsFocus(it, ffmpegOnly = link.getQueryParameter("ffmpeg") == "1") }
        val variant = link?.getQueryParameter("variant")
        // `server=silo` draws a plain Silo server (no Bloem extras); the default is a Bloem server.
        val bloemServer = link?.getQueryParameter("server") != "silo"
        val mode = when (link?.getQueryParameter("theme")) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
        setContent {
            VeilAdminTheme(mode) {
                SystemBarsFollowTheme()
                when {
                    settings -> FixtureAppSettings(FixtureAlerts.entries.firstOrNull { it.name.equals(link?.getQueryParameter("alerts"), true) } ?: FixtureAlerts.Off)
                    org -> FixtureOrganization(page ?: "organization.overview")
                    all -> FixtureAllServers(page ?: "platform.organizations", bloemServer)
                    else -> FixtureServer(page ?: "live.streams", settingsRoute, logsFocus, variant, bloemServer)
                }
            }
        }
    }
}
