<?php
final class Loader {
	public function controller($route, $data = array()) {}
	public function model($route) {}
	public function view($route, $data = array()) {}
	public function language($route, $key = '') {}
	public function library($route) {}
}
class Controller {
	protected $registry;
	public function __get($key) { return $this->registry->get($key); }
}
class Model {
	protected $registry;
	public function __get($key) { return $this->registry->get($key); }
}
class DB {
	public function query($sql) {}
	public function escape($value) {}
}
class Url {
	public function link($route, $args = '', $secure = false) {}
}
class Language {
	public function get($key) {}
	public function load($route) {}
}
