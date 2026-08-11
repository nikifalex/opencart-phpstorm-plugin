package io.github.nikifalex.opencart.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable

object OcPhpUtil {

    /** Classes declared in the file (in OpenCart there is exactly one per file). */
    fun classesIn(project: Project, file: VirtualFile): List<PhpClass> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(psi, PhpClass::class.java).toList()
    }

    fun firstClassIn(project: Project, file: VirtualFile): PhpClass? = classesIn(project, file).firstOrNull()

    /** `$this` used as the receiver of a call or a field access. */
    fun isThis(element: PsiElement?): Boolean = (element as? Variable)?.name == "this"

    /** An access of the form `$this-><field>`. */
    fun thisFieldName(element: PsiElement?): String? {
        val ref = element as? FieldReference ?: return null
        if (!isThis(ref.classReference)) return null
        return ref.name
    }

    /** A call of the form `$this-><holder>-><method>(...)`, e.g. `$this->load->controller(...)`. */
    fun isCallOnThisField(call: MethodReference, holder: String, method: String): Boolean {
        if (call.name != method) return false
        return thisFieldName(call.classReference) == holder
    }

    /** Position of the string literal among the call arguments. */
    fun argumentIndex(literal: StringLiteralExpression): Int {
        val call = enclosingCall(literal) ?: return -1
        return call.parameters.indexOfFirst { it === literal }
    }

    /** The call the literal is an argument of (literal → ParameterList → MethodReference). */
    fun enclosingCall(literal: StringLiteralExpression): MethodReference? =
        literal.parent?.parent as? MethodReference

    fun stringValue(literal: StringLiteralExpression): String? {
        if (literal.text.contains("$") && literal.text.startsWith("\"")) return null // interpolation is not a route
        return literal.contents.takeIf { it.isNotEmpty() }
    }
}
