package com.floxy.viewer.psi

import com.floxy.viewer.model.*
import com.goide.psi.GoFile
import com.intellij.openapi.project.Project

/**
 * AST-подобные узлы для представления цепочки вызовов
 */
sealed class ChainNode {
    abstract val stepName: String

    data class StepNode(
        override val stepName: String,
        val handler: String
    ) : ChainNode()

    data class OnFailureNode(
        override val stepName: String,
        val handler: String
    ) : ChainNode()

    data class SavePointNode(
        override val stepName: String
    ) : ChainNode()

    data class WaitHumanConfirmNode(
        override val stepName: String
    ) : ChainNode()

    data class JoinStepNode(
        override val stepName: String,
        val waitFor: List<String>,
        val strategy: String? = null,
        val quorum: Int? = null
    ) : ChainNode()

    data class ForkNode(
        override val stepName: String,
        val branches: List<List<ChainNode>>
    ) : ChainNode()

    data class ConditionNode(
        override val stepName: String,
        val conditionExpr: String? = null,
        val thenBranch: List<ChainNode> = emptyList(),
        val elseBranch: List<ChainNode> = emptyList()
    ) : ChainNode()
}

/**
 * Visitor интерфейс для обхода AST
 */
interface ChainVisitor {
    fun visitStep(node: ChainNode.StepNode, context: VisitorContext)
    fun visitOnFailure(node: ChainNode.OnFailureNode, context: VisitorContext)
    fun visitSavePoint(node: ChainNode.SavePointNode, context: VisitorContext)
    fun visitWaitHumanConfirm(node: ChainNode.WaitHumanConfirmNode, context: VisitorContext)
    fun visitJoinStep(node: ChainNode.JoinStepNode, context: VisitorContext)
    fun visitFork(node: ChainNode.ForkNode, context: VisitorContext)
    fun visitCondition(node: ChainNode.ConditionNode, context: VisitorContext)
}

/**
 * Контекст для visitor'а
 */
class VisitorContext(
    val model: FlowModel,
    var lastStep: String? = null,
    val branchPrefix: String = ""
) {
    private val forkStack = mutableListOf<ForkContext>()

    fun pushFork(forkName: String) {
        forkStack.add(ForkContext(forkName))
    }

    fun popFork(): ForkContext? {
        return if (forkStack.isNotEmpty()) forkStack.removeAt(forkStack.lastIndex) else null
    }

    fun currentFork(): ForkContext? = forkStack.lastOrNull()

    fun addBranchLastNode(nodeName: String) {
        currentFork()?.addBranchLastNode(nodeName)
    }

    fun createBranchContext(branchNumber: Int): VisitorContext {
        val newPrefix = if (branchPrefix.isEmpty()) {
            "_branch_$branchNumber"
        } else {
            "${branchPrefix}_$branchNumber"
        }
        return VisitorContext(model, null, newPrefix)
    }

    fun getFullStepName(stepName: String): String {
        return if (branchPrefix.isEmpty()) stepName else "$stepName$branchPrefix"
    }

    class ForkContext(val forkName: String) {
        val branchLastNodes = mutableListOf<String>()

        fun addBranchLastNode(nodeName: String) {
            branchLastNodes.add(nodeName)
        }
    }
}

/**
 * Дополнительные visitor'ы для разных задач
 */

// Валидатор структуры графа
class FlowGraphValidator : ChainVisitor {
    val errors = mutableListOf<String>()
    private var forkDepth = 0

    override fun visitStep(node: ChainNode.StepNode, context: VisitorContext) {
        if (node.stepName.isBlank()) {
            errors.add("Empty step name")
        }
        // advance chain context similarly to builder
        val full = context.getFullStepName(node.stepName)
        context.lastStep = full
        context.addBranchLastNode(full)
    }

    override fun visitOnFailure(node: ChainNode.OnFailureNode, context: VisitorContext) {
        if (context.lastStep == null) {
            errors.add("OnFailure '${node.stepName}' without preceding step")
        }
        // does not change lastStep
    }

