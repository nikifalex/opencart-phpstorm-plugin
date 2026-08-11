package ru.opencart.idea.core

/**
 * Application side (DIR_APPLICATION). It decides where a route resolves to: called from an admin
 * controller, load->model('catalog/product') loads the admin model, and the catalog one from catalog.
 */
enum class OcArea(val configApplication: String) {
    ADMIN("Admin"),
    CATALOG("Catalog"),

    /** system/, install/ and anything outside admin|catalog — routes from there are ambiguous. */
    SYSTEM("");

    val isApplication: Boolean get() = this == ADMIN || this == CATALOG
}
