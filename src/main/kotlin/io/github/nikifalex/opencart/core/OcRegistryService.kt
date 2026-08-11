package io.github.nikifalex.opencart.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.nio.charset.StandardCharsets

/**
 * The "registry key → class" map, that is what `$this->db`, `$this->cart` and friends really are.
 *
 * The key set is not hardcoded: it is read from system/framework.php, the startup controllers and
 * index.php of the installation at hand, so custom builds (ocStore, in-house libraries loaded via
 * $this->load->library()) are picked up as well. The static table is only a fallback.
 */
@Service(Service.Level.PROJECT)
class OcRegistryService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): OcRegistryService = project.service()

        /** Fallback table used when the installation could not be parsed. */
        private val FALLBACK_OC3 = mapOf(
            "config" to "\\Config", "log" to "\\Log", "event" to "\\Event", "load" to "\\Loader",
            "request" to "\\Request", "response" to "\\Response", "db" to "\\DB",
            "session" to "\\Session", "cache" to "\\Cache", "url" to "\\Url",
            "language" to "\\Language", "document" to "\\Document", "openbay" to "\\Openbay",
            "cart" to "\\Cart\\Cart", "customer" to "\\Cart\\Customer", "user" to "\\Cart\\User",
            "currency" to "\\Cart\\Currency", "tax" to "\\Cart\\Tax", "weight" to "\\Cart\\Weight",
            "length" to "\\Cart\\Length", "affiliate" to "\\Cart\\Affiliate",
            "encryption" to "\\Encryption", "image" to "\\Image", "mail" to "\\Mail",
            "pagination" to "\\Pagination", "template" to "\\Template",
        )

        private val FALLBACK_OC4 = mapOf(
            "config" to "\\Opencart\\System\\Engine\\Config",
            "load" to "\\Opencart\\System\\Engine\\Loader",
            "event" to "\\Opencart\\System\\Engine\\Event",
            "autoloader" to "\\Opencart\\System\\Engine\\Autoloader",
            "log" to "\\Opencart\\System\\Library\\Log",
            "request" to "\\Opencart\\System\\Library\\Request",
            "response" to "\\Opencart\\System\\Library\\Response",
            "db" to "\\Opencart\\System\\Library\\DB",
            "session" to "\\Opencart\\System\\Library\\Session",
            "cache" to "\\Opencart\\System\\Library\\Cache",
            "url" to "\\Opencart\\System\\Library\\Url",
            "language" to "\\Opencart\\System\\Library\\Language",
            "document" to "\\Opencart\\System\\Library\\Document",
            "template" to "\\Opencart\\System\\Library\\Template",
            "cart" to "\\Opencart\\System\\Library\\Cart\\Cart",
            "customer" to "\\Opencart\\System\\Library\\Cart\\Customer",
            "user" to "\\Opencart\\System\\Library\\Cart\\User",
            "currency" to "\\Opencart\\System\\Library\\Cart\\Currency",
            "tax" to "\\Opencart\\System\\Library\\Cart\\Tax",
            "weight" to "\\Opencart\\System\\Library\\Cart\\Weight",
            "length" to "\\Opencart\\System\\Library\\Cart\\Length",
            "affiliate" to "\\Opencart\\System\\Library\\Cart\\Affiliate",
        )

        private val SET_WITH_NEW = Regex("""->set\(\s*['"]([a-zA-Z0-9_]+)['"]\s*,\s*new\s+\\?([A-Za-z0-9_\\]+)""")
        private val SET_WITH_VAR = Regex("""->set\(\s*['"]([a-zA-Z0-9_]+)['"]\s*,\s*\$([a-zA-Z0-9_]+)\s*\)""")
        private val VAR_ASSIGN = Regex("""\$([a-zA-Z0-9_]+)\s*=\s*new\s+\\?([A-Za-z0-9_\\]+)""")
        private val LIBRARY_CALL = Regex("""->library\(\s*['"]([a-zA-Z0-9_/]+)['"]""")
    }

    /** Registry keys of the installation: name → class FQN. */
    fun registryClasses(root: OcRoot): Map<String, String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = OcProjectService.getInstance(project).roots().associate { it.dir.path to build(it) }
            CachedValueProvider.Result.create(map, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }[root.dir.path] ?: emptyMap()

    private fun build(root: OcRoot): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        result += if (root.version == OcVersion.OC4) FALLBACK_OC4 else FALLBACK_OC3

        for (file in scanTargets(root)) {
            parseInto(root, file, result)
        }
        return result
    }

    /** Files where the engine fills the registry. */
    private fun scanTargets(root: OcRoot): List<VirtualFile> {
        val files = ArrayList<VirtualFile>()
        root.system?.findChild("framework.php")?.let { files += it }
        root.dir.findChild("index.php")?.let { files += it }
        root.admin?.findChild("index.php")?.let { files += it }
        for (area in listOf(OcArea.ADMIN, OcArea.CATALOG)) {
            val startup = root.appDir(area)?.findFileByRelativePath("controller/startup") ?: continue
            files += startup.children.filter { !it.isDirectory && it.extension == "php" }
        }
        return files
    }

    private fun parseInto(root: OcRoot, file: VirtualFile, out: MutableMap<String, String>) {
        val text = read(file)
        if (text.isEmpty()) return

        val vars = HashMap<String, String>()
        for (m in VAR_ASSIGN.findAll(text)) {
            vars[m.groupValues[1]] = m.groupValues[2]
        }
        for (m in SET_WITH_NEW.findAll(text)) {
            out[m.groupValues[1]] = normalize(m.groupValues[2])
        }
        for (m in SET_WITH_VAR.findAll(text)) {
            vars[m.groupValues[2]]?.let { out[m.groupValues[1]] = normalize(it) }
        }
        // $this->load->library('user/user') registers an object under the route basename
        for (m in LIBRARY_CALL.findAll(text)) {
            val route = m.groupValues[1]
            val name = route.substringAfterLast('/')
            val fqn = libraryClass(root, route) ?: continue
            out.putIfAbsent(name, fqn)
        }
    }

    /** system/library/<route>.php → FQN of the library class. */
    private fun libraryClass(root: OcRoot, route: String): String? {
        val file = root.system?.findFileByRelativePath("library/$route.php") ?: return null
        val text = read(file)
        val ns = Regex("""namespace\s+([A-Za-z0-9_\\]+)\s*;""").find(text)?.groupValues?.get(1)
        val cls = Regex("""\bclass\s+([A-Za-z0-9_]+)""").find(text)?.groupValues?.get(1) ?: return null
        return if (ns != null) "\\$ns\\$cls" else "\\$cls"
    }

    private fun normalize(raw: String): String = "\\" + raw.trim('\\')

    private fun read(file: VirtualFile): String = try {
        String(file.inputStream.use { it.readNBytes(64 * 1024) }, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
