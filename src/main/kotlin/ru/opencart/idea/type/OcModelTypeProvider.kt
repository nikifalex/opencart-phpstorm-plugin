package ru.opencart.idea.type

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4
import ru.opencart.idea.core.OcArea
import ru.opencart.idea.core.OcContext
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRouteKind
import ru.opencart.idea.core.OcRoutes

/**
 * Types `$this->model_catalog_product` accesses.
 *
 * The engine stores a Proxy with the model methods copied onto it, so the type is invisible to static
 * analysis. It is restored by mapping the registry key back to a route — model_catalog_product →
 * catalog/product — walking the models that actually exist: otherwise
 * `model_extension_payment_bank_transfer` could not be split, because an underscore inside a file name
 * is indistinguishable from a path separator.
 */
class OcModelTypeProvider : PhpTypeProvider4 {

    companion object {
        const val KEY = 'ɱ'

        /** Signature separator: occurs neither in field names nor in paths. */
        private const val SEP = '\u0007'

        /** Registry key from a route: str_replace(['/', '-', '.'], ['_', '', ''], $route). */
        fun registryKey(route: String): String = route.replace("/", "_").replace("-", "").replace(".", "")

        /** Reverse lookup: registry key → route of an existing model. */
        fun routeForRegistryKey(project: Project, ctx: OcContext?, fieldName: String): String? {
            val key = fieldName.removePrefix("model_")
            val contexts = ArrayList<OcContext>()
            ctx?.let { contexts += it }
            for (root in OcProjectService.getInstance(project).roots()) {
                for (area in listOf(OcArea.ADMIN, OcArea.CATALOG)) {
                    contexts += OcContext(root, area)
                }
            }
            for (c in contexts) {
                for (route in OcRoutes.listRoutes(c, OcRouteKind.MODEL)) {
                    if (registryKey(route) == key) return route
                }
            }
            return key.replace('_', '/').takeIf { it.contains('/') }
        }
    }

    override fun getKey(): Char = KEY

    override fun getType(element: PsiElement): PhpType? {
        if (DumbService.isDumb(element.project)) return null
        val ref = element as? FieldReference ?: return null
        val name = OcPhpUtil.thisFieldName(ref) ?: return null
        if (!name.startsWith("model_") || name.length <= "model_".length) return null
        val url = element.containingFile?.originalFile?.virtualFile?.url ?: return null
        return PhpType().add("#$KEY$name$SEP$url")
    }

    /**
     * The signature is deliberately not collapsed into an FQN: admin/model/catalog/product.php and
     * catalog/model/catalog/product.php declare the very same class ModelCatalogProduct, so the right
     * one cannot be picked by name — the concrete PhpClass is returned from [getBySignature] instead.
     */
    override fun complete(expression: String, project: Project): PhpType? = null

    override fun getBySignature(
        expression: String,
        visited: MutableSet<String>,
        depth: Int,
        project: Project,
    ): MutableCollection<out PhpNamedElement> {
        val payload = expression.removePrefix("#$KEY").removePrefix("#")
        val name = payload.substringBefore(SEP)
        val url = payload.substringAfter(SEP, "")
        if (url.isEmpty() || !name.startsWith("model_")) return ArrayList()

        val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return ArrayList()
        val ctx = OcProjectService.getInstance(project).contextOf(file) ?: return ArrayList()

        val route = routeForRegistryKey(project, ctx, name) ?: return ArrayList()
        val modelFile = OcRoutes.phpFile(ctx, route, OcRouteKind.MODEL)
            ?: OcRoutes.phpFile(ctx.copy(area = other(ctx.area)), route, OcRouteKind.MODEL)
            ?: return ArrayList()
        val phpClass = OcPhpUtil.firstClassIn(project, modelFile) ?: return ArrayList()
        return arrayListOf(phpClass)
    }

    private fun other(area: OcArea): OcArea = if (area == OcArea.ADMIN) OcArea.CATALOG else OcArea.ADMIN
}
