package io.github.nikifalex.opencart

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Method
import io.github.nikifalex.opencart.event.OcEventName
import io.github.nikifalex.opencart.event.OcEventService
import io.github.nikifalex.opencart.toolwindow.OcRoutesPanel

/** The event system: name parsing, the subscription index and trigger/action navigation. */
class OcEventTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private fun setUpStore() {
        myFixture.copyDirectoryToProject("oc3", "")
    }

    private fun referenceAt(path: String, text: String): PsiReference? {
        val caret = text.indexOf("<caret>")
        require(caret >= 0) { "no <caret> marker in the text" }
        val psiFile = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        return psiFile.findReferenceAt(caret)
    }

    fun testEventNameParsing() {
        val event = OcEventName.parse("catalog/model/checkout/order/addOrderHistory/after")
        assertNotNull(event)
        assertEquals("checkout/order/addOrderHistory", event!!.route)
        assertEquals("after", event.moment)
    }

    fun testTriggerNavigatesToWrappedMethod() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/extension/total/test_event.php",
            """
            <?php
            class ControllerExtensionTotalTestEvent extends Controller {
                public function install() {
                    ${'$'}this->load->model('setting/event');
                    ${'$'}this->model_setting_event->addEvent('t', 'catalog/model/checkout/order/addOrder<caret>History/after', 'extension/total/voucher/send');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the event name", reference)
        val variants = (reference as PsiPolyVariantReference).multiResolve(false).map { it.element }
        assertTrue(
            "the event does not lead to the wrapped method: $variants",
            variants.any { it is Method && it.name == "addOrderHistory" },
        )
    }

    fun testActionNavigatesToHandler() {
        setUpStore()
        val reference = referenceAt(
            "admin/controller/extension/total/test_action.php",
            """
            <?php
            class ControllerExtensionTotalTestAction extends Controller {
                public function install() {
                    ${'$'}this->model_setting_event->addEvent('t', 'catalog/model/checkout/order/addOrderHistory/after', 'extension/total/vouch<caret>er/send');
                }
            }
            """.trimIndent(),
        )
        assertNotNull("no reference on the handler", reference)
        val variants = (reference as PsiPolyVariantReference).multiResolve(false).map { it.element }
        assertTrue(
            "the handler does not resolve to a method: $variants",
            variants.any { it is Method && it.name == "send" },
        )
    }

    fun testSubscriptionIsIndexed() {
        setUpStore()
        val subscriptions = OcEventService.getInstance(project).subscriptions()
        assertTrue(
            "the addEvent subscription was not indexed: $subscriptions",
            subscriptions.any {
                it.trigger == "catalog/model/checkout/order/addOrderHistory/after" &&
                    it.action == "extension/total/voucher/send"
            },
        )
    }

    fun testSubscriberMatchingIgnoresArea() {
        setUpStore()
        val matched = OcEventService.getInstance(project)
            .subscribersOf("catalog/model/checkout/order/addOrderHistory/after")
        assertTrue("the subscription did not match the event: $matched", matched.isNotEmpty())
    }

    fun testGutterShowsEventHandlers() {
        setUpStore()
        myFixture.configureFromExistingVirtualFile(
            myFixture.findFileInTempDir("catalog/model/checkout/order.php"),
        )
        myFixture.doHighlighting()
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(
            "no gutter marker with event handlers: $tooltips",
            tooltips.any { it.contains("event handlers") },
        )
    }

    fun testToolWindowTreeBuilds() {
        setUpStore()
        val panel = OcRoutesPanel(project)
        assertNotNull(panel)
    }
}
