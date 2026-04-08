package org.kvxd.vinlien.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64

fun generateJwtSecret(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

@Serializable
data class ServerConfig(
    val jwtSecret: String = "",
    val secureCookies: Boolean = false,
    val lastFmApiKey: String = "",
    val lastFmUsername: String = "",
    val dbUrl: String = "jdbc:postgresql://localhost:5432/invidious",
    val dbUser: String = "invidious",
    val dbPass: String = "invidious",
    val invidiousUrl: String = "http://localhost:3000"
)

object Config {
    private val file = File("data/config.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    var data = ServerConfig()
        private set

    init {
        file.parentFile?.mkdirs()

        var saveNeeded = false
        if (file.exists()) {
            data = json.decodeFromString(file.readText())
        } else {
            saveNeeded = true
            file.createNewFile()
        }

        if (data.jwtSecret.isBlank()) {
            data = data.copy(jwtSecret = generateJwtSecret())
            saveNeeded = true
        }

        System.getenv("JWT_SECRET")?.takeIf { it.isNotBlank() }?.let { data = data.copy(jwtSecret = it) }
        System.getenv("SECURE_COOKIES")?.toBooleanStrictOrNull()?.let { data = data.copy(secureCookies = it) }
        System.getenv("LASTFM_API_KEY")?.takeIf { it.isNotBlank() }?.let { data = data.copy(lastFmApiKey = it) }
        System.getenv("LASTFM_USERNAME")?.takeIf { it.isNotBlank() }?.let { data = data.copy(lastFmUsername = it) }
        System.getenv("DB_URL")?.takeIf { it.isNotBlank() }?.let { data = data.copy(dbUrl = it) }
        System.getenv("DB_USER")?.takeIf { it.isNotBlank() }?.let { data = data.copy(dbUser = it) }
        System.getenv("DB_PASSWORD")?.takeIf { it.isNotBlank() }?.let { data = data.copy(dbPass = it) }
        System.getenv("INVIDIOUS_URL")?.takeIf { it.isNotBlank() }?.let { data = data.copy(invidiousUrl = it) }

        if (saveNeeded) {
            file.writeText(json.encodeToString(data))
        }
    }
}