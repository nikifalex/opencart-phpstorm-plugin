package ru.opencart.idea.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.GroupStatement

/** Inserts `$this->load->model('<route>');` at the top of the method where the model is used. */
class OcAddLoadModelFix(private val route: String) : LocalQuickFix {

    override fun getFamilyName(): String = "OpenCart: add \$this->load->model('$route')"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val function = PsiTreeUtil.getParentOfType(element, Function::class.java) ?: return
        val body = PsiTreeUtil.findChildOfType(function, GroupStatement::class.java) ?: return
        val firstStatement = body.firstPsiChild ?: return

        val file = element.containingFile ?: return
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return

        val offset = firstStatement.textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val indent = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))
            .takeWhile { it == ' ' || it == '\t' }

        document.insertString(lineStart, "$indent\$this->load->model('$route');\n")
        documentManager.commitDocument(document)
    }
}
