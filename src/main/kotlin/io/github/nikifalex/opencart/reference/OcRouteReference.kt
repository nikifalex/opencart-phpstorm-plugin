package io.github.nikifalex.opencart.reference

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcPhpUtil
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcRoutes
import io.github.nikifalex.opencart.psi.OcStringRole

/**
 * Ctrl+click on a route string: `'catalog/product'` → the controller, model, template or language
 * file. For controllers it jumps straight to the called method when the route names one.
 */
class OcRouteReference(
    element: StringLiteralExpression,
    private val role: OcStringRole,
) : PsiPolyVariantReferenceBase<StringLiteralExpression>(element, valueRange(element)) {

    companion object {
        private fun valueRange(element: StringLiteralExpression): TextRange =
            ElementManipulators.getValueTextRange(element)
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val literal = element
        val route = OcPhpUtil.stringValue(literal) ?: return ResolveResult.EMPTY_ARRAY
        if (!OcRoutes.isRouteLike(route)) return ResolveResult.EMPTY_ARRAY
        val ctx = OcProjectService.getInstance(literal.project).contextOf(literal) ?: return ResolveResult.EMPTY_ARRAY

        val targets: List<PsiElement> = when (role) {
            OcStringRole.CONTROLLER_ROUTE -> phpTargets(ctx, route, OcRouteKind.CONTROLLER)
            OcStringRole.MODEL_ROUTE -> phpTargets(ctx, route, OcRouteKind.MODEL)
            OcStringRole.VIEW_ROUTE -> OcRoutes.resolveTemplates(ctx, route).mapNotNull { psiFile(it) }
            OcStringRole.LANGUAGE_ROUTE -> OcRoutes.resolveLanguages(ctx, route).mapNotNull { psiFile(it) }
            OcStringRole.LIBRARY_ROUTE -> libraryTargets(ctx, route)
            else -> emptyList()
        }
        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    private fun phpTargets(ctx: OcContext, route: String, kind: OcRouteKind): List<PsiElement> {
        val target = when (kind) {
            OcRouteKind.CONTROLLER -> OcRoutes.resolveController(ctx, route)
            else -> OcRoutes.resolveModel(ctx, route)
        } ?: return emptyList()

        val phpClass = OcPhpUtil.firstClassIn(element.project, target.file)
        if (phpClass != null) {
            val method = target.method?.let { name -> phpClass.findMethodByName(name) }
            return listOfNotNull(method ?: phpClass)
        }
        return listOfNotNull(psiFile(target.file))
    }

    private fun libraryTargets(ctx: OcContext, route: String): List<PsiElement> {
        val file = ctx.root.system?.findFileByRelativePath("library/$route.php") ?: return emptyList()
        val phpClass = OcPhpUtil.firstClassIn(element.project, file)
        return listOfNotNull(phpClass ?: psiFile(file))
    }

    private fun psiFile(file: VirtualFile): PsiElement? = PsiManager.getInstance(element.project).findFile(file)

    override fun getVariants(): Array<Any> = emptyArray()
}
