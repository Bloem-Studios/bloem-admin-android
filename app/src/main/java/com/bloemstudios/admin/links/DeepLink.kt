package com.bloemstudios.admin.links

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where a link wants to go: a saved server ([serverId]) or All servers (null), a shell page
 * ([destinationId], null for the page the server opens on), and optionally one item on that page ([itemId], for example
 * a request or a stream). The item is only an id the page looks up; it is never a name, a title or an action.
 */
data class DeepLinkTarget(val serverId: String?, val destinationId: String?, val itemId: String? = null)

/**
 * The app's own link scheme, used by alerts, the home-screen widget, the Quick Settings tile and the launcher
 * shortcuts: `bloemadmin://server/{serverId}/{page}` for one saved server and `bloemadmin://all/{page}` for All servers.
 *
 * A link only ever names a place. It carries no action, no token and no address, and the query and fragment are
 * ignored. Another app can send one, so the result is a request that the app checks against its saved servers and
 * opens behind the app lock and the admin check.
 */
object DeepLink {
    const val SCHEME = "bloemadmin"
    private const val HOST_SERVER = "server"
    private const val HOST_ALL = "all"
    private const val MAX_LENGTH = 256

    private val pages = linkedMapOf(
        "now-playing" to "live.streams",
        "requests" to "work.requests",
        "dashboard" to "overview.dashboard",
        "logs" to "system.logs",
        "users" to "people.users",
        "activity" to "live.activity",
        "settings" to "system.server",
        "jobs" to "work.jobs",
        "audit" to "system.audit",
        "libraries" to "content.libraries",
        "nodes" to "playback.nodes",
        "backups" to "system.backups",
        "plugins" to "system.plugins",
    )

    /** The longest item id a link may carry; a longer one is dropped and the link opens its page. */
    private const val MAX_ITEM_LENGTH = 128

    /** The page names a link may use, in the shell's order of importance. */
    val pageSlugs: List<String> = pages.keys.toList()

    fun destinationFor(slug: String): String? = pages[slug]

    fun parse(link: String?): DeepLinkTarget? {
        if (link.isNullOrEmpty() || link.length > MAX_LENGTH) return null
        val uri = try { URI(link) } catch (_: URISyntaxException) { return null }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val segments = (uri.rawPath ?: "").split('/').drop(1).map(::decode)
        return when (uri.rawAuthority?.lowercase()) {
            HOST_ALL -> {
                val page = segments.getOrNull(0)?.let(pages::get)
                DeepLinkTarget(null, page, itemOf(page, segments.getOrNull(1)))
            }
            HOST_SERVER -> {
                val id = segments.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
                val page = segments.getOrNull(1)?.let(pages::get)
                DeepLinkTarget(id, page, itemOf(page, segments.getOrNull(2)))
            }
            else -> null
        }
    }

    /** An item only means something on a known page, and only as a short, non-empty id. */
    private fun itemOf(page: String?, raw: String?): String? =
        raw?.takeIf { page != null && it.isNotEmpty() && it.length <= MAX_ITEM_LENGTH }

    fun build(serverId: String, slug: String? = null, itemId: String? = null): String =
        "$SCHEME://$HOST_SERVER/${encode(serverId)}" + (slug?.let { "/$it" + (itemId?.let { id -> "/${encode(id)}" } ?: "") } ?: "")

    fun buildAll(slug: String? = null, itemId: String? = null): String =
        "$SCHEME://$HOST_ALL" + (slug?.let { "/$it" + (itemId?.let { id -> "/${encode(id)}" } ?: "") } ?: "")

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // A plus sign in a path is a plus sign, not a space.
    private fun decode(value: String) = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { "" }
}
