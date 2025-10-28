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
            sb.appendLine("component \"${step.name}$extras\" as ${alias(step.name)} <<$stereotype>>")
        }
        
        // Edges
        model.edges.forEach { e ->
            val label = when (e.kind) {
                "onFailure" -> " : onFailure"
                "else" -> " : else"
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
                else -> " -->"
            }
            sb.appendLine("${alias(e.from)}$style ${alias(e.to)}$label")
        }
        
        sb.appendLine("@enduml")
        return sb.toString()
    }

    private fun alias(name: String): String = name.replace("[^A-Za-z0-9_]".toRegex(), "_")
}
