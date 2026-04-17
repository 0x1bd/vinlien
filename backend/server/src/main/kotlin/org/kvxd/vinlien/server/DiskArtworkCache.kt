package org.kvxd.vinlien.server

import java.io.File
import java.security.MessageDigest

class DiskArtworkCache(
    private val dir: File = File("data/cache/artwork"),
    private val ttlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val maxBytes: Long = 500L * 1024 * 1024
) {
    init { dir.mkdirs() }

    private fun urlToName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun get(url: String): Pair<ByteArray, String>? {
        return try {
            val name = urlToName(url)
            val imgFile = File(dir, name)
            val ctFile = File(dir, "$name.ct")
            if (!imgFile.exists() || !ctFile.exists()) return null
            if (System.currentTimeMillis() - imgFile.lastModified() > ttlMs) {
                imgFile.delete()
                ctFile.delete()
                return null
            }
            imgFile.readBytes() to ctFile.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun put(url: String, bytes: ByteArray, contentType: String) {
        try {
            evictIfNeeded(bytes.size.toLong())
            val name = urlToName(url)
            File(dir, name).writeBytes(bytes)
            File(dir, "$name.ct").writeText(contentType)
        } catch (_: Exception) {

        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded(incoming: Long) {
        val imageFiles = dir.listFiles { f -> !f.name.endsWith(".ct") } ?: return
        var totalSize = imageFiles.sumOf { it.length() }
        if (totalSize + incoming <= maxBytes) return
        imageFiles.sortedBy { it.lastModified() }.forEach { f ->
            if (totalSize + incoming <= maxBytes) return
            totalSize -= f.length()
            f.delete()
            File(dir, "${f.name}.ct").delete()
        }
    }
}