    override fun visitSavePoint(node: ChainNode.SavePointNode, context: VisitorContext) {
        val full = context.getFullStepName(node.stepName)
        context.lastStep = full
        context.addBranchLastNode(full)
    }
    override fun visitWaitHumanConfirm(node: ChainNode.WaitHumanConfirmNode, context: VisitorContext) {
        val full = context.getFullStepName(node.stepName)
        context.lastStep = full
        context.addBranchLastNode(full)
    }

    override fun visitJoinStep(node: ChainNode.JoinStepNode, context: VisitorContext) {
        if (context.currentFork() == null) {
            errors.add("JoinStep '${node.stepName}' without active Fork")
        }
        // close fork scope if any to mirror builder semantics
        context.popFork()
        val full = context.getFullStepName(node.stepName)
        context.lastStep = full
        context.addBranchLastNode(full)
    }

    override fun visitFork(node: ChainNode.ForkNode, context: VisitorContext) {
        if (node.branches.isEmpty()) {
            errors.add("Fork '${node.stepName}' has no branches")
        }
        forkDepth++
        if (forkDepth > 5) {
            errors.add("Fork nesting too deep (max 5): '${node.stepName}'")
        }

        // open fork scope
        context.pushFork(context.getFullStepName(node.stepName))

        // Validate each branch with proper branch index-specific context
        node.branches.forEachIndexed { idx, branch ->
            val branchCtx = context.createBranchContext(idx + 1)
            branch.forEach { branchNode ->
                accept(branchNode, branchCtx, this)
            }
            branchCtx.lastStep?.let { context.addBranchLastNode(it) }
        }

        // after fork, the logical last step is the fork head
        context.lastStep = context.getFullStepName(node.stepName)

        forkDepth--
    }

    override fun visitCondition(node: ChainNode.ConditionNode, context: VisitorContext) {
        if (node.thenBranch.isEmpty() && node.elseBranch.isEmpty()) {
            errors.add("Condition '${node.stepName}' has no branches")
        }
        // else branch processed using separate context similar to builder
        if (node.elseBranch.isNotEmpty()) {
            val elseCtx = VisitorContext(context.model, null, context.branchPrefix)
            node.elseBranch.forEach { elseNode ->
                accept(elseNode, elseCtx, this)
            }
            elseCtx.lastStep?.let { context.addBranchLastNode(it) }
        }
        // mark condition as the last in main chain so next is considered 'then'
        val full = context.getFullStepName(node.stepName)
        context.lastStep = full
        context.addBranchLastNode(full)
    }

    private fun accept(node: ChainNode, context: VisitorContext, visitor: ChainVisitor) {
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
}

// Статистика по графу
class FlowStatisticsCollector : ChainVisitor {
    var totalSteps = 0
    var forkCount = 0
    var maxBranchDepth = 0
    var conditionCount = 0
    private var currentDepth = 0

    override fun visitStep(node: ChainNode.StepNode, context: VisitorContext) {
        totalSteps++
    }

    override fun visitOnFailure(node: ChainNode.OnFailureNode, context: VisitorContext) {
        totalSteps++
    }

    override fun visitSavePoint(node: ChainNode.SavePointNode, context: VisitorContext) {
        totalSteps++
    }

    override fun visitWaitHumanConfirm(node: ChainNode.WaitHumanConfirmNode, context: VisitorContext) {
        totalSteps++
    }

    override fun visitJoinStep(node: ChainNode.JoinStepNode, context: VisitorContext) {
        totalSteps++
    }

    override fun visitFork(node: ChainNode.ForkNode, context: VisitorContext) {
        forkCount++
        currentDepth++
        maxBranchDepth = maxOf(maxBranchDepth, currentDepth)

        node.branches.forEach { branch ->
            branch.forEach { branchNode ->
                accept(branchNode, context.createBranchContext(1), this)
            }
        }

        currentDepth--
    }

    override fun visitCondition(node: ChainNode.ConditionNode, context: VisitorContext) {
        conditionCount++
        totalSteps++
    }

    private fun accept(node: ChainNode, context: VisitorContext, visitor: ChainVisitor) {
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

    override fun toString(): String = """
        Statistics:
        - Total steps: $totalSteps
        - Fork count: $forkCount
        - Max branch depth: $maxBranchDepth
        - Condition count: $conditionCount
    """.trimIndent()
}

/**
 * Visitor для построения FlowModel из AST
 */
class FlowModelBuilder : ChainVisitor {

