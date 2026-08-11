<?php
class ControllerExtensionTotalVoucher extends Controller {
	public function install() {
		$this->load->model('setting/event');
		$this->model_setting_event->addEvent('voucher', 'catalog/model/checkout/order/addOrderHistory/after', 'extension/total/voucher/send');
	}
}
