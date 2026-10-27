package com.deepseek.plugin.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import java.io.File

/**
 * Lucene index construction and incremental updates (for text/document only).
 * Scans document text files (.md, .txt, .rst, etc.) under the project source root and builds a BM25 inverted index after chunking.
 *
 * Purpose: provides unstructured text retrieval for RAG, suitable for READMEs, docs, config notes, etc.
 * Note: code file search has been superseded by AgenticSearch (grep/glob/read tools).
 * See com.deepseek.plugin.search.AgenticSearch for details.
 *
 * Indexing strategy:
 * - Stored in ByteBuffersDirectory (in-heap memory), released when the project closes
 * - First call to buildIndex performs a full build
 * - A single file can be updated incrementally via onFileSaved
 * - Built-in dirty detection: detectDirtyFiles compares file timestamps
 */
class RagIndexer(private val project: Project) {

    /** Lucene in-memory directory; the same directory can be safely shared by multiple IndexReaders */
    private val directory: Directory = ByteBuffersDirectory()

    private val analyzer = StandardAnalyzer()
    private val splitter = ChunkSplitter()
    private val indexerLock = Any()

    /** File timestamp cache used for dirty detection */
    private val fileTimestamps = mutableMapOf<String, Long>()
    /** Timestamp of the most recent full/incremental update */
    private var lastBuildTime: Long = 0
    /** Minimum rebuild interval (milliseconds) */
    private val rebuildIntervalMs = 30_000L

    // ── Supported source file types (text/document files only) ──
    private val supportedExtensions = setOf("md", "txt", "rst", "adoc", "asciidoc",
        "markdown", "html", "htm", "xml", "yaml", "yml", "properties", "json", "toml",
        "ini", "cfg", "conf")

    // ── Public methods ──

    /** Returns the Lucene directory for the Retriever to open an IndexReader */
    fun getDirectory(): Directory = directory

    /** Returns the analyzer for the Retriever to use when building queries */
    fun getAnalyzer(): StandardAnalyzer = analyzer

    /**
     * Ensures the index is built and not stale.
     * A rebuild is triggered when any of the following holds: more than 30 seconds since the last build / files have changed
     */
    fun ensureFresh() {
        val now = System.currentTimeMillis()
        if (now - lastBuildTime < rebuildIntervalMs) {
            // Perform incremental dirty detection within the time window
            val dirty = detectDirtyFiles()
            if (dirty.isEmpty()) return
            incrementalUpdate(dirty)
            lastBuildTime = now
            return
        }
        buildIndex()
    }

    // ── Full build ──

    private fun buildIndex() {
        synchronized(indexerLock) {
            val writer = createIndexWriter()
            writer.deleteAll()
            fileTimestamps.clear()

            val sourceRoots = getSourceRootPaths()
            for (root in sourceRoots) {
                val rootFile = File(root)
                if (rootFile.exists()) {
                    collectFiles(rootFile, rootFile, writer)
                }
            }
            writer.commit()
            writer.close()
            lastBuildTime = System.currentTimeMillis()
        }
    }

    // ── Incremental update ──

    private fun incrementalUpdate(dirtyPaths: Set<String>) {
        synchronized(indexerLock) {
            val writer = createIndexWriter()

            for (dirtyPath in dirtyPaths) {
                val file = File(dirtyPath)
                if (!file.exists()) {
                    // File deleted → remove the corresponding chunks
                    val relativePath = getRelativePath(file)
                    writer.deleteDocuments(Term("filePath", relativePath))
                    fileTimestamps.remove(dirtyPath)
                    continue
                }
                if (!isSupportedFile(file)) continue

                val relativePath = getRelativePath(file)
                val content = file.readText()
                // Delete old chunks
                writer.deleteDocuments(Term("filePath", relativePath))
                // Write new chunks
                val chunks = splitter.split(relativePath, content)
                for (chunk in chunks) {
                    writer.addDocument(toLuceneDoc(chunk))
                }
                fileTimestamps[dirtyPath] = file.lastModified()
            }

            writer.commit()
            writer.close()
            lastBuildTime = System.currentTimeMillis()
        }
    }

    // ── Dirty file detection (reuses the timestamp approach) ──

    private fun detectDirtyFiles(): Set<String> {
        val dirty = mutableSetOf<String>()
        val sourceRoots = getSourceRootPaths()
        for (root in sourceRoots) {
            val rootFile = File(root)
            if (rootFile.exists()) {
                checkDirty(rootFile, rootFile, dirty)
            }
        }
        return dirty
    }

    private fun checkDirty(root: File, dir: File, dirty: MutableSet<String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "build" || name == "target" ||
                    name == "node_modules" || name == ".git" || name == "out" ||
                    name == "dist" || name == ".gradle"
                ) continue
                checkDirty(root, child, dirty)
            } else if (isSupportedFile(child)) {
                val path = child.absolutePath
                val cached = fileTimestamps[path]
                val current = child.lastModified()
                if (cached == null || cached != current) {
                    dirty.add(path)
                }
            }
        }
    }

    // ── File collection (for full builds) ──

    private fun collectFiles(root: File, dir: File, writer: IndexWriter) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "build" || name == "target" ||
                    name == "node_modules" || name == ".git" || name == "out" ||
                    name == "dist" || name == ".gradle"
                ) continue
                collectFiles(root, child, writer)
            } else if (isSupportedFile(child)) {
                try {
                    val relativePath = child.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart(File.separatorChar)
                    val content = child.readText()
                    fileTimestamps[child.absolutePath] = child.lastModified()
                    val chunks = splitter.split(relativePath, content)
                    for (chunk in chunks) {
                        writer.addDocument(toLuceneDoc(chunk))
                    }
                } catch (_: Exception) {
                    // Skip unreadable files
                }
            }
        }
    }

    // ── Utility methods ──

    /** Gets the source root path via the IntelliJ API */
    private fun getSourceRootPaths(): List<String> {
        val paths = mutableListOf<String>()
        try {
            val roots = ApplicationManager.getApplication().runReadAction(
                Computable<List<VirtualFile>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots.toList()
                }
            )
            for (root in roots) {
                paths.add(root.path.replace('/', File.separatorChar))
            }
        } catch (_: Exception) {}
        return paths
    }

    /** Gets a file's path relative to the project root or source root */
    private fun getRelativePath(file: File): String {
        val base = project.basePath ?: return file.name
        return file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
    }

    private fun isSupportedFile(file: File): Boolean {
        val ext = file.extension ?: return false
        return ext.lowercase() in supportedExtensions
    }

    private fun createIndexWriter(): IndexWriter {
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            // Keep Lucene from consuming too much memory
            ramBufferSizeMB = 64.0
        }
        return IndexWriter(directory, config)
    }

    private fun toLuceneDoc(chunk: CodeChunk): Document {
        return Document().apply {
            add(StringField("id", chunk.id, Field.Store.YES))
            add(StringField("filePath", chunk.filePath, Field.Store.YES))
            add(IntPoint("startLine", chunk.startLine))
            add(IntPoint("endLine", chunk.endLine))
            // Stored field used for display
            add(StringField("startLineStr", chunk.startLine.toString(), Field.Store.YES))
            add(StringField("endLineStr", chunk.endLine.toString(), Field.Store.YES))
            // Body — tokenized + stored, supports BM25 full-text search
            add(TextField("content", chunk.content, Field.Store.YES))
        }
    }
}
