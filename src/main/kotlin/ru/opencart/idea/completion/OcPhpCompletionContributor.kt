package ru.opencart.idea.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import ru.opencart.idea.core.OcArea
import ru.opencart.idea.core.OcContext
import ru.opencart.idea.core.OcProjectService
import ru.opencart.idea.core.OcRouteKind
import ru.opencart.idea.core.OcRoutes
import ru.opencart.idea.core.OcSettingsService
import ru.opencart.idea.lang.OcLanguage
import ru.opencart.idea.psi.OcCalls
import ru.opencart.idea.psi.OcStringRole

/**
 * Completion inside strings: controller/model/template/language routes, language keys, setting keys
 * and event names.
 */
class OcPhpCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withParent(StringLiteralExpression::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val literal = parameters.position.parent as? StringLiteralExpression ?: return
                    complete(literal, parameters, result)
                }
            },
        )
    }

    private fun complete(
        literal: StringLiteralExpression,
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val project = literal.project
        val service = OcProjectService.getInstance(project)
        if (!service.isOpenCartProject()) return
        val role = OcCalls.roleOf(literal) ?: return

        if (role == OcStringRole.EVENT_NAME || role == OcStringRole.EVENT_ACTION) {
            completeEvent(literal, parameters, result, role)
            return
        }

        val ctx = service.contextOf(literal) ?: return

        when (role) {
            OcStringRole.CONTROLLER_ROUTE -> addRoutes(result, OcRoutes.listRoutes(ctx, OcRouteKind.CONTROLLER), AllIcons.Nodes.Class)
            OcStringRole.MODEL_ROUTE -> addRoutes(result, OcRoutes.listRoutes(ctx, OcRouteKind.MODEL), AllIcons.Nodes.Class)
            OcStringRole.VIEW_ROUTE -> addRoutes(result, OcRoutes.listRoutes(ctx, OcRouteKind.VIEW), AllIcons.FileTypes.Html)
            OcStringRole.LANGUAGE_ROUTE -> addRoutes(result, OcRoutes.listRoutes(ctx, OcRouteKind.LANGUAGE), AllIcons.FileTypes.Properties)
            OcStringRole.LIBRARY_ROUTE -> addRoutes(result, listLibraries(literal), AllIcons.Nodes.PpLib)
            OcStringRole.LANGUAGE_KEY -> addLanguageKeys(literal, result)
            OcStringRole.CONFIG_KEY -> addSettingKeys(literal, result)
            else -> {}
        }
    }

    /**
     * Event names are built as `<area>/<kind>/<route>[/<method>]/<before|after>`, so suggestions follow
     * the prefix already typed: first the area and the kind, then the real routes. Enumerating every
     * combination would produce tens of thousands of entries.
     */
    private fun completeEvent(
        literal: StringLiteralExpression,
        parameters: CompletionParameters,
        result: CompletionResultSet,
        role: OcStringRole,
    ) {
        val project = literal.project
        val service = OcProjectService.getInstance(project)
        val root = service.rootOf(literal.containingFile?.originalFile?.virtualFile)
            ?: service.roots().firstOrNull()
            ?: return

        val typed = parameters.position.text
            .substringBefore(com.intellij.codeInsight.completion.CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
            .trim('\'', '"')
        val filtered = result.withPrefixMatcher(typed)

        if (role == OcStringRole.EVENT_ACTION) {
            for (area in listOf(OcArea.ADMIN, OcArea.CATALOG)) {
                for (route in OcRoutes.listRoutes(OcContext(root, area), OcRouteKind.CONTROLLER)) {
                    filtered.addElement(
                        LookupElementBuilder.create(route)
                            .withIcon(AllIcons.Nodes.Class)
                            .withTypeText(if (area == OcArea.ADMIN) "admin" else "catalog", true),
                    )
                }
            }
            return
        }

        val parts = typed.split('/')
        val area = when (parts.getOrNull(0)) {
            "admin" -> OcArea.ADMIN
            "catalog" -> OcArea.CATALOG
            else -> null
        }
        val kind = when (parts.getOrNull(1)) {
            "controller" -> OcRouteKind.CONTROLLER
            "model" -> OcRouteKind.MODEL
            "view" -> OcRouteKind.VIEW
            "language" -> OcRouteKind.LANGUAGE
            else -> null
        }

        if (area == null || kind == null) {
            for (a in listOf("catalog", "admin")) {
                for (k in listOf("controller", "model", "view", "language")) {
                    filtered.addElement(
                        LookupElementBuilder.create("$a/$k/")
                            .withIcon(AllIcons.Nodes.Plugin)
                            .withTypeText("event", true),
                    )
                }
            }
            return
        }

        val prefix = "${parts[0]}/${parts[1]}/"
        for (route in OcRoutes.listRoutes(OcContext(root, area), kind)) {
            for (moment in listOf("before", "after")) {
                filtered.addElement(
                    LookupElementBuilder.create("$prefix$route/$moment")
                        .withIcon(AllIcons.Nodes.Plugin)
                        .withTypeText("event", true),
                )
            }
        }
    }

    private fun addRoutes(result: CompletionResultSet, routes: List<String>, icon: Icon) {
        for (route in routes) {
            result.addElement(
                LookupElementBuilder.create(route)
                    .withIcon(icon)
                    .withTypeText("OpenCart", true),
            )
        }
    }

    private fun listLibraries(literal: StringLiteralExpression): List<String> {
        val ctx = OcProjectService.getInstance(literal.project).contextOf(literal) ?: return emptyList()
        val libDir = ctx.root.system?.findChild("library") ?: return emptyList()
        val out = LinkedHashSet<String>()
        fun walk(dir: VirtualFile, prefix: String) {
            for (child in dir.children) {
                if (child.isDirectory) walk(child, "$prefix${child.name}/")
                else if (child.extension == "php") out += "$prefix${child.nameWithoutExtension}"
            }
        }
        walk(libDir, "")
        return out.toList()
    }

    private fun addSettingKeys(literal: StringLiteralExpression, result: CompletionResultSet) {
        val root = OcProjectService.getInstance(literal.project).rootOf(
            literal.containingFile?.originalFile?.virtualFile,
        ) ?: return
        for (key in OcSettingsService.getInstance(literal.project).keys(root)) {
            result.addElement(
                LookupElementBuilder.create(key)
                    .withIcon(AllIcons.General.Settings)
                    .withTypeText("setting", true),
            )
        }
    }

    private fun addLanguageKeys(literal: StringLiteralExpression, result: CompletionResultSet) {
        val seen = HashSet<String>()
        for (file in OcLanguage.availableFiles(literal)) {
            for (entry in OcLanguage.entriesIn(literal.project, file)) {
                if (!seen.add(entry.key)) continue
                result.addElement(
                    LookupElementBuilder.create(entry.key)
                        .withIcon(AllIcons.FileTypes.Properties)
                        .withTypeText(entry.value?.take(60) ?: file.name, true),
                )
            }
        }
    }
}
