package com.bloemstudios.admin.widget

/** The five home-screen widgets. [storage] is what the surface preferences keep, so it never changes. */
enum class WidgetKind(val storage: String, val deepLinkSlug: String) {
    ServerStatus("server_status", "now-playing"),
    NowPlaying("now_playing", "now-playing"),
    Requests("requests", "requests"),
    Libraries("libraries", "libraries"),
    Nodes("nodes", "nodes");

    companion object {
        /** An entry from before there were several kinds, or one this build does not know, is Server status. */
        fun fromStorage(value: String?): WidgetKind = entries.firstOrNull { it.storage == value } ?: ServerStatus
    }
}

/** A widget on a home screen: its system id, what it is, and the saved server it shows (null for All servers). */
data class PlacedWidget(val id: Int, val kind: WidgetKind, val target: String?)
