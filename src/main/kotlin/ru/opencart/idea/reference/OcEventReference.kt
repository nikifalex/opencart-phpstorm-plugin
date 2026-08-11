package ru.opencart.idea.reference

import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.event.OcEventNavigation
import ru.opencart.idea.psi.OcStringRole

/**
 * Ctrl+click on an event name and on the route of its handler:
 *  - `'catalog/model/checkout/order/addHistory/after'` → the model method the event wraps;
 *  - `'extension/total/voucher/send'` → the handler controller method.
 */
class OcEventReference(
    element: StringLiteralExpression,
    private val role: OcStringRole,
) : PsiPolyVariantReferenceBase<StringLiteralExpression>(element, ElementManipulators.getValueTextRange(element)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val raw = element.contents
        if (raw.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val project = element.project
        val service = OcProjectService.getInstance(project)
        val file = element.containingFile?.originalFile?.virtualFile
        val root = service.rootOf(file) ?: service.roots().firstOrNull() ?: return ResolveResult.EMPTY_ARRAY

        val targets: List<PsiElement> = when (role) {
            OcStringRole.EVENT_NAME -> OcEventNavigation.targetsOfTrigger(project, root, raw)
            OcStringRole.EVENT_ACTION -> OcEventNavigation.targetsOfAction(project, root, raw)
            else -> emptyList()
        }
        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
