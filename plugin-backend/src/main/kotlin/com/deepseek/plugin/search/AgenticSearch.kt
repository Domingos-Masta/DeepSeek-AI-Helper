package com.deepseek.plugin.search

import com.deepseek.plugin.api.*

import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Agentic Search engine.
 *
 * Provides three search tools — grep / glob / read — for the LLM to call autonomously to retrieve code information.
 *
 * grep and read delegate to [FileAccessService] (the unified file access layer), sharing the same
 * implementation as MCP tools and Q&A full-text scanning. Falls back automatically if FileAccessService calls fail.
 *
 * glob keeps an independent implementation (pure pattern matching, no file reading required).
 *
 * Usage:
 *   val engine = AgenticSearch(project)
 *   val result = engine.grep("getUserById", "*.java")
 *   val files = engine.glob("glob pattern")
 *   val content = engine.read("path/to/file.java", 10, 50)
 */
class AgenticSearch(
    private val project: Project,
    private val fileAccess: FileAccessService = ChainedFileAccess()
) {

    // ── Maximum line limit (prevents a single oversized file from overflowing the context) ──
    private val maxReadLines = 500
    private val maxReadChars = 30_000

    // ── Maximum grep matches (prevents too many matches to handle) ──
    private val maxGrepMatches = 100
    private val maxGrepFiles = 30

    // ── Ignored directories ──
    private val ignoredDirs = setOf(".git", ".gradle", "build", "target", "node_modules",
        "out", "dist", ".idea", ".mvn", ".settings", "bin", "obj", "__pycache__", "venv")

    // ── Supported code file extensions ──
    private val codeExtensions = setOf("java", "kt", "kts", "scala", "groovy",
        "py", "js", "ts", "jsx", "tsx", "vue", "go", "rs", "rb", "php",
        "swift", "c", "cpp", "h", "hpp", "cs", "dart", "sql", "gradle",
        "xml", "yaml", "yml", "json", "properties", "css", "scss", "less")

    // ════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Content search (similar to ripgrep).
     *
     * Delegates to [FileAccessService.searchInProject]; falls back automatically to
     * recursive VirtualFile scanning.
     *
     * @param query       Search keyword or regular expression
     * @param filePattern Optional file-name wildcard filter
     * @return GrepResult containing all matching lines
     */
    fun grep(query: String, filePattern: String? = null): GrepResult {
        val basePath = project.basePath ?: return GrepResult(query, emptyList(), 0, false)
        val regex = try {
            Regex(query, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            Regex.escape(query).toRegex(RegexOption.IGNORE_CASE)
        }

        // Try searching via FileAccessService first
        try {
            val searchResults = fileAccess.searchInProject(query, maxGrepMatches, project, basePath)
            if (searchResults.isNotEmpty()) {
                val matches = searchResults.map { sr ->
                    val matchResult = regex.find(sr.lineContent) ?: return@map null
                    GrepMatch(
                        filePath = sr.filePath,
                        lineNumber = sr.lineNumber,
                        lineText = sr.lineContent,
                        matchStart = matchResult.range.first,
                        matchEnd = matchResult.range.last + 1
                    )
                }.filterNotNull()

                if (matches.isNotEmpty()) {
                    val filesInResult = matches.map { it.filePath }.distinct()
                    return GrepResult(
                        query = query,
                        matches = matches.take(maxGrepMatches),
                        totalMatches = matches.size,
                        truncated = filesInResult.size > maxGrepFiles
                    )
                }
            }
        } catch (_: Exception) {
            // FileAccessService failed, fall through to legacy
        }

        // Fallback: legacy VirtualFile recursive scan
        return grepLegacy(query, filePattern, regex)
    }

    private fun grepLegacy(query: String, filePattern: String?, regex: Regex): GrepResult {
        val allMatches = mutableListOf<GrepMatch>()
        val roots = getSourceRoots()
        var truncated = false

        for (root in roots) {
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
            val matches = grepInDir(root, root, regex, filePattern, allMatches.size)
            allMatches.addAll(matches)
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
        }

        val filesInResult = allMatches.map { it.filePath }.distinct()
        if (filesInResult.size > maxGrepFiles) {
            truncated = true
        }

        return GrepResult(
            query = query,
            matches = allMatches.take(maxGrepMatches),
            totalMatches = allMatches.size,
            truncated = truncated
        )
    }

    /**
     * Searches for files by name pattern.
     *
     * @param pattern File wildcard pattern
     * @return GlobResult containing the list of matching file paths
     */
    fun glob(pattern: String): GlobResult {
        val matchedFiles = mutableListOf<String>()
        val roots = getSourceRoots()

        val regexPattern = globToRegex(pattern)

        for (root in roots) {
            collectFiles(root, root, regexPattern, matchedFiles)
        }

        return GlobResult(
            pattern = pattern,
            files = matchedFiles.sorted()
        )
    }

    /**
     * Reads the content of a specified file.
     *
     * Delegates to [FileAccessService.readFile]; falls back to File.readText() on failure.
     *
     * @param filePath  File path relative to the project root
     * @param startLine Optional start line number (1-based, inclusive)
     * @param endLine   Optional end line number (1-based, inclusive)
     * @return ReadResult containing the file content
     */
    fun read(filePath: String, startLine: Int? = null, endLine: Int? = null): ReadResult {
        val basePath = project.basePath ?: return ReadResult(filePath, "", 0, 0, 0)

        // Try reading via FileAccessService first
        try {
            val content = fileAccess.readFile(filePath, project)
            if (content != null) {
                return buildReadResult(filePath, content, startLine, endLine)
            }
        } catch (_: Exception) {
            // fall through
        }

        // Fallback: legacy direct file read
        return readLegacy(filePath, startLine, endLine)
    }

    private fun readLegacy(filePath: String, startLine: Int?, endLine: Int?): ReadResult {
        val basePath = project.basePath ?: return ReadResult(filePath, "", 0, 0, 0)
        val roots = getSourceRootPaths()
        val allTryPaths = listOf(basePath) + roots
        var file: File? = null

        for (root in allTryPaths) {
            val candidate = File(root, filePath)
            if (candidate.exists() && candidate.isFile) {
                file = candidate
                break
            }
            val absolute = File(filePath)
            if (absolute.exists() && absolute.isFile) {
                file = absolute
                break
            }
        }

        if (file == null) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        val content = try {
            file.readText()
        } catch (e: Exception) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        return buildReadResult(filePath, content, startLine, endLine)
    }

    private fun buildReadResult(filePath: String, content: String, startLine: Int?, endLine: Int?): ReadResult {
        val lines = content.lines()
        val totalLines = lines.size
        val actualStart = (startLine ?: 1).coerceIn(1, totalLines) - 1
        val actualEnd = (endLine ?: totalLines).coerceIn(1, totalLines)

        val limitedEnd = if ((actualEnd - actualStart) > maxReadLines) {
            actualStart + maxReadLines
        } else {
            actualEnd
        }

        val selectedLines = lines.subList(actualStart, limitedEnd.coerceAtMost(totalLines))
        var resultContent = selectedLines.joinToString("\n")

        if (resultContent.length > maxReadChars) {
            resultContent = resultContent.take(maxReadChars) + "\n// ... (truncated ${resultContent.length - maxReadChars} chars)"
        }

        return ReadResult(
            filePath = filePath,
            content = resultContent,
            startLine = actualStart + 1,
            endLine = limitedEnd,
            totalLines = totalLines
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Directory scan (for fallback)
    // ════════════════════════════════════════════════════════════════

    private fun grepInDir(
        root: VirtualFile,
        dir: VirtualFile,
        regex: Regex,
        filePattern: String?,
        currentCount: Int
    ): List<GrepMatch> {
        if (currentCount >= maxGrepMatches) return emptyList()
        val result = mutableListOf<GrepMatch>()

        for (child in dir.children ?: return result) {
            if (result.size >= maxGrepMatches) break
            if (child.isDirectory) {
                val name = child.name
                if (name in ignoredDirs || name.startsWith(".")) continue
                result.addAll(grepInDir(root, child, regex, filePattern, result.size + currentCount))
            } else if (isCodeFile(child) && matchesFilePattern(child, filePattern)) {
                val match = searchInFile(root, child, regex)
                if (match != null) {
                    result.addAll(match)
                }
            }
        }
        return result
    }

    private fun searchInFile(root: VirtualFile, file: VirtualFile, regex: Regex): List<GrepMatch>? {
        val relativePath = getRelativePath(root, file)
        val lines: List<String> = try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<String>> {
                    val psiFile = PsiManager.getInstance(project).findFile(file)
                    psiFile?.text?.lines() ?: String(file.contentsToByteArray(), Charsets.UTF_8).lines()
                }
            )
        } catch (e: Exception) {
            return null
        }

        val matches = mutableListOf<GrepMatch>()
        for ((index, line) in lines.withIndex()) {
            val matchResult = regex.find(line) ?: continue
            matches.add(
                GrepMatch(
                    filePath = relativePath,
                    lineNumber = index + 1,
                    lineText = line.trim(),
                    matchStart = matchResult.range.first,
                    matchEnd = matchResult.range.last + 1
                )
            )
        }
        return matches.ifEmpty { null }
    }

    private fun collectFiles(
        root: VirtualFile,
        dir: VirtualFile,
        regex: Regex,
        result: MutableList<String>
    ) {
        for (child in dir.children ?: return) {
            if (child.isDirectory) {
                val name = child.name
                if (name in ignoredDirs || name.startsWith(".")) continue
                collectFiles(root, child, regex, result)
            } else if (isCodeFile(child)) {
                val relativePath = getRelativePath(root, child)
                if (regex.containsMatchIn(relativePath)) {
                    result.add(relativePath)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Utility methods
    // ════════════════════════════════════════════════════════════════

    private fun isCodeFile(file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext.lowercase() in codeExtensions
    }

    private fun matchesFilePattern(file: VirtualFile, pattern: String?): Boolean {
        if (pattern == null) return true
        val name = file.name
        val path = file.path
        val regex = globToRegex(pattern)
        return regex.containsMatchIn(name) || regex.containsMatchIn(path)
    }

    private fun getRelativePath(root: VirtualFile, file: VirtualFile): String {
        val rootPath = root.path.trimEnd('/')
        val filePath = file.path
        return filePath.removePrefix(rootPath).trimStart('/')
    }

    private fun getSourceRoots(): List<VirtualFile> {
        return try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<VirtualFile>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots.toList()
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSourceRootPaths(): List<String> {
        return try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<String>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots
                        .map { it.path }
                        .toList()
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val regexStr = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        append(".*")
                        i += 2
                        if (i < pattern.length && pattern[i] == '/') {
                            append("/?")
                            i++
                        }
                    }
                    c == '*' -> {
                        append("[^/]*")
                        i++
                    }
                    c == '?' -> {
                        append("[^/]")
                        i++
                    }
                    c == '.' -> {
                        append("\\.")
                        i++
                    }
                    c == '/' -> {
                        append("/")
                        i++
                    }
                    else -> {
                        append(Regex.escape(c.toString()))
                        i++
                    }
                }
            }
            append('$')
        }
        return Regex(regexStr, setOf(RegexOption.IGNORE_CASE))
    }
}
