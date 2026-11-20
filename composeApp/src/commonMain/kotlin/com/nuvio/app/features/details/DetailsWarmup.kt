package com.nuvio.app.features.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.nuvio.app.navigation.DetailRoute
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Fork: "GTA-style" head start for the details page.
 *
 * - When a details page is opened, its metadata starts loading straight away, before the
 *   native page push and the page's first frame, instead of from the page's first composition.
 * - The poster that was tapped is remembered, so while the details load the page shows that
 *   poster (already in the image cache) behind the loading indicator instead of a black screen.
 */
internal object DetailsWarmup {
    private const val MaxPlaceholders = 8
    private val PressWindow = 3.seconds

    private var tappedPosterUrl: String? = null
    private var tappedAt: TimeMark? = null
    private val placeholders = LinkedHashMap<String, String>()

    fun onPosterTapped(imageUrl: String?) {
        tappedPosterUrl = imageUrl?.takeIf { it.isNotBlank() }
        tappedAt = TimeSource.Monotonic.markNow()
    }

    fun onNavigate(route: DetailRoute) {
        val key = key(route.type, route.id)
        val url = tappedPosterUrl?.takeIf { tappedAt?.elapsedNow()?.let { it < PressWindow } == true }
        tappedPosterUrl = null
        tappedAt = null
        if (url != null) {
            placeholders.remove(key)
            placeholders[key] = url
            while (placeholders.size > MaxPlaceholders) placeholders.remove(placeholders.keys.first())
        }
        runCatching { MetaDetailsRepository.load(route.type, route.id) }
    }

    fun placeholderPoster(type: String, id: String): String? = placeholders[key(type, id)]

    private fun key(type: String, id: String) = "$type:$id"
}

/** Fork: the tapped poster, softly scaled up and darkened, shown while details load. */
@Composable
internal fun DetailsLoadingPlaceholder(type: String, id: String) {
    val url = remember(type, id) { DetailsWarmup.placeholderPoster(type, id) } ?: return
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            // Small, inexact size so the poster already in the memory cache is reused.
            .size(Size(240, 360))
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.6f to Color.Black.copy(alpha = 0.7f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
    }
}
