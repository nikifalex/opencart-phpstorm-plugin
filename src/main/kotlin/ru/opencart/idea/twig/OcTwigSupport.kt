package ru.opencart.idea.twig

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import ru.opencart.idea.core.OcContext
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRouteKind
import ru.opencart.idea.core.OcRoutes

/** A variable passed to the template: `$data['products'] = ...` in the controller. */
data class OcTemplateVariable(val name: String, val element: PsiElement, val file: VirtualFile)

/**
 * Links a template with the controller that renders it.
 *
 * In OpenCart the template and the controller share the route (catalog/product.twig ←→
 * controller/catalog/product.php), so the template variables are the keys of the `$data` array
 * (`$this->data` in 1.5) of that controller.
 */
object OcTwigSupport {

    /** Controllers that may render this template. */
    fun controllersFor(project: Project, templateFile: VirtualFile): List<VirtualFile> {
        val service = OcProjectService.getInstance(project)
        val root = service.rootOf(templateFile) ?: return emptyList()
        val area = root.areaOf(templateFile) ?: return emptyList()
        val route = OcRoutes.routeOf(root, templateFile) ?: return emptyList()
        val ctx = OcContext(root, area)

        val direct = OcRoutes.phpFile(ctx, route, OcRouteKind.CONTROLLER)
        if (direct != null) return listOf(direct)

        // Partial templates (common/column_left and friends) sometimes sit deeper than the controller
        val parentRoute = route.substringBeforeLast('/', "")
        if (parentRoute.isNotEmpty()) {
            OcRoutes.phpFile(ctx, parentRoute, OcRouteKind.CONTROLLER)?.let { return listOf(it) }
        }
        return emptyList()
    }

    /** The `$data[...]` keys the controller passes to the template. */
    fun variablesFor(project: Project, templateFile: VirtualFile): List<OcTemplateVariable> {
        val result = LinkedHashMap<String, OcTemplateVariable>()
        for (controller in controllersFor(project, templateFile)) {
            val psi: PsiFile = PsiManager.getInstance(project).findFile(controller) ?: continue
            for (assignment in PsiTreeUtil.findChildrenOfType(psi, AssignmentExpression::class.java)) {
                val access = assignment.variable as? ArrayAccessExpression ?: continue
                if (!isDataArray(access.value)) continue
                val key = (access.index?.value as? StringLiteralExpression)?.contents ?: continue
                result.putIfAbsent(key, OcTemplateVariable(key, access, controller))
            }
        }
        return result.values.toList()
    }

    /** `$data[...]` (2.x–4.x) or `$this->data[...]` (1.5). */
    private fun isDataArray(element: PsiElement?): Boolean = when (element) {
        is Variable -> element.name == "data"
        is FieldReference -> element.name == "data" && (element.classReference as? Variable)?.name == "this"
        else -> false
    }
}
