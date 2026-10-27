package com.deepseek.plugin.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Base64

/**
 * Change management store — isolated per project, persisted to `.idea/deepseek-changes.json`.
 *
 * Follows the file persistence pattern of [com.deepseek.plugin.settings.SkillStore]:
 * - A JSON file under each project's own `.idea/` directory
 * - Loaded on construction, saved immediately after every modification
 * - Change records are restored automatically when the project is reopened
 *
 * The backed-up original file content is stored Base64-encoded in the JSON.
 */
@Service(Service.Level.PROJECT)
class ChangeManagementStore(project: Project) {

    private val storeFile: File? = project.basePath?.let {
        val dir = File(it, ".idea")
        if (dir.isDirectory || dir.mkdirs()) File(dir, "deepseek-changes.json") else null
    }

    private val gson = Gson()

    /** All change records (reverse chronological; newest first) */
    private val _records = mutableListOf<ChangeRecord>()

    /** Read-only view exposed to the outside */
    val records: List<ChangeRecord> get() = _records.toList()

    init {
        load()
    }

    /**
     * Adds a new change record and persists it.
     */
    fun addRecord(record: ChangeRecord) {
        _records.add(0, record)
        save()
    }

    /**
     * Removes the specified change record and persists.
     */
    fun removeRecord(record: ChangeRecord) {
        _records.remove(record)
        save()
    }

    /**
     * Clears all change records and persists.
     */
    fun clearAll() {
        _records.clear()
        save()
    }

    /**
     * Rolls back files based on FileChangeInfo:
     * - Newly created file (isNew=true): delete the file
     * - Modified file (isNew=false): write the original content back to disk
     * @return true means rollback succeeded, false means it failed
     */
    fun rollbackFile(change: FileChangeInfo): Boolean {
        return try {
            val file = File(change.filePath)
            if (change.isNew) {
                if (!file.exists()) {
                    System.err.println("[ChangeManagement] rollback: new file already deleted ${change.filePath}")
                    return true
                }
                val deleted = file.delete()
                if (deleted) {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
                }
                deleted
            } else {
                if (!file.exists()) {
                    System.err.println("[ChangeManagement] rollback failed: file not found ${change.filePath}")
                    return false
                }
                file.writeBytes(change.originalContent)
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
                true
            }
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] rollback failed for ${change.filePath}: ${e.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  File persistence — JSON (same pattern as SkillStore)
    // ════════════════════════════════════════════════════════════════

    /** Loads change records from the JSON file */
    private fun load() {
        val file = storeFile ?: return
        if (!file.exists()) return
        try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<ChangeRecordData>>() {}.type
            val dataList: List<ChangeRecordData> = gson.fromJson(json, type) ?: return
            _records.clear()
            for (data in dataList) {
                val changes = data.changes.map { fc ->
                    FileChangeInfo(
                        filePath = fc.filePath,
                        originalContent = Base64.getDecoder().decode(fc.originalContent),
                        isNew = fc.isNew,
                        timestamp = fc.timestamp
                    )
                }.toMutableList()
                _records.add(
                    ChangeRecord(
                        title = data.title,
                        timestamp = data.timestamp,
                        changes = changes
                    )
                )
            }
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] Failed to load changes: ${e.message}")
        }
    }

    /** Saves change records to the JSON file */
    private fun save() {
        val file = storeFile ?: return
        try {
            val dataList = _records.map { record ->
                ChangeRecordData(
                    title = record.title,
                    timestamp = record.timestamp,
                    changes = record.changes.map { change ->
                        FileChangeData(
                            filePath = change.filePath,
                            originalContent = Base64.getEncoder().encodeToString(change.originalContent),
                            isNew = change.isNew,
                            timestamp = change.timestamp
                        )
                    }
                )
            }
            file.writeText(gson.toJson(dataList), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] Failed to save changes: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON serialization DTO (Base64-encoded file content)
    // ════════════════════════════════════════════════════════════════

    private data class ChangeRecordData(
        val title: String,
        val timestamp: Long,
        val changes: List<FileChangeData>
    )

    private data class FileChangeData(
        val filePath: String,
        val originalContent: String, // Base64 encoded
        val isNew: Boolean,
        val timestamp: Long
    )
}
