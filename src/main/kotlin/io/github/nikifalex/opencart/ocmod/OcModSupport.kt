package io.github.nikifalex.opencart.ocmod

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRoot
import java.nio.charset.StandardCharsets

/**
 * Parsing of OCMOD and vQmod modifications.
 *
 * OCMOD: `<file path="catalog/controller/product/product.php">` (3.x allows `*` in the path),
 * vQmod: `<file name="..." path="...">`. Both look for `<search>` as a substring of the target file,
 * which makes a modification gone stale after an engine upgrade visible statically.
 */
object OcModSupport {

    /** A file describes a modification when its root tag is <modification>. */
    fun isModificationFile(file: XmlFile): Boolean =
        file.rootTag?.name == "modification" || file.rootTag?.name == "modifications"

    /** The store the modification file belongs to (the file itself may live outside the root). */
    fun rootFor(project: Project, file: VirtualFile?): OcRoot? {
        val service = OcProjectService.getInstance(project)
        return service.rootOf(file) ?: service.roots().firstOrNull()
    }

    /** Path from the attribute, honouring a renamed admin directory and the vQmod name/path split. */
    fun declaredPath(fileTag: XmlTag): String? {
        val path = fileTag.getAttributeValue("path")
        val name = fileTag.getAttributeValue("name")
        val raw = when {
            !name.isNullOrBlank() && !path.isNullOrBlank() -> "${path.trimEnd('/')}/$name"
            !name.isNullOrBlank() -> name
            !path.isNullOrBlank() -> path
            else -> return null
        }
        return raw.trim().removePrefix("./").trimStart('/')
    }

    /** Store files affected by <file>; supports `*` in the path. */
    fun targets(root: OcRoot, declaredPath: String): List<VirtualFile> {
        val path = remapAdmin(root, declaredPath)
        if (!path.contains('*')) {
            return listOfNotNull(root.dir.findFileByRelativePath(path))
        }
        val segments = path.split('/')
        val result = ArrayList<VirtualFile>()
        collect(root.dir, segments, 0, result)
        return result
    }

    /** OCMOD always spells the admin directory as admin/, even when the site renamed it. */
    private fun remapAdmin(root: OcRoot, path: String): String =
        if (path.startsWith("admin/") && root.adminDirName != "admin") {
            root.adminDirName + path.removePrefix("admin")
        } else {
            path
        }

    private fun collect(dir: VirtualFile, segments: List<String>, index: Int, out: MutableList<VirtualFile>) {
        if (index >= segments.size || out.size > 200) return
        val segment = segments[index]
        val last = index == segments.size - 1
        val regex = globToRegex(segment)
        for (child in dir.children) {
            if (!regex.matches(child.name)) continue
            if (last) {
                if (!child.isDirectory) out += child
            } else if (child.isDirectory) {
                collect(child, segments, index + 1, out)
            }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            for (ch in glob) {
                when (ch) {
                    '*' -> append("[^/]*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }
        return Regex(pattern)
    }

    /** Whether the <search> text occurs in one of the target files. */
    fun searchMatches(root: OcRoot, fileTag: XmlTag, searchText: String, isRegex: Boolean): Boolean {
        val declared = declaredPath(fileTag) ?: return true
        val files = targets(root, declared)
        if (files.isEmpty()) return true // a missing target is reported by a separate check

        val needle = searchText.trim()
        if (needle.isEmpty()) return true
        val regex = if (isRegex) runCatching { Regex(needle) }.getOrNull() else null

        return files.any { file ->
            val content = read(file) ?: return@any false
            if (regex != null) {
                regex.containsMatchIn(content)
            } else if (needle.contains('\n')) {
                // the engine applies a multi-line search line by line, so compare trimmed lines
                val lines = needle.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val contentLines = content.lines().map { it.trim() }
                lines.all { contentLines.contains(it) }
            } else {
                content.contains(needle)
            }
        }
    }

    fun searchTag(element: PsiElement): XmlTag? = element as? XmlTag

    private fun read(file: VirtualFile): String? = try {
        String(file.contentsToByteArray(), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
