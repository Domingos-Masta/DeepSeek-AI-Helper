package com.deepseek.plugin.cli

import com.deepseek.plugin.store.ChangeManagementStore
import com.deepseek.plugin.store.ChangeRecord
import com.deepseek.plugin.store.FileChangeInfo
import com.intellij.openapi.project.Project
import java.io.File

/**
 * CLI Agent change tracking: snapshot before execution → compare after execution → write to [ChangeManagementStore] (rollback-capable).
 *
 * Only tracks text source files (by extension whitelist), excluding build artifacts and VCS/IDE directories.
 */
object CliChangeTracker {

    private val EXCLUDED_DIRS = setOf(
        ".git", ".idea", "build", "node_modules", ".gradle", "target", "dist", "out",
        ".deepseek", ".intellijPlatform", ".kotlin"
    )

    private val EXTENSIONS = setOf(
        "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "php", "rb", "swift", "vue", "json", "xml", "yml", "yaml", "toml", "gradle",
        "properties", "md", "txt", "html", "css", "scss", "sql", "sh", "bat", "ini", "conf", "env"
    )

    /**
     * Takes a content snapshot of text source files under the project root.
     * @return relative path (forward slashes) → file content
     */
    fun snapshot(projectDir: File): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        if (!projectDir.isDirectory) return map
        try {
            projectDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .filter { file ->
                    try {
                        val rel = file.relativeTo(projectDir).toString().replace('\\', '/')
                        rel.split('/').none { it in EXCLUDED_DIRS }
                    } catch (_: Exception) {
                        false
                    }
                }
                .forEach { f ->
                    try {
                        map[f.relativeTo(projectDir).path.replace('\\', '/')] = f.readBytes()
                    } catch (_: Exception) { }
                }
        } catch (_: Exception) { }
        return map
    }

    /**
     * Compares the before/after snapshots and writes changes to [ChangeManagementStore].
     * @return number of recorded file changes (0 = no changes)
     */
    fun recordChanges(
        project: Project,
        before: Map<String, ByteArray>,
        after: Map<String, ByteArray>,
        title: String
    ): Int {
        val changes = mutableListOf<FileChangeInfo>()
        val allKeys = before.keys + after.keys
        for (key in allKeys.distinct()) {
            val b = before[key]
            val a = after[key]
            when {
                b == null && a != null ->
                    changes.add(FileChangeInfo(key, ByteArray(0), isNew = true))
                b != null && a != null && !b.contentEquals(a) ->
                    changes.add(FileChangeInfo(key, b))
                // Files deleted by the CLI are not rolled back (ChangeManagementStore does not support delete recovery)
            }
        }
        if (changes.isEmpty()) return 0
        val store = project.getService(ChangeManagementStore::class.java)
        store.addRecord(ChangeRecord(title = title, changes = changes))
        return changes.size
    }
}
