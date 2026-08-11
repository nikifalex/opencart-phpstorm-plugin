package ru.opencart.idea.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.nio.charset.StandardCharsets

/** Installation plus application side for a particular file. */
data class OcContext(val root: OcRoot, val area: OcArea) {
    val version: OcVersion get() = root.version
}

/**
 * Finds OpenCart installations in the project and answers the question "where am I".
 *
 * Detection is content based rather than name based: production sites rename the admin directory,
 * and the store itself often lives in public_html/, www/ and the like.
 */
@Service(Service.Level.PROJECT)
class OcProjectService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): OcProjectService = project.service()

        private const val MAX_SCAN_DEPTH = 3
        private val SKIP_DIRS = setOf(
            "vendor", "node_modules", ".git", ".idea", "image", "storage", "cache",
            "download", "logs", "log", "upload", "backup",
        )
        private val NOT_APP_DIRS = setOf("system", "install", "catalog", "vendor", "extension")
    }

    /** Every OpenCart installation in the project. */
    fun roots(): List<OcRoot> = CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(
            detectRoots(),
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    }

    fun contextOf(file: VirtualFile?): OcContext? {
        val vf = file ?: return null
        val root = rootOf(vf) ?: return null
        val area = root.areaOf(vf) ?: return null
        return OcContext(root, area)
    }

    fun contextOf(element: PsiElement?): OcContext? {
        val file = element?.containingFile?.originalFile?.virtualFile ?: return null
        return contextOf(file)
    }

    /** Installation owning the file (the deepest one, in case a store is nested inside another). */
    fun rootOf(file: VirtualFile?): OcRoot? {
        val vf = file ?: return null
        return roots()
            .filter { vf.path.startsWith(it.dir.path + "/") || vf.path == it.dir.path }
            .maxByOrNull { it.dir.path.length }
    }

    fun isOpenCartProject(): Boolean = roots().isNotEmpty()

    // --- detection ------------------------------------------------------------

    private fun detectRoots(): List<OcRoot> {
        val found = LinkedHashMap<String, OcRoot>()
        for (contentRoot in ProjectRootManager.getInstance(project).contentRoots) {
            scan(contentRoot, 0, found)
        }
        return found.values.toList()
    }

    private fun scan(dir: VirtualFile, depth: Int, out: MutableMap<String, OcRoot>) {
        if (!dir.isDirectory || !dir.isValid) return
        detect(dir)?.let { out[it.dir.path] = it }
        if (depth >= MAX_SCAN_DEPTH) return
        for (child in dir.children) {
            if (!child.isDirectory) continue
            val name = child.name
            if (name.startsWith(".") || name.lowercase() in SKIP_DIRS) continue
            scan(child, depth + 1, out)
        }
    }

    /** A directory is an OpenCart root when it holds system/engine/loader.php and catalog/. */
    private fun detect(dir: VirtualFile): OcRoot? {
        val system = dir.findChild("system") ?: return null
        val loader = system.findFileByRelativePath("engine/loader.php") ?: return null
        dir.findChild("catalog") ?: return null
        val adminDir = findAdminDir(dir) ?: return null
        return OcRoot(dir, detectVersion(dir, loader), adminDir.name)
    }

    /** Admin is a directory with controller/ + model/ + language/ + view/ that is not catalog/system. */
    private fun findAdminDir(root: VirtualFile): VirtualFile? {
        val candidates = root.children.filter { child ->
            child.isDirectory &&
                child.name.lowercase() !in NOT_APP_DIRS &&
                !child.name.startsWith(".") &&
                child.findChild("controller") != null &&
                child.findChild("model") != null &&
                child.findChild("view") != null &&
                child.findChild("language") != null
        }
        // Prefer a directory with typical admin controllers, then "admin", then the first match.
        return candidates.firstOrNull { it.findFileByRelativePath("controller/common/dashboard.php") != null }
            ?: candidates.firstOrNull { it.findFileByRelativePath("controller/common/home.php") != null }
            ?: candidates.firstOrNull { it.name == "admin" }
            ?: candidates.firstOrNull()
    }

    private fun detectVersion(root: VirtualFile, loader: VirtualFile): OcVersion {
        // 4.x is the only branch with namespaces in the core.
        if (readHead(loader, 2048).contains("namespace Opencart")) return OcVersion.OC4

        versionConstant(root)?.let { v ->
            return when {
                v.startsWith("1.") -> OcVersion.OC15
                v.startsWith("2.") -> OcVersion.OC2
                v.startsWith("3.") -> OcVersion.OC3
                v.startsWith("4.") -> OcVersion.OC4
                else -> OcVersion.OC3
            }
        }

        // Fallback marker: Twig templates appeared in the core in 3.0.
        val hasTwig = root.findFileByRelativePath("system/library/template/twig.php") != null ||
            root.findFileByRelativePath("system/library/template/Twig.php") != null
        return if (hasTwig) OcVersion.OC3 else OcVersion.OC2
    }

    /** define('VERSION', '3.0.3.8') taken from index.php of the store root. */
    private fun versionConstant(root: VirtualFile): String? {
        val index = root.findChild("index.php") ?: return null
        val text = readHead(index, 8192)
        val m = Regex("""define\s*\(\s*['"]VERSION['"]\s*,\s*['"]([^'"]+)['"]""").find(text) ?: return null
        return m.groupValues[1]
    }

    private fun readHead(file: VirtualFile, limit: Int): String = try {
        val bytes = file.inputStream.use { it.readNBytes(limit) }
        String(bytes, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
