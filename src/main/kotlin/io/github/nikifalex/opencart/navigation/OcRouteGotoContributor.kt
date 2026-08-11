package io.github.nikifalex.opencart.navigation

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import io.github.nikifalex.opencart.core.OcArea
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcPhpUtil
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcRoutes

/**
 * Route search in "Go to Symbol" (Ctrl+Alt+Shift+N): type `catalog/product` and get the controller
 * and the model of both store sides without recalling the file path.
 */
class OcRouteGotoContributor : ChooseByNameContributor {

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
        val names = LinkedHashSet<String>()
        for (ctx in contexts(project)) {
            names += OcRoutes.listRoutes(ctx, OcRouteKind.CONTROLLER)
            names += OcRoutes.listRoutes(ctx, OcRouteKind.MODEL)
        }
        return names.toTypedArray()
    }

    override fun getItemsByName(
        name: String,
        pattern: String,
        project: Project,
        includeNonProjectItems: Boolean,
    ): Array<NavigationItem> {
        val items = ArrayList<NavigationItem>()
        val manager = PsiManager.getInstance(project)
        for (ctx in contexts(project)) {
            for (kind in listOf(OcRouteKind.CONTROLLER, OcRouteKind.MODEL)) {
                val file = OcRoutes.phpFile(ctx, name, kind) ?: continue
                val phpClass = OcPhpUtil.firstClassIn(project, file)
                val item = phpClass as? NavigationItem ?: manager.findFile(file) as? NavigationItem
                item?.let { items += it }
            }
        }
        return items.toTypedArray()
    }

    private fun contexts(project: Project): List<OcContext> =
        OcProjectService.getInstance(project).roots().flatMap { root ->
            listOf(OcContext(root, OcArea.ADMIN), OcContext(root, OcArea.CATALOG))
        }
}