    override fun visitStep(node: ChainNode.StepNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(fullName, node.handler, FlowStepType.Task)

        context.lastStep?.let { last ->
            if (last != fullName) {
                // Проверяем, является ли предыдущий шаг Condition
                val prevStep = context.model.steps[last]
                val edgeKind = if (prevStep?.type == FlowStepType.Condition) "then" else "next"
                context.model.edges.add(FlowEdge(last, fullName, edgeKind))
            }
        }

        context.lastStep = fullName
        context.addBranchLastNode(fullName)
    }

    override fun visitOnFailure(node: ChainNode.OnFailureNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(fullName, node.handler, FlowStepType.Task)

        context.lastStep?.let { last ->
            context.model.edges.add(FlowEdge(last, fullName, "onFailure"))
        }

        // OnFailure не меняет lastStep для основной цепочки
    }

    override fun visitSavePoint(node: ChainNode.SavePointNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(fullName, null, FlowStepType.SavePoint)

        context.lastStep?.let { last ->
            if (last != fullName) {
                context.model.edges.add(FlowEdge(last, fullName, "next"))
            }
        }

        context.lastStep = fullName
        context.addBranchLastNode(fullName)
    }

    override fun visitWaitHumanConfirm(node: ChainNode.WaitHumanConfirmNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(fullName, null, FlowStepType.Human)

        context.lastStep?.let { last ->
            if (last != fullName) {
                context.model.edges.add(FlowEdge(last, fullName, "next"))
            }
        }

        context.lastStep = fullName
        context.addBranchLastNode(fullName)
    }

    override fun visitJoinStep(node: ChainNode.JoinStepNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(
            fullName,
            null,
            FlowStepType.Join,
            joinStrategy = node.strategy,
            joinQuorum = node.quorum
        )

        // Соединяем все последние ноды из веток Fork с Join
        val forkContext = context.popFork()
        forkContext?.branchLastNodes?.forEach { branchLastNode ->
            context.model.edges.add(FlowEdge(branchLastNode, fullName, "join"))
        }

        context.lastStep?.let { last ->
            if (last != fullName && forkContext == null) {
                // Если нет активного Fork, соединяем с предыдущим шагом
                context.model.edges.add(FlowEdge(last, fullName, "next"))
            }
        }

        context.lastStep = fullName
        context.addBranchLastNode(fullName)
    }

    override fun visitFork(node: ChainNode.ForkNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)

        // Извлекаем имена шагов из веток для parallelBranches
        val parallelBranches = node.branches.map { branch ->
            branch.map { it.stepName }
        }

        context.model.steps[fullName] = FlowStep(
            fullName,
            null,
            FlowStepType.Fork,
            parallelBranches = parallelBranches
        )

        context.lastStep?.let { last ->
            if (last != fullName) {
                context.model.edges.add(FlowEdge(last, fullName, "next"))
            }
        }

        // Создаем контекст для Fork
        context.pushFork(fullName)

        // Обрабатываем каждую ветку
        node.branches.forEachIndexed { branchIndex, branchNodes ->
            val branchContext = context.createBranchContext(branchIndex + 1)

            // Соединяем Fork с первым шагом ветки
            if (branchNodes.isNotEmpty()) {
                val firstStepName = branchContext.getFullStepName(branchNodes.first().stepName)
                context.model.edges.add(FlowEdge(fullName, firstStepName, "split"))
            }

            // Обходим все узлы ветки
            branchNodes.forEach { branchNode ->
                accept(branchNode, branchContext, this)
            }
            
            // Добавляем последний шаг ветки в контекст Fork
            branchContext.lastStep?.let { context.addBranchLastNode(it) }
        }

        context.lastStep = fullName
    }

