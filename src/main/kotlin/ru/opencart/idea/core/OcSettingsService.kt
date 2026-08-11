package ru.opencart.idea.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.nio.charset.StandardCharsets

/**
 * Setting keys (`$this->config->get('config_...')`).
 *
 * Values live in the setting table, but the key names themselves are discoverable statically: the
 * basic ones in the system/config files, the rest in the install/opencart.sql dump.
 */
@Service(Service.Level.PROJECT)
class OcSettingsService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): OcSettingsService = project.service()

        private val ARRAY_KEY = Regex("""\${'$'}_\[\s*['"]([a-zA-Z0-9_]+)['"]\s*]""")
        private val SETTING_KEY = Regex("""['"](config_[a-zA-Z0-9_]+)['"]""")
    }

    fun keys(root: OcRoot): Set<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = OcProjectService.getInstance(project).roots().associate { it.dir.path to collect(it) }
            CachedValueProvider.Result.create(map, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }[root.dir.path] ?: emptySet()

    private fun collect(root: OcRoot): Set<String> {
        val keys = LinkedHashSet<String>()

        root.system?.findChild("config")?.children
            ?.filter { !it.isDirectory && it.extension == "php" }
            ?.forEach { file -> ARRAY_KEY.findAll(read(file, 128 * 1024)).forEach { keys += it.groupValues[1] } }

        root.dir.findFileByRelativePath("install/opencart.sql")?.let { sql ->
            SETTING_KEY.findAll(read(sql, 2 * 1024 * 1024)).forEach { keys += it.groupValues[1] }
        }

        return keys
    }

    private fun read(file: VirtualFile, limit: Int): String = try {
        String(file.inputStream.use { it.readNBytes(limit) }, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
