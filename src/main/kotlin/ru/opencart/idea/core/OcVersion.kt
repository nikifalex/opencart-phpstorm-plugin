package ru.opencart.idea.core

/**
 * Engine generation. Differences that matter to the plugin:
 *  - OC15/OC2: .tpl templates, classes without namespaces, a view route may include the theme;
 *  - OC3: .twig templates, classes without namespaces (ControllerCatalogProduct);
 *  - OC4: \Opencart\{Admin|Catalog}\... namespaces, method after a dot in the route, extension/<code>/.
 */
enum class OcVersion(val displayName: String) {
    OC15("OpenCart 1.5"),
    OC2("OpenCart 2.x"),
    OC3("OpenCart 3.x"),
    OC4("OpenCart 4.x");

    /** Templates are PHP .tpl in 1.5/2.x and Twig starting with 3.0. */
    val templateExtensions: List<String>
        get() = when (this) {
            OC15, OC2 -> listOf("tpl", "twig")
            OC3, OC4 -> listOf("twig", "tpl")
        }

    /** Namespaced classes only appeared in 4.x. */
    val hasNamespaces: Boolean
        get() = this == OC4

    /** In 4.x a route may carry the method name after a dot: catalog/product.list */
    val methodInRoute: Boolean
        get() = this == OC4
}
