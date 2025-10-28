package com.floxy.viewer.psi

import com.floxy.viewer.model.*
import com.goide.psi.GoFile
import com.intellij.openapi.project.Project

/**
 * Parses floxy.Builder chains from Go files using robust text parsing.
 * This avoids depending on Go PSI internals that may differ across SDK versions.
 */
class FlowPsiAnalyzer(private val project: Project? = null) {

    fun collectFlows(file: GoFile): List<FlowModel> {
        val fileText = file.text ?: return emptyList()
        return parseText(fileText)
    }

    fun collectFlowsFromText(fileText: String): List<FlowModel> = parseText(fileText)

    private fun parseText(fileText: String): List<FlowModel> {
        val flows = mutableListOf<FlowModel>()

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

            // Extract the first string literal as name (supports "..." or `...`)
            val nameMatch = Regex("([\"`])(.+?)\\1", setOf(RegexOption.DOT_MATCHES_ALL)).find(args)
            val name = nameMatch?.groupValues?.getOrNull(2) ?: run { startIndex = closeIdx + 1; continue }

            // Extract the first integer literal after the name as version
            val afterNameIndex = nameMatch.range.last + 1
            val versionMatch = Regex("\\b(\\d+)\\b").find(args, afterNameIndex)
            val version = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            // Locate the first .Build() after this constructor call
            val build = buildPattern.find(fileText, closeIdx)
            if (build != null) {
                val chainText = fileText.substring(closeIdx + 1, build.range.first)
                val model = parseChain(chainText, name, version)
                if (model != null) flows.add(model)
                startIndex = build.range.last + 1
            } else {
                startIndex = closeIdx + 1
            }
        }
        return flows
    }

    private fun parseChain(chainText: String, name: String, version: Int): FlowModel? {
        val model = FlowModel(name = name, version = version)
        var lastStep: String? = null

        // Allow optional extra args after required strings (opts, strategies, etc.)
        val ws = "\\s*"
        // Lightweight patterns just to detect method names; actual arg extraction is done programmatically
        val methodNamePattern = Regex("""\A\.${ws}(Step|Then|OnFailure|SavePoint|WaitHumanConfirm|JoinStep|Fork|Condition)${ws}\(""")

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
                        if (quote == '"' && ch == '\\' && k + 1 < args.length) { // handle escapes in "..."
                            k++
                            sb.append(args[k])
                            k++
                            continue
                        }
                        sb.append(ch)
                        k++
                    }
                    res.add(sb.toString())
                    // skip until next comma to simplify
                    while (k < args.length && args[k] != ',') k++
                    if (k < args.length && args[k] == ',') k++
                } else {
                    // skip token (identifier/nested call/array), roughly
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

        // Scan chainText left-to-right and extract .MethodName(<balanced parentheses>) calls
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
                        val stepName = strs[0]
                        val handler = strs[1]
                        model.steps[stepName] = FlowStep(stepName, handler, FlowStepType.Task)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        lastStep = stepName
                    }
                }
                "OnFailure" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 2)
                    if (strs.size >= 2) {
                        val stepName = strs[0]
                        val handler = strs[1]
                        model.steps[stepName] = FlowStep(stepName, handler, FlowStepType.Task)
                        if (lastStep != null) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "onFailure"))
                        }
                    }
                }
                "SavePoint" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val stepName = strs[0]
                        model.steps[stepName] = FlowStep(stepName, null, FlowStepType.SavePoint)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        lastStep = stepName
                    }
                }
                "WaitHumanConfirm" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val stepName = strs[0]
                        model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Human)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        lastStep = stepName
                    }
                }
                "JoinStep" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val stepName = strs[0]
                        model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Join)
                        
                        // Find the last steps from all parallel branches and connect them to join
                        val lastForkStep = findLastForkStep(model)
                        if (lastForkStep != null) {
                            val forkStep = model.steps[lastForkStep]
                            forkStep?.parallelBranches?.forEachIndexed { branchIndex, branchSteps ->
                                if (branchSteps.isNotEmpty()) {
                                    val lastBranchStepName = "${branchSteps.last()}_branch_${branchIndex + 1}"
                                    model.edges.add(FlowEdge(lastBranchStepName, stepName, "join"))
                                }
                            }
                        }
                        
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        lastStep = stepName
                    }
                }
                "Fork" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val stepName = strs[0]
                        val parallelBranches = extractParallelBranches(callText)
                        val branchStructures = extractParallelBranchStructures(callText)
                        
                        // Create the fork step
                        model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Fork, parallelBranches = parallelBranches)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        
                        // Create steps for each parallel branch and add connections
                        branchStructures.forEachIndexed { branchIndex, branchSteps ->
                            createStructuredBranchSteps(model, stepName, branchSteps, branchIndex + 1)
                        }
                        
                        lastStep = stepName
                    }
                }
                "Condition" -> {
                    val args = extractArgs(callText)
                    val strs = extractStrings(args, 1)
                    if (strs.isNotEmpty()) {
                        val stepName = strs[0]
                        model.steps[stepName] = FlowStep(stepName, null, FlowStepType.Condition)
                        if (lastStep != null && lastStep != stepName) {
                            model.edges.add(FlowEdge(lastStep!!, stepName, "next"))
                        }
                        lastStep = stepName
                    }
                }
            }

            i = close + 1
        }

        return if (model.steps.isNotEmpty()) model else null
    }

    private fun extractParallelBranches(forkCallText: String): List<List<String>> {
        val branches = mutableListOf<List<String>>()
        
        // Find all function calls within the Fork call using a more robust approach
        var i = 0
        while (i < forkCallText.length) {
            val funcStart = forkCallText.indexOf("func(", i)
            if (funcStart == -1) break
            
            val openBrace = forkCallText.indexOf('{', funcStart)
            if (openBrace == -1) break
            
            val closeBrace = findMatchingBrace(forkCallText, openBrace)
            if (closeBrace == -1) break
            
            val branchContent = forkCallText.substring(openBrace + 1, closeBrace)
            val branchSteps = extractStepsFromBranch(branchContent)
            
            if (branchSteps.isNotEmpty()) {
                branches.add(branchSteps)
            }
            
            i = closeBrace + 1
        }
        
        return branches
    }
    
    private fun extractParallelBranchStructures(forkCallText: String): List<List<BranchStep>> {
        val branches = mutableListOf<List<BranchStep>>()
        
        // Find all function calls within the Fork call using a more robust approach
        var i = 0
        while (i < forkCallText.length) {
            val funcStart = forkCallText.indexOf("func(", i)
            if (funcStart == -1) break
            
            val openBrace = forkCallText.indexOf('{', funcStart)
            if (openBrace == -1) break
            
            val closeBrace = findMatchingBrace(forkCallText, openBrace)
            if (closeBrace == -1) break
            
            val branchContent = forkCallText.substring(openBrace + 1, closeBrace)
            val branchSteps = extractBranchStructure(branchContent)
            
            if (branchSteps.isNotEmpty()) {
                branches.add(branchSteps)
            }
            
            i = closeBrace + 1
        }
        
        return branches
    }
    
    private fun extractStepsFromBranch(branchContent: String): List<String> {
        val steps = mutableListOf<String>()
        
        // Extract all method calls from this branch (including nested Fork)
        val methodPattern = Regex("""\.\s*(Step|Then|Condition|OnFailure|SavePoint|WaitHumanConfirm|Fork|JoinStep)\s*\(\s*([\"`])([^\"`]+)\2""")
        val methodMatches = methodPattern.findAll(branchContent)
        
        for (methodMatch in methodMatches) {
            val methodName = methodMatch.groupValues[1]
            val stepName = methodMatch.groupValues[3]
            
            steps.add(stepName)
            
            // If this is a Fork, recursively extract steps from its branches
            if (methodName == "Fork") {
                val forkStart = methodMatch.range.first
                val forkEnd = findMatchingParen(branchContent, forkStart + methodName.length)
                if (forkEnd != -1) {
                    val forkContent = branchContent.substring(forkStart, forkEnd + 1)
                    val nestedBranches = extractParallelBranches(forkContent)
                    // Add nested steps to the current branch
                    nestedBranches.forEach { nestedSteps ->
                        steps.addAll(nestedSteps)
                    }
                }
            }
        }
        
        return steps
    }
    
    private fun extractBranchStructure(branchContent: String): List<BranchStep> {
        val steps = mutableListOf<BranchStep>()
        
        // Extract all method calls from this branch
        val methodPattern = Regex("""\.\s*(Step|Then|Condition|OnFailure|SavePoint|WaitHumanConfirm|Fork|JoinStep)\s*\(\s*([\"`])([^\"`]+)\2""")
        val methodMatches = methodPattern.findAll(branchContent)
        
        for (methodMatch in methodMatches) {
            val methodName = methodMatch.groupValues[1]
            val stepName = methodMatch.groupValues[3]
            
            when (methodName) {
                "Fork" -> {
                    val forkStart = methodMatch.range.first
                    val forkEnd = findMatchingParen(branchContent, forkStart + methodName.length)
                    if (forkEnd != -1) {
                        val forkContent = branchContent.substring(forkStart, forkEnd + 1)
                        val nestedBranches = extractParallelBranches(forkContent)
                        steps.add(BranchStep(stepName, BranchStepType.Fork, nestedBranches))
                    }
                }
                "JoinStep" -> {
                    steps.add(BranchStep(stepName, BranchStepType.Join))
                }
                "Condition" -> {
                    steps.add(BranchStep(stepName, BranchStepType.Condition))
                }
                else -> {
                    steps.add(BranchStep(stepName, BranchStepType.Task))
                }
            }
        }
        
        return steps
    }
    
    private data class BranchStep(
        val name: String,
        val type: BranchStepType,
        val nestedBranches: List<List<String>> = emptyList()
    )
    
    private enum class BranchStepType {
        Task, Condition, Fork, Join
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
                    i++ // skip escaped character
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
                    i++ // skip escaped character
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

    private fun findLastForkStep(model: FlowModel): String? {
        return model.steps.values
            .filter { it.type == FlowStepType.Fork }
            .maxByOrNull { model.edges.indexOfFirst { edge -> edge.to == it.name } }
            ?.name
    }

    private fun createStructuredBranchSteps(model: FlowModel, forkStepName: String, branchSteps: List<BranchStep>, branchNumber: Int) {
        if (branchSteps.isEmpty()) return
        
        var lastStepName: String? = null
        
        var skipNextSteps = 0
        
        branchSteps.forEachIndexed { stepIndex, branchStep ->
            // Skip steps that are already processed as part of condition branching
            if (skipNextSteps > 0) {
                skipNextSteps--
                return@forEachIndexed
            }
            
            val fullStepName = "${branchStep.name}_branch_$branchNumber"
            
            // Create the step
            val stepType = when (branchStep.type) {
                BranchStepType.Task -> FlowStepType.Task
                BranchStepType.Condition -> FlowStepType.Condition
                BranchStepType.Fork -> FlowStepType.Fork
                BranchStepType.Join -> FlowStepType.Join
            }
            model.steps[fullStepName] = FlowStep(fullStepName, null, stepType)
            
            // Connect fork to first step
            if (stepIndex == 0) {
                model.edges.add(FlowEdge(forkStepName, fullStepName, "split"))
            }
            
            // Handle special step types
            when (branchStep.type) {
                BranchStepType.Fork -> {
                    // Connect to previous step
                    if (lastStepName != null) {
                        model.edges.add(FlowEdge(lastStepName!!, fullStepName, "next"))
                    }
                    // Recursively create nested fork steps
                    branchStep.nestedBranches.forEachIndexed { nestedBranchIndex, nestedSteps ->
                        createStructuredBranchSteps(model, fullStepName, nestedSteps.map { BranchStep(it, BranchStepType.Task) }, branchNumber)
                    }
                    lastStepName = fullStepName
                }
                BranchStepType.Join -> {
                    // Connect to previous step
                    if (lastStepName != null) {
                        model.edges.add(FlowEdge(lastStepName!!, fullStepName, "next"))
                    }
                    // Connect to the join step from the fork
                    model.edges.add(FlowEdge(forkStepName, fullStepName, "join"))
                    lastStepName = fullStepName
                }
                BranchStepType.Condition -> {
                    // Connect to previous step
                    if (lastStepName != null) {
                        model.edges.add(FlowEdge(lastStepName!!, fullStepName, "next"))
                    }
                    // Handle condition branching - look for else and then steps
                    val elseStepIndex = stepIndex + 1
                    val thenStepIndex = stepIndex + 2
                    
                    if (elseStepIndex < branchSteps.size) {
                        val elseStepName = "${branchSteps[elseStepIndex].name}_branch_$branchNumber"
                        model.steps[elseStepName] = FlowStep(elseStepName, null, FlowStepType.Task)
                        model.edges.add(FlowEdge(fullStepName, elseStepName, "else"))
                        skipNextSteps++ // Skip processing else step in main loop
                    }
                    
                    if (thenStepIndex < branchSteps.size) {
                        val thenStepName = "${branchSteps[thenStepIndex].name}_branch_$branchNumber"
                        model.steps[thenStepName] = FlowStep(thenStepName, null, FlowStepType.Task)
                        model.edges.add(FlowEdge(fullStepName, thenStepName, "then"))
                        skipNextSteps++ // Skip processing then step in main loop
                        // НЕ создаем связь между else и then - это альтернативные ветки!
                    }
                    lastStepName = fullStepName
                }
                else -> {
                    // Regular step - connect to previous step
                    if (lastStepName != null) {
                        model.edges.add(FlowEdge(lastStepName!!, fullStepName, "next"))
                    }
                    lastStepName = fullStepName
                }
            }
        }
    }
}
