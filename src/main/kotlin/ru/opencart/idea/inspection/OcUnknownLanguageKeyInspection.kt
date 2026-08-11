package ru.opencart.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.lang.OcLanguage
import ru.opencart.idea.psi.OcCalls
import ru.opencart.idea.psi.OcStringRole
import ru.opencart.idea.quickfix.OcAddLanguageKeyFix

/**
 * `$this->language->get('text_foo')` with no `$_['text_foo']` in any loaded language file.
 * At runtime such a key is printed verbatim — a bare "text_foo" in the middle of the page.
 */
class OcUnknownLanguageKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val service = OcProjectService.getInstance(holder.project)
        if (!service.isOpenCartProject()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PhpElementVisitor() {
            override fun visitPhpStringLiteralExpression(expression: StringLiteralExpression) {
                if (OcCalls.roleOf(expression) != OcStringRole.LANGUAGE_KEY) return
                val key = OcPhpUtil.stringValue(expression) ?: return
                if (service.contextOf(expression) == null) return

                val files = OcLanguage.availableFiles(expression)
                if (files.isEmpty()) return // could not tell what is loaded, so stay silent
                if (OcLanguage.findKey(expression, key).isNotEmpty()) return
                if (OcLanguage.findKeyAnywhere(expression, key, limit = 1).isNotEmpty()) return

                holder.registerProblem(
                    expression,
                    "OpenCart: key '$key' not found in the language files",
                    ProblemHighlightType.WEAK_WARNING,
                    OcAddLanguageKeyFix(key),
                )
            }
        }
    }
}
