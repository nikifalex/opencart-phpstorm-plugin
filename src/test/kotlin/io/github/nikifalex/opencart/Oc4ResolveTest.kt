package io.github.nikifalex.opencart

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcVersion

/**
 * OpenCart 4: namespaced classes, a method after a dot in the route (catalog/product.list) and
 * extensions living in extension/<code>/.
 */
class Oc4ResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun setUpStore() {
        myFixture.copyDirectoryToProject("oc4", "")
    }

    private fun referenceAt(path: String, text: String): PsiReference? {
        val caret = text.indexOf("<caret>")
        require(caret >= 0) { "no <caret> marker in the text" }
        val psiFile = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        return psiFile.findReferenceAt(caret)
    }

    fun testVersionIsDetected() {
        setUpStore()
        val roots = OcProjectService.getInstance(project).roots()
        assertEquals(1, roots.size)
        assertEquals(OcVersion.OC4, roots[0].version)
    }

    fun testRouteWithMethodResolvesToMethod() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test.php",
            """
            <?php
            namespace Opencart\Admin\Controller\Catalog;
            class Test extends \Opencart\System\Engine\Controller {
                public function index(): void {
                    ${'$'}this->url->link('catalog/pro<caret>duct.list');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the route carrying a method", reference)
        val resolved = reference!!.resolve()
        assertTrue("a route with a method must lead to that method: $resolved", resolved is Method && resolved.name == "list")
    }

    fun testExtensionRouteResolves() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_ext.php",
            """
            <?php
            namespace Opencart\Admin\Controller\Catalog;
            class TestExt extends \Opencart\System\Engine\Controller {
                public function index(): void {
                    ${'$'}this->load->controller('extension/demo/mod<caret>ule/demo');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the extension route", reference)
        assertTrue("extension route does not resolve", reference!!.resolve() is PhpClass)
    }

    fun testLoadedModelHasNamespacedType() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_model.php",
            """
            <?php
            namespace Opencart\Admin\Controller\Catalog;
            class TestModel extends \Opencart\System\Engine\Controller {
                public function index(): void {
                    ${'$'}this->load->model('catalog/product');
                    ${'$'}this->model_catalog_product->getPro<caret>duct(1);
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the model method", reference)
        val variants = (reference as PsiPolyVariantReference).multiResolve(false).map { it.element }
        assertTrue(
            "4.x model method does not resolve: $variants",
            variants.any { it is Method && it.name == "getProduct" },
        )
    }

    fun testRegistryTypeFromFramework() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_db.php",
            """
            <?php
            namespace Opencart\Admin\Controller\Catalog;
            class TestDb extends \Opencart\System\Engine\Controller {
                public function index(): void {
                    ${'$'}this->db->esc<caret>ape('x');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the DB method", reference)
        val resolved = reference!!.resolve()
        assertTrue("\$this->db is not typed in 4.x: $resolved", resolved is Method)
    }
}
