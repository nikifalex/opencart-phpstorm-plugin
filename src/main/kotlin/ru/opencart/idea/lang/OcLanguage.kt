package ru.opencart.idea.lang

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import ru.opencart.idea.core.OcContext
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRoutes

/** A key definition in a language file: the `$_['key']` element itself and its value. */
data class OcLanguageEntry(val key: String, val value: String?, val element: PsiElement, val file: VirtualFile)

/**
 * Language file handling: which files the current controller loads and which keys they hold.
 *
 * Keys are always declared as `$_['text_success'] = '...';` and loaded through
 * `$this->load->language('catalog/product')`, so the set of available keys is derived from the
 * language() calls in the current class plus the common language file (<code>/<code>.php).
 */
object OcLanguage {

    /** Routes of the language files loaded by the class owning the element. */
    fun loadedRoutes(element: PsiElement): List<String> {
        val phpClass = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
        val scope: PsiElement = phpClass ?: element.containingFile ?: return emptyList()
        val routes = LinkedHashSet<String>()
        for (call in PsiTreeUtil.findChildrenOfType(scope, MethodReference::class.java)) {
            val isLanguageLoad = (call.name == "language" && OcPhpUtil.thisFieldName(call.classReference) == "load") ||
                (call.name == "load" && OcPhpUtil.thisFieldName(call.classReference) == "language")
            if (!isLanguageLoad) continue
            val literal = call.parameters.firstOrNull() as? StringLiteralExpression ?: continue
            routes += literal.contents
        }
        return routes.toList()
    }

    /** Language files whose keys are available at this point in the code. */
    fun availableFiles(element: PsiElement): List<VirtualFile> {
        val ctx = OcProjectService.getInstance(element.project).contextOf(element) ?: return emptyList()
        val files = ArrayList<VirtualFile>()
        for (route in loadedRoutes(element)) {
            files += OcRoutes.resolveLanguages(ctx, route)
        }
        files += commonFiles(ctx)
        return files.distinct()
    }

    /** The common dictionary of a language: catalog/language/en-gb/en-gb.php */
    fun commonFiles(ctx: OcContext): List<VirtualFile> =
        OcRoutes.languageDirs(ctx).mapNotNull { dir -> dir.findChild("${dir.name}.php") }

    /** Every key declared in the file. */
    fun entriesIn(project: Project, file: VirtualFile): List<OcLanguageEntry> {
        val psi: PsiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val result = ArrayList<OcLanguageEntry>()
        for (assignment in PsiTreeUtil.findChildrenOfType(psi, AssignmentExpression::class.java)) {
            val access = assignment.variable as? ArrayAccessExpression ?: continue
            if ((access.value as? Variable)?.name != "_") continue
            val index = access.index?.value as? StringLiteralExpression ?: continue
            val value = (assignment.value as? StringLiteralExpression)?.contents
            result += OcLanguageEntry(index.contents, value, access, file)
        }
        return result
    }

    /** Definitions of the key among the available language files. */
    fun findKey(element: PsiElement, key: String): List<OcLanguageEntry> =
        availableFiles(element).flatMap { entriesIn(element.project, it) }.filter { it.key == key }

    /** The key anywhere in the installation — a fallback when no load->language() call was found. */
    fun findKeyAnywhere(element: PsiElement, key: String, limit: Int = 20): List<OcLanguageEntry> {
        val ctx = OcProjectService.getInstance(element.project).contextOf(element) ?: return emptyList()
        val result = ArrayList<OcLanguageEntry>()
        val langRoot = ctx.root.appDir(ctx.area)?.findChild("language") ?: return emptyList()
        val firstLang = langRoot.children.firstOrNull { it.isDirectory } ?: return emptyList()
        walk(firstLang) { file ->
            if (file.extension == "php") {
                result += entriesIn(element.project, file).filter { it.key == key }
            }
            result.size < limit
        }
        return result
    }

    private fun walk(dir: VirtualFile, visit: (VirtualFile) -> Boolean) {
        for (child in dir.children) {
            if (child.isDirectory) {
                walk(child, visit)
            } else if (!visit(child)) {
                return
            }
        }
    }
}
