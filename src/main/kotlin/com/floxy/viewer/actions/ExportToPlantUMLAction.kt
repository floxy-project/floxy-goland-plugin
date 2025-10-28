package com.floxy.viewer.actions

import com.floxy.viewer.render.PlantUmlRenderer
import com.floxy.viewer.services.FlowModelService
import com.goide.psi.GoFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class ExportToPlantUMLAction : AnAction() {
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
            Messages.showInfoMessage(project, "No floxy.Builder flows found in file", "Floxy Viewer")
            return
        }
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Choose directory to save .puml files"
        val dir: VirtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val renderer = PlantUmlRenderer()
        models.forEach { model ->
            val text = renderer.render(model)
            val name = "${model.name}_v${model.version}.puml"
            val child = dir.findChild(name) ?: dir.createChildData(this, name)
            VfsUtil.saveText(child, text)
        }
        Messages.showInfoMessage(project, "Exported ${models.size} diagram(s) to ${dir.path}", "Floxy Viewer")
    }
}
