package com.deepseek.plugin.handler

import com.deepseek.plugin.chat.ChatPanel
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Intercepts editor paste events and detects whether the clipboard contains an image.
 *
 * Execution order:
 * 1. Lightweight check for an image (only checks the flavor list, without reading data)
 * 2. No image → delegate to originalHandler, leaving native paste completely unaffected
 * 3. Image present → first delegate text paste to originalHandler, then save the image to the chat attachment
 */
@Suppress("DEPRECATION")
class PasteImageHandler(private val originalHandler: EditorActionHandler?) : EditorActionHandler() {

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        // Lightweight check: whether an image is present
        val hasImage = try {
            val sysClip = Toolkit.getDefaultToolkit().systemClipboard
            sysClip.getAvailableDataFlavors().any { DataFlavor.imageFlavor.equals(it) }
        } catch (_: Exception) {
            false
        }

        // No image → delegate to the original handler with zero interference
        if (!hasImage) {
            originalHandler?.execute(editor, caret, dataContext)
            return
        }

        // Image present: first delegate text paste to the original handler
        originalHandler?.execute(editor, caret, dataContext)

        // Then save the image to the chat attachment
        try {
            val sysClip = Toolkit.getDefaultToolkit().systemClipboard
            val image = sysClip.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return
            val project = editor.project ?: ProjectManager.getInstance().defaultProject
            val savedFile = saveImageToProject(image, project)
            addAsChatAttachment(project, savedFile)
        } catch (_: Exception) {
            // Silently ignore
        }
    }

    @Throws(IOException::class)
    private fun saveImageToProject(image: java.awt.Image, project: Project): File {
        val projectPath = project.basePath ?: throw IOException("Unable to get the project path")
        val imagesDir = File(projectPath, "images")
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val imageFile = File(imagesDir, "image_${System.currentTimeMillis()}.png")
        ImageIO.write(toBufferedImage(image), "png", imageFile)
        return imageFile
    }

    private fun toBufferedImage(image: java.awt.Image): BufferedImage {
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return bi
    }

    private fun addAsChatAttachment(project: Project, file: File) {
        val chatPanel = ChatPanel.currentInstance
        chatPanel?.addFileAttachmentFromExternal(file)
    }
}
