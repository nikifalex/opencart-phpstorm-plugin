# OpenCart plugin for PhpStorm

[![Build](https://github.com/nikifalex/opencart-phpstorm-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/nikifalex/opencart-phpstorm-plugin/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/nikifalex/opencart-phpstorm-plugin?include_prereleases)](https://github.com/nikifalex/opencart-phpstorm-plugin/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

PhpStorm plugin for **OpenCart 1.5 / 2.x / 3.x / 4.x and ocStore**. It teaches the IDE the parts of
OpenCart that static analysis cannot see: string routes, the Registry magic, loaded models, language
keys, Twig variables, OCMOD modifications and the event system.

Think of it as Laravel Idea, but for OpenCart.

## Installation

1. Download `opencart-phpstorm-plugin-<version>.zip` from the [Releases](https://github.com/nikifalex/opencart-phpstorm-plugin/releases) page.
2. In PhpStorm: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the archive.
3. Restart the IDE.

Requires PhpStorm **2026.2** or newer. The plugin activates itself in any project where an OpenCart
installation is found (detected by `system/engine/loader.php` and `catalog/`), including stores that
live in a subdirectory such as `public_html/` and stores whose admin directory has been renamed.

## Features

### Type inference

- `$this->model_catalog_product->getProduct()` resolves to the loaded model, honouring the
  admin/catalog side and the 4.x namespace layout. The class is taken from the actual file rather
  than by name: in 2.x/3.x the admin and catalog models declare the very same class name.
- `$this->db`, `$this->config`, `$this->cart`, `$this->session`, `$this->user`, … — the registry map
  is read from `system/framework.php`, `index.php` and the startup controllers of the store at hand,
  so custom libraries loaded via `$this->load->library()` are typed as well.

### Navigation and completion

- `load->controller()`, `load->model()`, `load->view()`, `load->language()`, `load->library()`,
  `url->link()`, `template->render()`, `new Action()`. In 4.x a route carrying a method
  (`catalog/product.list`) jumps straight to that method; in 2.x/3.x the trailing segment is
  recognised as a method too.
- `language->get('text_success')` → the `$_['text_success']` line in the language file; completion
  shows the translated value next to every key.
- `config->get('config_...')` — completion for setting keys.
- Go to Symbol (<kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>N</kbd>) finds controllers and
  models by their route.
- Gutter icons on a controller class lead to the template and the language files of the same route.

### Twig

- Completion for template variables based on `$data[...]` of the matching controller.
- Ctrl+click on `{{ heading_title }}` jumps to where the variable is assigned, or to the language key.

### OCMOD / vQmod

- Ctrl+click from `<file path="...">` to the target file, wildcard paths included.
- Inspection: the target is missing, or the `<search>` fragment is not present in the target file —
  meaning the modification will silently not apply. A common reason for "the module stopped working
  after the upgrade".

### Events

- Ctrl+click on an event name (`'catalog/model/checkout/order/addHistory/after'`) opens the method the
  event wraps; on a handler route (`'extension/total/voucher/send'`) it opens the handler itself.
- Gutter icons: on a method — who subscribes to it; on a handler — where the subscription is registered.
- Completion for event names driven by the prefix you typed (`catalog/model/` offers real model routes
  plus `before`/`after`).
- Subscriptions are indexed from `addEvent(...)` calls in both flavours: positional (1.5–3.x) and array
  (4.x). Matching mirrors the engine: the area segment is dropped, comparison is prefix based and
  supports `*` and `?`.

### OpenCart tool window

- A tree of store → admin/catalog → controllers / models / templates / language files with counts,
  plus an "Events" branch listing `trigger → action` pairs.
- Double click opens the file, and for handlers the exact method. Type to search the tree. The tree is
  built in the background, so a large store does not freeze the UI.

### Inspections with quick fixes

- Unknown route → create the controller, model, template or language file (language files are created
  for every installed language at once).
- `$this->model_x_y` used without `load->model('x/y')` → insert the missing load call.
- Unknown language key → add the key to every language.

### Also

- SQL highlighting inside `$this->db->query(...)`.
- Generator: **Tools → New OpenCart Module…** creates an admin controller with settings saving, a
  template, language files for every language and the catalog part — laid out for the store version
  (`extension/<code>/` for 4.x, `admin/controller/extension/module/` for 2.3/3.x, `module/` for
  1.5/2.0).

## Development

```bash
./gradlew build          # compile and test
./gradlew test           # 22 integration tests against synthetic OC3 and OC4 stores
./gradlew buildPlugin    # build/distributions/opencart-phpstorm-plugin-<version>.zip
./gradlew runIde         # PhpStorm sandbox with the plugin installed
```

By default Gradle downloads a PhpStorm distribution from the JetBrains repository (~1.5 GB). To build
against an IDE you already have, create `local.properties` (not under version control):

```properties
phpstormLocalPath=/home/user/.local/share/JetBrains/Toolbox/apps/phpstorm-2
```

Installing the built archive into a local IDE by hand:

```bash
rm -rf ~/.local/share/JetBrains/PhpStorm2026.2/opencart-phpstorm-plugin
unzip -o -q build/distributions/opencart-phpstorm-plugin-0.1.1.zip -d ~/.local/share/JetBrains/PhpStorm2026.2/
```

Releases are cut by tag: `git tag v0.1.0 && git push origin v0.1.0`. The workflow builds the archive
and publishes it under Releases with the notes taken from the top section of `CHANGELOG.md`.

## Project layout

| Package | Responsibility |
|---|---|
| `core` | store detection, versions, admin/catalog areas, route ↔ file mapping, registry, settings |
| `type` | PhpTypeProvider for models and registry objects |
| `reference`, `completion` | references and completion inside PHP strings |
| `lang` | language files and keys |
| `twig` | controller ↔ template link |
| `ocmod` | OCMOD/vQmod references and applicability checks |
| `event` | event name parsing, subscription index, trigger ↔ action navigation |
| `toolwindow` | routes and events panel |
| `inspection`, `quickfix` | inspections and quick fixes |
| `generator` | module and file skeleton generation |
| `navigation`, `marker`, `sql` | Go to Symbol, gutter icons, SQL injection |

Synthetic stores used by the tests live in `src/test/testData/oc3` and `src/test/testData/oc4`.

## Known limitations

- In 2.x/3.x `admin/model/catalog/product.php` and `catalog/model/catalog/product.php` declare classes
  with identical names, so "Go to Declaration" sometimes offers both. Completion is unaffected.
- Event subscriptions are only visible where they are registered in code (`addEvent`). Events inserted
  straight into the `event` table cannot be seen statically.

## Author

Built by [nikifalex](https://github.com/nikifalex). Questions, bug reports and feature requests are
welcome in [GitHub issues](https://github.com/nikifalex/opencart-phpstorm-plugin/issues) or in
Telegram: [@t523651](https://t.me/t523651).

## License

[MIT](LICENSE)
