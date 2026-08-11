package ru.opencart.idea.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import ru.opencart.idea.core.OcProjectService

/** The "OpenCart" window only shows up in projects where a store installation was found. */
class OcToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        OcProjectService.getInstance(project).isOpenCartProject()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OcRoutesPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Routes", false)
        toolWindow.contentManager.addContent(content)
    }
}
