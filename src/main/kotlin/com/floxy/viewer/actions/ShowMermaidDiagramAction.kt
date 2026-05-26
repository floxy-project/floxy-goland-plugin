package com.floxy.viewer.actions

import com.floxy.viewer.render.MermaidRenderer
import com.floxy.viewer.services.FlowModelService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile

class ShowMermaidDiagramAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (vFile.extension != "go") {
            Messages.showInfoMessage(project, "Not a Go file", "Floxy Viewer")
            return
        }

        val models = ApplicationManager.getApplication().runReadAction<List<com.floxy.viewer.model.FlowModel>> {
            project.getService(FlowModelService::class.java).collectFromFile(vFile)
        }
        if (models.isEmpty()) {
            Messages.showInfoMessage(project, "No floxy.NewBuilder flows found in file", "Floxy Viewer")
            return
        }
        val renderer = MermaidRenderer()
        models.forEach { model ->
            val text = renderer.render(model)
            val fileName = "${model.name}_v${model.version}.mmd"
            val lf = LightVirtualFile(fileName, text)
            FileEditorManager.getInstance(project).openFile(lf, true)
        }
    }
}
