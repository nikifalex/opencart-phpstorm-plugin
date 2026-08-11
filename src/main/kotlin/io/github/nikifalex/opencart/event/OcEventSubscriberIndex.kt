package io.github.nikifalex.opencart.event

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.jetbrains.php.lang.PhpFileType

/**
 * Index of event registrations: `trigger` → `action`.
 *
 * Subscriptions live in the `event` table but are added by the module installation code, so
 * statically they are visible through `addEvent(...)` calls: positional arguments in 2.x/3.x and an
 * array in 4.x. Parsing works on the file text — PSI is unavailable during indexing and the call
 * shape is stable anyway.
 */
class OcEventSubscriberIndex : FileBasedIndexExtension<String, String>() {

    companion object {
        val KEY: ID<String, String> = ID.create("io.github.nikifalex.opencart.event.subscribers")

        /** addEvent('code', 'trigger', 'action') — 1.5–3.x */
        private val POSITIONAL = Regex(
            """add(?:Event|_event)\s*\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'""",
            RegexOption.IGNORE_CASE,
        )

        /** addEvent(['code' => ..., 'trigger' => ..., 'action' => ...]) — 4.x */
        private val ARRAY_CALL = Regex("""addEvent\s*\(\s*\[(.*?)]\s*\)""", RegexOption.DOT_MATCHES_ALL)
        private val ARRAY_TRIGGER = Regex("""'trigger'\s*=>\s*'([^']*)'""")
        private val ARRAY_ACTION = Regex("""'action'\s*=>\s*'([^']*)'""")

        /** Subscriptions for an event, honouring prefix comparison and `*` in the registered trigger. */
        fun subscribersOf(project: Project, eventName: String): Map<String, List<String>> {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.allScope(project)
            val result = LinkedHashMap<String, MutableList<String>>()
            index.processAllKeys(KEY, { registered ->
                if (matches(registered, eventName)) {
                    val actions = index.getValues(KEY, registered, scope)
                    if (actions.isNotEmpty()) {
                        result.getOrPut(registered) { ArrayList() }.addAll(actions)
                    }
                }
                true
            }, project)
            return result
        }

        /** Files where the subscription is declared. */
        fun filesOf(project: Project, trigger: String): Collection<VirtualFile> =
            FileBasedIndex.getInstance().getContainingFiles(KEY, trigger, GlobalSearchScope.allScope(project))

        fun allTriggers(project: Project): List<String> {
            val keys = ArrayList<String>()
            FileBasedIndex.getInstance().processAllKeys(KEY, { keys.add(it); true }, project)
            return keys
        }

        /** The engine compares like this: preg_match('/^' . trigger_with_wildcards . '/', $event). */
        private fun matches(registered: String, eventName: String): Boolean {
            val normalized = registered.substringAfter('/', registered) // the area is dropped on registration
            val event = eventName.substringAfter('/', eventName)
            if (!registered.contains('*') && !registered.contains('?')) {
                return event.startsWith(normalized) || eventName.startsWith(registered)
            }
            val pattern = Regex.escape(normalized)
                .replace("\\*", "\\E.*\\Q")
                .replace("\\?", "\\E.\\Q")
            return runCatching { Regex("^$pattern").containsMatchIn(event) }.getOrDefault(false)
        }
    }

    override fun getName(): ID<String, String> = KEY

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content ->
        val text = content.contentAsText.toString()
        if (!text.contains("addEvent")) return@DataIndexer emptyMap()

        val result = HashMap<String, String>()
        for (match in POSITIONAL.findAll(text)) {
            val trigger = match.groupValues[2]
            val action = match.groupValues[3]
            if (trigger.isNotEmpty()) result[trigger] = action
        }
        for (match in ARRAY_CALL.findAll(text)) {
            val body = match.groupValues[1]
            val trigger = ARRAY_TRIGGER.find(body)?.groupValues?.get(1) ?: continue
            val action = ARRAY_ACTION.find(body)?.groupValues?.get(1) ?: ""
            if (trigger.isNotEmpty()) result[trigger] = action
        }
        result
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getVersion(): Int = 2

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(PhpFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true
}
