package com.bloemstudios.admin.requests

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Reads a poster image from its (https) address; null when it cannot be read. Tests provide their own. */
typealias PosterLoader = suspend (url: String) -> ImageBitmap?

val LocalPosterLoader = staticCompositionLocalOf<PosterLoader> { { url -> TmdbPosters.load(url) } }

/** Loads TMDB posters over plain HTTPS with a small in-memory cache; the app has no image library. */
object TmdbPosters {
    private const val CACHE_BYTES = 8 * 1024 * 1024
    private const val TIMEOUT_MILLIS = 10_000
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it.asImageBitmap() }
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MILLIS
                    connection.readTimeout = TIMEOUT_MILLIS
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                    cache.put(url, bitmap)
                    bitmap.asImageBitmap()
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** A request's TMDB poster, or a plain tile when it has none or it cannot be read. Decorative: the title names the request. */
@Composable
fun RequestPoster(path: String?, modifier: Modifier = Modifier, size: String = "w92") {
    val url = RequestQueue.posterUrl(path, size)
    val loader = LocalPosterLoader.current
    var image by remember(url, loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, loader) {
        image = if (url == null) null else try {
            loader(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(VeilShapes.ControlCorner))
            .background(VeilColors.Card)
            .clearAndSetSemantics { },
    ) {
        val loaded = image
        if (loaded != null) Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else LucideIcon(R.drawable.lucide_film, contentDescription = null, tint = VeilColors.Muted, modifier = Modifier.align(Alignment.Center))
    }
}
