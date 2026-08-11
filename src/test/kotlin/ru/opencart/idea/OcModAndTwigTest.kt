package ru.opencart.idea

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Checks for OCMOD modifications and the controller → Twig template link. */
class OcModAndTwigTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun setUpStore() {
        myFixture.copyDirectoryToProject("oc3", "")
    }

    fun testOcmodFilePathResolves() {
        setUpStore()
        val xml = """
            <modification>
                <name>Test</name>
                <code>test</code>
                <file path="admin/model/catalog/product.php">
                    <operation>
                        <search><![CDATA[class ModelCatalogProduct]]></search>
                        <add position="after"><![CDATA[// added]]></add>
                    </operation>
                </file>
            </modification>
        """.trimIndent()
        val file = myFixture.addFileToProject("modules/test.ocmod.xml", xml)
        val offset = xml.indexOf("admin/model/catalog/product.php") + 5
        val reference = file.findReferenceAt(offset)
        assertNotNull("no reference on the modification target file", reference)
        val targets = (reference as PsiPolyVariantReference).multiResolve(false).map { it.element }
        assertTrue("target file not found: $targets", targets.any { it is PsiFile && it.name == "product.php" })
    }

    fun testOcmodMissingSearchIsReported() {
        setUpStore()
        myFixture.addFileToProject(
            "modules/broken.ocmod.xml",
            """
            <modification>
                <name>Broken</name>
                <code>broken</code>
                <file path="admin/model/catalog/product.php">
                    <operation>
                        <search><![CDATA[class ThisTextDoesNotExistAnywhere]]></search>
                        <add position="after"><![CDATA[// added]]></add>
                    </operation>
                </file>
            </modification>
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(
            myFixture.findFileInTempDir("modules/broken.ocmod.xml"),
        )
        myFixture.enableInspections(ru.opencart.idea.ocmod.OcModSearchInspection())
        val highlights = myFixture.doHighlighting()
        assertTrue(
            "a missing <search> was not reported: " + highlights.mapNotNull { it.description },
            highlights.any { it.description?.contains("missing in the target file") == true },
        )
    }

    fun testTwigCompletionSuggestsControllerVariables() {
        setUpStore()
        myFixture.addFileToProject(
            "admin/controller/catalog/twigtest.php",
            """
            <?php
            class ControllerCatalogTwigtest extends Controller {
                public function index() {
                    ${'$'}data['product_list'] = 1;
                    ${'$'}this->response->setOutput(${'$'}this->load->view('catalog/twigtest', ${'$'}data));
                }
            }
            """.trimIndent(),
        )
        val template = myFixture.addFileToProject("admin/view/template/catalog/twigtest.twig", "{{ prod }}")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)
        myFixture.editor.caretModel.moveToOffset(template.text.indexOf("prod") + 4)
        myFixture.completeBasic()
        // A single match is inserted right away, leaving the lookup empty but the text changed.
        val strings = myFixture.lookupElementStrings
        val completed = strings?.contains("product_list") ?: myFixture.editor.document.text.contains("product_list")
        assertTrue("no product_list suggestion: ${strings ?: myFixture.editor.document.text}", completed)
    }
}
