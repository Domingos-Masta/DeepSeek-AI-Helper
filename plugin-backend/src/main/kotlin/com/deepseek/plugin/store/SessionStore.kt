package com.deepseek.plugin.store

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.ChatSession
import com.deepseek.plugin.api.SessionException
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists chat sessions to disk, loads them on startup.
 * Sessions are kept indefinitely.
 *
 * Crash-resilient design:
 * - Atomic write: write to .tmp first, then rename, to avoid file corruption if a crash happens mid-write
 * - Backup file: the .bak file is synced on every successful save
 * - Auto-recovery: when the main file is corrupted, restore automatically from .bak
 *
 * File: <project>/.idea/deepseek-sessions.json
 * Backup: <project>/.idea/deepseek-sessions.json.bak
 */
class SessionStore(private val projectBasePath: String?) {

    companion object {
        private val LOG = Logger.getInstance(SessionStore::class.java)
        private val gson = Gson()
        private const val FILE_NAME = "deepseek-sessions.json"
        private const val BACKUP_NAME = "deepseek-sessions.json.bak"
    }

    data class SessionData(
        val name: String,
        val messages: MutableList<ChatMessage>,
        var totalTokens: Int,
        var lastActiveTime: Long
    )

    data class StoreData(
        val sessions: MutableList<SessionData> = mutableListOf(),
        var sessionCounter: Int = 1
    )

    /** Main file */
    private val file: File?
        get() {
            val base = projectBasePath ?: return null
            val dir = File(base, ".idea")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, FILE_NAME)
        }

    /** Backup file */
    private val backupFile: File?
        get() {
            val base = projectBasePath ?: return null
            val dir = File(base, ".idea")
            return File(dir, BACKUP_NAME)
        }

    /**
     * Load sessions from disk. Returns null if nothing saved.
     * Automatically restores a corrupted main file from the backup.
     */
    fun load(): Pair<List<ChatSession>, Int>? {
        val f = file ?: return null
        if (!f.exists()) return null

        // Try loading the main file
        val primary = tryLoad(f)
        if (primary != null) return primary

        // Main file corrupted; try restoring from backup
        val bak = backupFile
        if (bak != null && bak.exists()) {
            LOG.warn("Main session file corrupted; restoring from backup: ${bak.absolutePath}")
            val restored = tryLoad(bak)
            if (restored != null) {
                // Restore succeeded → overwrite the main file with the backup
                try {
                    Files.copy(bak.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    LOG.warn("Unable to restore the session file from backup", e)
                }
                return restored
            }
        }

        // Backup is also corrupted
        LOG.error("Both the session file and its backup are corrupted; session lost")
        return null
    }

    /** Attempts to load from the specified file; returns null if the file is corrupted */
    private fun tryLoad(f: File): Pair<List<ChatSession>, Int>? {
        return try {
            val json = f.readText(Charsets.UTF_8)
            val store = gson.fromJson(json, StoreData::class.java) ?: return null

            val sessions = store.sessions.map { d ->
                ChatSession(d.name, d.messages, d.totalTokens, d.lastActiveTime)
            }

            Pair(sessions, store.sessionCounter)
        } catch (e: Exception) {
            LOG.warn("Unable to load session file: ${f.absolutePath}", e)
            null
        }
    }

    /**
     * Save sessions to disk.
     *
     * Write strategy:
     * 1. First atomically write the .tmp file
     * 2. rename .tmp → main file
     * 3. Copy to the .bak backup in sync
     */
    fun save(sessions: List<ChatSession>, sessionCounter: Int) {
        val f = file ?: return
        try {
            val store = StoreData(
                sessions = sessions.map {
                    SessionData(it.name, it.messages, it.totalTokens, it.lastActiveTime)
                }.toMutableList(),
                sessionCounter = sessionCounter
            )
            val json = gson.toJson(store)

            // 1. Atomic write to .tmp
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)

            // 2. rename → main file
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            // 3. Sync backup
            val bak = backupFile
            if (bak != null) {
                try {
                    Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    LOG.warn("Unable to create the session backup file", e)
                }
            }
        } catch (e: Exception) {
            SessionException("Failed to save session", e).also {
                LOG.error(it.toLogString())
            }
        }
    }
}
