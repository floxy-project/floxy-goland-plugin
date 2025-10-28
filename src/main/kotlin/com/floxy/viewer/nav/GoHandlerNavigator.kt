package com.floxy.viewer.nav

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

object GoHandlerNavigator {
    fun findFunctionInFile(file: GoFile, name: String): GoFunctionDeclaration? {
        val funcs = PsiTreeUtil.findChildrenOfType(file, GoFunctionDeclaration::class.java)
        return funcs.firstOrNull { it.name == name }
    }

    fun navigateTo(element: PsiElement?) {
        if (element == null) return
        val project = element.project
        val file = element.containingFile?.virtualFile ?: return
        val offset = element.textOffset
        OpenFileDescriptor(project, file, offset).navigate(true)
    }
}
