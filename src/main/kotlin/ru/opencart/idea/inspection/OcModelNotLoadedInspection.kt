package ru.opencart.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.quickfix.OcAddLoadModelFix
import ru.opencart.idea.type.OcModelTypeProvider

/**
 * `$this->model_catalog_product->...` without a preceding `$this->load->model('catalog/product')`.
 *
 * One of the most common OpenCart mistakes: the call fails with "Call to a member function on null"
 * (2.x/3.x) or an undefined property, and only on the scenario where that line actually runs.
 */
class OcModelNotLoadedInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val service = OcProjectService.getInstance(holder.project)
        if (!service.isOpenCartProject()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PhpElementVisitor() {
            override fun visitPhpFieldReference(fieldReference: FieldReference) {
                val name = OcPhpUtil.thisFieldName(fieldReference) ?: return
                if (!name.startsWith("model_") || name.length <= "model_".length) return
                if (service.contextOf(fieldReference) == null) return

                val phpClass = PsiTreeUtil.getParentOfType(fieldReference, PhpClass::class.java) ?: return
                val expectedKey = name.removePrefix("model_")
                if (loadedModelKeys(phpClass).contains(expectedKey)) return

                val route = OcModelTypeProvider.routeForRegistryKey(
                    holder.project,
                    service.contextOf(fieldReference),
                    name,
                ) ?: return

                holder.registerProblem(
                    fieldReference,
                    "OpenCart: model '$route' is not loaded — call \$this->load->model('$route')",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    OcAddLoadModelFix(route),
                )
            }
        }
    }

    /** Registry keys of every model loaded anywhere in the class. */
    private fun loadedModelKeys(phpClass: PhpClass): Set<String> {
        val keys = HashSet<String>()
        for (call in PsiTreeUtil.findChildrenOfType(phpClass, MethodReference::class.java)) {
            if (call.name != "model") continue
            if (OcPhpUtil.thisFieldName(call.classReference) != "load") continue
            val literal = call.parameters.firstOrNull() as? StringLiteralExpression ?: continue
            keys += OcModelTypeProvider.registryKey(literal.contents)
        }
        return keys
    }
}
