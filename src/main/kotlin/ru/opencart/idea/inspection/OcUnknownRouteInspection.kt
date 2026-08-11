package ru.opencart.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRouteKind
import ru.opencart.idea.core.OcRoutes
import ru.opencart.idea.psi.OcCalls
import ru.opencart.idea.psi.OcStringRole
import ru.opencart.idea.quickfix.OcCreateRouteFileFix

/**
 * Reports a route that matches no file: `$this->load->model('catalog/produkt')`.
 *
 * In OpenCart such typos only surface at runtime ("Could not load model ..."), sometimes only on one
 * particular page.
 */
class OcUnknownRouteInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val service = OcProjectService.getInstance(holder.project)
        if (!service.isOpenCartProject()) return PsiElementVisitor.EMPTY_VISITOR

        return object : PhpElementVisitor() {
            override fun visitPhpStringLiteralExpression(expression: StringLiteralExpression) {
                val role = OcCalls.roleOf(expression) ?: return
                val route = OcPhpUtil.stringValue(expression) ?: return
                if (!OcRoutes.isRouteLike(route)) return
                val ctx = service.contextOf(expression) ?: return

                val (exists, kind) = when (role) {
                    OcStringRole.CONTROLLER_ROUTE ->
                        (OcRoutes.resolveController(ctx, route) != null) to OcRouteKind.CONTROLLER
                    OcStringRole.MODEL_ROUTE ->
                        (OcRoutes.resolveModel(ctx, route) != null) to OcRouteKind.MODEL
                    OcStringRole.VIEW_ROUTE ->
                        OcRoutes.resolveTemplates(ctx, route).isNotEmpty() to OcRouteKind.VIEW
                    OcStringRole.LANGUAGE_ROUTE ->
                        OcRoutes.resolveLanguages(ctx, route).isNotEmpty() to OcRouteKind.LANGUAGE
                    else -> return
                }
                if (exists) return

                // url->link() often points at another store or an external route, so keep it weak
                val severity = if (role == OcStringRole.CONTROLLER_ROUTE) {
                    ProblemHighlightType.WEAK_WARNING
                } else {
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                }

                holder.registerProblem(
                    expression,
                    "OpenCart: no file found for route '$route' (${describe(kind)})",
                    severity,
                    OcCreateRouteFileFix(route, kind),
                )
            }
        }
    }

    private fun describe(kind: OcRouteKind): String = when (kind) {
        OcRouteKind.CONTROLLER -> "controller"
        OcRouteKind.MODEL -> "model"
        OcRouteKind.VIEW -> "template"
        OcRouteKind.LANGUAGE -> "language file"
    }
}
