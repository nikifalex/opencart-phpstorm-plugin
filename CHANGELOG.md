# Changelog

## 1.1

- SQL injection in `$this->db->query()` now covers the whole concatenated query instead of each string
  separately, so a normal OpenCart query is no longer reported as broken SQL. `DB_PREFIX` is replaced
  by the table prefix from config.php, and other expressions become placeholders that keep the
  statement parseable.
- Queries written with interpolation (`"select * from {$table} where id = " . (int)$id`) keep their
  highlighting as well: the interpolated parts become placeholders chosen by their position, so a
  table name reads as a name and a value as a bound parameter.
- With several stores in one project each query uses the prefix of the installation its file belongs
  to, rather than whichever `DB_PREFIX` the index happens to resolve.

## 0.1.1

- Renamed the plugin id, the Kotlin package and the Gradle project to match the repository name:
  `io.github.nikifalex.opencart` and `opencart-phpstorm-plugin`. The distribution archive is now
  `opencart-phpstorm-plugin-<version>.zip`; remove the old `opencart-idea` directory from the IDE
  plugins folder when upgrading a manual installation.

## 0.1.0

First release.

- Type inference for the Registry magic: `$this->db`, `$this->config`, `$this->cart`, `$this->session`
  and the other registry objects — the map is read from the store itself, not from a hardcoded table.
- Type inference for loaded models: `$this->model_catalog_product->getProduct()`.
- Route navigation and completion: `load->controller/model/view/language/library`, `url->link`,
  `template->render`, `new Action()`; in 4.x a route carrying a method after a dot.
- Language keys: navigation, completion showing translated values, inspection for missing keys.
- Completion for `config->get()` setting keys.
- Twig: completion for variables coming from the controller `$data`, navigation to their assignment.
- OCMOD/vQmod: navigation to target files (wildcards included) and a check that `<search>` matches.
- Events: `trigger` → wrapped method and `action` → handler navigation, gutter icons, name completion,
  subscription index built from `addEvent()` in both call flavours.
- OpenCart tool window: tree of routes and event subscriptions.
- Inspections with quick fixes: unknown route, model used without `load->model()`, unknown language key.
- Module generator producing the layout of the detected store version.
- SQL highlighting inside `$this->db->query()`.
- Go to Symbol by route, gutter icons from a controller to its template and language files.
