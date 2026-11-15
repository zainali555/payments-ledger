package com.zainali.ledger.reconciliation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only: {@code /admin/**} is restricted in the filter chain (DECISIONS 14). */
@RestController
class ReconciliationController {

	private final ReconciliationService service;

	ReconciliationController(ReconciliationService service) {
		this.service = service;
	}

	@PostMapping("/admin/reconciliation")
	ReconciliationReport reconcile() {
		return service.reconcile();
	}

}
