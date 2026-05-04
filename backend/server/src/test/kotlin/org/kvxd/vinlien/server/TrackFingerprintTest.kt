package org.kvxd.vinlien.server

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackFingerprintTest {

    @Test
    fun `plain title is normalized to lowercase`() {
        assertEquals("hello world", TrackFingerprint.of("Hello World"))
    }

    @Test
    fun `content after dash separator is kept`() {
        assertEquals("song name", TrackFingerprint.of("Artist - Song Name"))
    }

    @Test
    fun `parenthesized content is stripped`() {
        assertEquals("song", TrackFingerprint.of("Song (Official Music Video)"))
    }

    @Test
    fun `bracketed content is stripped`() {
        assertEquals("song", TrackFingerprint.of("Song [HD]"))
    }

    @Test
    fun `noise words are removed`() {
        val fp = TrackFingerprint.of("Song Official Audio HD")
        assert(!fp.contains("official")) { "Expected 'official' to be removed from '$fp'" }
        assert(!fp.contains("audio")) { "Expected 'audio' to be removed from '$fp'" }
        assert(!fp.contains("hd")) { "Expected 'hd' to be removed from '$fp'" }
    }

    @Test
    fun `feat annotation is removed`() {
        val fp = TrackFingerprint.of("Song feat Artist")
        assert(!fp.contains("feat")) { "Expected 'feat' to be removed from '$fp'" }
    }

    @Test
    fun `remix annotation is removed`() {
        val fp = TrackFingerprint.of("Song Remix")
        assert(!fp.contains("remix")) { "Expected 'remix' to be removed from '$fp'" }
    }

    @Test
    fun `non-alphanumeric characters except spaces are removed`() {
        assertEquals("song title", TrackFingerprint.of("Song-Title"))
        // hyphens become spaces and then collapse
        val fp = TrackFingerprint.of("Song...Title")
        assert(!fp.contains(".")) { "Expected dots to be removed from '$fp'" }
    }

    @Test
    fun `multiple whitespace collapses to single space`() {
        assertEquals("song title", TrackFingerprint.of("Song   Title"))
    }

    @Test
    fun `live annotation is removed`() {
        val fp = TrackFingerprint.of("Song Live")
        assert(!fp.contains("live")) { "Expected 'live' to be removed from '$fp'" }
    }

    @Test
    fun `lyric video annotation is removed`() {
        val fp = TrackFingerprint.of("Song Lyric Video")
        assert(!fp.contains("lyric")) { "Expected 'lyric' to be removed from '$fp'" }
        assert(!fp.contains("video")) { "Expected 'video' to be removed from '$fp'" }
    }

    @Test
    fun `same fingerprint for equivalent titles`() {
        val a = TrackFingerprint.of("Song (Official Audio)")
        val b = TrackFingerprint.of("Song [Official Audio]")
        assertEquals(a, b)
    }

    @Test
    fun `hq annotation is removed`() {
        val fp = TrackFingerprint.of("Song HQ")
        assert(!fp.contains("hq")) { "Expected 'hq' to be removed from '$fp'" }
    }
}
