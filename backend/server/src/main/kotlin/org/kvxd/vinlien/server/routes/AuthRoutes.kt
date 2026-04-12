package org.kvxd.vinlien.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.kvxd.vinlien.server.Config
import org.kvxd.vinlien.server.DatabaseFactory.dbQuery
import org.kvxd.vinlien.server.Users
import org.kvxd.vinlien.shared.models.ChangePasswordReq
import org.kvxd.vinlien.shared.models.User
import org.mindrot.jbcrypt.BCrypt
import java.util.Date
import java.util.UUID

@Serializable
data class AuthReq(val username: String, val pass: String)

fun Route.authRoutes(secret: String) {
    val secureCookie = Config.data.secureCookies
    val cookieExtensions = mapOf("SameSite" to "Strict")

    post("/api/auth/login") {
        val req = call.receive<AuthReq>()

        val userRow = dbQuery { Users.selectAll().where { Users.username eq req.username }.singleOrNull() }
        if (userRow != null && BCrypt.checkpw(req.pass, userRow[Users.passwordHash])) {
            val role = userRow[Users.role]
            val id = userRow[Users.id]

            if (role == "PENDING") return@post call.respond(HttpStatusCode.Forbidden, "Account pending admin approval")

            val token = JWT.create().withClaim("username", req.username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 3600000)).sign(Algorithm.HMAC256(secret))
            val refreshToken = JWT.create().withClaim("username", req.username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 604800000)).sign(Algorithm.HMAC256(secret))

            call.response.cookies.append(
                Cookie(
                    name = "access_token",
                    value = token,
                    httpOnly = true,
                    secure = secureCookie,
                    path = "/",
                    maxAge = 3600,
                    extensions = cookieExtensions
                )
            )
            call.response.cookies.append(
                Cookie(
                    name = "refresh_token",
                    value = refreshToken,
                    httpOnly = true,
                    secure = secureCookie,
                    path = "/",
                    maxAge = 604800,
                    extensions = cookieExtensions
                )
            )

            // default admin requires a password change
            val isDefaultAdminWithDefaultPassword = (req.username == "admin" && req.pass == "admin")
            call.respond(User(id, req.username, role, null, isDefaultAdminWithDefaultPassword))
        } else {
            call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
        }
    }

    post("/api/auth/register") {
        val req = call.receive<AuthReq>()
        val exists = dbQuery { Users.selectAll().where { Users.username eq req.username }.count() > 0 }
        if (exists) return@post call.respond(HttpStatusCode.Conflict)

        dbQuery {
            Users.insert {
                it[id] = UUID.randomUUID().toString()
                it[username] = req.username
                it[role] = "PENDING"
                it[passwordHash] = BCrypt.hashpw(req.pass, BCrypt.gensalt())
            }
        }
        call.respond(HttpStatusCode.Created)
    }

    authenticate("auth-jwt") {
        post("/api/auth/change-password") {
            val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val username = principal.payload.getClaim("username").asString()
            val req = call.receive<ChangePasswordReq>()

            dbQuery {
                Users.update({ Users.username eq username }) {
                    it[passwordHash] = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }

    post("/api/auth/refresh") {
        val refreshToken =
            call.request.cookies["refresh_token"] ?: return@post call.respond(HttpStatusCode.Unauthorized)
        try {
            val decoded = JWT.require(Algorithm.HMAC256(secret)).build().verify(refreshToken)
            val username = decoded.getClaim("username").asString()
            val id = decoded.getClaim("id").asString()

            val newToken = JWT.create().withClaim("username", username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 3600000)).sign(Algorithm.HMAC256(secret))
            val newRefreshToken = JWT.create().withClaim("username", username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 604800000)).sign(Algorithm.HMAC256(secret))

            call.response.cookies.append(
                Cookie(
                    name = "access_token",
                    value = newToken,
                    httpOnly = true,
                    secure = secureCookie,
                    path = "/",
                    maxAge = 3600,
                    extensions = cookieExtensions
                )
            )
            call.response.cookies.append(
                Cookie(
                    name = "refresh_token",
                    value = newRefreshToken,
                    httpOnly = true,
                    secure = secureCookie,
                    path = "/",
                    maxAge = 604800,
                    extensions = cookieExtensions
                )
            )

            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token")
        }
    }

    post("/api/auth/logout") {
        call.response.cookies.append(
            Cookie(
                name = "access_token",
                value = "",
                httpOnly = true,
                secure = secureCookie,
                path = "/",
                maxAge = 0,
                extensions = cookieExtensions
            )
        )
        call.response.cookies.append(
            Cookie(
                name = "refresh_token",
                value = "",
                httpOnly = true,
                secure = secureCookie,
                path = "/",
                maxAge = 0,
                extensions = cookieExtensions
            )
        )
        call.respond(HttpStatusCode.OK)
    }
}