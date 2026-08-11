package ru.opencart.idea.core

import com.intellij.openapi.vfs.VirtualFile

/**
 * An OpenCart installation found inside the project.
 *
 * [adminDirName] is almost always renamed on production sites, so it is detected by content rather
 * than by name. [extensionDir] only exists in 4.x (extension/<code>/admin|catalog|system).
 */
data class OcRoot(
    val dir: VirtualFile,
    val version: OcVersion,
    val adminDirName: String,
) {
    val admin: VirtualFile? get() = dir.findChild(adminDirName)
    val catalog: VirtualFile? get() = dir.findChild("catalog")
    val system: VirtualFile? get() = dir.findChild("system")
    val extensionDir: VirtualFile? get() = if (version == OcVersion.OC4) dir.findChild("extension") else null

    fun appDir(area: OcArea): VirtualFile? = when (area) {
        OcArea.ADMIN -> admin
        OcArea.CATALOG -> catalog
        OcArea.SYSTEM -> system
    }

    /** Application directory name on disk — needed to build paths inside extension/<code>/. */
    fun appDirName(area: OcArea): String = when (area) {
        OcArea.ADMIN -> "admin"
        OcArea.CATALOG -> "catalog"
        OcArea.SYSTEM -> "system"
    }

    /** Application side the file belongs to; null when the file is outside this installation. */
    fun areaOf(file: VirtualFile): OcArea? {
        val rel = relativePath(file) ?: return null
        val head = rel.substringBefore('/')
        return when {
            head == adminDirName -> OcArea.ADMIN
            head == "catalog" -> OcArea.CATALOG
            head == "system" -> OcArea.SYSTEM
            head == "extension" -> {
                // extension/<code>/<app>/...
                val parts = rel.split('/')
                when (parts.getOrNull(2)) {
                    "admin" -> OcArea.ADMIN
                    "catalog" -> OcArea.CATALOG
                    "system" -> OcArea.SYSTEM
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Extension code (extension/<code>/...) for files of 4.x extensions. */
    fun extensionCodeOf(file: VirtualFile): String? {
        val rel = relativePath(file) ?: return null
        val parts = rel.split('/')
        return if (parts.size > 2 && parts[0] == "extension") parts[1] else null
    }

    fun relativePath(file: VirtualFile): String? {
        val rootPath = dir.path
        val path = file.path
        if (!path.startsWith(rootPath)) return null
        if (path.length == rootPath.length) return ""
        if (path[rootPath.length] != '/') return null
        return path.substring(rootPath.length + 1)
    }
}
