package com.zainali.ledger.transfer;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.zainali.ledger.common.ApiDocs;
import com.zainali.ledger.common.ApiHeaders;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.idempotency.IdempotentResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@Tag(name = ApiDocs.TAG_TRANSFERS)
class DepositController {

	private final DepositService service;

	DepositController(DepositService service) {
		this.service = service;
	}

	/** The header comes first so a missing Idempotency-Key is reported before the body is read. */
	@Operation(summary = "Deposit money (ADMIN)",
			description = "Moves money from the currency's EXTERNAL account to the account, as one double-entry transfer. "
					+ "`amount` is in minor units, 1 to 10^12.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
					examples = @ExampleObject(name = "Deposit 150.00 EUR", value = ApiDocs.DEPOSIT_REQUEST))))
	@ApiResponse(responseCode = "201", description = "Deposit completed (or replayed: see `Idempotency-Replayed`)",
			headers = @Header(name = "Idempotency-Replayed", description = "`true` only on a replayed response"),
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.DEPOSIT_RESPONSE)))
	@PostMapping("/accounts/{accountId}/deposits")
	ResponseEntity<TransferResponse> deposit(
			@Parameter(description = ApiDocs.IDEMPOTENCY_KEY_DESCRIPTION, example = ApiDocs.IDEMPOTENCY_KEY_EXAMPLE)
			@RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
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
