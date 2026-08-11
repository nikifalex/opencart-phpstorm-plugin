package io.github.nikifalex.opencart.generator

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.nikifalex.opencart.core.OcArea
import io.github.nikifalex.opencart.core.OcRoot
import io.github.nikifalex.opencart.core.OcVersion
import java.nio.charset.StandardCharsets

/** What exactly gets generated. */
data class OcModuleRequest(
    val root: OcRoot,
    val code: String,
    val title: String,
    val withCatalog: Boolean,
)

/**
 * Generates the skeleton of an extension module: an admin controller that saves its settings, language
 * files for every installed language, a template and optionally the catalog part.
 *
 * The layout differs between versions: in 4.x the module lives in extension/<code>/, in 2.3/3.x in
 * admin/controller/extension/module/, in 1.5/2.0 in admin/controller/module/.
 */
object OcModuleGenerator {

    fun generate(request: OcModuleRequest): List<VirtualFile> {
        val created = ArrayList<VirtualFile>()
        val root = request.root
        val code = request.code

        when (root.version) {
            OcVersion.OC4 -> {
                val base = VfsUtil.createDirectoryIfMissing(root.dir, "extension/$code") ?: return emptyList()
                write(base, "install.json", installJson(request))?.let { created += it }
                write(base, "admin/controller/module/$code.php", oc4AdminController(request))?.let { created += it }
                write(base, "admin/view/template/module/$code.twig", oc4Template(request))?.let { created += it }
                for (lang in languageCodes(root, OcArea.ADMIN)) {
                    write(base, "admin/language/$lang/module/$code.php", languageFile(request))?.let { created += it }
                }
                if (request.withCatalog) {
                    write(base, "catalog/controller/module/$code.php", oc4CatalogController(request))?.let { created += it }
                    write(base, "catalog/view/template/module/$code.twig", "<div>{{ heading_title }}</div>\n")?.let { created += it }
                }
            }
            else -> {
                val admin = root.admin ?: return emptyList()
                val prefix = legacyPrefix(root)
                val route = "$prefix$code"
                write(admin, "controller/$route.php", legacyAdminController(request, route))?.let { created += it }
                write(admin, templatePath(admin, route, root), legacyTemplate(request))?.let { created += it }
                for (lang in languageCodes(root, OcArea.ADMIN)) {
                    write(admin, "language/$lang/$route.php", languageFile(request))?.let { created += it }
                }
                if (request.withCatalog) {
                    val catalog = root.catalog
                    if (catalog != null) {
                        write(catalog, "controller/$route.php", legacyCatalogController(request, route))?.let { created += it }
                        val ext = root.version.templateExtensions.first()
                        write(catalog, "view/theme/default/template/$route.$ext", legacyTemplate(request))?.let { created += it }
                        for (lang in languageCodes(root, OcArea.CATALOG)) {
                            write(catalog, "language/$lang/$route.php", languageFile(request))?.let { created += it }
                        }
                    }
                }
            }
        }
        return created
    }

    /** In 2.3/3.x modules live in extension/module, in older versions simply in module. */
    private fun legacyPrefix(root: OcRoot): String {
        val hasExtensionDir = root.admin?.findFileByRelativePath("controller/extension/module") != null
        return if (hasExtensionDir) "extension/module/" else "module/"
    }

    private fun templatePath(admin: VirtualFile, route: String, root: OcRoot): String {
        val ext = root.version.templateExtensions.first()
        return if (admin.findChild("view")?.findChild("template") != null) {
            "view/template/$route.$ext"
        } else {
            "view/theme/default/template/$route.$ext"
        }
    }

    private fun languageCodes(root: OcRoot, area: OcArea): List<String> =
        root.appDir(area)?.findChild("language")?.children
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: listOf("en-gb")

    private fun write(base: VirtualFile, relPath: String, content: String): VirtualFile? = try {
        val dirPath = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val dir = if (dirPath.isEmpty()) base else VfsUtil.createDirectoryIfMissing(base, dirPath)
        dir?.findChild(name) ?: dir?.createChildData(this, name)?.also {
            it.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }

    // --- file contents ----------------------------------------------------------

    private fun classSuffix(code: String): String =
        code.split('_', '-').filter { it.isNotEmpty() }.joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }

