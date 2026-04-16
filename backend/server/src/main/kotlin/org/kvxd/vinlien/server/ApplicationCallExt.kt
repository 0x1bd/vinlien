package org.kvxd.vinlien.server

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun ApplicationCall.getUserId(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()

fun ApplicationCall.getUsername(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("username")?.asString()
