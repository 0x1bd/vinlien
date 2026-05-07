import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.MusicProvider
import org.kvxd.vinlien.backends.StreamResolutionResult
import org.kvxd.vinlien.backends.StreamResolver
import org.kvxd.vinlien.shared.models.media.Track

class StreamResolverTest {
    @Test
    fun `unversioned target rejects slowed candidate`() = runTest {
        val provider = FakeAudioProvider(
            Track(
                id = "stream:slowed",
                artist = "L.___",
                title = "Decline ( slowed ) - plaguedoll, kellv",
                durationMs = 180_000L
            ),
            Track(
                id = "stream:original",
                artist = "plaguedoll - Topic",
                title = "Decline",
                durationMs = 120_000L
            )
        )
        val resolver = StreamResolver(listOf(provider))

        val streamUrl = resolver.resolve(
            Track(
                id = "itunes:decline",
                artist = "plaguedoll, KellV",
                title = "Decline",
                durationMs = 120_000L
            )
        )

        assertEquals("https://streams.test/stream:original", streamUrl)
    }

    @Test
    fun `comma separated target artists match individual collaborators`() = runTest {
        val provider = FakeAudioProvider(
            Track(
                id = "stream:title-only",
                artist = "Someone Else",
                title = "Decline",
                durationMs = 120_000L
            ),
            Track(
                id = "stream:artist-match",
                artist = "plaguedoll - Topic",
                title = "Decline",
                durationMs = 120_000L
            )
        )
        val resolver = StreamResolver(listOf(provider))

        val streamUrl = resolver.resolve(
            Track(
                id = "itunes:decline",
                artist = "plaguedoll, KellV",
                title = "Decline",
                durationMs = 120_000L
            )
        )

        assertEquals("https://streams.test/stream:artist-match", streamUrl)
    }

    @Test
    fun `raw youtube id resolves through youtube music provider`() = runTest {
        val provider = FakeAudioProvider(id = "ytmusic")
        val resolver = StreamResolver(listOf(provider))

        val streamUrl = resolver.resolve(
            Track(
                id = "0MP3eBOoZj0",
                artist = "PR1SVX",
                title = "Crystals",
                durationMs = 63_000L
            )
        )

        assertEquals("https://streams.test/0MP3eBOoZj0", streamUrl)
    }

    @Test
    fun `duration-doubled visualizer does not beat matching song`() = runTest {
        val provider = FakeAudioProvider(
            Track(
                id = "ytmusic:visualizer",
                artist = "PR1SVX",
                title = "PR1SVX - CRYSTALS [Official Visualizer]",
                durationMs = 125_000L
            ),
            Track(
                id = "ytmusic:song",
                artist = "PR1SVX",
                title = "CRYSTALS",
                durationMs = 69_000L
            ),
            id = "ytmusic"
        )
        val resolver = StreamResolver(listOf(provider))

        val streamUrl = resolver.resolve(
            Track(
                id = "itunes:crystals",
                artist = "PR1SVX",
                title = "Crystals",
                durationMs = 69_000L
            )
        )

        assertEquals("https://streams.test/ytmusic:song", streamUrl)
    }

    @Test
    fun `native soundcloud visualizer is ranked against youtube music song`() = runTest {
        val soundCloud = FakeAudioProvider(id = "sc")
        val youtubeMusic = FakeAudioProvider(
            Track(
                id = "ytmusic:song",
                artist = "PR1SVX",
                title = "CRYSTALS",
                durationMs = 69_000L
            ),
            id = "ytmusic"
        )
        val resolver = StreamResolver(listOf(soundCloud, youtubeMusic))

        val result = resolver.resolveWithProvider(
            Track(
                id = "sc:visualizer",
                artist = "PR1SVX",
                title = "PR1SVX - CRYSTALS [Official Visualizer]",
                durationMs = 125_000L
            )
        )

        assertEquals("https://streams.test/ytmusic:song", result.streamUrl)
        assertEquals("ytmusic", result.providerId)
    }

    private class FakeAudioProvider(
        private vararg val tracks: Track,
        override val id: String = "stream"
    ) : MusicProvider {
        override val name = "Stream"
        override val capabilities = setOf(Capability.AUDIO_STREAM)

        override suspend fun searchAudio(query: String): List<Track> = tracks.toList()

        override suspend fun resolveStream(track: Track): StreamResolutionResult =
            StreamResolutionResult.Success("https://streams.test/${track.id}", id)
    }
}
