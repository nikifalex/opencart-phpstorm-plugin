package io.github.nikifalex.opencart.sql

import com.intellij.lang.Language
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.SimpleInjection
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import io.github.nikifalex.opencart.core.OcPhpUtil
import io.github.nikifalex.opencart.core.OcProjectService

/**
 * Highlights SQL inside `$this->db->query("...")` as real SQL.
 *
 * OpenCart queries are strings concatenated with DB_PREFIX, so without an injection they stay plain
 * grey text: no highlighting, no syntax checking, no table completion.
 */
class OcSqlInjectionContributor : LanguageInjectionContributor {

    override fun getInjection(context: PsiElement): Injection? {
        val literal = context as? StringLiteralExpression ?: return null
        if (!isDbQueryArgument(literal)) return null
        if (!OcProjectService.getInstance(context.project).isOpenCartProject()) return null
        if (OcProjectService.getInstance(context.project).contextOf(context) == null) return null

        val sql = Language.findLanguageByID("MySQL")
            ?: Language.findLanguageByID("GenericSQL")
            ?: Language.findLanguageByID("SQL")
            ?: return null
        return SimpleInjection(sql, "", "", null)
    }

    /** The literal is an argument of db->query(), including inside a concatenation. */
    private fun isDbQueryArgument(literal: StringLiteralExpression): Boolean {
        var current: PsiElement = literal
        // walk up through concatenations until the argument list
        repeat(12) {
            val parent = current.parent ?: return false
            if (parent is MethodReference) {
                return parent.name == "query" && OcPhpUtil.thisFieldName(parent.classReference) == "db"
            }
            current = parent
        }
        return false
    }
}
