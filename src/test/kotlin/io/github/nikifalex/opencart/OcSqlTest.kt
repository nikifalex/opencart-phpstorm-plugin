package io.github.nikifalex.opencart

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.sql.OcSqlInjector

/**
 * Checks how the argument of `$this->db->query()` is glued into a single SQL statement.
 *
 * The concatenation is what matters here: OpenCart always builds queries out of DB_PREFIX and escaped
 * values, and every part injected on its own would be reported as broken SQL.
 */
class OcSqlTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val q = "\""   // a PHP quote, to keep the queries below readable
    private val s = "'"    // and a single one, for the escaping tests

    private var counter = 0

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("oc3", "")
    }

    /** Puts the query into a model and returns the SQL an injection would show, or null for no injection. */
    private fun injectedSql(query: String): String? {
        val call = queryCall("db", "query", query)
        val argument = call.parameters.firstOrNull() ?: return null
        val root = OcProjectService.getInstance(project).roots().single()
        val prefix = OcProjectService.getInstance(project).dbPrefix(root)
        val places = OcSqlInjector().buildPlaces(argument, prefix)
        if (places.isEmpty()) return null
        return places.joinToString("") { it.prefix + it.host.contents + it.suffix }
    }

    /**
     * The same, but through the platform: the text of the SQL file the editor really shows.
     *
     * Database Tools inject into these concatenations as well, so this is also what pins that the
     * plugin's injection is the one that wins — its placeholder is `?`, theirs is `%name`.
     */
    private fun injectedFileText(query: String): String? {
        val call = queryCall("db", "query", query)
        val literal = PsiTreeUtil.findChildrenOfType(call, StringLiteralExpression::class.java).first()
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(literal) ?: return null
        val files = injected.map { it.first }.filterIsInstance<PsiFile>().distinct()
        assertEquals("the query must become exactly one SQL file", 1, files.size)
        return files.first().text
    }

    private fun queryCall(field: String, method: String, argument: String): MethodReference {
        val file = myFixture.addFileToProject(
            "admin/model/catalog/test_sql_${counter++}.php",
            """
            <?php
            class ModelCatalogTestSql extends Model {
                public function run(${'$'}data, ${'$'}id, ${'$'}table) {
                    ${'$'}this->$field->$method($argument);
                }
            }
            """.trimIndent(),
        )
        return PsiTreeUtil.findChildrenOfType(file, MethodReference::class.java).first { it.name == method }
    }

    /** The literal is a host only inside db->query(); a route or a cache key must stay plain PHP. */
    private fun isInjected(field: String, method: String, argument: String): Boolean {
        val call = queryCall(field, method, argument)
        val literal = PsiTreeUtil.findChildrenOfType(call, StringLiteralExpression::class.java).first()
        return OcSqlInjector().queryArgument(literal) != null
    }

    fun testDbPrefixComesFromConfig() {
        val root = OcProjectService.getInstance(project).roots().single()
        assertEquals("shop_", OcProjectService.getInstance(project).dbPrefix(root))
    }

    /** A store checked out without config.php is not broken, it just has no prefix to read yet. */
    fun testPrefixFallsBackToTheInstallerDefault() {
        myFixture.copyDirectoryToProject("oc4", "shop2")
        val service = OcProjectService.getInstance(project)
        val root = service.roots().single { it.dir.name == "shop2" }
        assertEquals("oc_", service.dbPrefix(root))
    }

    fun testPrefixConstantIsReplacedByTheRealPrefix() {
        val sql = injectedSql("${q}update $q.DB_PREFIX.${q}product SET quantity=quantity_store_1+quantity_store_2$q")
        assertEquals("update shop_product SET quantity=quantity_store_1+quantity_store_2", sql)
    }

    fun testValueInsideQuotesBecomesParameter() {
        val sql = injectedSql(
            "${q}select * from $q . DB_PREFIX . ${q}product where product_id = '$q . (int)\$id . $q'$q",
        )
        assertEquals("select * from shop_product where product_id = '?'", sql)
    }

    fun testValueAfterOperatorBecomesParameter() {
        val sql = injectedSql("${q}select * from $q . DB_PREFIX . ${q}product where product_id = $q . \$id")
        assertEquals("select * from shop_product where product_id = ?", sql)
    }

    fun testValueAfterKeywordBecomesParameter() {
        val sql = injectedSql("${q}select * from $q . DB_PREFIX . ${q}product order by $q . \$data['sort']")
        assertEquals("select * from shop_product order by ?", sql)
    }

    /** A variable between two finished parts of a query carries SQL of its own, not a value. */
    fun testSqlFragmentIsDroppedFromTheStatement() {
        val sql = injectedSql(
            "${q}select * from $q . DB_PREFIX . ${q}product p $q . \$data['sql'] . $q order by p.sort_order$q",
        )
        assertEquals("select * from shop_product p  order by p.sort_order", sql)
    }

    fun testInterpolatedPartIsInlinedAsParameter() {
        val sql = injectedSql("${q}select * from $q . DB_PREFIX . ${q}product where product_id = '\$id'$q")
        assertEquals("select * from shop_product where product_id = '?'", sql)
    }

    /** Interpolation in the very first literal must not cost the whole query its highlighting. */
    fun testInterpolatedFirstLiteralIsInlined() {
        val sql = injectedSql("${q}select * from {\$table} where product_id = $q . (int)\$id . $q and status = 1$q")
        assertEquals("select * from t where product_id = ? and status = 1", sql)
    }

    /** The placeholder follows the position: a table name is an identifier, not a bound value. */
    fun testInterpolatedTableNameBecomesIdentifier() {
        val sql = injectedSql("${q}select * from {\$table}$q . $q where status = 1$q")
        assertEquals("select * from t where status = 1", sql)
    }

    /** Without a single plain literal there is nothing to host the injection. */
    fun testFullyInterpolatedQueryIsNotInjected() {
        assertNull(injectedSql("${q}select * from {\$table} where product_id = '\$id'$q"))
    }

    /** A table name asked for by position is an identifier: `?` there would not even parse. */
    fun testTableExpressionBecomesIdentifier() {
        val sql = injectedSql("${q}select * from $q . \$table . $q where status = 1$q")
        assertEquals("select * from t where status = 1", sql)
    }

    /** `$id` inside a single quoted string is text, not interpolation, so the literal stays a host. */
    fun testDollarInASingleQuotedStringIsNotInterpolation() {
        val sql = injectedSql("${s}select * from $s . DB_PREFIX . ${s}product where note = \$id$s")
        assertEquals("select * from shop_product where note = \$id", sql)
    }

    /** Generated code can concatenate endlessly; past the guard the query is left alone. */
    fun testTooLongConcatenationIsNotInjected() {
        val parts = (1..200).joinToString(" . ") { "${q}x$q" }
        assertNull(injectedSql(parts))
    }

    fun testPlainLiteralIsInjectedAsIs() {
        assertEquals("select * from oc_product", injectedSql("${q}select * from oc_product$q"))
    }

    /** Nothing sensible can be shown when the statement itself lives in a variable. */
    fun testQueryStartingWithAVariableIsNotInjected() {
        assertNull(injectedSql("\$data['sql'] . $q limit 1$q"))
    }

    /** End to end: the concatenation really becomes one SQL file inside the editor. */
    fun testConcatenationIsInjectedAsOneSqlFile() {
        assertEquals(
            "update shop_product SET quantity=1",
            injectedFileText("${q}update $q.DB_PREFIX.${q}product SET quantity=1$q"),
        )
    }

    /**
     * Database Tools inject into the same literal; `?` proves the plugin's injection is the one shown.
     *
     * Their placeholder is `%name`, so if the platform ever prefers their injector this fails instead
     * of quietly changing what the editor shows.
     */
    fun testPluginInjectionWinsOverTheBundledOne() {
        val sql = injectedFileText("${q}select * from $q . DB_PREFIX . ${q}product where id = '$q . (int)\$id . $q'$q")
        assertEquals("select * from shop_product where id = '?'", sql)
    }

    /**
     * PHP escaping reaches the injected document as it is written, and the placeholder still lands.
     *
     * The platform does not resolve `\"` when it builds the SQL file, so the heuristic reads the same
     * raw text — this pins that the two stay in agreement.
     */
    fun testPhpEscapingIsPassedThroughToTheStatement() {
        val sql = injectedFileText("${q}select * from $q . DB_PREFIX . ${q}product where name = \\${q}x\\$q and id = $q . (int)\$id")
        assertEquals("select * from shop_product where name = \\\"x\\\" and id = ?", sql)
    }

    /** Two stores in one project: the prefix is the one of the installation the file belongs to. */
    fun testEachInstallationUsesItsOwnPrefix() {
        myFixture.copyDirectoryToProject("oc4", "shop2")
        myFixture.addFileToProject("shop2/config.php", "<?php\ndefine('DB_PREFIX', 'two_');\n")
        val file = myFixture.addFileToProject(
            "shop2/admin/model/catalog/second_store.php",
            """
            <?php
            class ModelCatalogSecondStore extends Model {
                public function run() {
                    ${'$'}this->db->query("select * from " . DB_PREFIX . "product");
                }
            }
            """.trimIndent(),
        )
        val literal = PsiTreeUtil.findChildrenOfType(file, StringLiteralExpression::class.java).first()
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(literal)
        assertNotNull("no SQL injected in the second store", injected)
        assertEquals("select * from two_product", (injected!!.first().first as PsiFile).text)
    }

    fun testOnlyDbQueryIsInjected() {
        assertTrue(isInjected("db", "query", "${q}select * from $q . DB_PREFIX . ${q}product$q"))
        assertFalse(isInjected("cache", "get", "${q}select * from $q . DB_PREFIX . ${q}product$q"))
        assertFalse(isInjected("db", "escape", "${q}select * from $q . DB_PREFIX . ${q}product$q"))
    }
}
