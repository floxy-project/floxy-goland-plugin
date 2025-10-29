package com.floxy.viewer.ui

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.nav.GoHandlerNavigator
import com.floxy.viewer.services.FlowModelService
import com.goide.psi.GoFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel

class FloxyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Flows tab
//        val flowsPanelWrap = SimpleToolWindowPanel(true, true)
//        flowsPanelWrap.setContent(createFlowsPanel(project))
//        toolWindow.contentManager.addContent(contentFactory.createContent(flowsPanelWrap, "Flows", false))

        // Diagram tab (embedded PlantUML)
        val diagramPanel = PlantUmlPreviewPanel(project)
        diagramPanel.refreshFromEditor()
        toolWindow.contentManager.addContent(contentFactory.createContent(diagramPanel, "Diagram", false))

        // Validation tab
        val validationPanel = ValidationPanel(project)
        validationPanel.refreshFromEditor()
        toolWindow.contentManager.addContent(contentFactory.createContent(validationPanel, "Validation", false))

//        // Placeholder tabs
//        val instancesPanel = JPanel(BorderLayout()).apply { add(JBLabel("Instances (future)"), BorderLayout.CENTER) }
//        val eventsPanel = JPanel(BorderLayout()).apply { add(JBLabel("Events (future)"), BorderLayout.CENTER) }
//        toolWindow.contentManager.addContent(contentFactory.createContent(instancesPanel, "Instances", false))
//        toolWindow.contentManager.addContent(contentFactory.createContent(eventsPanel, "Events", false))
    }

    private fun createFlowsPanel(project: Project): JPanel {
        val splitter = JBSplitter(false, 0.3f)
        val flowsListModel = DefaultListModel<FlowModel>()
        val flowsList = JBList(flowsListModel)
        val stepsListModel = DefaultListModel<String>()
        val stepsList = JBList(stepsListModel)

        splitter.firstComponent = JBScrollPane(flowsList)
        splitter.secondComponent = JBScrollPane(stepsList)

        fun refresh() {
            flowsListModel.clear()
            stepsListModel.clear()
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val file = editor?.file ?: return
            val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return
            val models = project.getService(FlowModelService::class.java).collectFromFile(file)
            models.forEach { flowsListModel.addElement(it) }
        }

        // Subscribe to model updates to auto-refresh
        project.messageBus.connect().subscribe(FlowModelService.TOPIC, object : FlowModelService.Listener {
            override fun onModelsUpdated(event: FlowModelService.ModelsEvent) {
                val editor = FileEditorManager.getInstance(project).selectedEditor ?: return
                if (editor.file == event.file) {
                    ApplicationManager.getApplication().invokeLater { refresh() }
                }
            }
        })
        // Subscribe to selection events from Diagram to sync selection
        project.messageBus.connect().subscribe(FlowModelService.SELECTION_TOPIC, object : FlowModelService.SelectionListener {
            override fun onStepSelected(event: FlowModelService.SelectionEvent) {
                val editor = FileEditorManager.getInstance(project).selectedEditor ?: return
                if (editor.file != event.file) return
                // Find flow containing the step and select it
                for (i in 0 until flowsListModel.size()) {
                    val flow = flowsListModel.getElementAt(i)
                    if (flow.steps.containsKey(event.stepName)) {
                        flowsList.selectedIndex = i
                        // update steps list then select step
                        stepsListModel.clear()
                        flow.steps.values.forEach { stepsListModel.addElement(it.name) }
                        val idx = (0 until stepsListModel.size()).firstOrNull { stepsListModel.getElementAt(it) == event.stepName }
                        if (idx != null) stepsList.selectedIndex = idx
                        break
                    }
                }
            }
        })

        flowsList.addListSelectionListener {
            stepsListModel.clear()
            val sel = flowsList.selectedValue ?: return@addListSelectionListener
            sel.steps.values.forEach { stepsListModel.addElement(it.name) }
        }

        stepsList.addListSelectionListener {
            val flow = flowsList.selectedValue ?: return@addListSelectionListener
            val stepName = stepsList.selectedValue ?: return@addListSelectionListener
            val handler = flow.steps[stepName]?.handler ?: return@addListSelectionListener
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val file = editor?.file ?: return@addListSelectionListener
            val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return@addListSelectionListener
            val fn = GoHandlerNavigator.findFunctionInFile(psiFile, handler) ?: return@addListSelectionListener
            GoHandlerNavigator.navigateTo(fn)
        }

        refresh()
        val panel = JPanel(BorderLayout())
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }
}
