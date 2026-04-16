package org.kvxd.vinlien.server

object TrackFingerprint {

    fun of(title: String): String {
        var t = title.lowercase()
        if (t.contains(" - ")) t = t.substringAfter(" - ")
        t = t.replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        t = t.replace(Regex("[^a-z0-9 ]"), "")
        t = t.replace(Regex("\\b(official|music video|lyric video|audio|live|remix|hd|hq|ft|feat)\\b"), "")
        return t.trim().replace(Regex("\\s+"), " ")
    }
}
