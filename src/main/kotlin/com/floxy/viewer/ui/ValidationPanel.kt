package com.floxy.viewer.ui

import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import com.floxy.viewer.services.FlowModelService
import com.goide.psi.GoFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.*

class ValidationPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel)

    init {
        add(JBScrollPane(list), BorderLayout.CENTER)
        // Subscribe for updates
        project.messageBus.connect().subscribe(FlowModelService.TOPIC, object : FlowModelService.Listener {
            override fun onModelsUpdated(event: FlowModelService.ModelsEvent) {
                val editor = FileEditorManager.getInstance(project).selectedEditor ?: return
                if (editor.file == event.file) {
                    ApplicationManager.getApplication().invokeLater { refreshFromEditor() }
                }
            }
        })

        // Click: try to navigate to mentioned step handler/name if present in the editor file
        list.addListSelectionListener {
            val sel = list.selectedValue ?: return@addListSelectionListener
            val stepName = extractQuoted(sel) ?: return@addListSelectionListener
            val editor = FileEditorManager.getInstance(project).selectedEditor ?: return@addListSelectionListener
            val psiFile = PsiManager.getInstance(project).findFile(editor.file) as? GoFile ?: return@addListSelectionListener
            // Try to navigate to a function with the same name
            // Note: detailed mapping could be added later using model context
            // For now, do nothing if not resolvable
        }
    }

    fun refreshFromEditor() {
        listModel.clear()
        val editor = FileEditorManager.getInstance(project).selectedEditor ?: return
        val file = editor.file ?: return
        val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return
        val analyzer = VisitorBasedFlowAnalyzer()
        val results = analyzer.analyzeFlowsDetailed(psiFile.text)
        if (results.isEmpty()) {
            listModel.addElement("No floxy.NewBuilder flows found in file")
            return
        }
        results.forEach { r ->
            listModel.addElement("Flow ${r.model.name} v${r.model.version}:")
            if (r.errors.isEmpty()) {
                listModel.addElement("  OK")
            } else {
                r.errors.forEach { err -> listModel.addElement("  [ERROR] $err") }
            }
            listModel.addElement("")
        }
    }

    private fun extractQuoted(s: String): String? {
        val i1 = s.indexOf('\'')
        if (i1 < 0) return null
        val i2 = s.indexOf('\'', i1 + 1)
        return if (i2 > i1) s.substring(i1 + 1, i2) else null
    }
}
