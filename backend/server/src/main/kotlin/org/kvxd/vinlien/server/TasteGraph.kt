package org.kvxd.vinlien.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kvxd.vinlien.shared.models.media.Track
import kotlin.math.ln
import kotlin.math.sqrt

data class TasteTrackSignal(
    val track: Track,
    val weight: Double,
    val timestampMs: Long,
    val source: String
)

data class TasteCapsuleModel(
    val key: String,
    val label: String,
    val features: Map<String, Double>,
    val weight: Double
)

object TasteGraph {
    private const val MAX_FEATURES = 36
    private val json = Json { ignoreUnknownKeys = true }

    private val tokenStopWords = setOf(
        "a", "an", "and", "are", "at", "by", "feat", "featuring", "for", "from", "in", "into",
        "is", "it", "live", "mix", "of", "on", "or", "radio", "remaster", "remastered", "remix",
        "the", "to", "version", "with", "you", "your"
    )

    fun extractFeatures(track: Track): Map<String, Double> {
        val features = mutableMapOf<String, Double>()

        fun add(key: String, weight: Double) {
            val normalized = key.lowercase().trim()
            if (normalized.length < 3 || weight <= 0.0) return
            features[normalized] = (features[normalized] ?: 0.0) + weight
        }

        val primaryArtist = primaryArtist(track)
        add("artist:$primaryArtist", 3.2)
        track.artists
            .map { it.normArtist() }
            .filter { it.isNotBlank() && it != primaryArtist }
            .take(4)
            .forEach { add("coartist:$it", 1.5) }

        track.albumTitle?.normFeatureValue()?.takeIf { it.isNotBlank() }?.let { add("album:$it", 1.4) }
        track.albumId?.normFeatureValue()?.takeIf { it.isNotBlank() }?.let { add("albumid:$it", 1.2) }

        val provider = track.id.substringBefore(":", "").takeIf { it.isNotBlank() }
        provider?.let { add("provider:$it", 0.25) }

        if (track.lastFmUrl != null) add("metadata:lastfm", 0.35)
        track.artworkUrl?.let { artworkUrl ->
            if (!artworkUrl.contains("ytimg.com")) add("metadata:artwork", 0.25)
        }
        if (track.durationMs > 0) add("duration:${durationBucket(track.durationMs)}", 0.35)

        titleTokens(track.title).forEach { add("token:$it", 0.65) }
        track.albumTitle?.let { titleTokens(it).forEach { token -> add("albumtoken:$token", 0.45) } }

        return trimFeatures(features)
    }

    fun buildCapsules(signals: List<TasteTrackSignal>, maxCapsules: Int = 7): List<TasteCapsuleModel> {
        if (signals.isEmpty()) return emptyList()

        val capsules = mutableListOf<MutableCapsule>()
        signals
            .filter { it.weight > 0.0 }
            .sortedWith(compareByDescending<TasteTrackSignal> { it.weight }.thenByDescending { it.timestampMs })
            .take(400)
            .forEachIndexed { index, signal ->
                val features = extractFeatures(signal.track)
                if (features.isEmpty()) return@forEachIndexed

                val best = capsules
                    .map { it to cosine(it.features, features) }
                    .maxByOrNull { it.second }

                val target = when {
                    best != null && best.second >= 0.27 -> best.first
                    capsules.size < maxCapsules -> MutableCapsule("taste-${index + 1}")
                    best != null -> best.first
                    else -> MutableCapsule("taste-${index + 1}")
                }

                if (target !in capsules) capsules.add(target)
                target.add(features, signal.weight)
            }

        return capsules
            .map { it.toModel() }
            .filter { it.weight > 0.0 }
            .sortedByDescending { it.weight }
    }

    fun activeCapsules(
        capsules: List<TasteCapsuleModel>,
        contextTracks: List<Track>,
        limit: Int = 3
    ): List<Pair<TasteCapsuleModel, Double>> {
        if (capsules.isEmpty()) return emptyList()
        val context = centroid(contextTracks.map(::extractFeatures))
        if (context.isEmpty()) return capsules.take(limit).map { it to 1.0 }

        return capsules
            .map { it to cosine(it.features, context) }
            .sortedByDescending { it.second + ln(1.0 + it.first.weight) * 0.04 }
            .take(limit)
    }

    fun capsuleFit(track: Track, capsules: List<Pair<TasteCapsuleModel, Double>>): Double {
        if (capsules.isEmpty()) return 0.0
        val features = extractFeatures(track)
        return capsules.maxOfOrNull { (capsule, contextFit) ->
            cosine(features, capsule.features) * (0.65 + contextFit.coerceIn(0.0, 1.0) * 0.35)
        } ?: 0.0
    }

