package ru.opencart.idea.ocmod

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import ru.opencart.idea.core.OcProjectService

/**
 * Checks that the modification will apply at all: the `<file path="...">` target exists and the
 * `<search>` text occurs in that file.
 *
 * This is exactly how modules break after an OpenCart upgrade: OCMOD silently skips the operation
 * (error="skip"), the feature disappears and the code shows no trace of it.
 */
class OcModSearchInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? XmlFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!OcModSupport.isModificationFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        if (!OcProjectService.getInstance(holder.project).isOpenCartProject()) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val root = OcModSupport.rootFor(holder.project, file.virtualFile) ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                when (tag.name) {
                    "file" -> checkTarget(tag)
                    "search" -> checkSearch(tag)
                }
            }

            private fun checkTarget(tag: XmlTag) {
                val declared = OcModSupport.declaredPath(tag) ?: return
                if (OcModSupport.targets(root, declared).isNotEmpty()) return
                val anchor = tag.getAttribute("path")?.valueElement
                    ?: tag.getAttribute("name")?.valueElement
                    ?: return
                holder.registerProblem(
                    anchor,
                    "OCMOD: file '$declared' not found in the store — the modification will not apply",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }

            private fun checkSearch(tag: XmlTag) {
                val fileTag = tag.parentTag?.parentTag?.takeIf { it.name == "file" }
                    ?: tag.parentTag?.takeIf { it.name == "file" }
                    ?: return
                val text = tag.value.trimmedText
                if (text.isBlank()) return
                val isRegex = tag.getAttributeValue("regex")?.lowercase() in setOf("true", "1")
                if (OcModSupport.searchMatches(root, fileTag, text, isRegex)) return

                holder.registerProblem(
                    tag,
                    "OCMOD: the <search> fragment is missing in the target file — the operation will be skipped",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
    }
}
