package io.github.nikifalex.opencart.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.nikifalex.opencart.core.OcArea
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcRoutes
import io.github.nikifalex.opencart.core.OcVersion
import io.github.nikifalex.opencart.generator.OcSkeletons
import java.nio.charset.StandardCharsets

/**
 * Creates the missing route file straight from the highlight: a controller, model, template or
 * language file. Language files are created for every installed language at once, otherwise half the
 * store stays untranslated.
 */
class OcCreateRouteFileFix(
    private val route: String,
    private val kind: OcRouteKind,
) : LocalQuickFix {

    override fun getFamilyName(): String = when (kind) {
        OcRouteKind.CONTROLLER -> "OpenCart: create controller '$route'"
        OcRouteKind.MODEL -> "OpenCart: create model '$route'"
        OcRouteKind.VIEW -> "OpenCart: create template '$route'"
        OcRouteKind.LANGUAGE -> "OpenCart: create language file '$route'"
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val ctx = OcProjectService.getInstance(project).contextOf(element) ?: return
        val area = if (ctx.area.isApplication) ctx.area else OcArea.CATALOG
        val appDir = appRoot(ctx, area) ?: return
        val relRoute = relativeRoute(ctx)

        val created: List<VirtualFile> = when (kind) {
            OcRouteKind.CONTROLLER ->
                listOfNotNull(create(appDir, "controller/$relRoute.php", OcSkeletons.controller(ctx, route)))
            OcRouteKind.MODEL ->
                listOfNotNull(create(appDir, "model/$relRoute.php", OcSkeletons.model(ctx, route)))
            OcRouteKind.VIEW -> {
                val ext = ctx.version.templateExtensions.first()
                val viewDir = appDir.findChild("view")
                val templateRoot = viewDir?.findChild("template")
                    ?: viewDir?.findChild("theme")?.findChild("default")?.findChild("template")
                val target = templateRoot ?: appDir
                val prefix = if (templateRoot != null) "" else "view/template/"
                listOfNotNull(create(target, "$prefix$relRoute.$ext", OcSkeletons.template(ctx, route)))
            }
            OcRouteKind.LANGUAGE ->
                OcRoutes.languageDirs(OcContext(ctx.root, area)).mapNotNull { dir ->
                    create(dir, "$relRoute.php", OcSkeletons.languageFile(route))
                }
        }
        created.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** Application root for the route; for extension/<code>/... in 4.x it is extension/<code>/<app>. */
    private fun appRoot(ctx: OcContext, area: OcArea): VirtualFile? {
        if (ctx.version == OcVersion.OC4 && route.startsWith("extension/")) {
            val code = route.split('/').getOrNull(1) ?: return null
            return ctx.root.extensionDir?.findChild(code)?.findChild(ctx.root.appDirName(area))
        }
        return ctx.root.appDir(area)
    }

    /** Path inside the application: for extension routes the extension/<code>/ prefix is dropped. */
    private fun relativeRoute(ctx: OcContext): String =
        if (ctx.version == OcVersion.OC4 && route.startsWith("extension/")) {
            route.split('/').drop(2).joinToString("/")
        } else {
            route
        }

    private fun create(base: VirtualFile, relPath: String, content: String): VirtualFile? = try {
        val parentPath = relPath.substringBeforeLast('/', "")
        val fileName = relPath.substringAfterLast('/')
        val parent = if (parentPath.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, parentPath)
        parent?.findChild(fileName) ?: parent?.createChildData(this, fileName)?.also {
            it.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }
}
