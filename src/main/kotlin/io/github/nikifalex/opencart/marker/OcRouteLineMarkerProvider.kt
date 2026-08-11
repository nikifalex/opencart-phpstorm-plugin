package io.github.nikifalex.opencart.marker

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.psi.elements.PhpClass
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRoutes

/**
 * Gutter icons on a controller/model class: jump to the template and the language files of the same
 * route. Saves the constant manual walk admin/controller → admin/view/template → admin/language.
 */
class OcRouteLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val phpClass = element.parent as? PhpClass ?: return
        if (phpClass.nameIdentifier !== element) return

        val project = element.project
        val service = OcProjectService.getInstance(project)
        val file = element.containingFile?.originalFile?.virtualFile ?: return
        val root = service.rootOf(file) ?: return
        val area = root.areaOf(file) ?: return
        val route = OcRoutes.routeOf(root, file) ?: return
        val ctx = OcContext(root, area)

        val templates = OcRoutes.resolveTemplates(ctx, route).mapNotNull { PsiManager.getInstance(project).findFile(it) }
        if (templates.isNotEmpty()) {
            result.add(
                NavigationGutterIconBuilder.create(AllIcons.FileTypes.Html)
                    .setTargets(templates)
                    .setTooltipText("OpenCart: template of route '$route'")
                    .createLineMarkerInfo(element),
            )
        }

        val languages = OcRoutes.resolveLanguages(ctx, route).mapNotNull { PsiManager.getInstance(project).findFile(it) }
        if (languages.isNotEmpty()) {
            result.add(
                NavigationGutterIconBuilder.create(AllIcons.FileTypes.Properties)
                    .setTargets(languages)
                    .setTooltipText("OpenCart: language files of route '$route'")
                    .createLineMarkerInfo(element),
            )
        }
    }
}
