package ru.opencart.idea.twig

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext
import com.jetbrains.twig.TwigTokenTypes
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.lang.OcLanguage

/**
 * Ctrl+click on a template variable: `{{ heading_title }}` → where the controller assigns it
 * (`$data['heading_title'] = ...`), and for a language key also the line in the language file.
 */
class OcTwigReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(TwigTokenTypes.IDENTIFIER),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    if (!OcProjectService.getInstance(element.project).isOpenCartProject()) {
                        return PsiReference.EMPTY_ARRAY
                    }
                    if (element.textLength == 0) return PsiReference.EMPTY_ARRAY
                    return arrayOf(OcTwigVariableReference(element))
                }
            },
        )
    }
}

class OcTwigVariableReference(
    element: PsiElement,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.text
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val file = element.containingFile?.originalFile?.virtualFile ?: return ResolveResult.EMPTY_ARRAY
        val project = element.project

        val targets = ArrayList<PsiElement>()
        OcTwigSupport.variablesFor(project, file)
            .filter { it.name == name }
            .forEach { targets += it.element }

        // Language keys reach the template under their own names, so lead to their declaration too
        for (controller in OcTwigSupport.controllersFor(project, file)) {
            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(controller) ?: continue
            targets += OcLanguage.findKey(psi, name).map { it.element }
            if (targets.isNotEmpty()) break
        }

        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
