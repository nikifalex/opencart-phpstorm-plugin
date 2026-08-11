package ru.opencart.idea.event

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex

/** An event subscription: what is registered and which route it calls. */
data class OcEventSubscription(val trigger: String, val action: String)

/**
 * Cache of every subscription in the project. The line marker asks for subscribers of every method in
 * a file, so the index is walked once and the matching happens in memory afterwards.
 */
@Service(Service.Level.PROJECT)
class OcEventService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): OcEventService = project.service()
    }

    fun subscriptions(): List<OcEventSubscription> {
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                collect(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
    }

    /** Subscriptions that will fire on this event (prefix comparison, `*` supported). */
    fun subscribersOf(eventName: String): List<OcEventSubscription> =
        subscriptions().filter { matches(it.trigger, eventName) }

    private fun collect(): List<OcEventSubscription> {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val result = ArrayList<OcEventSubscription>()
        try {
            index.processAllKeys(OcEventSubscriberIndex.KEY, { trigger ->
                for (action in index.getValues(OcEventSubscriberIndex.KEY, trigger, scope)) {
                    result += OcEventSubscription(trigger, action)
                }
                true
            }, project)
        } catch (e: Exception) {
            return emptyList()
        }
        return result
    }

    /** The engine does preg_match('/^' . trigger . '/', $event) with the area already stripped. */
    private fun matches(registered: String, eventName: String): Boolean {
        val normalized = stripArea(registered)
        val event = stripArea(eventName)
        if (!registered.contains('*') && !registered.contains('?')) {
            return event.startsWith(normalized)
        }
        val pattern = Regex.escape(normalized)
            .replace("\\*", "\\E.*\\Q")
            .replace("\\?", "\\E.\\Q")
        return runCatching { Regex("^$pattern").containsMatchIn(event) }.getOrDefault(false)
    }

    private fun stripArea(name: String): String {
        val head = name.substringBefore('/')
        return if (head == "catalog" || head == "admin") name.substringAfter('/') else name
    }
}
