package io.github.nikifalex.opencart.twig

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.jetbrains.twig.TwigTokenTypes
import io.github.nikifalex.opencart.core.OcProjectService

/**
 * Completion for variables in Twig templates: `{{ head<caret> }}` offers everything the controller put
 * into `$data`. Without it OpenCart templates are written blind — the IDE knows nothing about them.
 */
class OcTwigCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(TwigTokenTypes.IDENTIFIER),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val project = parameters.position.project
                    if (!OcProjectService.getInstance(project).isOpenCartProject()) return
                    val file = parameters.originalFile.virtualFile ?: return

                    for (variable in OcTwigSupport.variablesFor(project, file)) {
                        result.addElement(
                            LookupElementBuilder.create(variable.name)
                                .withIcon(AllIcons.Nodes.Variable)
                                .withTypeText(variable.file.name, true),
                        )
                    }
                }
            },
        )
    }
}
