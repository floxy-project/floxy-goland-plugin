package com.floxy.viewer.actions

import com.floxy.viewer.render.PlantUmlRenderer
import com.floxy.viewer.services.FlowModelService
import com.goide.psi.GoFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.ByteArrayOutputStream

class ExportToImageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? GoFile
        if (psi == null) {
            Messages.showInfoMessage(project, "Not a Go file", "Floxy Viewer")
            return
        }
        val models = project.getService(FlowModelService::class.java).collectFromFile(vFile)
        if (models.isEmpty()) {
            Messages.showInfoMessage(project, "No floxy.NewBuilder flows found in file", "Floxy Viewer")
            return
        }
        val format = chooseFormat(project) ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Choose directory to save ${format.display} diagrams"
        val dir: VirtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val renderer = PlantUmlRenderer()
        var saved = 0
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            models.forEach { model ->
                val puml = renderer.render(model)
                val bytes = renderToBytes(puml, format)
                val name = "${model.name}_v${model.version}.${format.ext}"
                ApplicationManager.getApplication().invokeAndWait {
                    ApplicationManager.getApplication().runWriteAction {
                        val child = dir.findChild(name) ?: dir.createChildData(this, name)
                        child.setBinaryContent(bytes)
                    }
                }
                saved++
            }
        }, "Exporting Floxy diagrams", false, project)
        Messages.showInfoMessage(project, "Exported $saved ${format.display} file(s) to ${dir.path}", "Floxy Viewer")
    }

    private fun renderToBytes(source: String, format: ImageFormat): ByteArray {
        val reader = SourceStringReader(source)
        val os = ByteArrayOutputStream()
        val ff = when (format) {
            ImageFormat.PNG -> FileFormat.PNG
            ImageFormat.SVG -> FileFormat.SVG
        }
        reader.outputImage(os, FileFormatOption(ff))
        os.flush()
        val bytes = os.toByteArray()
        os.close()
        return bytes
    }

    private fun chooseFormat(project: com.intellij.openapi.project.Project): ImageFormat? {
        val options = arrayOf("PNG", "SVG")
        val idx = Messages.showDialog(project, "Choose export format", "Floxy Export", options, 0, null)
        return when (idx) {
            0 -> ImageFormat.PNG
            1 -> ImageFormat.SVG
            else -> null
        }
    }

    private enum class ImageFormat(val ext: String, val display: String) { PNG("png", "PNG"), SVG("svg", "SVG") }
}
