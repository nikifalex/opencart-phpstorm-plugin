package ru.opencart.idea

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcVersion

/**
 * Checks against a synthetic OpenCart 3 installation: store detection, route resolution, model and
 * registry types, language keys.
 *
 * Test files always go inside admin/ or catalog/: outside an application the plugin deliberately stays
 * silent, because a route has no unambiguous meaning there.
 */
class OcResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun setUpStore() {
        myFixture.copyDirectoryToProject("oc3", "")
    }

    /** Adds a file to the project and returns the reference at the `<caret>` marker. */
    private fun referenceAt(path: String, text: String): PsiReference? {
        val caret = text.indexOf("<caret>")
        require(caret >= 0) { "no <caret> marker in the text" }
        val psiFile = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        return psiFile.findReferenceAt(caret)
    }

    fun testStoreIsDetected() {
        setUpStore()
        val roots = OcProjectService.getInstance(project).roots()
        assertEquals(1, roots.size)
        assertEquals(OcVersion.OC3, roots[0].version)
        assertEquals("admin", roots[0].adminDirName)
    }

    fun testModelRouteResolvesToModelClass() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test.php",
            """
            <?php
            class ControllerCatalogTest extends Controller {
                public function index() {
                    ${'$'}this->load->model('catalog/pro<caret>duct');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the model route", reference)
        val resolved = reference!!.resolve()
        assertTrue("route does not resolve to a model class: $resolved", resolved is PhpClass)
    }

    fun testControllerRouteResolvesFromUrlLink() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_url.php",
            """
            <?php
            class ControllerCatalogTestUrl extends Controller {
                public function index() {
                    ${'$'}this->url->link('common/dash<caret>board');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the controller route", reference)
        assertTrue(reference!!.resolve() is PhpClass)
    }

    fun testLanguageKeyResolves() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_lang.php",
            """
            <?php
            class ControllerCatalogTestLang extends Controller {
                public function index() {
                    ${'$'}this->load->language('catalog/product');
                    ${'$'}this->language->get('text_suc<caret>cess');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the language key", reference)
        assertNotNull("language key does not resolve", reference!!.resolve())
    }

    fun testLoadedModelHasType() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_type.php",
            """
            <?php
            class ControllerCatalogTestType extends Controller {
                public function index() {
                    ${'$'}this->load->model('catalog/product');
                    ${'$'}this->model_catalog_product->getPro<caret>duct(1);
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the model method", reference)
        // In OpenCart 3 the admin and catalog models declare the same class name, so resolution yields
        // both variants — assert that the method is found at all.
        val variants = (reference as PsiPolyVariantReference).multiResolve(false).map { it.element }
        assertTrue(
            "model method does not resolve (type not inferred): $variants",
            variants.any { it is Method && it.name == "getProduct" },
        )
    }

    fun testRegistryObjectHasType() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/catalog/test_registry.php",
            """
            <?php
            class ControllerCatalogTestRegistry extends Controller {
                public function index() {
                    ${'$'}this->db->que<caret>ry("SELECT 1");
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the DB method", reference)
        val resolved = reference!!.resolve()
        assertTrue("\$this->db is not typed: $resolved", resolved is Method)
    }

    fun testRouteCompletionSuggestsExistingModels() {
        setUpStore()
        val file = myFixture.addFileToProject(
            "admin/controller/catalog/test_completion.php",
            """
            <?php
            class ControllerCatalogTestCompletion extends Controller {
                public function index() {
                    ${'$'}this->load->model('');
                }
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("model('") + "model('".length)
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("no catalog/product suggestion: $strings", strings.contains("catalog/product"))
    }
}
