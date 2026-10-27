package com.deepseek.plugin.store

import java.io.File

/**
 * File change info — records a single file's original content and path.
 *
 * @param filePath      File path relative to the project root (used for display and rollback location)
 * @param originalContent Backed-up original file content (in memory); ByteArray(0) for newly created files
 * @param isNew          Whether the file was newly created this time (rollback deletes it instead of writing back)
 * @param timestamp      Timestamp when the backup was created
 */
data class FileChangeInfo(
    val filePath: String,
    val originalContent: ByteArray,
    val isNew: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Determines whether the file has been modified by comparing content (for new files, checks whether it still exists) */
    fun isModified(): Boolean {
        val f = File(filePath)
        if (!f.exists()) return false // file already deleted
        if (isNew) return false       // newly created file; "modified" does not apply
        return f.readBytes().contentEquals(originalContent).not()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChangeInfo) return false
        return filePath == other.filePath && originalContent.contentEquals(other.originalContent) && isNew == other.isNew && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = filePath.hashCode()
        result = 31 * result + originalContent.contentHashCode()
        result = 31 * result + isNew.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Change record — all file changes produced by one Agent operation.
 *
 * @param title     Title, e.g. "changes for requirement xxx"
 * @param timestamp Change time
 * @param changes   List of files involved in this change
 */
data class ChangeRecord(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val changes: MutableList<FileChangeInfo> = mutableListOf()
) {
    /** Formatted time string */
    fun formattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