    override fun visitCondition(node: ChainNode.ConditionNode, context: VisitorContext) {
        val fullName = context.getFullStepName(node.stepName)
        context.model.steps[fullName] = FlowStep(fullName, null, FlowStepType.Condition)

        context.lastStep?.let { last ->
            if (last != fullName) {
                context.model.edges.add(FlowEdge(last, fullName, "next"))
            }
        }

        // Обрабатываем else ветку (из func внутри Condition)
        if (node.elseBranch.isNotEmpty()) {
            val elseContext = VisitorContext(context.model, null, context.branchPrefix)
            
            node.elseBranch.forEachIndexed { index, elseNode ->
                if (index == 0) {
                    // Первый шаг в else ветке - соединяем с Condition
                    val elseStepName = elseContext.getFullStepName(elseNode.stepName)
                    context.model.edges.add(FlowEdge(fullName, elseStepName, "else"))
                }
                    
                accept(elseNode, elseContext, this)
            }
                
            // Запоминаем последний шаг else-ветки для возможного соединения
            elseContext.lastStep?.let { context.addBranchLastNode(it) }
        }

        // Устанавливаем специальный флаг, что следующий шаг должен быть соединён как "then"
        context.lastStep = fullName
        context.addBranchLastNode(fullName)
    }

    private fun accept(node: ChainNode, context: VisitorContext, visitor: ChainVisitor) {
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
}

/**
 * Парсер, который строит AST из текста
 */
class ChainParser {

