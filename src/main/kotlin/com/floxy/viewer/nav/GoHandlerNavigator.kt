package com.floxy.viewer.nav

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

object GoHandlerNavigator {
    fun findFunctionInFile(file: GoFile, name: String): GoFunctionDeclaration? {
        val funcs = PsiTreeUtil.findChildrenOfType(file, GoFunctionDeclaration::class.java)
        return funcs.firstOrNull { it.name == name }
    }

    /**
     * Attempts to find a handler implementation by scanning methods named `Name` that return the given literal.
     * Matches patterns like: `func (h *MyHandler) Name() string { return "handler-id" }`.
     */
    fun findHandlerImplementation(file: GoFile, handlerId: String): PsiElement? {
        val escaped = Regex.escape(handlerId)
        val returnRegex = Regex("return\\s+([\"`])$escaped\\1")

        // Search Go methods with receiver: func (h *T) Name() string { return "..." }
        val methods = PsiTreeUtil.findChildrenOfType(file, GoMethodDeclaration::class.java)
        methods.firstOrNull { it.name == "Name" && it.block?.text?.let { txt -> returnRegex.containsMatchIn(txt) } == true }?.let { return it }

        // Also check free functions named Name (rare, but harmless)
        val funcs = PsiTreeUtil.findChildrenOfType(file, GoFunctionDeclaration::class.java)
        funcs.firstOrNull { it.name == "Name" && it.block?.text?.let { txt -> returnRegex.containsMatchIn(txt) } == true }?.let { return it }

        return null
    }

    fun navigateTo(element: PsiElement?) {
        if (element == null) return
        val project = element.project
        val file = element.containingFile?.virtualFile ?: return
        val offset = element.textOffset
        OpenFileDescriptor(project, file, offset).navigate(true)
    }
}
