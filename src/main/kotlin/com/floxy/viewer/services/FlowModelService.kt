package com.floxy.viewer.services

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import com.goide.psi.GoFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FlowModelService(private val project: Project) : Disposable {

    data class ModelsEvent(val file: VirtualFile, val models: List<FlowModel>)
    data class SelectionEvent(val file: VirtualFile, val stepName: String)

    interface Listener {
        fun onModelsUpdated(event: ModelsEvent)
    }
    interface SelectionListener {
        fun onStepSelected(event: SelectionEvent)
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("FloxyFlowsUpdated", Listener::class.java)
        val SELECTION_TOPIC: Topic<SelectionListener> = Topic.create("FloxyStepSelected", SelectionListener::class.java)
        private const val DEBOUNCE_MS: Int = 2000
    }

    private val cache = ConcurrentHashMap<VirtualFile, List<FlowModel>>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        // Listen PSI changes within Go files
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) = schedule(event)
            override fun childAdded(event: PsiTreeChangeEvent) = schedule(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = schedule(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = schedule(event)
            override fun childMoved(event: PsiTreeChangeEvent) = schedule(event)
        }, this)

        // Listen VFS changes (rename/move/delete) - removed for compatibility
    }

    private fun schedule(event: PsiTreeChangeEvent) {
        val file = event.file?.virtualFile ?: return
        schedule(file)
    }

    private fun schedule(file: VirtualFile) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh(file) }, DEBOUNCE_MS)
    }

    private fun refresh(file: VirtualFile) {
        val analyzer = VisitorBasedFlowAnalyzer()
        val models = ApplicationManager.getApplication().runReadAction<List<FlowModel>> {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return@runReadAction emptyList()
            analyzer.collectFlowsFromText(psiFile.text)
        }
        cache[file] = models
        project.messageBus.syncPublisher(TOPIC).onModelsUpdated(ModelsEvent(file, models))
    }

    fun collectFromFile(file: VirtualFile): List<FlowModel> {
        return cache[file] ?: run {
            ApplicationManager.getApplication().runReadAction<List<FlowModel>> {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return@runReadAction emptyList()
                val analyzer = VisitorBasedFlowAnalyzer()
                val models = analyzer.collectFlowsFromText(psiFile.text)
                cache[file] = models
                models
            }
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