    fun parse(chainText: String): List<ChainNode> {
        val nodes = mutableListOf<ChainNode>()

        fun extractArgs(call: String): String {
            val paren = call.indexOf('(')
            val close = findMatchingParen(call, paren)
            return if (paren != -1 && close != -1) call.substring(paren + 1, close) else ""
        }

        fun extractStrings(args: String, max: Int): List<String> {
            val res = mutableListOf<String>()
            var k = 0
            while (k < args.length && res.size < max) {
                while (k < args.length && args[k].isWhitespace()) k++
                if (k >= args.length) break
                val c = args[k]
                if (c == '"' || c == '`') {
                    val quote = c
                    k++
                    val sb = StringBuilder()
                    while (k < args.length) {
                        val ch = args[k]
                        if (ch == quote) { k++; break }
                        if (quote == '"' && ch == '\\' && k + 1 < args.length) {
                            k++
                            sb.append(args[k])
                            k++
                            continue
                        }
                        sb.append(ch)
                        k++
                    }
                    res.add(sb.toString())
                    while (k < args.length && args[k] != ',') k++
                    if (k < args.length && args[k] == ',') k++
                } else {
                    var depth = 0
                    while (k < args.length) {
                        val ch = args[k]
                        if (ch == '(' || ch == '[' || ch == '{') depth++
                        else if (ch == ')' || ch == ']' || ch == '}') { if (depth == 0) break; depth-- }
                        else if (depth == 0 && ch == ',') { k++; break }
                        k++
                    }
                }
            }
            return res
        }

        val methodNamePattern = Regex("""\A\.[\s]*(Step|Then|OnFailure|SavePoint|WaitHumanConfirm|JoinStep|Fork|Condition)[\s]*\(""")

        var i = 0
        while (i < chainText.length) {
            val dot = chainText.indexOf('.', i)
            if (dot == -1) break
            val nextOpen = chainText.indexOf('(', dot)
            if (nextOpen == -1) break
            val close = findMatchingParen(chainText, nextOpen)
            if (close == -1) break
            val callText = chainText.substring(dot, close + 1)

            val methodMatcher = methodNamePattern.find(callText)
            val method = methodMatcher?.groupValues?.getOrNull(1)

            when (method) {
                "Step", "Then" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 2)
                    if (strs.size >= 2) {
                        nodes.add(ChainNode.StepNode(strs[0], strs[1]))
                    }
                }
                "OnFailure" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 2)
                    if (strs.size >= 2) {
                        nodes.add(ChainNode.OnFailureNode(strs[0], strs[1]))
                    }
                }
                "SavePoint" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        nodes.add(ChainNode.SavePointNode(strs[0]))
                    }
                }
                "WaitHumanConfirm" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        nodes.add(ChainNode.WaitHumanConfirmNode(strs[0]))
                    }
                }
                "JoinStep" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        // TODO: извлечь strategy и quorum из аргументов
                        nodes.add(ChainNode.JoinStepNode(strs[0], emptyList()))
                    }
                }
                "Fork" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val branches = extractBranches(callText)
                        nodes.add(ChainNode.ForkNode(strs[0], branches))
                    }
                }
                "Condition" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 2)
                    if (strs.isNotEmpty()) {
                        val conditionExpr = if (strs.size >= 2) strs[1] else null
                        val elseBranch = extractElseBranch(callText)
                        nodes.add(ChainNode.ConditionNode(strs[0], conditionExpr, emptyList(), elseBranch))
                    }
                }
            }

            i = close + 1
        }

        return nodes
    }

    private fun extractElseBranch(conditionCallText: String): List<ChainNode> {
        // Ищем func( внутри вызова Condition - это else-ветка
        var i = 0
        while (i < conditionCallText.length) {
            val funcStart = conditionCallText.indexOf("func(", i)
            if (funcStart == -1) break

            val openBrace = conditionCallText.indexOf('{', funcStart)
            if (openBrace == -1) break

            val closeBrace = findMatchingBrace(conditionCallText, openBrace)
            if (closeBrace == -1) break

            val branchContent = conditionCallText.substring(openBrace + 1, closeBrace)
            val branchNodes = parse(branchContent)

            if (branchNodes.isNotEmpty()) {
                return branchNodes
            }

            i = closeBrace + 1
        }

        return emptyList()
    }

    private fun extractBranches(forkCallText: String): List<List<ChainNode>> {
        val branches = mutableListOf<List<ChainNode>>()

        var i = 0
        while (i < forkCallText.length) {
            val funcStart = forkCallText.indexOf("func(", i)
            if (funcStart == -1) break

            val openBrace = forkCallText.indexOf('{', funcStart)
            if (openBrace == -1) break

            val closeBrace = findMatchingBrace(forkCallText, openBrace)
            if (closeBrace == -1) break

            val branchContent = forkCallText.substring(openBrace + 1, closeBrace)
            val branchNodes = parse(branchContent)

            if (branchNodes.isNotEmpty()) {
                branches.add(branchNodes)
            }

            i = closeBrace + 1
        }

        return branches
    }

    private fun findMatchingBrace(text: String, openIdx: Int): Int {
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
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun findMatchingParen(text: String, openIdx: Int): Int {
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
}

/**
 * Результат анализа графа
 */
data class FlowAnalysisResult(
    val model: FlowModel,
    val errors: List<String>,
    val statistics: String
)

/**
 * Главный анализатор с использованием Visitor pattern
 */
class VisitorBasedFlowAnalyzer {

    private val parser = ChainParser()

    /**
     * Простой метод - только построение модели
     */
    fun collectFlowsFromText(fileText: String): List<FlowModel> {
        return analyzeFlowsDetailed(fileText).map { it.model }
    }

    /**
     * Полный анализ с валидацией и статистикой
     */
    fun analyzeFlowsDetailed(fileText: String): List<FlowAnalysisResult> {
        val results = mutableListOf<FlowAnalysisResult>()
        val buildPattern = Regex("""\.\s*Build\(\)""", setOf(RegexOption.DOT_MATCHES_ALL))

        var startIndex = 0
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
            val closeIdx = findMatchingParen(fileText, openParenIdx)
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
                    val context = VisitorContext(model)
                    val builder = FlowModelBuilder()

                    // Обходим AST с помощью visitor
                    ast.forEach { node ->
                        accept(node, context, builder)
                    }

                    // Валидация
                    val validator = FlowGraphValidator()
                    val validationContext = VisitorContext(model)
                    ast.forEach { node ->
                        accept(node, validationContext, validator)
                    }

                    // Статистика
                    val statsCollector = FlowStatisticsCollector()
                    val statsContext = VisitorContext(model)
                    ast.forEach { node ->
                        accept(node, statsContext, statsCollector)
                    }

                    results.add(
                        FlowAnalysisResult(
                            model = model,
                            errors = validator.errors,
                            statistics = statsCollector.toString()
                        )
                    )
                }

                startIndex = build.range.last + 1
            } else {
                startIndex = closeIdx + 1
            }
        }

        return results
    }

    private fun accept(node: ChainNode, context: VisitorContext, visitor: ChainVisitor) {
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

    private fun findMatchingParen(text: String, openIdx: Int): Int {
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
}
