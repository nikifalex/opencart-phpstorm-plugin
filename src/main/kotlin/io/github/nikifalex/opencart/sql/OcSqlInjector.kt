package io.github.nikifalex.opencart.sql

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ConcatenationExpression
import com.jetbrains.php.lang.psi.elements.ConstantReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import io.github.nikifalex.opencart.core.OcPhpUtil
import io.github.nikifalex.opencart.core.OcProjectService
import org.jetbrains.annotations.VisibleForTesting

/**
 * Injects SQL into `$this->db->query(...)` treating the whole argument as one statement.
 *
 * Every OpenCart query is built as `"select * from " . DB_PREFIX . "product where id = '" . $id . "'"`,
 * so injecting each literal on its own turns a valid query into several broken fragments and paints a
 * red error over normal code. Here all the parts are glued into a single SQL document: `DB_PREFIX`
 * becomes the real table prefix taken from config.php and any other expression becomes a placeholder
 * that keeps the statement parseable.
 *
 * Database Tools bring an SQL injection of their own that also glues concatenations and resolves
 * `DB_PREFIX`, and for a plain `select` the two agree. It gives up on shapes this one still handles —
 * `"update " . DB_PREFIX . "product SET quantity=1"` gets no injection at all — and it resolves the
 * constant through the PHP index, which has nothing to say about which of several installations in the
 * project the file belongs to. Both injectors are offered for the same literal and the platform takes
 * the first one; `OcSqlTest` pins which text ends up in the editor, so a change in that order fails the
 * build instead of silently swapping behaviour.
 */
class OcSqlInjector : MultiHostInjector {

    companion object {
        /** Runaway guard: real queries are a handful of parts, not hundreds. */
        private const val MAX_OPERANDS = 128
        private const val MAX_PARENT_DEPTH = 16

        /** Keywords after which a missing expression must become a bound parameter. */
        private val VALUE_KEYWORDS = setOf(
            "select", "and", "or", "not", "in", "like", "values", "between", "limit", "offset",
            "by", "set", "when", "then", "else", "having", "on", "using", "case",
        )

        /** Keywords after which SQL expects a name, so a placeholder has to look like an identifier. */
        private val IDENTIFIER_KEYWORDS = setOf("from", "join", "into", "update", "table", "truncate", "describe")

        private val VALUE_PUNCTUATION = "=<>(,+-*/%!|&~^".toSet()

        /** `$var`, `$obj->field`, `$arr['key']`, `{$expr}` inside a double quoted string. */
        private val INTERPOLATION = Regex("""\{\$[^}]*}|\$\{[^}]*}|\$[A-Za-z_]\w*(?:->\w+|\[[^]]*])*""")

        private val SQL_LANGUAGE_IDS = listOf("MySQL", "GenericSQL", "SQL")
    }

    /** One host literal with the read-only text that precedes and (for the last one) follows it. */
    data class Place(
        val host: StringLiteralExpression,
        val prefix: String,
        val suffix: String,
    )

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(StringLiteralExpression::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = context as? StringLiteralExpression ?: return
        val argument = queryArgument(literal) ?: return
        // The injection is registered once, from the leftmost host of the concatenation. Answering
        // this before anything else keeps the other literals of the same query off the expensive path:
        // they would build the very same places only to drop them here.
        if (leadingHost(argument) !== literal) return

        val service = OcProjectService.getInstance(context.project)
        val ocContext = service.contextOf(context) ?: return

        val places = buildPlaces(argument, service.dbPrefix(ocContext.root))
        if (places.isEmpty()) return

        val language = sqlLanguage() ?: return
        registrar.startInjecting(language)
        for (place in places) {
            registrar.addPlace(place.prefix, place.suffix, place.host, ElementManipulators.getValueTextRange(place.host))
        }
        registrar.doneInjecting()
    }

    /**
     * Splits the query argument into injection places.
     *
     * Everything that is not a plain string literal — `DB_PREFIX`, variables, `$this->db->escape()`,
     * interpolated strings — turns into read-only text attached to a neighbouring host.
     */
    @VisibleForTesting
    fun buildPlaces(argument: PsiElement, dbPrefix: String): List<Place> {
        val operands = flatten(argument)
        if (operands.isEmpty() || operands.size > MAX_OPERANDS) return emptyList()
        // A query starting with an expression (`$sql . " limit 1"`) has no statement to inject into:
        // whatever stands in for the variable, the fragment stays invalid SQL. A literal with
        // interpolation is a different matter — its text is known, so it is inlined and the first
        // plain literal after it carries the injection.
        if (operands.first() !is StringLiteralExpression) return emptyList()

        val places = mutableListOf<Place>()
        val sql = StringBuilder()   // the statement built so far, used to choose a placeholder
        val pending = StringBuilder()   // text waiting for the next host, or trailing text

        for (operand in operands) {
            val literal = operand as? StringLiteralExpression
            if (literal != null && isHost(literal)) {
                places += Place(literal, pending.toString(), "")
                pending.setLength(0)
                sql.append(literal.contents)
                continue
            }
            val text = when {
                literal != null -> inlineInterpolation(literal.contents, sql)   // no injection into interpolation
                else -> placeholderFor(operand, sql, dbPrefix)
            }
            pending.append(text)
            sql.append(text)
        }

        if (places.isEmpty()) return emptyList()
        if (pending.isEmpty()) return places
        val last = places.removeAt(places.size - 1)
        return places + last.copy(suffix = pending.toString())
    }

