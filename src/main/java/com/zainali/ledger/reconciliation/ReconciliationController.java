package com.zainali.ledger.reconciliation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zainali.ledger.common.ApiDocs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** ADMIN only: {@code /admin/**} is restricted in the filter chain (DECISIONS 14). */
@RestController
@Tag(name = ApiDocs.TAG_ADMIN)
class ReconciliationController {

	private final ReconciliationService service;

	ReconciliationController(ReconciliationService service) {
		this.service = service;
	}

	@Operation(summary = "Run a reconciliation check (ADMIN)",
			description = "Read-only. Checks that every transfer's signed entries sum to zero and that every cached balance equals "
					+ "the sum of its entries. Returns 200 whether or not it found mismatches; `consistent` says which. No Idempotency-Key needed.")
	@ApiResponse(responseCode = "200", description = "The report",
			content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "Consistent", value = ApiDocs.RECONCILIATION_RESPONSE),
					@ExampleObject(name = "Mismatch found", value = ApiDocs.RECONCILIATION_MISMATCH_RESPONSE) }))
	@PostMapping("/admin/reconciliation")
	ReconciliationReport reconcile() {
		return service.reconcile();
	}

}
