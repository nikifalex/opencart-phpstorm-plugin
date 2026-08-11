<?php
$registry->set('load', $loader);
$db = new \Opencart\System\Library\DB('mysqli', 'localhost', 'user', 'pass', 'db', '3306');
$registry->set('db', $db);
