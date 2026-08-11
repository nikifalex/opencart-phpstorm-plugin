package io.github.nikifalex.opencart.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRoutes
import io.github.nikifalex.opencart.lang.OcLanguage
import java.nio.charset.StandardCharsets

/**
 * Appends `$_['key'] = '';` to the language files of the route — to every installed language at once,
 * so there is no need to walk through en-gb, de-de and the rest by hand.
 */
class OcAddLanguageKeyFix(private val key: String) : LocalQuickFix {

    override fun getFamilyName(): String = "OpenCart: add key '$key' to the language files"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val ctx = OcProjectService.getInstance(project).contextOf(element) ?: return
        val routes = OcLanguage.loadedRoutes(element)
        val targets: List<VirtualFile> = if (routes.isNotEmpty()) {
            routes.flatMap { OcRoutes.resolveLanguages(ctx, it) }
        } else {
            OcLanguage.commonFiles(ctx)
        }

        var opened = false
        for (file in targets.distinct()) {
            append(file, "\$_['$key'] = '';\n")
            if (!opened) {
                FileEditorManager.getInstance(project).openFile(file, true)
                opened = true
            }
        }
    }

    private fun append(file: VirtualFile, text: String) {
        try {
            val current = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            val separator = if (current.endsWith("\n")) "" else "\n"
            file.setBinaryContent((current + separator + text).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            // the file may be read-only, so skip it silently
        }
    }
}
