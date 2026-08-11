package ru.opencart.idea.ocmod

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import ru.opencart.idea.core.OcProjectService

/**
 * Ctrl+click from `<file path="catalog/controller/product/product.php">` into the store file itself.
 * Paths containing `*` resolve to every matching file at once.
 */
class OcModReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue().withParent(
                XmlPatterns.xmlAttribute().withParent(XmlPatterns.xmlTag().withName("file")),
            ),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
                    val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
                    if (!OcModSupport.isModificationFile(file)) return PsiReference.EMPTY_ARRAY
                    if (!OcProjectService.getInstance(element.project).isOpenCartProject()) {
                        return PsiReference.EMPTY_ARRAY
                    }
                    return arrayOf(OcModFileReference(value))
                }
            },
        )
    }
}

class OcModFileReference(
    element: XmlAttributeValue,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(
    element,
    TextRange(1, maxOf(1, element.textLength - 1)),
) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val tag = element.parent?.parent as? XmlTag ?: return ResolveResult.EMPTY_ARRAY
        val project = element.project
        val root = OcModSupport.rootFor(project, element.containingFile?.originalFile?.virtualFile)
            ?: return ResolveResult.EMPTY_ARRAY
        val declared = OcModSupport.declaredPath(tag) ?: return ResolveResult.EMPTY_ARRAY

        val manager = PsiManager.getInstance(project)
        return OcModSupport.targets(root, declared)
            .mapNotNull { manager.findFile(it) }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