    private fun languageFile(request: OcModuleRequest): String = """
        <?php
        // Heading
        ${'$'}_['heading_title'] = '${request.title}';

        // Text
        ${'$'}_['text_extension'] = 'Extensions';
        ${'$'}_['text_success']   = 'Success: You have modified the module!';
        ${'$'}_['text_edit']      = 'Edit Module';

        // Entry
        ${'$'}_['entry_status'] = 'Status';

        // Error
        ${'$'}_['error_permission'] = 'Warning: You do not have permission to modify this module!';
    """.trimIndent() + "\n"

    private fun legacyTemplate(request: OcModuleRequest): String = """
        {{ header }}{{ column_left }}
        <div id="content">
          <div class="page-header">
            <div class="container-fluid">
              <h1>{{ heading_title }}</h1>
            </div>
          </div>
          <div class="container-fluid">
            <form action="{{ action }}" method="post" enctype="multipart/form-data" class="form-horizontal">
              <div class="form-group">
                <label class="col-sm-2 control-label" for="input-status">{{ entry_status }}</label>
                <div class="col-sm-10">
                  <select name="module_${request.code}_status" id="input-status" class="form-control">
                    <option value="1"{% if module_status %} selected{% endif %}>{{ text_enabled }}</option>
                    <option value="0"{% if not module_status %} selected{% endif %}>{{ text_disabled }}</option>
                  </select>
                </div>
              </div>
            </form>
          </div>
        </div>
        {{ footer }}
    """.trimIndent() + "\n"

    private fun oc4Template(request: OcModuleRequest): String = legacyTemplate(request)

    private fun legacyAdminController(request: OcModuleRequest, route: String): String {
        val cls = "Controller" + classSuffix(route.replace('/', '_'))
        val code = request.code
        return """
            <?php
            class $cls extends Controller {
            ${'\t'}private ${'$'}error = array();

            ${'\t'}public function index() {
            ${'\t'}${'\t'}${'$'}this->load->language('$route');
            ${'\t'}${'\t'}${'$'}this->document->setTitle(${'$'}this->language->get('heading_title'));
            ${'\t'}${'\t'}${'$'}this->load->model('setting/setting');

            ${'\t'}${'\t'}if ((${'$'}this->request->server['REQUEST_METHOD'] == 'POST') && ${'$'}this->validate()) {
            ${'\t'}${'\t'}${'\t'}${'$'}this->model_setting_setting->editSetting('module_$code', ${'$'}this->request->post);
            ${'\t'}${'\t'}${'\t'}${'$'}this->session->data['success'] = ${'$'}this->language->get('text_success');
            ${'\t'}${'\t'}${'\t'}${'$'}this->response->redirect(${'$'}this->url->link('marketplace/extension', 'user_token=' . ${'$'}this->session->data['user_token'] . '&type=module', true));
            ${'\t'}${'\t'}}

            ${'\t'}${'\t'}${'$'}data['action'] = ${'$'}this->url->link('$route', 'user_token=' . ${'$'}this->session->data['user_token'], true);
            ${'\t'}${'\t'}${'$'}data['module_status'] = ${'$'}this->config->get('module_${code}_status');

            ${'\t'}${'\t'}${'$'}data['header'] = ${'$'}this->load->controller('common/header');
            ${'\t'}${'\t'}${'$'}data['column_left'] = ${'$'}this->load->controller('common/column_left');
            ${'\t'}${'\t'}${'$'}data['footer'] = ${'$'}this->load->controller('common/footer');

            ${'\t'}${'\t'}${'$'}this->response->setOutput(${'$'}this->load->view('$route', ${'$'}data));
            ${'\t'}}

            ${'\t'}protected function validate() {
            ${'\t'}${'\t'}if (!${'$'}this->user->hasPermission('modify', '$route')) {
            ${'\t'}${'\t'}${'\t'}${'$'}this->error['warning'] = ${'$'}this->language->get('error_permission');
            ${'\t'}${'\t'}}

            ${'\t'}${'\t'}return !${'$'}this->error;
            ${'\t'}}
            }
        """.trimIndent() + "\n"
    }

