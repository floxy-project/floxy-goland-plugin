package com.floxy.viewer.psi

import com.floxy.viewer.model.FlowModel

// Test-only shim to preserve old text-based tests while production uses PSI-only paths.
fun VisitorBasedFlowAnalyzer.collectFlowsFromText(fileText: String): List<FlowModel> {
    val results = analyzeFlowsDetailed(fileText)
    return results.map { it.model }
}

fun VisitorBasedFlowAnalyzer.analyzeFlowsDetailed(fileText: String): List<FlowAnalysisResult> {
    val results = mutableListOf<FlowAnalysisResult>()
    val parser = ChainParser()

    var startIndex = 0
    val buildPattern = Regex("""\.\s*Build\(\)""", setOf(RegexOption.DOT_MATCHES_ALL))

    while (true) {
        val nb = fileText.indexOf("NewBuilder(", startIndex)
        val nbt = fileText.indexOf("NewBuidler(", startIndex)
        val openIdx = when {
            nb == -1 && nbt == -1 -> break
            nb == -1 -> nbt
            nbt == -1 -> nb
            else -> minOf(nb, nbt)
        }
        val isTypo = fileText.startsWith("NewBuidler(", openIdx)
        val nameToken = if (isTypo) "NewBuidler" else "NewBuilder"
        val openParenIdx = openIdx + nameToken.length
        val closeIdx = findMatchingParenCompat(fileText, openParenIdx)
        if (closeIdx == -1) break
        val args = fileText.substring(openParenIdx + 1, closeIdx)

        val nameMatch = Regex("([\"`])(.+?)\\1", setOf(RegexOption.DOT_MATCHES_ALL)).find(args)
        val name = nameMatch?.groupValues?.getOrNull(2) ?: run { startIndex = closeIdx + 1; continue }

        val afterNameIndex = nameMatch.range.last + 1
        val versionMatch = Regex("\\b(\\d+)\\b").find(args, afterNameIndex)
        val version = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        val build = buildPattern.find(fileText, closeIdx)
        if (build != null) {
            val chainText = fileText.substring(closeIdx + 1, build.range.first)
            val ast = parser.parse(chainText)
            if (ast.isNotEmpty()) {
                val model = FlowModel(name = name, version = version)
                val builder = FlowModelBuilder()
                val ctx = VisitorContext(model)
                ast.forEach { node ->
                    acceptCompat(node, ctx, builder)
                }

                val validator = FlowGraphValidator()
                val vctx = VisitorContext(model)
                ast.forEach { node -> acceptCompat(node, vctx, validator) }
                validator.postValidate(model)

                val statsCollector = FlowStatisticsCollector()
                val sctx = VisitorContext(model)
                ast.forEach { node -> acceptCompat(node, sctx, statsCollector) }

                results.add(FlowAnalysisResult(model, validator.errors, statsCollector.toString()))
            }
            startIndex = build.range.last + 1
        } else {
            startIndex = closeIdx + 1
        }
    }

    return results
}

private fun findMatchingParenCompat(text: String, openIdx: Int): Int {
    var i = openIdx
    var depth = 0
    var inString: Char? = null
    while (i < text.length) {
        val c = text[i]
        if (inString != null) {
            if (c == inString) {
                inString = null
            } else if (c == '\\' && inString == '"' && i + 1 < text.length) {
                i++
            }
        } else {
            if (c == '"' || c == '`') {
                inString = c
            } else if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

private fun acceptCompat(node: ChainNode, context: VisitorContext, visitor: ChainVisitor) {
    when (node) {
        is ChainNode.StepNode -> visitor.visitStep(node, context)
        is ChainNode.OnFailureNode -> visitor.visitOnFailure(node, context)
        is ChainNode.SavePointNode -> visitor.visitSavePoint(node, context)
        is ChainNode.WaitHumanConfirmNode -> visitor.visitWaitHumanConfirm(node, context)
        is ChainNode.JoinStepNode -> visitor.visitJoinStep(node, context)
        is ChainNode.ForkNode -> visitor.visitFork(node, context)
        is ChainNode.ConditionNode -> visitor.visitCondition(node, context)
    }
}
