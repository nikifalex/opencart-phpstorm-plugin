<?php
namespace Opencart\System\Library;
class DB {
	public function query(string $sql) {}
	public function escape(string $value): string { return $value; }
}
