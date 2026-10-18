package dev.lumen.tv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide cache for network fetches. Several screens show the same data (weather on the
 * home screen, in the screensaver header, its forecast band and night mode...); this makes
 * them share one download per [ttlMillis] instead of each fetching on its own. Concurrent
 * callers for the same key wait for the one fetch in flight. Failures aren't cached.
 */
class Memo<K : Any, V : Any>(private val ttlMillis: Long) {

    private val entries = mutableMapOf<K, Pair<Long, V>>()
    private val locks = mutableMapOf<K, Mutex>()

    suspend fun get(key: K, fetch: suspend () -> V?): V? {
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val cached = synchronized(entries) { entries[key] }
            if (cached != null && System.currentTimeMillis() - cached.first < ttlMillis) return@withLock cached.second
            fetch()?.also { value -> synchronized(entries) { entries[key] = System.currentTimeMillis() to value } }
        }
    }
}
