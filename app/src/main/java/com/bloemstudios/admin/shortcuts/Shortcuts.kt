package com.bloemstudios.admin.shortcuts

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.R
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer

/** The saved servers the admin opened most recently, newest first. Ids only; used for the launcher's dynamic shortcuts. */
class RecentServers(private val preferences: SharedPreferences) {
    fun ids(): List<String> = preferences.getString(KEY, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }

    @Synchronized
    fun touch(serverId: String) {
        if (serverId.isEmpty() || SEPARATOR in serverId) return
        write((listOf(serverId) + ids().filterNot { it == serverId }).take(MAX))
    }

    @Synchronized
    fun retain(savedIds: Set<String>) {
        val kept = ids().filter { it in savedIds }
        if (kept != ids()) write(kept)
    }

    private fun write(ids: List<String>) = preferences.edit().putString(KEY, ids.joinToString(SEPARATOR.toString())).apply()

    companion object {
        const val FILE = "recent_servers"
        private const val KEY = "ids"
        private const val SEPARATOR = '\n'
        private const val MAX = 5
    }
}

/** One launcher shortcut to publish: a page of one saved server. */
data class DynamicShortcutSpec(val id: String, val serverId: String, val serverName: String, val slug: String) {
    val link: String get() = DeepLink.build(serverId, slug)
}

private const val SERVERS_OFFERED = 2
private val PAGES_OFFERED = listOf("now-playing", "requests")

/** Now playing and Requests for each of the most recently used saved servers: at most four, so a launcher can show them all. */
fun dynamicShortcutSpecs(saved: List<AdminServer>, recentIds: List<String>): List<DynamicShortcutSpec> =
    recentIds.mapNotNull { id -> saved.firstOrNull { it.id == id } }.distinctBy { it.id }.take(SERVERS_OFFERED).flatMap { server ->
        PAGES_OFFERED.map { slug -> DynamicShortcutSpec("server:${server.id}:$slug", server.id, server.displayName, slug) }
    }

object ShortcutPublisher {
    /**
     * Replaces the app's dynamic shortcuts with the current ones, so a removed server's shortcuts disappear. The static
     * shortcuts (res/xml/shortcuts.xml) open the same pages for All servers. A shortcut names a server and a page, nothing else.
     */
    fun publish(context: Context, saved: List<AdminServer>, recentIds: List<String>) {
        val app = context.applicationContext
        val shortcuts = dynamicShortcutSpecs(saved, recentIds).mapIndexed { rank, spec ->
            val nowPlaying = spec.slug == "now-playing"
            ShortcutInfoCompat.Builder(app, spec.id)
                .setShortLabel(app.getString(if (nowPlaying) R.string.shortcut_server_now_playing_short else R.string.shortcut_server_requests_short, spec.serverName))
                .setLongLabel(app.getString(if (nowPlaying) R.string.shortcut_server_now_playing_long else R.string.shortcut_server_requests_long, spec.serverName))
                .setIcon(IconCompat.createWithResource(app, if (nowPlaying) R.drawable.ic_shortcut_now_playing else R.drawable.ic_shortcut_requests))
                .setRank(rank)
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(spec.link), app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts) }
    }
}
