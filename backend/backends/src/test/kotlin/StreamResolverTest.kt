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

    private class FakeAudioProvider(private vararg val tracks: Track) : MusicProvider {
        override val id = "stream"
        override val name = "Stream"
        override val capabilities = setOf(Capability.AUDIO_STREAM)

        override suspend fun searchAudio(query: String): List<Track> = tracks.toList()

        override suspend fun resolveStream(track: Track): StreamResolutionResult =
            StreamResolutionResult.Success("https://streams.test/${track.id}", id)
    }
}
