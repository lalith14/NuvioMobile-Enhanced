package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Episode ratings from IMDb's free daily datasets, published once a day by this fork's
 * "IMDb episode ratings data" workflow on GitHub Pages. Each show lives in one small file
 * chosen by its IMDb number modulo 1000 (tt0460649 -> 649.json).
 */
internal object ImdbDatasetEpisodeRatings {
    private const val BaseUrl = "https://lalith14.github.io/NuvioMobile-Enhanced/imdb-ratings/v1"
    private const val ShardCount = 1000
    private const val FreshMs = 6L * 60 * 60 * 1000
    private const val RetryAfterFailureMs = 2L * 60 * 1000
    private const val TimeoutMs = 10_000L
    private const val MaxCachedShards = 40

    private class Shard(val shows: JsonObject?, val fetchedAt: Long, val failedAt: Long = 0L)

    private val log = Logger.withTag("ImdbDatasetRatings")
    private val json = Json { ignoreUnknownKeys = true }
    private val shardIdRegex = Regex("^tt(\\d+)$")
    private val shards = LinkedHashMap<Int, Shard>()
    private val lock = Mutex()

    /**
     * Ratings for one show as (season, episode) -> rating.
     * An empty map means IMDb has no episode ratings for the show; null means the file
     * could not be downloaded (so the caller can fall back to what it showed before).
     */
    suspend fun ratingsFor(imdbId: String): Map<Pair<Int, Int>, Double>? {
        val shardIndex = shardIdRegex.find(imdbId.trim())?.groupValues?.get(1)?.toLongOrNull()
            ?.let { (it % ShardCount).toInt() }
            ?: return null
        val shows = lock.withLock { shardFor(shardIndex) } ?: return null
        val seasons = shows[imdbId.trim()] as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, Double>()
        seasons.forEach { (season, episodes) ->
            val seasonNumber = season.toIntOrNull() ?: return@forEach
            (episodes as? JsonObject)?.forEach { (episode, rating) ->
                val episodeNumber = episode.toIntOrNull() ?: return@forEach
                val value = runCatching { rating.jsonPrimitive.doubleOrNull }.getOrNull()
                if (value != null && value > 0.0) result[seasonNumber to episodeNumber] = value
            }
        }
        return result
    }

    private suspend fun shardFor(index: Int): JsonObject? {
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = shards[index]
        if (cached?.shows != null && now - cached.fetchedAt in 0..FreshMs) return cached.shows
        if (cached != null && cached.failedAt > 0 && now - cached.failedAt in 0..RetryAfterFailureMs) {
            return cached.shows
        }

        val url = "$BaseUrl/${index.toString().padStart(3, '0')}.json"
        val downloaded = withTimeoutOrNull(TimeoutMs) {
            withContext(Dispatchers.Default) {
                runCatching { json.parseToJsonElement(httpGetText(url)).jsonObject }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Could not load IMDb ratings file $url: ${error.message}" }
                    }
                    .getOrNull()
            }
        }

        shards.remove(index)
        shards[index] = if (downloaded != null) {
            Shard(shows = downloaded, fetchedAt = now)
        } else {
            // Keep an older copy if we have one; it is still better than nothing.
            Shard(shows = cached?.shows, fetchedAt = cached?.fetchedAt ?: 0L, failedAt = now)
        }
        while (shards.size > MaxCachedShards) shards.remove(shards.keys.first())
        return shards[index]?.shows
    }
}
