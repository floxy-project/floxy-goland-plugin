package com.floxy.viewer.model

enum class FlowStepType { Task, Fork, Join, SavePoint, Condition, Human }

data class FlowStep(
    val name: String,
    val handler: String? = null,
    val type: FlowStepType = FlowStepType.Task,
    val noIdempotent: Boolean = false,
    val maxRetries: Int? = null,
    // Optional join metadata (for Join nodes)
    val joinStrategy: String? = null,
    val joinQuorum: Int? = null
)

data class FlowEdge(
    val from: String,
    val to: String,
    // edge kinds: next | onFailure | branch | join | else | cond_true | cond_false | split
    val kind: String = "next"
)

data class FlowModel(
    val name: String,
    val version: Int,
    val steps: MutableMap<String, FlowStep> = linkedMapOf(),
    val edges: MutableList<FlowEdge> = mutableListOf()
)
