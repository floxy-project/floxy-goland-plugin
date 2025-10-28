package com.floxy.viewer.actions

import com.floxy.viewer.services.FlowModelService
import com.floxy.viewer.validate.Validator
import com.goide.psi.GoFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

class ValidateFlowAction : AnAction(), DumbAware {
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
        val validator = Validator()
        val report = buildString {
            models.forEach { m ->
                val issues = validator.validate(m)
                appendLine("Flow ${m.name} v${m.version}:")
                if (issues.isEmpty()) appendLine("  OK") else issues.forEach { i ->
                    appendLine("  [${i.severity}] ${i.message}")
                }
                appendLine()
            }
        }
        Messages.showInfoMessage(project, report, "Floxy Validation")
    }
}
