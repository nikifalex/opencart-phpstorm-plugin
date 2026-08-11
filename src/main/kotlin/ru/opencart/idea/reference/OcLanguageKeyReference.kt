package ru.opencart.idea.reference

import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import ru.opencart.idea.lang.OcLanguage

/** `$this->language->get('text_success')` → the `$_['text_success'] = ...` line in a language file. */
class OcLanguageKeyReference(
    element: StringLiteralExpression,
) : PsiPolyVariantReferenceBase<StringLiteralExpression>(element, ElementManipulators.getValueTextRange(element)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.contents
        if (key.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val entries = OcLanguage.findKey(element, key).ifEmpty { OcLanguage.findKeyAnywhere(element, key) }
        return entries.map { PsiElementResolveResult(it.element) }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
