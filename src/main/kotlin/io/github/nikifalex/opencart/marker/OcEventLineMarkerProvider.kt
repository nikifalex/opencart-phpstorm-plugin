package io.github.nikifalex.opencart.marker

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.psi.elements.Method
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcRoutes
import io.github.nikifalex.opencart.event.OcEventNavigation
import io.github.nikifalex.opencart.event.OcEventService
import io.github.nikifalex.opencart.event.OcEventSubscriberIndex

/**
 * Gutter icons on methods related to the event system:
 *  - on a controller/model method — jump to the handlers subscribed to it;
 *  - on a handler method — jump to where the subscription is registered.
 *
 * Subscriptions live in the `event` table, so the link between `addEvent(...)` and the code it
 * intercepts is invisible anywhere else.
 */
class OcEventLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val method = element.parent as? Method ?: return
        if (method.nameIdentifier !== element) return

        val project = element.project
        if (DumbService.isDumb(project)) return

        val service = OcProjectService.getInstance(project)
        val file = element.containingFile?.originalFile?.virtualFile ?: return
        val root = service.rootOf(file) ?: return
        val area = root.areaOf(file) ?: return
        val route = OcRoutes.routeOf(root, file) ?: return
        val kind = kindOf(root, file) ?: return

        val events = OcEventService.getInstance(project)

        // 1. Who subscribes to this method
        val handlers = LinkedHashMap<String, MutableSet<String>>()
        for (name in OcEventNavigation.triggerNamesFor(root, area, kind, route, method.name)) {
            for (subscription in events.subscribersOf(name)) {
                if (subscription.action.isNotEmpty()) {
                    handlers.getOrPut(subscription.action) { LinkedHashSet() } += subscription.trigger
                }
            }
        }
        if (handlers.isNotEmpty()) {
            val targets = handlers.keys.flatMap { OcEventNavigation.targetsOfAction(project, root, it) }
            if (targets.isNotEmpty()) {
                result.add(
                    NavigationGutterIconBuilder.create(AllIcons.Nodes.Plugin)
                        .setTargets(targets)
                        .setTooltipText("OpenCart: event handlers (${handlers.keys.joinToString(", ")})")
                        .createLineMarkerInfo(element),
                )
            }
        }

        // 2. This method is a handler itself
        if (kind == OcRouteKind.CONTROLLER) {
            val asAction = "$route/${method.name}"
            val triggers = events.subscriptions()
                .filter { it.action == asAction || it.action == route }
                .map { it.trigger }
                .distinct()
            if (triggers.isNotEmpty()) {
                val manager = PsiManager.getInstance(project)
                val declarations = triggers
                    .flatMap { OcEventSubscriberIndex.filesOf(project, it) }
                    .mapNotNull { manager.findFile(it) }
                if (declarations.isNotEmpty()) {
                    result.add(
                        NavigationGutterIconBuilder.create(AllIcons.Nodes.Related)
                            .setTargets(declarations)
                            .setTooltipText("OpenCart: invoked by event ${triggers.joinToString(", ")}")
                            .createLineMarkerInfo(element),
                    )
                }
            }
        }
    }

    /** controller/ or model/ in the file path decides which event wraps it. */
    private fun kindOf(root: io.github.nikifalex.opencart.core.OcRoot, file: com.intellij.openapi.vfs.VirtualFile): OcRouteKind? {
        val rel = root.relativePath(file) ?: return null
        return when {
            rel.contains("/controller/") -> OcRouteKind.CONTROLLER
            rel.contains("/model/") -> OcRouteKind.MODEL
            else -> null
        }
    }
}
