package org.kvxd.vinlien.backends

import org.slf4j.LoggerFactory

object Profiler {
    private val log = LoggerFactory.getLogger(Profiler::class.java)

    suspend fun <T> measure(label: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsedMs = System.currentTimeMillis() - start
            log.debug("[profiler] {} took {}ms", label, elapsedMs)
        }
    }
}
