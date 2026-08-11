<?php
namespace Opencart\System\Engine;
class Loader {
	public function controller(string $route, mixed ...$args): mixed { return ''; }
	public function model(string $route): void {}
	public function view(string $route, array $data = [], string $code = ''): string { return ''; }
	public function language(string $route, string $prefix = '', string $code = ''): array { return []; }
}
class Controller {
	public function __get(string $key): object { return new \stdClass(); }
}
class Model {
	public function __get(string $key): object { return new \stdClass(); }
}
