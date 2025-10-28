package com.floxy.viewer.render

import com.floxy.viewer.model.*

class PlantUmlRenderer {
    fun render(model: FlowModel): String {
        val sb = StringBuilder()
        sb.appendLine("@startuml")
        sb.appendLine("title ${model.name} v${model.version}")
        
        // Declare nodes
        model.steps.values.forEach { step ->
            val stereotype = when (step.type) {
                FlowStepType.Task -> "task"
                FlowStepType.Fork -> "fork"
                FlowStepType.Join -> "join"
                FlowStepType.SavePoint -> "savepoint"
                FlowStepType.Condition -> "cond"
                FlowStepType.Human -> "human"
                FlowStepType.Parallel -> "parallel"
            }
            val color = when (step.type) {
                // Pastel/light colors for readability
                FlowStepType.Task -> "#E3F2FD"      // light blue
                FlowStepType.Fork -> "#FFF3E0"      // light orange
                FlowStepType.Join -> "#E8F5E9"      // light green
                FlowStepType.SavePoint -> "#F3E5F5" // light purple
                FlowStepType.Condition -> "#FFFDE7" // light yellow
                FlowStepType.Human -> "#FBE9E7"     // light red/orange
                FlowStepType.Parallel -> "#ECEFF1"  // light grey
            }
            val extras = buildString {
                if (step.type == FlowStepType.Join && step.joinStrategy != null) {
                    append("\\n[")
                    append(step.joinStrategy)
                    step.joinQuorum?.let { append(":$it") }
                    append("]")
                }
                if (step.handler != null && step.handler.isNotEmpty()) {
                    append("\\n(${step.handler})")
                }
            }
            
            // For parallel branch steps, show original name without branch suffix
            val displayName = if (step.name.contains("_branch_")) {
                step.name.substringBefore("_branch_")
            } else {
                step.name
            }
            
            // PlantUML allows per-component color via trailing #color
            sb.appendLine("component \"$displayName$extras\" as ${alias(step.name)} <<$stereotype>> $color")
        }
        
        // Edges
        model.edges.forEach { e ->
            val label = when (e.kind) {
                "onFailure" -> " : onFailure"
                "else" -> " : else"
                "then" -> " : then"
                "branch" -> " : branch"
                "join" -> " : join"
                "cond_true" -> " : true"
                "cond_false" -> " : false"
                "split" -> " : split"
                "next" -> ""
                else -> ""
            }
            val style = when (e.kind) {
                "onFailure" -> " ..>"
                "cond_false" -> " ..>"
                "else" -> " ..>"
                "then" -> " -->"
                "split" -> " -->"
                "join" -> " -->"
                else -> " -->"
            }
            sb.appendLine("${alias(e.from)}$style ${alias(e.to)}$label")
        }
        
        sb.appendLine("@enduml")
        return sb.toString()
    }

    private fun alias(name: String): String = name.replace("[^A-Za-z0-9_]".toRegex(), "_")
}
