package io.github.nikifalex.opencart.event

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import io.github.nikifalex.opencart.core.OcArea
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcPhpUtil
import io.github.nikifalex.opencart.core.OcRoot
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcRoutes

/**
 * An OpenCart event name: `catalog/model/checkout/order/addHistory/after`.
 *
 * In the `event` table the trigger keeps the area in its first segment, but the startup controller
 * drops it on registration (`substr($trigger, strpos($trigger, '/') + 1)`), because the core fires
 * `trigger('model/checkout/order/addHistory/before')` already inside its own application. Matching is
 * prefix based and supports `*` and `?`, so a subscription may be shorter than the event.
 */
data class OcEventName(
    val area: OcArea?,
    val kind: OcRouteKind?,
    val route: String,
    val method: String?,
    val moment: String?,
) {
    companion object {
        private val MOMENTS = setOf("before", "after")
        private val KINDS = mapOf(
            "controller" to OcRouteKind.CONTROLLER,
            "model" to OcRouteKind.MODEL,
            "view" to OcRouteKind.VIEW,
            "language" to OcRouteKind.LANGUAGE,
        )

        fun parse(raw: String): OcEventName? {
            val parts = raw.trim().trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null

            var index = 0
            val area = when (parts[0]) {
                "catalog" -> OcArea.CATALOG
                "admin" -> OcArea.ADMIN
                else -> null
            }
            if (area != null) index++

            val kind = KINDS[parts.getOrNull(index)]
            if (kind != null) index++

            var tail = parts.drop(index)
            val moment = tail.lastOrNull()?.takeIf { it in MOMENTS }
            if (moment != null) tail = tail.dropLast(1)
            if (tail.isEmpty()) return OcEventName(area, kind, "", null, moment)

            // For controllers and models the last segment is a method name, unless it is part of the path.
            return OcEventName(area, kind, tail.joinToString("/"), null, moment)
        }
    }

    val hasWildcard: Boolean get() = route.contains('*') || route.contains('?')
}

/** Where an event name and a handler route lead to. */
object OcEventNavigation {

    /** Event target: a controller/model method, a template or a language file. */
    fun targetsOfTrigger(project: Project, root: OcRoot, raw: String): List<PsiElement> {
        val event = OcEventName.parse(raw) ?: return emptyList()
        if (event.route.isEmpty() || event.hasWildcard) return emptyList()

        val areas = event.area?.let { listOf(it) } ?: listOf(OcArea.CATALOG, OcArea.ADMIN)
        val kinds = event.kind?.let { listOf(it) } ?: listOf(OcRouteKind.CONTROLLER, OcRouteKind.MODEL)

        val result = ArrayList<PsiElement>()
        for (area in areas) {
            val ctx = OcContext(root, area)
            for (kind in kinds) {
                when (kind) {
                    OcRouteKind.CONTROLLER, OcRouteKind.MODEL -> {
                        val target = if (kind == OcRouteKind.CONTROLLER) {
                            OcRoutes.resolveController(ctx, event.route)
                        } else {
                            OcRoutes.resolveModel(ctx, event.route)
                        } ?: continue
                        val phpClass = OcPhpUtil.firstClassIn(project, target.file)
                        val method = target.method?.let { phpClass?.findMethodByName(it) }
                        result += listOfNotNull(method ?: phpClass)
                    }
                    OcRouteKind.VIEW -> result += OcRoutes.resolveTemplates(ctx, event.route)
                        .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                    OcRouteKind.LANGUAGE -> result += OcRoutes.resolveLanguages(ctx, event.route)
                        .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                }
            }
            if (result.isNotEmpty()) break
        }
        return result
    }

    /** Event handler — an ordinary controller route with a method: `extension/total/voucher/send`. */
    fun targetsOfAction(project: Project, root: OcRoot, raw: String): List<PsiElement> {
        val result = ArrayList<PsiElement>()
        for (area in listOf(OcArea.CATALOG, OcArea.ADMIN)) {
            val ctx = OcContext(root, area)
            val target = OcRoutes.resolveController(ctx, raw) ?: continue
            val phpClass = OcPhpUtil.firstClassIn(project, target.file)
            val method = target.method?.let { phpClass?.findMethodByName(it) }
            result += listOfNotNull(method ?: phpClass)
        }
        return result
    }

    /**
     * Event names the core announces this method under: `<area>/<kind>/<route>/<method>/<moment>`.
     * The engine matches by prefix, so a subscription may omit both the method and the moment.
     */
    fun triggerNamesFor(root: OcRoot, area: OcArea, kind: OcRouteKind, route: String, method: String?): List<String> {
        val areaName = if (area == OcArea.ADMIN) "admin" else "catalog"
        val kindName = when (kind) {
            OcRouteKind.CONTROLLER -> "controller"
            OcRouteKind.MODEL -> "model"
            OcRouteKind.VIEW -> "view"
            OcRouteKind.LANGUAGE -> "language"
        }
        val base = "$areaName/$kindName/$route"
        val withMethod = method?.let { "$base/$it" }
        return buildList {
            if (withMethod != null) {
                add("$withMethod/before")
                add("$withMethod/after")
                add(withMethod)
            }
            add("$base/before")
            add("$base/after")
            add(base)
        }
    }
}