    /**
     * A literal can carry the injection only when it is a plain string.
     *
     * `"... where id = '$id'"` is edited as one piece of PHP, so the interpolated parts are inlined as
     * read-only text instead.
     */
    private fun isHost(literal: StringLiteralExpression): Boolean =
        literal.isValidHost && PsiTreeUtil.findChildOfType(literal, Variable::class.java) == null

    /** The literal that carries the injection for the whole concatenation, if there is one. */
    private fun leadingHost(argument: PsiElement): StringLiteralExpression? {
        val operands = flatten(argument)
        if (operands.firstOrNull() !is StringLiteralExpression) return null
        return operands.filterIsInstance<StringLiteralExpression>().firstOrNull { isHost(it) }
    }


    // --- placeholders ---------------------------------------------------------

    /** Text standing in for a non-literal part of the query. */
    private fun placeholderFor(operand: PsiElement, sql: CharSequence, dbPrefix: String): String {
        if (operand is ConstantReference && operand.name == "DB_PREFIX") return dbPrefix
        return placeholderAt(sql)
    }

    /**
     * Text of a literal that cannot host the injection because of interpolation.
     *
     * The literal parts are kept as they are and every `$var` gets the placeholder its position asks
     * for, so `"select * from {$table} where id = '$id'"` stays a readable statement instead of
     * turning into `select * from ? where id = '?'`.
     */
    private fun inlineInterpolation(contents: String, sql: CharSequence): String {
        if (!INTERPOLATION.containsMatchIn(contents)) return contents
        val statement = StringBuilder(sql)   // grows along with the result: the position decides the placeholder
        val out = StringBuilder()
        var last = 0
        for (match in INTERPOLATION.findAll(contents)) {
            val plain = contents.substring(last, match.range.first)
            statement.append(plain)
            val placeholder = placeholderAt(statement)
            statement.append(placeholder)
            out.append(plain).append(placeholder)
            last = match.range.last + 1
        }
        return out.append(contents, last, contents.length).toString()
    }

    private fun placeholderAt(sql: CharSequence): String = when (expectedAt(sql)) {
        Expected.VALUE -> "?"
        Expected.IDENTIFIER -> "t"
        // Most often a variable in this position carries a piece of SQL (` AND x = 1`), and the
        // statement stays valid without it.
        Expected.FRAGMENT -> ""
    }

    private enum class Expected { VALUE, IDENTIFIER, FRAGMENT }

    private fun expectedAt(sql: CharSequence): Expected {
        if (insideStringLiteral(sql)) return Expected.VALUE

        val trimmed = sql.trimEnd()
        val last = trimmed.lastOrNull() ?: return Expected.VALUE   // the query starts with the expression
        if (last in VALUE_PUNCTUATION) return Expected.VALUE

        val word = trimmed.takeLastWhile { it.isLetter() }.toString().lowercase()
        // A word ends here only if the previous character is not part of an identifier.
        val wordIsWhole = trimmed.length == word.length ||
            trimmed.getOrNull(trimmed.length - word.length - 1)?.let { !it.isLetterOrDigit() && it != '_' } == true
        if (word.isNotEmpty() && wordIsWhole && trimmed.length < sql.length) {
            // Only a keyword followed by a space asks for something: "order by " . $sort.
            if (word in IDENTIFIER_KEYWORDS) return Expected.IDENTIFIER
            if (word in VALUE_KEYWORDS) return Expected.VALUE
        }
        return Expected.FRAGMENT
    }

    /**
     * True when the query so far has an unclosed `'` or `"` — the placeholder lands inside a string.
     *
     * The text here is the raw contents of the literals, PHP escaping and all, because that is exactly
     * what the platform puts into the injected document: `"name = \"x\""` reaches SQL with the
     * backslashes still in place. Reading the same text keeps the guess and the visible statement in
     * agreement; the price is that a query written with escaped quotes is broken SQL for both of us.
     */
    private fun insideStringLiteral(sql: CharSequence): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\\' -> i++
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == c -> quote = null
            }
            i++
        }
        return quote != null
    }

    // --- PHP structure --------------------------------------------------------

    /** The whole first argument of `$this->db->query()` the literal belongs to. */
    @VisibleForTesting
    fun queryArgument(literal: StringLiteralExpression): PsiElement? {
        var current: PsiElement = literal
        repeat(MAX_PARENT_DEPTH) {
            when (val parent = current.parent ?: return null) {
                is ConcatenationExpression, is ParenthesizedExpression -> current = parent
                is ParameterList -> {
                    val call = parent.parent as? MethodReference ?: return null
                    if (call.name != "query" || OcPhpUtil.thisFieldName(call.classReference) != "db") return null
                    return if (call.parameters.firstOrNull() === current) current else null
                }
                else -> return null
            }
        }
        return null
    }

    /** Concatenation operands left to right; `a . b . c` is a tree of nested concatenations. */
    private fun flatten(argument: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val stack = ArrayDeque<PsiElement>()
        stack.addLast(argument)
        while (stack.isNotEmpty()) {
            if (result.size > MAX_OPERANDS) return result
            when (val element = unwrap(stack.removeLast())) {
                null -> continue
                is ConcatenationExpression -> {
                    element.rightOperand?.let { stack.addLast(it) }
                    element.leftOperand?.let { stack.addLast(it) }
                }
                else -> result += element
            }
        }
        return result
    }

    private fun unwrap(element: PsiElement?): PsiElement? =
        if (element is ParenthesizedExpression) unwrap(element.extract()) else element

    private fun sqlLanguage(): Language? = SQL_LANGUAGE_IDS.firstNotNullOfOrNull { Language.findLanguageByID(it) }
}
