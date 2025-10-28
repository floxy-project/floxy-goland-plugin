package com.floxy.viewer.psi

import com.floxy.viewer.model.*
import com.goide.psi.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Parses floxy.Builder chains from Go PSI and produces FlowModel(s).
 * Uses text-based parsing for GoLand 2025.2 compatibility
 */
class FlowPsiAnalyzer(private val project: Project) {

    fun collectFlows(file: GoFile): List<FlowModel> {
        val flows = mutableListOf<FlowModel>()
        
        // Get file text and parse it
        val fileText = file.text
        
        // Find all floxy.NewBuilder patterns (support common typo NewBuidler and package aliases)
        val newBuilderPattern = Regex("""\b(?:[A-Za-z_]\w*)\.(?:NewBuilder|NewBuidler)\("([^"]+)",\s*(\d+)\)""")
        val matches = newBuilderPattern.findAll(fileText)
        
        for (match in matches) {
            val name = match.groupValues[1]
            val version = match.groupValues[2].toIntOrNull() ?: 1
            
            // Find the corresponding .Build() call
            val buildPattern = Regex("""\.Build\(\)""")
            val buildMatches = buildPattern.findAll(fileText, match.range.last)
            
            for (buildMatch in buildMatches) {
                val model = parseBuilderChainFromText(fileText, match.range, buildMatch.range, name, version)
                if (model != null) {
                    flows.add(model)
                    break // Only take the first Build() after this NewBuilder
                }
            }
        }
        
        return flows
    }

    private fun parseBuilderChainFromText(
        fileText: String, 
        newBuilderRange: IntRange, 
        buildRange: IntRange, 
        name: String, 
        version: Int
    ): FlowModel? {
        val model = FlowModel(name = name, version = version)
        
        // Extract the text between NewBuilder and Build
        val chainText = fileText.substring(newBuilderRange.last + 1, buildRange.first)
        
        // Parse method calls
        // Allow optional extra arguments after required strings to match opts/... and complex signatures
        val stepPattern = Regex("""\.(Step|Then)\(\s*"([^"]+)"\s*,\s*"([^"]+)"(?:[^)]*)\)""")
        val onFailurePattern = Regex("""\.OnFailure\(\s*"([^"]+)"\s*,\s*"([^"]+)"(?:[^)]*)\)""")
        val savePointPattern = Regex("""\.SavePoint\(\s*"([^"]+)"\s*\)""")
        val waitHumanPattern = Regex("""\.WaitHumanConfirm\(\s*"([^"]+)"(?:[^)]*)\)""")
        val joinStepPattern = Regex("""\.JoinStep\(\s*"([^"]+)"[^)]*\)""")
        val forkPattern = Regex("""\.Fork\(\s*"([^"]+)"[^)]*\)""")
        
        var lastStep: String? = null
        
        // Process Step/Then calls
        stepPattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[2]
            val handler = match.groupValues[3]
            
            model.steps[stepName] = FlowStep(stepName, handler, FlowStepType.Task)
            
            if (lastStep != null && lastStep != stepName) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
            }
            lastStep = stepName
        }
        
        // Process OnFailure calls
        onFailurePattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[1]
            val handler = match.groupValues[2]
            
            model.steps[stepName] = FlowStep(stepName, handler, FlowStepType.Task)
            
            if (lastStep != null) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "onFailure"))
            }
        }
        
        // Process SavePoint calls
        savePointPattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[1]
            
            model.steps[stepName] = FlowStep(stepName, null, FlowStepType.SavePoint)
            
            if (lastStep != null && lastStep != stepName) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
            }
            lastStep = stepName
        }
        
        // Process WaitHumanConfirm calls
        waitHumanPattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[1]
            
            model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Human)
            
            if (lastStep != null && lastStep != stepName) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
            }
            lastStep = stepName
        }
        
        // Process JoinStep calls
        joinStepPattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[1]
            
            model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Join)
            
            if (lastStep != null) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
            }
            lastStep = stepName
        }
        
        // Process Fork calls
        forkPattern.findAll(chainText).forEach { match ->
            val stepName = match.groupValues[1]
            
            model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Fork)
            
            if (lastStep != null && lastStep != stepName) {
                model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
            }
            lastStep = stepName
        }
        
        return if (model.steps.isNotEmpty()) model else null
    }
}