    fun featureSimilarity(a: Track, b: Track): Double =
        cosine(extractFeatures(a), extractFeatures(b))

    fun featureSimilarity(a: Track, b: Map<String, Double>): Double =
        cosine(extractFeatures(a), b)

    fun centroid(features: List<Map<String, Double>>): Map<String, Double> {
        val merged = mutableMapOf<String, Double>()
        features.forEach { featureSet ->
            featureSet.forEach { (key, weight) ->
                merged[key] = (merged[key] ?: 0.0) + weight
            }
        }
        return trimFeatures(merged)
    }

    fun searchQueries(capsules: List<Pair<TasteCapsuleModel, Double>>, seedTrack: Track, limit: Int = 8): List<String> {
        val queries = mutableListOf<String>()
        val seedArtist = primaryArtist(seedTrack)
        if (seedArtist.isNotBlank()) queries.add(seedArtist)

        capsules.forEach { (capsule, _) ->
            val artists = topFeatureValues(capsule.features, "artist:", 3)
            val albums = topFeatureValues(capsule.features, "album:", 2)
            val tokens = topFeatureValues(capsule.features, "token:", 3)

            queries.addAll(artists)
            artists.firstOrNull()?.let { artist ->
                albums.firstOrNull()?.let { album -> queries.add("$artist $album") }
                tokens.take(2).forEach { token -> queries.add("$artist $token") }
            }
            if (seedArtist.isNotBlank()) tokens.firstOrNull()?.let { token -> queries.add("$seedArtist $token") }
        }

        return queries
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    fun topFeatureValues(features: Map<String, Double>, prefix: String, limit: Int): List<String> =
        features.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key.removePrefix(prefix) }

    fun serializeFeatures(features: Map<String, Double>): String =
        json.encodeToString(trimFeatures(features))

    fun deserializeFeatures(raw: String): Map<String, Double> =
        runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrDefault(emptyMap())

    fun primaryArtist(track: Track): String {
        val fromList = track.artists.firstOrNull()?.normArtist()
        if (!fromList.isNullOrBlank()) return fromList
        return track.artist
            .replace(Regex("""\s+(feat\.?|featuring|ft\.?|with)\s+.*$""", RegexOption.IGNORE_CASE), "")
            .split(",", "&", "/")
            .firstOrNull()
            ?.normArtist()
            .orEmpty()
    }

    private fun titleTokens(value: String): List<String> =
        value.lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in tokenStopWords && it.none(Char::isDigit) }
            .distinct()
            .take(8)

    private fun durationBucket(durationMs: Long): String = when {
        durationMs < 120_000 -> "short"
        durationMs < 240_000 -> "standard"
        durationMs < 420_000 -> "long"
        else -> "extended"
    }

    private fun trimFeatures(features: Map<String, Double>): Map<String, Double> =
        features
            .filterValues { it.isFinite() && it > 0.0 }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_FEATURES)
            .associate { it.key to it.value }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        val dot = small.entries.sumOf { (key, value) -> value * (large[key] ?: 0.0) }
        if (dot <= 0.0) return 0.0
        val aNorm = sqrt(a.values.sumOf { it * it })
        val bNorm = sqrt(b.values.sumOf { it * it })
        return if (aNorm <= 0.0 || bNorm <= 0.0) 0.0 else (dot / (aNorm * bNorm)).coerceIn(0.0, 1.0)
    }

    private fun String.normFeatureValue(): String =
        lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").trim().replace(Regex("""\s+"""), " ")

    private class MutableCapsule(private val key: String) {
        val features = mutableMapOf<String, Double>()
        private var totalWeight = 0.0

        fun add(trackFeatures: Map<String, Double>, weight: Double) {
            totalWeight += weight
            trackFeatures.forEach { (key, featureWeight) ->
                features[key] = (features[key] ?: 0.0) + featureWeight * weight
            }
        }

        fun toModel(): TasteCapsuleModel {
            val trimmed = trimFeatures(features)
            return TasteCapsuleModel(
                key = key,
                label = labelFor(trimmed),
                features = trimmed,
                weight = totalWeight
            )
        }

        private fun labelFor(features: Map<String, Double>): String {
            val artists = topFeatureValues(features, "artist:", 2)
            if (artists.isNotEmpty()) return artists.joinToString(" + ") { it.toDisplayLabel() }
            val tokens = topFeatureValues(features, "token:", 2)
            if (tokens.isNotEmpty()) return tokens.joinToString(" / ") { it.toDisplayLabel() }
            return "Taste pocket"
        }

        private fun String.toDisplayLabel(): String =
            split(" ").joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
    }
}
