package com.floxy.viewer.ui

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.nav.GoHandlerNavigator
import com.floxy.viewer.render.PlantUmlRenderer
import com.floxy.viewer.services.FlowModelService
import com.goide.psi.GoFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.messages.MessageBusConnection
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.awt.BorderLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.*

class PlantUmlPreviewPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val imageLabel = JLabel("No diagram", SwingConstants.CENTER)
    private val flowSelector = JComboBox<String>()
    private var currentFile: VirtualFile? = null
    private var flows: List<FlowModel> = emptyList()
    private var connection: MessageBusConnection? = null

    init {
        val top = JPanel(BorderLayout())
        val refreshBtn = JButton("Refresh")
        top.add(flowSelector, BorderLayout.CENTER)
        top.add(refreshBtn, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        add(JScrollPane(imageLabel), BorderLayout.CENTER)

        flowSelector.addActionListener { renderSelected() }
        refreshBtn.addActionListener { refreshFromEditor() }

        // Right-click context menu on diagram to navigate to handlers
        imageLabel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = flowSelector.selectedIndex.takeIf { it >= 0 } ?: return
                val model = flows.getOrNull(idx) ?: return
                val menu = JPopupMenu()
                val nav = JMenu("Go to handler")
                var added = 0
                model.steps.values.forEach { step ->
                    val handler = step.handler
                    if (!handler.isNullOrBlank()) {
                        val item = JMenuItem("${step.name} → $handler")
                        item.addActionListener {
                            val editor = FileEditorManager.getInstance(project).selectedEditor
                            val file = editor?.file ?: return@addActionListener
                            // Publish selection for sync with Flows tab
                            project.messageBus.syncPublisher(FlowModelService.SELECTION_TOPIC)
                                .onStepSelected(FlowModelService.SelectionEvent(file, step.name))
                            val target = ApplicationManager.getApplication().runReadAction<com.intellij.psi.PsiElement?> {
                                val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return@runReadAction null
                                // First, try to locate a method Name() that returns the handler string literal
                                GoHandlerNavigator.findHandlerImplementation(psiFile, handler)
                                    ?: GoHandlerNavigator.findFunctionInFile(psiFile, handler)
                            }
                            GoHandlerNavigator.navigateTo(target)
                        }
                        nav.add(item)
                        added++
                    }
                }
                if (added == 0) {
                    nav.isEnabled = false
                    nav.add(JMenuItem("No handlers in this flow").apply { isEnabled = false })
                }
                menu.add(nav)
                menu.show(e.component, e.x, e.y)
            }
        })

        // Subscribe to model updates
        connection = project.messageBus.connect()
        connection?.subscribe(FlowModelService.TOPIC, object : FlowModelService.Listener {
            override fun onModelsUpdated(event: FlowModelService.ModelsEvent) {
                if (event.file == currentFile) {
                    // Update on EDT
                    ApplicationManager.getApplication().invokeLater {
                        flows = event.models
                        flowSelector.model = DefaultComboBoxModel(flows.map { "${it.name} v${it.version}" }.toTypedArray())
                        if (flows.isNotEmpty()) {
                            flowSelector.selectedIndex = 0
                            renderSelected()
                        } else {
                            imageLabel.icon = null
                            imageLabel.text = "No flows detected"
                        }
                    }
                }
            }
        })
    }

    fun refreshFromEditor() {
        val editor = FileEditorManager.getInstance(project).selectedEditor
        val file = editor?.file ?: return
        val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return
        currentFile = file
        val service = project.getService(FlowModelService::class.java)
        flows = service.collectFromFile(file)
        flowSelector.model = DefaultComboBoxModel(flows.map { "${it.name} v${it.version}" }.toTypedArray())
        if (flows.isNotEmpty()) {
            flowSelector.selectedIndex = 0
            renderSelected(psiFile)
        } else {
            imageLabel.icon = null
            imageLabel.text = "No flows detected"
        }
    }

    private fun renderSelected(psiFile: GoFile? = null) {
        if (flows.isEmpty()) return
        val idx = flowSelector.selectedIndex.takeIf { it >= 0 } ?: 0
        val model = flows[idx]
        try {
            val puml = PlantUmlRenderer().render(model)
            val img = renderPlantUmlToImage(puml)
            if (img != null) {
                imageLabel.text = null
                val scaled = fitToScrollPane(img)
                imageLabel.icon = ImageIcon(scaled)
            } else {
                imageLabel.icon = null
                imageLabel.text = "Failed to render PlantUML"
            }
        } catch (e: Exception) {
            imageLabel.icon = null
            imageLabel.text = "Render error: ${e.message}"
        }
    }

    private fun renderPlantUmlToImage(source: String): Image? {
        return try {
            val reader = SourceStringReader(source)
            val os = ByteArrayOutputStream()
            reader.outputImage(os, FileFormatOption(FileFormat.PNG))
            os.flush()
            val bytes = os.toByteArray()
            os.close()
            ImageIO.read(bytes.inputStream())
        } catch (e: Exception) {
            null
        }
    }

    private fun fitToScrollPane(img: Image): Image {
        val viewport = (this.components.find { it is JScrollPane } as? JScrollPane)?.viewport ?: return img
        val maxW = viewport.width.takeIf { it > 0 } ?: 1200
        val maxH = viewport.height.takeIf { it > 0 } ?: 800
        val iw = img.getWidth(null)
        val ih = img.getHeight(null)
        if (iw <= 0 || ih <= 0) return img
        val scale = minOf(maxW.toDouble() / iw, maxH.toDouble() / ih, 1.0)
        return if (scale < 1.0) img.getScaledInstance((iw * scale).toInt(), (ih * scale).toInt(), Image.SCALE_SMOOTH) else img
    }
}