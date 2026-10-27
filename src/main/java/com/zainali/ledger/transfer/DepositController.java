package com.zainali.ledger.transfer;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.zainali.ledger.common.ApiHeaders;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.idempotency.IdempotentResult;

import jakarta.validation.Valid;

@RestController
class DepositController {

	private final DepositService service;

	DepositController(DepositService service) {
		this.service = service;
	}

	/** The header comes first so a missing Idempotency-Key is reported before the body is read. */
	@PostMapping("/accounts/{accountId}/deposits")
	ResponseEntity<TransferResponse> deposit(@RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
			@PathVariable long accountId, @Valid @RequestBody DepositRequest request, Authentication authentication) {
		IdempotentResult<TransferResponse> result = service.deposit(Caller.from(authentication), idempotencyKey,
				accountId, request.amount(), request.currency());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
		if (result.replayed()) {
			response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
		}
		return response.body(result.body());
	}

}
