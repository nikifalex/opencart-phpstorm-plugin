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
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRegistryService

/**
 * Types the Registry magic: `$this->db`, `$this->config`, `$this->session`, `$this->cart` and so on.
 *
 * In 2.x/3.x this goes through the controller `__get()`, in 4.x through a `__get()` returning
 * `object`, so without the plugin these objects offer no completion at all. The key list comes from
 * the installation itself (see [OcRegistryService]) rather than from a hardcoded table.
 */
class OcRegistryTypeProvider : PhpTypeProvider4 {

    companion object {
        const val KEY = 'ɽ'
        private const val SEP = '\u0007'
    }

    override fun getKey(): Char = KEY

    override fun getType(element: PsiElement): PhpType? {
        if (DumbService.isDumb(element.project)) return null
        val ref = element as? FieldReference ?: return null
        val name = OcPhpUtil.thisFieldName(ref) ?: return null
        if (name.startsWith("model_")) return null // handled by OcModelTypeProvider
        if (name.length > 40 || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        val url = element.containingFile?.originalFile?.virtualFile?.url ?: return null
        return PhpType().add("#$KEY$name$SEP$url")
    }

    override fun complete(expression: String, project: Project): PhpType? {
        val payload = expression.substring(2)
        val name = payload.substringBefore(SEP)
        val url = payload.substringAfter(SEP, "")
        if (url.isEmpty()) return null

        val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return null
        val root = OcProjectService.getInstance(project).rootOf(file) ?: return null
        val fqn = OcRegistryService.getInstance(project).registryClasses(root)[name] ?: return null
        return PhpType().add(fqn)
    }

    override fun getBySignature(
        expression: String,
        visited: MutableSet<String>,
        depth: Int,
        project: Project,
    ): MutableCollection<out PhpNamedElement> = PhpIndex.getInstance(project).getClassesByFQN(expression)
}
