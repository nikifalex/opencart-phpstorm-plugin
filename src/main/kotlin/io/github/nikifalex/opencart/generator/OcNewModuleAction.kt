package io.github.nikifalex.opencart.generator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.util.ui.FormBuilder
import io.github.nikifalex.opencart.core.OcProjectService
import io.github.nikifalex.opencart.core.OcRoot
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * "New OpenCart module": a single dialog produces an admin controller that saves its settings, a
 * template, language files for every language and optionally the catalog part — laid out for the
 * detected store version.
 */
class OcNewModuleAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        val roots = OcProjectService.getInstance(project).roots()
        if (roots.isEmpty()) {
            Messages.showWarningDialog(project, "No OpenCart installation found in this project.", "OpenCart")
            return
        }

        val dialog = OcNewModuleDialog(project, roots)
        if (!dialog.showAndGet()) return

        val request = dialog.request() ?: return
        val created = WriteCommandAction.writeCommandAction(project)
            .withName("Creating an OpenCart module")
            .compute<List<com.intellij.openapi.vfs.VirtualFile>, RuntimeException> {
                OcModuleGenerator.generate(request)
            }

        if (created.isEmpty()) {
            Messages.showErrorDialog(project, "Could not create the module files.", "OpenCart")
            return
        }
        created.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}

private class OcNewModuleDialog(project: Project, private val roots: List<OcRoot>) : DialogWrapper(project) {

    private val codeField = JTextField("my_module")
    private val titleField = JTextField("My Module")
    private val catalogBox = JCheckBox("Create the catalog part", true)
    private val rootBox = JComboBox(roots.map { "${it.dir.name} (${it.version.displayName})" }.toTypedArray())

    init {
        title = "New OpenCart Module"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Module code:", codeField)
        .addLabeledComponent("Title:", titleField)
        .addLabeledComponent("Store:", rootBox)
        .addComponent(catalogBox)
        .panel

    override fun doValidate(): ValidationInfo? {
        val code = codeField.text.trim()
        if (!Regex("^[a-z][a-z0-9_]*$").matches(code)) {
            return ValidationInfo("Module code: lowercase latin letters, digits and underscores only", codeField)
        }
        return null
    }

    fun request(): OcModuleRequest? {
        val root = roots.getOrNull(rootBox.selectedIndex) ?: return null
        return OcModuleRequest(
            root = root,
            code = codeField.text.trim(),
            title = titleField.text.trim().ifEmpty { codeField.text.trim() },
            withCatalog = catalogBox.isSelected,
        )
    }
}
