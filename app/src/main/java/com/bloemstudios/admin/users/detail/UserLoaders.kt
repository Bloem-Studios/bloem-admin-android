package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.ApiCollection
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One section's read: not yet read, read, or failed with the server's own message. */
internal sealed interface Section<out T> {
    data object Loading : Section<Nothing>
    data class Loaded<T>(val value: T) : Section<T>
    data class Failed(val message: ErrorText) : Section<Nothing>
}

private fun failureText(e: Exception, serverName: String?): ErrorText =
    if (e is ApiError) (if (serverName == null) e.toErrorText(R.string.user_section_failed) else e.toErrorText(serverName, R.string.user_section_failed))
    else ErrorText.Res(R.string.user_section_failed)

/** A read-only section of the User page that is read once, and again when the admin retries. */
internal class SectionLoader<T>(private val serverName: String? = null, private val fetch: suspend () -> T) {
    private val _state = MutableStateFlow<Section<T>>(Section.Loading)
    val state: StateFlow<Section<T>> = _state

    /** A failed re-read leaves the section already shown. */
    suspend fun load() {
        try {
            _state.value = Section.Loaded(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value !is Section.Loaded) _state.value = Section.Failed(failureText(e, serverName))
        }
    }
}

/** A cursor-paged read-only list. [loadMore] appends the next page; a repeated cursor ends the list. */
internal class PagedFeed<T>(
    private val serverName: String? = null,
    private val fetch: suspend (cursor: String?) -> ApiCollection<T>
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore
    private var cursor: String? = null
    private val seen = mutableSetOf<String>()

    /** Reads the first page again and replaces the list. */
    suspend fun reload() = read(first = true)

    suspend fun loadMore() {
        if (_hasMore.value) read(first = false)
    }

    private suspend fun read(first: Boolean) {
        if (_loading.value) return
        _loading.value = true
        try {
            val page = fetch(if (first) null else cursor)
            _items.value = if (first) page.items else _items.value + page.items
            if (first) seen.clear()
            val next = page.page?.takeIf { it.has_more }?.next_cursor
            _hasMore.value = next != null && seen.add(next)
            cursor = next
            _loaded.value = true
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = failureText(e, serverName)
        } finally {
            _loading.value = false
        }
    }
}

/** "Jan 2, 2026"; the original text when it is not an ISO instant. */
internal fun formatDate(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val instant = parseInstant(iso) ?: return iso
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)
}

/** "Jan 2, 2026, 3:04 AM". */
internal fun formatDateTime(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val instant = parseInstant(iso) ?: return iso
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)
}

private fun parseInstant(iso: String?): Instant? =
    iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "2 h 5 min", "45 min", "30 s". */
internal fun formatWatchTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$total s"
    }
}

/** "1.5 GB"; decimal units, as the server reports them. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
