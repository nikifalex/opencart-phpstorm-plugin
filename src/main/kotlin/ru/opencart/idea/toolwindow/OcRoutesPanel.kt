package ru.opencart.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import ru.opencart.idea.core.OcArea
import ru.opencart.idea.core.OcContext
import ru.opencart.idea.core.OcPhpUtil
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRouteKind
import ru.opencart.idea.core.OcRoutes
import ru.opencart.idea.event.OcEventService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A tree node that knows how to open its file. */
private data class OcRouteNode(
    val label: String,
    val file: VirtualFile?,
    val method: String? = null,
) {
    override fun toString(): String = label
}

/**
 * The "OpenCart" panel: a tree of store routes and registered event subscriptions.
 *
 * OpenCart routes are listed nowhere — they exist only as files on disk, so without such a tree there
 * is no single place to see what a store actually contains.
 */
class OcRoutesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("OpenCart")
    private val model = DefaultTreeModel(rootNode)
    private val tree = Tree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeSpeedSearch.installOn(tree, true) { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject?.toString() ?: ""
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openSelected()
            }
        })

        val actions = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Rebuild the route tree", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
        }
        val toolbar: ActionToolbar = ActionManager.getInstance()
            .createActionToolbar("OpenCartRoutes", actions, false)
        toolbar.targetComponent = tree

        add(toolbar.component, BorderLayout.WEST)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        refresh()
    }

    /**
     * Collection runs in the background under a read action: walking templates and languages of a big
     * store means thousands of files, and on the EDT that is a visible pause when the window opens.
     */
    fun refresh() {
        rootNode.removeAllChildren()
        rootNode.add(DefaultMutableTreeNode(OcRouteNode("Scanning…", null)))
        model.reload()

        ApplicationManager.getApplication().executeOnPooledThread {
            val built = ReadAction.compute<DefaultMutableTreeNode, RuntimeException> { buildTree() }
            ApplicationManager.getApplication().invokeLater({
                rootNode.removeAllChildren()
                while (built.childCount > 0) {
                    rootNode.add(built.getChildAt(0) as DefaultMutableTreeNode)
                }
                model.reload()
                expandTopLevels()
            }, ModalityState.any())
        }
    }

    private fun buildTree(): DefaultMutableTreeNode {
        val rootNode = DefaultMutableTreeNode("OpenCart")
        val service = OcProjectService.getInstance(project)
        val roots = service.roots()

        if (roots.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode(OcRouteNode("No OpenCart installation found", null)))
        }

        for (root in roots) {
            val storeNode = DefaultMutableTreeNode(
                OcRouteNode("${root.dir.name} — ${root.version.displayName}", root.dir),
            )
            for (area in listOf(OcArea.ADMIN, OcArea.CATALOG)) {
                val ctx = OcContext(root, area)
                val areaNode = DefaultMutableTreeNode(
                    OcRouteNode(if (area == OcArea.ADMIN) "admin (${root.adminDirName})" else "catalog", null),
                )
                for (kind in OcRouteKind.entries) {
                    val routes = OcRoutes.listRoutes(ctx, kind)
                    if (routes.isEmpty()) continue
                    val kindNode = DefaultMutableTreeNode(
                        OcRouteNode("${kindLabel(kind)} (${routes.size})", null),
                    )
                    for (route in routes.sorted()) {
                        val file = when (kind) {
                            OcRouteKind.CONTROLLER, OcRouteKind.MODEL -> OcRoutes.phpFile(ctx, route, kind)
                            OcRouteKind.VIEW -> OcRoutes.resolveTemplates(ctx, route).firstOrNull()
                            OcRouteKind.LANGUAGE -> OcRoutes.resolveLanguages(ctx, route).firstOrNull()
                        }
                        kindNode.add(DefaultMutableTreeNode(OcRouteNode(route, file)))
                    }
                    areaNode.add(kindNode)
                }
                if (areaNode.childCount > 0) storeNode.add(areaNode)
            }
            addEvents(storeNode)
            rootNode.add(storeNode)
        }
        return rootNode
    }

    /** Subscriptions are only visible through addEvent() calls, so they get a branch of their own. */
    private fun addEvents(storeNode: DefaultMutableTreeNode) {
        if (DumbService.isDumb(project)) return
        val subscriptions = OcEventService.getInstance(project).subscriptions()
        if (subscriptions.isEmpty()) return

        val eventsNode = DefaultMutableTreeNode(OcRouteNode("Events (${subscriptions.size})", null))
        val service = OcProjectService.getInstance(project)
        val root = service.roots().firstOrNull()
        for (subscription in subscriptions.sortedBy { it.trigger }) {
            val target = root?.let { r ->
                listOf(OcArea.ADMIN, OcArea.CATALOG)
                    .firstNotNullOfOrNull { area ->
                        OcRoutes.resolveController(OcContext(r, area), subscription.action)
                    }
            }
            eventsNode.add(
                DefaultMutableTreeNode(
                    OcRouteNode(
                        "${subscription.trigger}  →  ${subscription.action}",
                        target?.file,
                        target?.method,
                    ),
                ),
            )
        }
        storeNode.add(eventsNode)
    }

    private fun kindLabel(kind: OcRouteKind): String = when (kind) {
        OcRouteKind.CONTROLLER -> "Controllers"
        OcRouteKind.MODEL -> "Models"
        OcRouteKind.VIEW -> "Templates"
        OcRouteKind.LANGUAGE -> "Language files"
    }

    private fun expandTopLevels() {
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath(arrayOf<Any>(rootNode, rootNode.getChildAt(i))))
        }
    }

    private fun openSelected() {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val routeNode = node.userObject as? OcRouteNode ?: return
        val file = routeNode.file ?: return
        if (file.isDirectory) return

        val editors = FileEditorManager.getInstance(project)
        val method = routeNode.method?.let { name ->
            OcPhpUtil.firstClassIn(project, file)?.findMethodByName(name)
        }
        if (method != null) {
            method.navigate(true)
        } else {
            editors.openFile(file, true)
        }
    }
}
