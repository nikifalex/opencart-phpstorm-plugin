package ru.opencart.idea.core

import com.intellij.openapi.vfs.VirtualFile

/** What exactly a route string addresses. */
enum class OcRouteKind(val dirName: String) {
    CONTROLLER("controller"),
    MODEL("model"),
    VIEW("view"),
    LANGUAGE("language"),
}

/** Route resolution result: the file and, for controllers, the name of the called method. */
data class OcRouteTarget(val file: VirtualFile, val method: String? = null)

/**
 * Maps "route string ↔ file on disk" for every engine generation.
 *
 * Resolution goes through the file system rather than the class name: in 2.x/3.x the class is built
 * as 'Controller' . preg_replace(...) ignoring case (PHP class names are case insensitive), so the
 * only reliable anchor is the file path.
 */
object OcRoutes {

    /** Characters the engine strips from a route before using it. */
    private val ROUTE_CHARS = Regex("""^[a-zA-Z0-9_\-/.|]+$""")

    fun isRouteLike(text: String): Boolean =
        text.isNotEmpty() && text.length < 200 && ROUTE_CHARS.matches(text) && !text.startsWith("/")

    // --- controllers -----------------------------------------------------------

    /**
     * catalog/product → <app>/controller/catalog/product.php
     * catalog/product.list (4.x) → the same file, method list
     * catalog/product/edit (2.x/3.x) → file catalog/product.php, method edit
     */
    fun resolveController(ctx: OcContext, route: String): OcRouteTarget? =
        resolveWithMethod(ctx, route, OcRouteKind.CONTROLLER)

    fun resolveModel(ctx: OcContext, route: String): OcRouteTarget? =
        resolveWithMethod(ctx, route, OcRouteKind.MODEL)

    private fun resolveWithMethod(ctx: OcContext, rawRoute: String, kind: OcRouteKind): OcRouteTarget? {
        val route = sanitize(rawRoute)
        if (route.isEmpty()) return null

        if (ctx.version.methodInRoute && route.contains('.')) {
            val path = route.substringBeforeLast('.')
            val method = route.substringAfterLast('.')
            phpFile(ctx, path, kind)?.let { return OcRouteTarget(it, method) }
        }

        phpFile(ctx, route, kind)?.let { return OcRouteTarget(it) }

        // 2.x/3.x: the trailing segment may be a method (see Action::__construct)
        if (route.contains('/')) {
            val path = route.substringBeforeLast('/')
            val method = route.substringAfterLast('/')
            phpFile(ctx, path, kind)?.let { return OcRouteTarget(it, method) }
        }
        return null
    }

    /** Controller/model file for a bare route without a method. */
    fun phpFile(ctx: OcContext, route: String, kind: OcRouteKind): VirtualFile? {
        for (base in searchBases(ctx, kind, route)) {
            val (dir, rest) = base
            dir.findFileByRelativePath("$rest.php")?.takeIf { !it.isDirectory }?.let { return it }
        }
        return null
    }

    // --- templates -------------------------------------------------------------

    /**
     * Template file candidates. There are several because catalog templates are looked up per theme,
     * and in 1.5 the route already contains the theme name and the extension.
     */
    fun resolveTemplates(ctx: OcContext, rawRoute: String): List<VirtualFile> {
        val route = rawRoute.trim()
        if (route.isEmpty()) return emptyList()
        val result = LinkedHashSet<VirtualFile>()
        val bare = route.removeSuffix(".twig").removeSuffix(".tpl")

        for ((dir, rest) in searchBases(ctx, OcRouteKind.VIEW, bare)) {
            // <app>/view/template/<route>.(twig|tpl)
            val templateDir = dir.findChild("template")
            if (templateDir != null) {
                for (ext in ctx.version.templateExtensions) {
                    templateDir.findFileByRelativePath("$rest.$ext")?.let { result += it }
                }
            }
            // <app>/view/theme/<theme>/template/<route>.(twig|tpl)
            val themeDir = dir.findChild("theme")
            if (themeDir != null) {
                for (theme in themeDir.children.filter { it.isDirectory }) {
                    for (ext in ctx.version.templateExtensions) {
                        theme.findFileByRelativePath("template/$rest.$ext")?.let { result += it }
                    }
                }
                // 1.5: a route like default/template/common/header.tpl already carries the theme
                themeDir.findFileByRelativePath(route)?.takeIf { !it.isDirectory }?.let { result += it }
                for (ext in ctx.version.templateExtensions) {
                    themeDir.findFileByRelativePath("$bare.$ext")?.takeIf { !it.isDirectory }?.let { result += it }
                }
            }
        }
        return result.toList()
    }

    // --- language files --------------------------------------------------------

    /** Language files of the route across every installed language. */
    fun resolveLanguages(ctx: OcContext, rawRoute: String): List<VirtualFile> {
        val route = sanitize(rawRoute)
        if (route.isEmpty()) return emptyList()
        val result = ArrayList<VirtualFile>()
        for ((dir, rest) in searchBases(ctx, OcRouteKind.LANGUAGE, route)) {
            for (lang in dir.children.filter { it.isDirectory }) {
                lang.findFileByRelativePath("$rest.php")?.takeIf { !it.isDirectory }?.let { result += it }
            }
        }
        return result
    }

    /** Language directories of the installation for an application side: admin/language/en-gb, ... */
    fun languageDirs(ctx: OcContext): List<VirtualFile> {
        val app = ctx.root.appDir(ctx.area) ?: return emptyList()
        val langRoot = app.findChild("language") ?: return emptyList()
        return langRoot.children.filter { it.isDirectory }
    }

