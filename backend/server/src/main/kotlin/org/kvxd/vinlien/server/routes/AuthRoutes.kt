package org.kvxd.vinlien.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.kvxd.vinlien.server.Config
import org.kvxd.vinlien.server.db.Users
import org.kvxd.vinlien.server.db.repositories.UserRepository
import org.kvxd.vinlien.server.getUserId
import org.kvxd.vinlien.server.getUsername
import org.kvxd.vinlien.shared.models.auth.ChangePasswordReq
import org.kvxd.vinlien.shared.models.auth.User
import org.mindrot.jbcrypt.BCrypt
import java.util.Date

@Serializable
data class AuthReq(val username: String, val pass: String)

fun Route.authRoutes(secret: String) {
    val secureCookie = Config.data.secureCookies
    val cookieExtensions = mapOf("SameSite" to "Strict")

    post("/api/auth/login") {
        val req = call.receive<AuthReq>()

        val userRow = UserRepository.findByUsername(req.username)
        if (userRow != null && BCrypt.checkpw(req.pass, userRow[Users.passwordHash])) {
            val role = userRow[Users.role]
            val id = userRow[Users.id]

            if (role == "PENDING") return@post call.respond(HttpStatusCode.Forbidden, "Account pending admin approval")

            val token = JWT.create().withClaim("username", req.username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 3600000)).sign(Algorithm.HMAC256(secret))
            val refreshToken = JWT.create().withClaim("username", req.username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 604800000)).sign(Algorithm.HMAC256(secret))

            call.response.appendAuthCookies(token, refreshToken, secureCookie, cookieExtensions)
            val isDefaultAdminWithDefaultPassword = (req.username == "admin" && req.pass == "admin")
            call.respond(User(id, req.username, role, null, isDefaultAdminWithDefaultPassword))
        } else {
            call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
        }
    }

    post("/api/auth/register") {
        val req = call.receive<AuthReq>()
        val exists = UserRepository.userExistsByUsername(req.username)
        if (exists) return@post call.respond(HttpStatusCode.Conflict)

        UserRepository.createUser(
            username = req.username,
            passwordHash = BCrypt.hashpw(req.pass, BCrypt.gensalt()),
            role = "PENDING"
        )
        call.respond(HttpStatusCode.Created)
    }

    authenticate("auth-jwt") {
        post("/api/auth/change-password") {
            val username = call.getUsername() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<ChangePasswordReq>()
            UserRepository.updatePasswordByUsername(username, BCrypt.hashpw(req.newPassword, BCrypt.gensalt()))
            call.respond(HttpStatusCode.OK)
        }

        post("/api/auth/delete-data") {
            val userId = call.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            UserRepository.deleteUserData(userId)
            call.respond(HttpStatusCode.OK)
        }
    }

    post("/api/auth/refresh") {
        val refreshToken = call.request.cookies["refresh_token"]
            ?: return@post call.respond(HttpStatusCode.Unauthorized)
        try {
            val decoded = JWT.require(Algorithm.HMAC256(secret)).build().verify(refreshToken)
            val username = decoded.getClaim("username").asString()
            val id = decoded.getClaim("id").asString()

            val userExists = UserRepository.userExistsById(id)
            if (!userExists) return@post call.respond(HttpStatusCode.Unauthorized, "User no longer exists")

            val newToken = JWT.create().withClaim("username", username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 3600000)).sign(Algorithm.HMAC256(secret))
            val newRefreshToken = JWT.create().withClaim("username", username).withClaim("id", id)
                .withExpiresAt(Date(System.currentTimeMillis() + 604800000)).sign(Algorithm.HMAC256(secret))

            call.response.appendAuthCookies(newToken, newRefreshToken, secureCookie, cookieExtensions)
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token")
        }
    }

    post("/api/auth/logout") {
        call.response.clearAuthCookies(secureCookie, cookieExtensions)
        call.respond(HttpStatusCode.OK)
    }
}

private fun ApplicationResponse.appendAuthCookies(
    accessToken: String,
    refreshToken: String,
    secure: Boolean,
    extensions: Map<String, String>
) {
    cookies.append(Cookie("access_token", accessToken, httpOnly = true, secure = secure, path = "/", maxAge = 3600, extensions = extensions))
    cookies.append(Cookie("refresh_token", refreshToken, httpOnly = true, secure = secure, path = "/", maxAge = 604800, extensions = extensions))
}

private fun ApplicationResponse.clearAuthCookies(secure: Boolean, extensions: Map<String, String>) {
    cookies.append(Cookie("access_token", "", httpOnly = true, secure = secure, path = "/", maxAge = 0, extensions = extensions))
    cookies.append(Cookie("refresh_token", "", httpOnly = true, secure = secure, path = "/", maxAge = 0, extensions = extensions))
}
