<?php
class ModelCatalogProduct extends Model {
	public function getProduct($product_id) {
		return $this->db->query("SELECT * FROM product");
	}
	public function getProducts($data = array()) {}
}