    private fun legacyCatalogController(request: OcModuleRequest, route: String): String {
        val cls = "Controller" + classSuffix(route.replace('/', '_'))
        return """
            <?php
            class $cls extends Controller {
            ${'\t'}public function index(${'$'}setting) {
            ${'\t'}${'\t'}${'$'}this->load->language('$route');

            ${'\t'}${'\t'}${'$'}data['heading_title'] = ${'$'}this->language->get('heading_title');

            ${'\t'}${'\t'}return ${'$'}this->load->view('$route', ${'$'}data);
            ${'\t'}}
            }
        """.trimIndent() + "\n"
    }

    private fun oc4AdminController(request: OcModuleRequest): String {
        val cls = classSuffix(request.code)
        val ns = "Opencart\\Admin\\Controller\\Extension\\${classSuffix(request.code)}\\Module"
        val code = request.code
        return """
            <?php
            namespace $ns;
            class $cls extends \Opencart\System\Engine\Controller {
            ${'\t'}public function index(): void {
            ${'\t'}${'\t'}${'$'}this->load->language('extension/$code/module/$code');
            ${'\t'}${'\t'}${'$'}this->document->setTitle(${'$'}this->language->get('heading_title'));

            ${'\t'}${'\t'}${'$'}data['save'] = ${'$'}this->url->link('extension/$code/module/$code.save', 'user_token=' . ${'$'}this->session->data['user_token']);
            ${'\t'}${'\t'}${'$'}data['module_status'] = ${'$'}this->config->get('module_${code}_status');

            ${'\t'}${'\t'}${'$'}data['header'] = ${'$'}this->load->controller('common/header');
            ${'\t'}${'\t'}${'$'}data['column_left'] = ${'$'}this->load->controller('common/column_left');
            ${'\t'}${'\t'}${'$'}data['footer'] = ${'$'}this->load->controller('common/footer');

            ${'\t'}${'\t'}${'$'}this->response->setOutput(${'$'}this->load->view('extension/$code/module/$code', ${'$'}data));
            ${'\t'}}

            ${'\t'}public function save(): void {
            ${'\t'}${'\t'}${'$'}this->load->language('extension/$code/module/$code');

            ${'\t'}${'\t'}${'$'}json = [];

            ${'\t'}${'\t'}if (!${'$'}this->user->hasPermission('modify', 'extension/$code/module/$code')) {
            ${'\t'}${'\t'}${'\t'}${'$'}json['error'] = ${'$'}this->language->get('error_permission');
            ${'\t'}${'\t'}}

            ${'\t'}${'\t'}if (!${'$'}json) {
            ${'\t'}${'\t'}${'\t'}${'$'}this->load->model('setting/setting');
            ${'\t'}${'\t'}${'\t'}${'$'}this->model_setting_setting->editSetting('module_$code', ${'$'}this->request->post);

            ${'\t'}${'\t'}${'\t'}${'$'}json['success'] = ${'$'}this->language->get('text_success');
            ${'\t'}${'\t'}}

            ${'\t'}${'\t'}${'$'}this->response->addHeader('Content-Type: application/json');
            ${'\t'}${'\t'}${'$'}this->response->setOutput(json_encode(${'$'}json));
            ${'\t'}}
            }
        """.trimIndent() + "\n"
    }

    private fun oc4CatalogController(request: OcModuleRequest): String {
        val cls = classSuffix(request.code)
        val ns = "Opencart\\Catalog\\Controller\\Extension\\${classSuffix(request.code)}\\Module"
        val code = request.code
        return """
            <?php
            namespace $ns;
            class $cls extends \Opencart\System\Engine\Controller {
            ${'\t'}public function index(array ${'$'}setting): string {
            ${'\t'}${'\t'}${'$'}this->load->language('extension/$code/module/$code');

            ${'\t'}${'\t'}${'$'}data['heading_title'] = ${'$'}this->language->get('heading_title');

            ${'\t'}${'\t'}return ${'$'}this->load->view('extension/$code/module/$code', ${'$'}data);
            ${'\t'}}
            }
        """.trimIndent() + "\n"
    }

    private fun installJson(request: OcModuleRequest): String = """
        {
            "name": "${request.title}",
            "version": "1.0",
            "author": "",
            "link": ""
        }
    """.trimIndent() + "\n"
}
