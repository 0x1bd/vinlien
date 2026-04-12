package org.kvxd.vinlien.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.kvxd.vinlien.backends.AggregationEngine
import org.kvxd.vinlien.backends.Capability
import org.kvxd.vinlien.backends.deezer.DeezerMetadataProvider
import org.kvxd.vinlien.backends.invidious.LocalInvidiousBackend
import org.kvxd.vinlien.backends.itunes.ItunesMetadataProvider
import org.kvxd.vinlien.backends.lastfm.LastFmMetadataProvider
import org.kvxd.vinlien.backends.musicbrainz.MusicBrainzMetadataProvider
import org.kvxd.vinlien.backends.soundcloud.SoundCloudBackend
import org.kvxd.vinlien.server.routes.*
import org.slf4j.event.Level
import java.io.File

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    DatabaseFactory.init()

    install(CallLogging) {
        level = Level.INFO
        filter { call -> (call.response.status()?.value ?: 0) >= 400 }
    }
    install(ContentNegotiation) { json() }
    install(io.ktor.server.sse.SSE)

    intercept(ApplicationCallPipeline.Plugins) {
        val origin = call.request.headers[HttpHeaders.Origin] ?: return@intercept
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type, Authorization")
        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, DELETE, PATCH, OPTIONS")
        if (call.request.httpMethod == HttpMethod.Options) {
            call.response.header(HttpHeaders.AccessControlMaxAge, "86400")
            call.respond(HttpStatusCode.OK)
            finish()
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JWT.require(Algorithm.HMAC256(Config.data.jwtSecret)).build())
            authHeader { call ->
                call.request.cookies["access_token"]
                    ?.let { runCatching { HttpAuthHeader.Single("Bearer", it) }.getOrNull() }
                    ?: call.request.parseAuthorizationHeader()
            }
            validate { credential ->
                val userId = credential.payload.getClaim("id")?.asString()
                    ?.takeIf { it.isNotEmpty() } ?: return@validate null
                val userExists = DatabaseFactory.dbQuery {
                    Users.selectAll().where { Users.id eq userId }.count() > 0
                }
                if (!userExists) return@validate null
                credential.payload.getClaim("username").asString()
                    .takeIf { it.isNotEmpty() }
                    ?.let { JWTPrincipal(credential.payload) }
            }
        }
    }

    val providers = buildList {
        add(DeezerMetadataProvider())
        if (Config.data.lastFmApiKey.isNotBlank()) {
            add(LastFmMetadataProvider(Config.data.lastFmApiKey, Config.data.lastFmUsername))
        }
        add(ItunesMetadataProvider())
        add(MusicBrainzMetadataProvider())
        add(SoundCloudBackend())
        add(LocalInvidiousBackend(Config.data.invidiousUrl))
    }

    val engine = AggregationEngine(providers)

    routing {
        authRoutes(Config.data.jwtSecret)

        get("/api/providers") {
            call.respond(mapOf(
                "metadata" to providers
                    .filter { p -> p.capabilities.any { it != Capability.AUDIO_STREAM } }
                    .map { it.name },
                "audio" to providers
                    .filter { Capability.AUDIO_STREAM in it.capabilities }
                    .map { it.name }
            ))
        }

        authenticate("auth-jwt") {
            searchRoutes(engine)
            streamRoutes(engine)
            feedRoutes(engine)
            adminRoutes()
            playlistRoutes()
        }

        val frontendDist = listOf("frontend/build", "../../frontend/build", "../frontend/build")
            .map(::File)
            .firstOrNull { it.exists() && it.isDirectory }
            ?: File("frontend/build")

        get("/{...}") {
            val path = call.request.path().removePrefix("/")

            val requestedFile = File(frontendDist, path).canonicalFile
            val baseDir = frontendDist.canonicalFile

            if (path.isNotEmpty() && requestedFile.path.startsWith(baseDir.path) && requestedFile.exists() && requestedFile.isFile) {
                if (path.startsWith("_app/immutable/")) {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                }
                call.respondFile(requestedFile)
                return@get
            }

            val isAsset = path.startsWith("_app/") ||
                    listOf(".js", ".css", ".ico", ".png", ".svg", ".json").any { path.endsWith(it) }

            if (path.startsWith("api/") || isAsset) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val indexFile = File(frontendDist, "index.html")
            if (indexFile.exists()) {
                call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                call.respondFile(indexFile)
            } else {
                call.respond(HttpStatusCode.NotFound, "Frontend build not found. Run npm run build.")
            }
        }
    }
}