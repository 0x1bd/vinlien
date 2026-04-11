package org.kvxd.vinlien.backends

import org.slf4j.LoggerFactory

object BackendDebugger {
    private val log = LoggerFactory.getLogger("vinlien.debug")

    private val enabledProviders: Set<String> by lazy {
        val env = System.getenv("DEBUG_BACKENDS")?.trim() ?: return@lazy emptySet()
        when {
            env.equals("all", ignoreCase = true) || env.equals("true", ignoreCase = true) -> setOf("*")
            env.isNotBlank() -> env.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            else -> emptySet()
        }
    }

    fun isEnabled(providerId: String): Boolean =
        "*" in enabledProviders || providerId.lowercase() in enabledProviders

    fun logRequest(providerId: String, capability: String, url: String) {
        if (!isEnabled(providerId)) return
        log.info("[{}] [{}] --> {}", providerId, capability, url)
    }

    fun logResponse(providerId: String, capability: String, resultCount: Int, body: String) {
        if (!isEnabled(providerId)) return
        val snippet = body.replace(Regex("\\s+"), " ").take(500)
        val countStr = if (resultCount >= 0) "$resultCount result(s)" else "? results"
        log.info("[{}] [{}] <-- {} | body: {}", providerId, capability, countStr, snippet)
    }

    fun logError(providerId: String, capability: String, error: Throwable, url: String = "") {
        if (!isEnabled(providerId)) return
        if (url.isNotEmpty()) {
            log.error("[{}] [{}] ERROR on {} — {}", providerId, capability, url, error.message)
        } else {
            log.error("[{}] [{}] ERROR — {}", providerId, capability, error.message)
        }
    }

    fun logTimeout(providerId: String, capability: String) {
        log.warn("[engine] [{}] timed out on {}", providerId, capability)
    }

    fun logProviders(providers: List<MusicProvider>) {
        if (providers.isEmpty()) {
            log.info("[engine] No providers registered")
            return
        }
        log.info("[engine] Registered {} provider(s):", providers.size)
        providers.forEach { p ->
            val caps = p.capabilities.joinToString(", ") { it.name }
            log.info("[engine]   {} ({}) — [{}]", p.name, p.id, caps)
        }
        val ids = providers.joinToString(",") { it.id }
        log.info("[engine] Tip: set DEBUG_BACKENDS={}  (or 'all') to trace HTTP calls", ids)
    }
}
