package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * LRU cache for completion results.
 *
 * Inspired by the caching strategy of deepseek-copilot's TriggerCenter.
 * Uses (filePath, line, column±5, prefixNormalized) as the cache key.
 * When the user triggers completion near the same position again, the cached result is returned directly, skipping the API call.
 *
 * Thread safety: a ReentrantReadWriteLock protects concurrent access.
 */
class CompletionCache(private val maxSize: Int = 20) {

    companion object {
        private val LOG = Logger.getInstance(CompletionCache::class.java)

        /** Position matching window size (in characters) */
        private const val POSITION_WINDOW = 5

        /** Global singleton */
        @Volatile
        private var _instance: CompletionCache? = null

        fun getInstance(): CompletionCache {
            if (_instance == null) {
                synchronized(this) {
                    if (_instance == null) {
                        _instance = CompletionCache()
                    }
                }
            }
            return _instance!!
        }

        /** Updates the cache size from application settings */
        fun resetInstance(maxSize: Int) {
            synchronized(this) {
                _instance = CompletionCache(maxSize)
            }
        }
    }

    /** Cache key */
    data class CacheKey(
        val filePath: String,
        val line: Int,
        val column: Int,
        /** Normalized prefix text (first 50 characters) */
        val prefixNormalized: String
    )

    /** Cache entry */
    data class CacheEntry(
        val key: CacheKey,
        val suggestion: String,
        /** Number of cache hits */
        var hitCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Underlying LRU cache (access-order = true) */
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    private val lock = ReentrantReadWriteLock()

    // ==================== Public API ====================

    /**
     * Looks up the cache based on the context.
     *
     * @param filePath the file path
     * @param line the caret line number (0-based)
     * @param column the caret column number (0-based)
     * @param prefix the text before the caret
     * @return the cached completion text, or null on a miss
     */
    fun get(filePath: String, line: Int, column: Int, prefix: String): String? {
        if (maxSize <= 0) return null

        val normalizedPrefix = normalizePrefix(prefix)

        return lock.read<String?> {
            // Exact match
            val exactKey = CacheKey(filePath, line, column, normalizedPrefix)
            val exactEntry = cache[exactKey]
            if (exactEntry != null) {
                exactEntry.hitCount++
                LOG.debug("Cache HIT (exact): file=$filePath line=$line col=$column hits=${exactEntry.hitCount}")
                return@read exactEntry.suggestion
            }

            // Approximate match: same line, column within ±POSITION_WINDOW
            val candidates = cache.values.filter { entry ->
                entry.key.filePath == filePath &&
                    entry.key.line == line &&
                    kotlin.math.abs(entry.key.column - column) <= POSITION_WINDOW
            }

            // When approximate matches exist, try fuzzy prefix matching
            for (entry in candidates) {
                if (normalizedPrefix.startsWith(entry.key.prefixNormalized) ||
                    entry.key.prefixNormalized.startsWith(normalizedPrefix)
                ) {
                    entry.hitCount++
                    LOG.debug("Cache HIT (fuzzy): file=$filePath line=$line col=$column hits=${entry.hitCount}")
                    return@read entry.suggestion
                }
            }

            LOG.debug("Cache MISS: file=$filePath line=$line col=$column")
            null
        }
    }

    /**
     * Records a completion result into the cache.
     */
    fun put(filePath: String, line: Int, column: Int, prefix: String, suggestion: String) {
        if (maxSize <= 0) return
        if (suggestion.isBlank()) return

        val normalizedPrefix = normalizePrefix(prefix)
        val key = CacheKey(filePath, line, column, normalizedPrefix)
        val entry = CacheEntry(key = key, suggestion = suggestion)

        lock.write {
            cache[key] = entry
            LOG.debug("Cache PUT: file=$filePath line=$line col=$column suggestion_len=${suggestion.length}")
        }
    }

    /** Clears the cache */
    fun clear() {
        lock.write {
            cache.clear()
        }
    }

    /** Current cache size */
    fun size(): Int = lock.read { cache.size }

    /** Total number of cache hits */
    fun totalHits(): Int = lock.read { cache.values.sumOf { it.hitCount } }

    // ==================== Helper methods ====================

    /** Normalizes the prefix text: takes the first 50 characters and removes excess whitespace */
    private fun normalizePrefix(prefix: String): String {
        return prefix.trimEnd().take(50).replace(Regex("\\s+"), " ")
    }
}
