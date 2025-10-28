package com.floxy.viewer.validate

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.model.FlowStepType

class Validator {
    data class Issue(val severity: String, val message: String)

    fun validate(model: FlowModel): List<Issue> {
        val issues = mutableListOf<Issue>()
        // 0) Empty
        if (model.steps.isEmpty()) {
            issues += Issue("ERROR", "Flow has no steps")
            return issues
        }

        // 1) Basic graph integrity: dangling edges
        model.edges.forEach { e ->
            if (!model.steps.containsKey(e.from)) issues += Issue("ERROR", "Edge from unknown step '${e.from}'")
            if (!model.steps.containsKey(e.to)) issues += Issue("ERROR", "Edge to unknown step '${e.to}'")
        }

        // Build adjacency maps for multiple checks
        val outgoing = model.edges.groupBy({ it.from }, { it })
        val incoming = model.edges.groupBy({ it.to }, { it })

        // 2) Cycle detection on "next" edges
        run {
            val graph = model.edges.filter { it.kind == "next" }.groupBy({ it.from }, { it.to })
            val visited = mutableSetOf<String>()
            val stack = mutableSetOf<String>()
            fun dfs(n: String) {
                if (n in stack) {
                    issues += Issue("ERROR", "Cycle detected at step '$n'")
                    return
                }
                if (n in visited) return
                visited += n
                stack += n
                graph[n]?.forEach { dfs(it) }
                stack -= n
            }
            // Start nodes: nodes with no incoming next edges
            val startCandidates = model.steps.keys.filter { k -> (incoming[k]?.none { it.kind == "next" } ?: true) }
            (startCandidates.ifEmpty { listOf(model.steps.keys.first()) }).forEach { dfs(it) }
        }

        // 3) Unmatched joins / weak joins
        model.steps.values.filter { it.type == FlowStepType.Join }.forEach { join ->
            val incJoin = incoming[join.name]?.count { it.kind == "join" } ?: 0
            if (incJoin == 0) issues += Issue("WARN", "Join '${join.name}' has no incoming branch joins")
            if (incJoin == 1) issues += Issue("WARN", "Join '${join.name}' has only one incoming branch (no real join)")
        }

        // 4) Empty branches (Fork/Parallel heads must have branch edges)
        model.steps.values.filter { it.type == FlowStepType.Fork }.forEach { fork ->
            val branches = outgoing[fork.name]?.filter { it.kind == "branch" } ?: emptyList()
            if (branches.isEmpty()) issues += Issue("ERROR", "Fork '${fork.name}' has no branches")
        }

        // 5) Unreachable nodes: consider all edges except none
        run {
            val allAdj = model.edges.groupBy({ it.from }, { it.to })
            val allIncoming = incoming
            val roots = model.steps.keys.filter { k -> (allIncoming[k]?.isEmpty() ?: true) }
            val visited = mutableSetOf<String>()
            fun dfs(n: String) {
                if (!visited.add(n)) return
                allAdj[n]?.forEach { dfs(it) }
            }
            (roots.ifEmpty { listOf(model.steps.keys.first()) }).forEach { dfs(it) }
            model.steps.keys.filterNot { it in visited }.forEach { unreachable ->
                issues += Issue("WARN", "Step '$unreachable' is unreachable from any start")
            }
        }

        // 6) Multiple onFailure from the same step
        run {
            val onFailures = model.edges.filter { it.kind == "onFailure" }.groupBy { it.from }
            onFailures.forEach { (from, edges) ->
                if (edges.size > 1) issues += Issue("ERROR", "Step '$from' has multiple onFailure targets")
                edges.forEach { e -> if (!model.steps.containsKey(e.to)) issues += Issue("ERROR", "onFailure of '$from' points to missing step '${e.to}'") }
            }
        }

        // 7) Inconsistent retry/idempotency in parallel group (Fork)
        model.steps.values.filter { it.type == FlowStepType.Fork }.forEach { fork ->
            val branchHeads = outgoing[fork.name]?.filter { it.kind == "branch" }?.map { it.to } ?: emptyList()
            if (branchHeads.size >= 2) {
                val retries = branchHeads.mapNotNull { model.steps[it]?.maxRetries }.toSet()
                val idempot = branchHeads.mapNotNull { model.steps[it]?.noIdempotent }.toSet()
                if (retries.size > 1) issues += Issue("WARN", "Fork '${fork.name}' branches have different maxRetries")
                if (idempot.size > 1) issues += Issue("WARN", "Fork '${fork.name}' branches have mixed idempotency flags")
            }
        }

        return issues
    }
}
