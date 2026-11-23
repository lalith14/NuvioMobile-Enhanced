package com.nuvio.app.features.details

import androidx.compose.ui.geometry.Rect
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DetailRoute
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Fork: "artwork opening". The tapped poster grows into the details page and shrinks back to
 * its spot on Back, using iOS's own zoom transition (iosApp/iosApp/PosterZoomTransition.swift).
 *
 * Compose side, this only passes facts across:
 * - where the tapped poster is on screen (in points), handed to the page push of that title;
 * - when the details page's artwork is on screen, so the native poster cover can fade away.
 *
 * Swift reads it through `PosterOpenZoom.shared`.
 */
object PosterOpenZoom {
    /** A poster's frame on screen, in points (iOS) from the top left of the window. */
    class Tap(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val cornerRadius: Double,
    )

    private val PressWindow = 3.seconds
    private const val MaxKept = 8

    private var pendingTap: Tap? = null
    private var tappedAt: TimeMark? = null
    private val tapsByRoute = LinkedHashMap<String, Tap>()
    private val readyRoutes = LinkedHashSet<String>()
    private var readyListener: ((String) -> Unit)? = null

    /** Called by the poster card on a tap, with its bounds in pixels and the screen density. */
    internal fun onPosterTapped(boundsPx: Rect?, cornerRadiusPx: Float, density: Float) {
        if (boundsPx == null || density <= 0f || boundsPx.width <= 0f || boundsPx.height <= 0f) {
            pendingTap = null
            tappedAt = null
            return
        }
        pendingTap = Tap(
            x = (boundsPx.left / density).toDouble(),
            y = (boundsPx.top / density).toDouble(),
            width = (boundsPx.width / density).toDouble(),
            height = (boundsPx.height / density).toDouble(),
            cornerRadius = (cornerRadiusPx / density).toDouble(),
        )
        tappedAt = TimeSource.Monotonic.markNow()
    }

    /** Called as a details page is navigated to: the recent tap (if any) belongs to it. */
    internal fun onNavigate(route: DetailRoute) {
        val tap = pendingTap?.takeIf { tappedAt?.elapsedNow()?.let { it < PressWindow } == true }
        pendingTap = null
        tappedAt = null
        val key = key(route.type, route.id)
        readyRoutes.remove(key)
        if (tap != null) {
            tapsByRoute.remove(key)
            tapsByRoute[key] = tap
            while (tapsByRoute.size > MaxKept) tapsByRoute.remove(tapsByRoute.keys.first())
        }
    }

    /** Swift: the tap that opened [route], once (null when it wasn't opened from a poster). */
    fun takeTap(route: AppRoute): Tap? {
        val key = routeKey(route) ?: return null
        return tapsByRoute.remove(key)
    }

    /** Swift: the key [isArtworkReady] and the ready listener use for [route]. */
    fun routeKey(route: AppRoute): String? = (route as? DetailRoute)?.let { key(it.type, it.id) }

    /** Swift: whether the details page of [routeKey] already shows its artwork. */
    fun isArtworkReady(routeKey: String): Boolean = routeKey in readyRoutes

    /** Swift: told the route key each time a details page shows its artwork. */
    fun setArtworkReadyListener(listener: (String) -> Unit) {
        readyListener = listener
    }

    /** The details page of [type]/[id] shows its artwork (or has none to wait for). */
    internal fun artworkReady(type: String, id: String) {
        val key = key(type, id)
        if (!readyRoutes.add(key)) return
        while (readyRoutes.size > MaxKept) readyRoutes.remove(readyRoutes.first())
        readyListener?.invoke(key)
    }

    private fun key(type: String, id: String) = "$type:$id"
}