    // --- route listing (for completion) ----------------------------------------

    /**
     * Every route of the given kind reachable from the current context.
     * For 4.x this includes extensions (extension/<code>/...).
     */
    fun listRoutes(ctx: OcContext, kind: OcRouteKind): List<String> {
        val result = LinkedHashSet<String>()
        val app = ctx.root.appDir(ctx.area)

        when (kind) {
            OcRouteKind.CONTROLLER, OcRouteKind.MODEL -> {
                app?.findChild(kind.dirName)?.let { collectRoutes(it, "", "php", result) }
            }
            OcRouteKind.VIEW -> {
                app?.findChild("view")?.findChild("template")
                    ?.let { dir -> ctx.version.templateExtensions.forEach { collectRoutes(dir, "", it, result) } }
                app?.findChild("view")?.findChild("theme")?.children?.filter { it.isDirectory }?.forEach { theme ->
                    theme.findChild("template")
                        ?.let { dir -> ctx.version.templateExtensions.forEach { collectRoutes(dir, "", it, result) } }
                }
            }
            OcRouteKind.LANGUAGE -> {
                // Keys are the same in every language, so the first language directory is enough.
                languageDirs(ctx).firstOrNull()?.let { collectRoutes(it, "", "php", result) }
            }
        }

        if (ctx.version == OcVersion.OC4) {
            val appName = ctx.root.appDirName(ctx.area)
            ctx.root.extensionDir?.children?.filter { it.isDirectory }?.forEach { ext ->
                val extApp = ext.findChild(appName) ?: return@forEach
                when (kind) {
                    OcRouteKind.CONTROLLER, OcRouteKind.MODEL ->
                        extApp.findChild(kind.dirName)
                            ?.let { collectRoutes(it, "extension/${ext.name}/", "php", result) }
                    OcRouteKind.VIEW ->
                        extApp.findChild("view")?.findChild("template")
                            ?.let { dir -> collectRoutes(dir, "extension/${ext.name}/", "twig", result) }
                    OcRouteKind.LANGUAGE ->
                        extApp.findChild("language")?.children?.firstOrNull { it.isDirectory }
                            ?.let { collectRoutes(it, "extension/${ext.name}/", "php", result) }
                }
            }
        }
        return result.toList()
    }

    private fun collectRoutes(dir: VirtualFile, prefix: String, ext: String, out: MutableSet<String>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                collectRoutes(child, "$prefix${child.name}/", ext, out)
            } else if (child.extension == ext && child.nameWithoutExtension != "index") {
                out += "$prefix${child.nameWithoutExtension}"
            }
        }
    }

    // --- reverse mapping --------------------------------------------------------

    /** File → the route the engine loads it by (the inverse of resolution). */
    fun routeOf(root: OcRoot, file: VirtualFile): String? {
        val rel = root.relativePath(file) ?: return null
        val parts = rel.split('/')
        var idx = 0
        var prefix = ""
        if (parts[0] == "extension" && root.version == OcVersion.OC4) {
            if (parts.size < 4) return null
            prefix = "extension/${parts[1]}/"
            idx = 3 // extension/<code>/<app>/...
        } else {
            idx = 1 // <app>/...
        }
        if (parts.size <= idx) return null
        val kindDir = parts[idx]
        var tail = parts.drop(idx + 1)
        when (kindDir) {
            "controller", "model" -> {}
            "view" -> {
                tail = when (tail.firstOrNull()) {
                    "template" -> tail.drop(1)
                    "theme" -> tail.drop(2).let { if (it.firstOrNull() == "template") it.drop(1) else it }
                    else -> return null
                }
            }
            "language" -> tail = tail.drop(1) // language code
            else -> return null
        }
        if (tail.isEmpty()) return null
        val last = tail.last().substringBeforeLast('.')
        return prefix + (tail.dropLast(1) + last).joinToString("/")
    }

    // --- internals ---------------------------------------------------------------

    /**
     * Directories worth searching for the route file, along with the remaining path.
     * In 4.x the route extension/<code>/module/x lives in extension/<code>/<app>/<kind>/module/x.
     */
    private fun searchBases(ctx: OcContext, kind: OcRouteKind, route: String): List<Pair<VirtualFile, String>> {
        val bases = ArrayList<Pair<VirtualFile, String>>()
        val area = if (ctx.area.isApplication) ctx.area else OcArea.CATALOG

        if (ctx.version == OcVersion.OC4 && route.startsWith("extension/")) {
            val parts = route.split('/')
            if (parts.size >= 3) {
                val code = parts[1]
                val rest = parts.drop(2).joinToString("/")
                val extApp = ctx.root.extensionDir?.findChild(code)?.findChild(ctx.root.appDirName(area))
                extApp?.findChild(kind.dirName)?.let { bases += it to rest }
            }
        }

        ctx.root.appDir(area)?.findChild(kind.dirName)?.let { bases += it to route }

        // extension/ directories of some ocStore builds — checked just in case
        if (ctx.version == OcVersion.OC4 && !route.startsWith("extension/")) {
            ctx.root.extensionDir?.children?.filter { it.isDirectory }?.forEach { ext ->
                ext.findChild(ctx.root.appDirName(area))?.findChild(kind.dirName)?.let { bases += it to route }
            }
        }
        return bases
    }

    private fun sanitize(route: String): String = route.trim().trim('/')
}
