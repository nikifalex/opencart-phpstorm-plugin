package io.github.nikifalex.opencart.reference

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.psi.OcCalls
import io.github.nikifalex.opencart.psi.OcStringRole

/** Attaches references to every "magic" string of OpenCart. */
class OcReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(StringLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val literal = element as? StringLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    if (!OcProjectService.getInstance(element.project).isOpenCartProject()) {
                        return PsiReference.EMPTY_ARRAY
                    }
                    val role = OcCalls.roleOf(literal) ?: return PsiReference.EMPTY_ARRAY
                    return when (role) {
                        OcStringRole.CONTROLLER_ROUTE,
                        OcStringRole.MODEL_ROUTE,
                        OcStringRole.VIEW_ROUTE,
                        OcStringRole.LANGUAGE_ROUTE,
                        OcStringRole.LIBRARY_ROUTE,
                        -> arrayOf(OcRouteReference(literal, role))

                        OcStringRole.LANGUAGE_KEY -> arrayOf(OcLanguageKeyReference(literal))

                        OcStringRole.EVENT_NAME,
                        OcStringRole.EVENT_ACTION,
                        -> arrayOf(OcEventReference(literal, role))

                        else -> PsiReference.EMPTY_ARRAY
                    }
                }
            },
        )
    }
}
