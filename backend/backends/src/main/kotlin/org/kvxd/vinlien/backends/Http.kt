package org.kvxd.vinlien.backends

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

internal val sharedJson = Json { ignoreUnknownKeys = true }

internal val httpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 10000
    }
}

internal suspend fun fetch(url: String, headersMap: Map<String, String> = emptyMap()): String {
    val response = httpClient.get(url) {
        header(HttpHeaders.UserAgent, "Mozilla/5.0 Vinlien")
        headersMap.forEach { (k, v) -> header(k, v) }
    }
    if (!response.status.isSuccess()) throw Exception("HTTP ${response.status.value} for $url")
    return response.bodyAsText()
}

internal suspend fun fetchDebug(
    url: String,
    headersMap: Map<String, String> = emptyMap(),
    providerId: String,
    capability: String
): String {
    BackendDebugger.logRequest(providerId, capability, url)
    return try {
        val body = fetch(url, headersMap)
        BackendDebugger.logResponse(providerId, capability, -1, body)
        body
    } catch (e: Exception) {
        BackendDebugger.logError(providerId, capability, e, url)
        throw e
    }
}