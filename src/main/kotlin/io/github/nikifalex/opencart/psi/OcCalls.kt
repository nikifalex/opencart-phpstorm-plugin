package io.github.nikifalex.opencart.psi

import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import io.github.nikifalex.opencart.core.OcPhpUtil

/** What a string literal means in OpenCart code. */
enum class OcStringRole {
    /** Controller route: load->controller(), url->link(), new Action() */
    CONTROLLER_ROUTE,
    MODEL_ROUTE,
    VIEW_ROUTE,
    LANGUAGE_ROUTE,
    LIBRARY_ROUTE,

    /** Language file key: language->get() */
    LANGUAGE_KEY,

    /** Setting key: config->get()/set() */
    CONFIG_KEY,

    /** Event name: event->trigger()/register(), addEvent(..., trigger, ...) */
    EVENT_NAME,

    /** Event handler — a controller route with a method: addEvent(..., ..., action) */
    EVENT_ACTION,
}

/**
 * Recognises what a string literal is: a route, a language key, a setting or an event.
 * It keys off the `$this-><field>-><method>(...)` call shape, because both load and url are registry
 * objects rather than plain classes.
 */
object OcCalls {

    fun roleOf(literal: StringLiteralExpression): OcStringRole? {
        eventArrayRole(literal)?.let { return it }

        val call = OcPhpUtil.enclosingCall(literal)
        if (call != null) {
            return callRole(call, literal)
        }
        // new Action('catalog/product') is the entry point of the router and of event handlers
        val newExpression = literal.parent?.parent as? NewExpression
        if (newExpression != null && newExpression.classReference?.name == "Action") {
            return OcStringRole.CONTROLLER_ROUTE
        }
        return null
    }

    private fun callRole(call: MethodReference, literal: StringLiteralExpression): OcStringRole? {
        val method = call.name ?: return null
        val index = OcPhpUtil.argumentIndex(literal)

        // addEvent($code, $trigger, $action) in 3.x, called on model_setting_event
        if (method == "addEvent" || method == "editEvent") {
            return when (index) {
                1 -> OcStringRole.EVENT_NAME
                2 -> OcStringRole.EVENT_ACTION
                else -> null
            }
        }
        if (method == "getEventByCode" || method == "deleteEventByCode") return null

        if (index != 0) return null
        val holder = OcPhpUtil.thisFieldName(call.classReference) ?: fallbackHolder(call) ?: return null

        return when (holder) {
            "load", "loader" -> when (method) {
                "controller" -> OcStringRole.CONTROLLER_ROUTE
                "model" -> OcStringRole.MODEL_ROUTE
                "view" -> OcStringRole.VIEW_ROUTE
                "language" -> OcStringRole.LANGUAGE_ROUTE
                "library" -> OcStringRole.LIBRARY_ROUTE
                else -> null
            }
            "url" -> if (method == "link") OcStringRole.CONTROLLER_ROUTE else null
            "language" -> when (method) {
                "get" -> OcStringRole.LANGUAGE_KEY
                "load" -> OcStringRole.LANGUAGE_ROUTE
                else -> null
            }
            "config" -> if (method in setOf("get", "set", "has")) OcStringRole.CONFIG_KEY else null
            "event" -> if (method in setOf("trigger", "register", "unregister")) OcStringRole.EVENT_NAME else null
            "template" -> if (method == "render") OcStringRole.VIEW_ROUTE else null
            else -> null
        }
    }

    /**
     * 4.x passes the event as an array: `addEvent(['code' => ..., 'trigger' => ..., 'action' => ...])`,
     * so the role is decided by the key holding the literal.
     */
    private fun eventArrayRole(literal: StringLiteralExpression): OcStringRole? {
        val hashElement = literal.parent as? ArrayHashElement ?: return null
        if (hashElement.value !== literal) return null
        val array = hashElement.parent as? ArrayCreationExpression ?: return null
        val call = array.parent?.parent as? MethodReference ?: return null
        if (call.name != "addEvent" && call.name != "editEvent") return null

        return when ((hashElement.key as? StringLiteralExpression)?.contents) {
            "trigger" -> OcStringRole.EVENT_NAME
            "action" -> OcStringRole.EVENT_ACTION
            else -> null
        }
    }

    /**
     * Calls like `$loader->model(...)` or `$registry->get('load')->controller(...)` inside the core
     * and the startup scripts, where there is no `$this`.
     */
    private fun fallbackHolder(call: MethodReference): String? {
        val ref = call.classReference?.text ?: return null
        return when {
            ref.endsWith("load") || ref.endsWith("loader") -> "load"
            ref.endsWith("url") -> "url"
            ref.endsWith("language") -> "language"
            ref.endsWith("config") -> "config"
            ref.endsWith("event") -> "event"
            else -> null
        }
    }
}
