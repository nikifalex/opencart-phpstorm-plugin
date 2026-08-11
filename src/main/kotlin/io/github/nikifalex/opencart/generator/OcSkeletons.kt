package io.github.nikifalex.opencart.generator

import io.github.nikifalex.opencart.core.OcArea
import io.github.nikifalex.opencart.core.OcContext
import io.github.nikifalex.opencart.core.OcRouteKind
import io.github.nikifalex.opencart.core.OcVersion

/**
 * OpenCart file skeletons. Class naming differs between versions:
 *  - 1.5/2.x/3.x: ControllerCatalogProduct / ModelCatalogProduct without namespaces;
 *  - 4.x: namespace Opencart\Admin\Controller\Catalog plus class Product.
 */
object OcSkeletons {

    /** ControllerCatalogProduct for the route catalog/product. */
    fun legacyClassName(prefix: String, route: String): String =
        prefix + route.split('/', '_', '-').filter { it.isNotEmpty() }.joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }

    /** The "namespace, class name" pair for 4.x. */
    fun namespacedClass(ctx: OcContext, kind: OcRouteKind, route: String): Pair<String, String> {
        val app = if (ctx.area == OcArea.ADMIN) "Admin" else "Catalog"
        val kindPart = when (kind) {
            OcRouteKind.CONTROLLER -> "Controller"
            OcRouteKind.MODEL -> "Model"
            else -> "Controller"
        }
        val segments = route.split('/').filter { it.isNotEmpty() }.map { segment ->
            segment.split('_', '-').filter { it.isNotEmpty() }
                .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        }
        val className = segments.lastOrNull() ?: "Index"
        val ns = (listOf("Opencart", app, kindPart) + segments.dropLast(1)).joinToString("\\")
        return ns to className
    }

    fun controller(ctx: OcContext, route: String): String = when (ctx.version) {
        OcVersion.OC4 -> {
            val (ns, cls) = namespacedClass(ctx, OcRouteKind.CONTROLLER, route)
            """
            <?php
            namespace $ns;
            class $cls extends \Opencart\System\Engine\Controller {
            ${'\t'}public function index(): void {
            ${'\t'}${'\t'}${'$'}this->load->language('$route');

            ${'\t'}${'\t'}${'$'}data['header'] = ${'$'}this->load->controller('common/header');
            ${'\t'}${'\t'}${'$'}data['footer'] = ${'$'}this->load->controller('common/footer');

            ${'\t'}${'\t'}${'$'}this->response->setOutput(${'$'}this->load->view('$route', ${'$'}data));
            ${'\t'}}
            }
            """.trimIndent() + "\n"
        }
        else -> {
            val cls = legacyClassName("Controller", route)
            """
            <?php
            class $cls extends Controller {
            ${'\t'}public function index() {
            ${'\t'}${'\t'}${'$'}this->load->language('$route');

            ${'\t'}${'\t'}${'$'}data['header'] = ${'$'}this->load->controller('common/header');
            ${'\t'}${'\t'}${'$'}data['footer'] = ${'$'}this->load->controller('common/footer');

            ${'\t'}${'\t'}${'$'}this->response->setOutput(${'$'}this->load->view('$route', ${'$'}data));
            ${'\t'}}
            }
            """.trimIndent() + "\n"
        }
    }

    fun model(ctx: OcContext, route: String): String = when (ctx.version) {
        OcVersion.OC4 -> {
            val (ns, cls) = namespacedClass(ctx, OcRouteKind.MODEL, route)
            """
            <?php
            namespace $ns;
            class $cls extends \Opencart\System\Engine\Model {
            }
            """.trimIndent() + "\n"
        }
        else -> {
            val cls = legacyClassName("Model", route)
            """
            <?php
            class $cls extends Model {
            }
            """.trimIndent() + "\n"
        }
    }

    fun languageFile(route: String): String =
        "<?php\n// Heading\n\$_['heading_title'] = '';\n"

    fun template(ctx: OcContext, route: String): String = when (ctx.version) {
        OcVersion.OC15, OcVersion.OC2 -> "<?php echo \$header; ?>\n\n<?php echo \$footer; ?>\n"
        else -> "{{ header }}\n\n{{ footer }}\n"
    }
}
