package com.deepseek.plugin.api

import com.intellij.openapi.application.ApplicationManager
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Globally shared OkHttpClient singleton.
 *
 * All API clients share the same connection pool and thread pool to avoid recreating resources.
 *
 * Timeout strategy:
 * - [SHARED_CLIENT]: relaxed timeouts (connect 30s / read 120s / call 180s),
 *   suitable for streaming Chat requests (needs to keep the connection open to read the token stream).
 * - [chatSyncClient]: non-streaming Chat requests, callTimeout=90s.
 * - [completionClient]: FIM completion requests, callTimeout=30s for fast response.
 * - [stepFunClient]: image parsing, longer write timeout (uploading large images), callTimeout=180s.
 * - [translateClient]: translation requests, uses a dedicated thread pool and connection pool so it never blocks Chat.
 */
object HttpClientProvider {

    /** Base connection pool config — shared by all variants */
    private val connectionPool = okhttp3.ConnectionPool(
        maxIdleConnections = 16,
        keepAliveDuration = 10,
        timeUnit = TimeUnit.MINUTES
    )

    /** Default Dispatcher — maximum concurrent requests */
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 12
    }

    init {
        // H5: Install a global uncaught exception handler to prevent OkHttp callback threads from dying silently
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            System.err.println("[HttpClientProvider] Uncaught exception on thread ${thread.name}: ${throwable.message}")
            // Do not rethrow, to avoid crashing the IDE
        }
    }

    /** Most relaxed client: used for streaming chat (may have long periods with no data arriving) */
    val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .build()

    /** Non-streaming Chat client (used by Agent Actions), shorter read timeout */
    val chatSyncClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** FIM code completion client, fast response */
    val completionClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** StepFun image parsing client, longer write timeout (uploading large images) */
    val stepFunClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    // ══════════════════════════════════════════════════════════════
    //  Translation client — fully independent connection pool and dispatcher, no interference with Chat
    // ══════════════════════════════════════════════════════════════

    /** Dedicated connection pool for translation */
    private val translateConnectionPool = okhttp3.ConnectionPool(
        maxIdleConnections = 2,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    /** Dedicated translation Dispatcher (independent thread pool) */
    private val translateDispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 4
        maxRequestsPerHost = 2
    }

    /**
     * Translation client — uses a fully independent Dispatcher and ConnectionPool,
     * sharing no thread or connection resources with Chat / FIM / StepFun,
     * ensuring translation requests never block conversations or other features.
     */
    val translateClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .connectionPool(translateConnectionPool)
        .dispatcher(translateDispatcher)
        .build()

    // ══════════════════════════════════════════════════════════════
    // H3: Request rate limiting (Sliding Window Log)
    // ══════════════════════════════════════════════════════════════

    /**
     * Sliding-window rate limiter.
     *
     * Limits the request rate against the same API endpoint to avoid triggering API rate limiting (429).
     * Uses ConcurrentLinkedDeque to store timestamps; thread-safe and lock-free.
     */
    class RateLimiter(
        /** Time window length (milliseconds) */
        private val windowMs: Long = 60_000L,
        /** Maximum number of requests allowed within the window */
        private val maxRequests: Int = 30
    ) {
        private val timestamps = ConcurrentLinkedDeque<Long>()

        /**
         * Checks whether the current request is allowed to proceed.
         * @return true=allowed, false=rate-limited
         */
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            // Remove old timestamps outside the window
            val cutoff = now - windowMs
            while (true) {
                val oldest = timestamps.peekFirst()
                if (oldest == null || oldest >= cutoff) break
                timestamps.pollFirst()
            }
            if (timestamps.size >= maxRequests) {
                return false
            }
            timestamps.addLast(now)
            return true
        }

        /** Resets the counter */
        fun reset() {
            timestamps.clear()
        }
    }

    /** General Chat API rate limiter: 60 per minute (pipeline mode: 4 stages x 15 rounds) */
    val chatRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 60)

    /** FIM completion rate limiter: 60 per minute */
    val completionRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 60)

    /** StepFun image parsing rate limiter: 10 per minute */
    val stepFunRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 10)
}
