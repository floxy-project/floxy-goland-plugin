package com.floxy.viewer.actions

import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import com.goide.psi.GoFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager

class ValidateFlowAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? GoFile
        if (psi == null) {
            Messages.showInfoMessage(project, "Not a Go file", "Floxy Viewer")
            return
        }
        val analyzer = VisitorBasedFlowAnalyzer()
        val results = ApplicationManager.getApplication().runReadAction<List<com.floxy.viewer.psi.FlowAnalysisResult>> {
            analyzer.analyzeFlowsDetailed(psi)
        }
        if (results.isEmpty()) {
            Messages.showInfoMessage(project, "No floxy.NewBuilder flows found in file", "Floxy Viewer")
            return
        }
        val report = buildString {
            results.forEach { r ->
                appendLine("Flow ${r.model.name} v${r.model.version}:")
                if (r.errors.isEmpty()) appendLine("  OK") else r.errors.forEach { msg ->
                    appendLine("  [ERROR] $msg")
                }
                appendLine()
            }
        }
        Messages.showInfoMessage(project, report, "Floxy Validation")
    }
}
