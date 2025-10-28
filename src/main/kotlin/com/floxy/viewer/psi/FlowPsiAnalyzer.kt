package com.floxy.viewer.psi

import com.floxy.viewer.model.*
import com.goide.psi.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * Parses floxy.Builder chains from Go PSI and produces FlowModel(s).
 * Supported subset for MVP: NewBuilder -> Step/Then/OnFailure/SavePoint/WaitHumanConfirm -> Build
 */
class FlowPsiAnalyzer(private val project: Project) {

    fun collectFlows(file: GoFile): List<FlowModel> {
        val flows = mutableListOf<FlowModel>()
        file.accept(object : GoRecursiveVisitor() {
            override fun visitCallExpr(callExpr: GoCallExpr) {
                super.visitCallExpr(callExpr)
                val model = parseBuilderChain(callExpr)
                if (model != null) flows += model
            }
        })
        return flows
    }

    private fun parseBuilderChain(call: GoCallExpr): FlowModel? {
        // Try to detect terminal .Build() call and go backwards
        val selector = call.expression as? GoSelectorExpr ?: return null
        if (selector.identifier?.text != "Build") return null

        // Traverse the qualifier chain to find floxy.NewBuilder(name, version)
        val chain = collectSelectorChain(selector)
        val newBuilderCall = chain.firstOrNull { it is GoCallExpr && (it.expression as? GoSelectorExpr) == null }
        val funcRef = (newBuilderCall as? GoCallExpr)?.expression as? GoReferenceExpression ?: return null
        if (funcRef.text != "floxy.NewBuilder") return null

        val args = newBuilderCall!!.argumentList?.expressionList ?: return null
        if (args.size < 2) return null
        val name = constantString(args[0]) ?: return null
        val version = constantInt(args[1]) ?: 1
        val model = FlowModel(name = name, version = version)

        // Iterate chain to fill steps/edges
        var lastStep: String? = null
        chain.asReversed().forEach { element ->
            val callExpr = element as? GoCallExpr ?: return@forEach
            val sel = callExpr.expression as? GoSelectorExpr ?: return@forEach
            val method = sel.identifier?.text ?: return@forEach
            when (method) {
                "Step", "Then" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.size >= 2) {
                        val stepName = constantString(a[0]) ?: return@forEach
                        val handler = constantString(a[1])
                        ensureStep(model, stepName, handler, FlowStepType.Task)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges += FlowEdge(lastStep!!, stepName, kind = "next")
                        }
                        lastStep = stepName
                    }
                }
                "OnFailure" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.size >= 2) {
                        val stepName = constantString(a[0]) ?: return@forEach
                        val handler = constantString(a[1])
                        ensureStep(model, stepName, handler, FlowStepType.Task)
                        if (lastStep != null) {
                            model.edges += FlowEdge(lastStep!!, stepName, kind = "onFailure")
                        }
                    }
                }
                "SavePoint" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.isNotEmpty()) {
                        val stepName = constantString(a[0]) ?: return@forEach
                        ensureStep(model, stepName, null, FlowStepType.SavePoint)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges += FlowEdge(lastStep!!, stepName, kind = "next")
                        }
                        lastStep = stepName
                    }
                }
                "WaitHumanConfirm" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.isNotEmpty()) {
                        val stepName = constantString(a[0]) ?: return@forEach
                        ensureStep(model, stepName, null, FlowStepType.Human)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges += FlowEdge(lastStep!!, stepName, kind = "next")
                        }
                        lastStep = stepName
                    }
                }
                "JoinStep" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.isNotEmpty()) {
                        val joinName = constantString(a[0]) ?: return@forEach
                        // Extract waitFor slice and strategy
                        val waitFor = extractStringSlice(a.getOrNull(1))
                        val strategyText = a.getOrNull(2)?.text
                        val joinMeta = strategyText?.trim('"')
                        val existing = model.steps[joinName]
                        if (existing == null) {
                            model.steps[joinName] = FlowStep(
                                name = joinName,
                                type = FlowStepType.Join,
                                joinStrategy = joinMeta
                            )
                        }
                        // Connect listed branches to join
                        waitFor.forEach { src ->
                            if (model.steps[src] == null) ensureStep(model, src, null, FlowStepType.Task)
                            model.edges += FlowEdge(src, joinName, kind = "join")
                        }
                        // Connect previous step to join
                        if (lastStep != null) {
                            model.edges += FlowEdge(lastStep!!, joinName, kind = "next")
                        }
                        lastStep = joinName
                    }
                }
                "Condition" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.size >= 2) {
                        val condName = constantString(a[0]) ?: return@forEach
                        val condition = constantString(a[1])
                        ensureStep(model, condName, condition, FlowStepType.Condition)
                        if (lastStep != null && lastStep != condName) {
                            model.edges += FlowEdge(lastStep!!, condName, kind = "next")
                        }
                        // Else branch: try to resolve first Step(...) call inside func literal (arg #3)
                        val elseFunc = a.getOrNull(2) as? GoFunctionLit
                        if (elseFunc != null) {
                            val elseHead = findFirstStepNameInFunc(elseFunc)
                            if (elseHead != null) {
                                ensureStep(model, elseHead, null, FlowStepType.Task)
                                model.edges += FlowEdge(condName, elseHead, kind = "cond_false")
                            } else {
                                // Fallback marker node
                                val elseNode = "${condName}__else"
                                ensureStep(model, elseNode, null, FlowStepType.Task)
                                model.edges += FlowEdge(condName, elseNode, kind = "cond_false")
                            }
                        }
                        lastStep = condName
                    }
                }
                "Parallel" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.isNotEmpty()) {
                        val forkName = constantString(a[0]) ?: return@forEach
                        ensureStep(model, forkName, null, FlowStepType.Fork)
                        if (lastStep != null && lastStep != forkName) {
                            model.edges += FlowEdge(lastStep!!, forkName, kind = "next")
                        }
                        // Parse branch tasks from NewTask(...) calls
                        val branchHeads = mutableListOf<String>()
                        for (i in 1 until a.size) {
                            val task = parseNewTaskCall(a[i])
                            if (task != null) {
                                val (sName, handler) = task
                                ensureStep(model, sName, handler, FlowStepType.Task)
                                model.edges += FlowEdge(forkName, sName, kind = "branch")
                                branchHeads += sName
                            }
                        }
                        // Auto-join node
                        val joinName = "${forkName}_join"
                        if (model.steps[joinName] == null) {
                            model.steps[joinName] = FlowStep(name = joinName, type = FlowStepType.Join, joinStrategy = "all")
                        }
                        // Connect branches to join
                        branchHeads.forEach { head ->
                            model.edges += FlowEdge(head, joinName, kind = "join")
                        }
                        // Connect fork to join
                        model.edges += FlowEdge(forkName, joinName, kind = "next")
                        lastStep = joinName
                    }
                }
                "Fork" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.isNotEmpty()) {
                        val forkName = constantString(a[0]) ?: return@forEach
                        ensureStep(model, forkName, null, FlowStepType.Fork)
                        if (lastStep != null && lastStep != forkName) {
                            model.edges += FlowEdge(lastStep!!, forkName, kind = "next")
                        }
                        // Parse branch functions
                        val branchHeads = mutableListOf<String>()
                        for (i in 1 until a.size) {
                            val branchFunc = a[i] as? GoFunctionLit
                            if (branchFunc != null) {
                                val branchHead = findFirstStepNameInFunc(branchFunc)
                                if (branchHead != null) {
                                    ensureStep(model, branchHead, null, FlowStepType.Task)
                                    model.edges += FlowEdge(forkName, branchHead, kind = "branch")
                                    branchHeads += branchHead
                                }
                            }
                        }
                        lastStep = forkName
                    }
                }
                "ForkJoin" -> {
                    val a = callExpr.argumentList?.expressionList ?: return@forEach
                    if (a.size >= 3) {
                        val forkName = constantString(a[0]) ?: return@forEach
                        val joinName = constantString(a[2]) ?: return@forEach
                        ensureStep(model, forkName, null, FlowStepType.Fork)
                        if (lastStep != null && lastStep != forkName) {
                            model.edges += FlowEdge(lastStep!!, forkName, kind = "next")
                        }
                        if (model.steps[joinName] == null) {
                            model.steps[joinName] = FlowStep(name = joinName, type = FlowStepType.Join)
                        }
                        model.edges += FlowEdge(forkName, joinName, kind = "next")
                        lastStep = joinName
                    }
                }
            }
        }

        // Reverse edges created from reverse traversal if needed
        normalizeEdges(model)
        return if (model.steps.isNotEmpty()) model else null
    }

    private fun ensureStep(model: FlowModel, name: String, handler: String?, type: FlowStepType) {
        val existing = model.steps[name]
        if (existing == null) {
            model.steps[name] = FlowStep(name, handler = handler, type = type)
        } else if (existing.handler == null && handler != null) {
            model.steps[name] = existing.copy(handler = handler)
        }
    }

    private fun normalizeEdges(model: FlowModel) {
        // No-op for now; edges added already forward except for swap() usage
    }

    private fun constantString(e: PsiElement?): String? = when (e) {
        is GoStringLiteral -> e.decodedText
        else -> null
    }

    private fun constantInt(e: PsiElement?): Int? = when (e) {
        is GoLiteral -> e.text.toIntOrNull()
        else -> null
    }

    private fun collectSelectorChain(selector: GoSelectorExpr): List<PsiElement> {
        val chain = mutableListOf<PsiElement>()
        var expr: PsiElement? = selector
        while (true) {
            val call = expr?.parent as? GoCallExpr ?: break
            chain += call
            val qual = (call.expression as? GoSelectorExpr)?.qualifier
            expr = qual ?: (call.expression as? GoReferenceExpression)
            if (expr is GoReferenceExpression) {
                // Reached a root reference (e.g., floxy.NewBuilder)
                break
            }
        }
        // Append the root call if present
        (expr?.parent as? GoCallExpr)?.let { chain += it }
        return chain
    }

    // Try to find the first Step/Then call in a function literal and return its step name (first arg string)
    private fun findFirstStepNameInFunc(func: GoFunctionLit): String? {
        var found: String? = null
        func.accept(object : GoRecursiveVisitor() {
            override fun visitCallExpr(callExpr: GoCallExpr) {
                if (found != null) return
                val sel = callExpr.expression as? GoSelectorExpr ?: return
                val method = sel.identifier?.text
                if (method == "Step" || method == "Then") {
                    val a = callExpr.argumentList?.expressionList
                    val name = constantString(a?.getOrNull(0))
                    if (name != null) {
                        found = name
                        return
                    }
                }
                super.visitCallExpr(callExpr)
            }
        })
        return found
    }

    // Extract string array literal: e.g., []string{"a","b"}
    private fun extractStringSlice(e: PsiElement?): List<String> {
        val result = mutableListOf<String>()
        val comp = e as? GoCompositeLit ?: return result
        comp.literalValue?.elementList?.forEach { el ->
            val expr = el.expressionList.firstOrNull()
            val s = constantString(expr)
            if (s != null) result += s
        }
        return result
    }

    // Parse NewTask(...) from an expression; return pair(name, handler) if matches
    private fun parseNewTaskCall(e: PsiElement?): Pair<String, String?>? {
        val call = e as? GoCallExpr ?: return null
        val ref = call.expression as? GoReferenceExpression ?: return null
        val name = ref.text
        if (name != "NewTask" && name != "floxy.NewTask") return null
        val args = call.argumentList?.expressionList ?: return null
        val stepName = constantString(args.getOrNull(0)) ?: return null
        val handler = constantString(args.getOrNull(1))
        return stepName to handler
    }
}
