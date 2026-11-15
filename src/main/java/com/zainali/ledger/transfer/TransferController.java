package com.zainali.ledger.transfer;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
@RequestMapping("/transfers")
@Tag(name = ApiDocs.TAG_TRANSFERS)
class TransferController {

	private final TransferService service;
	private final ReversalService reversals;

	TransferController(TransferService service, ReversalService reversals) {
		this.service = service;
		this.reversals = reversals;
	}

	/** The header comes first so a missing Idempotency-Key is reported before the body is read. */
	@Operation(summary = "Transfer money",
			description = "Moves `amount` (minor units, 1 to 10^12) from the source to the target account, one debit and one credit "
					+ "in one transaction. Sent by the owner of the source account, or by an ADMIN between any two accounts. "
					+ "The currency must match both accounts. EXTERNAL accounts cannot be used.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
					examples = @ExampleObject(name = "Send 25.00 EUR", value = ApiDocs.TRANSFER_REQUEST))))
	@ApiResponse(responseCode = "201", description = "Transfer completed (or replayed: see `Idempotency-Replayed`)",
			headers = { @Header(name = "Location", description = "URL of the transfer"),
					@Header(name = "Idempotency-Replayed", description = "`true` only on a replayed response") },
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.TRANSFER_RESPONSE)))
	@PostMapping
	ResponseEntity<TransferResponse> transfer(
			@Parameter(description = ApiDocs.IDEMPOTENCY_KEY_DESCRIPTION, example = ApiDocs.IDEMPOTENCY_KEY_EXAMPLE)
			@RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
			@Valid @RequestBody TransferRequest request, Authentication authentication) {
		IdempotentResult<TransferResponse> result = service.transfer(Caller.from(authentication), idempotencyKey,
				request.sourceAccountId(), request.targetAccountId(), request.amount(), request.currency());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(result.body().id());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status()).location(location);
		if (result.replayed()) {
			response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
		}
		return response.body(result.body());
	}

	/** ADMIN only (filter chain). Returns the new REVERSAL transfer, with a Location pointing at it. */
	@Operation(summary = "Reverse a transfer (ADMIN)",
			description = "Creates a REVERSAL transfer that moves the amount back and marks the original REVERSED. "
					+ "Only a COMPLETED transfer of type TRANSFER can be reversed, and only once. No request body.")
	@ApiResponse(responseCode = "201", description = "Reversal completed (or replayed: see `Idempotency-Replayed`)",
			headers = { @Header(name = "Location", description = "URL of the reversal transfer"),
					@Header(name = "Idempotency-Replayed", description = "`true` only on a replayed response") },
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.REVERSAL_RESPONSE)))
	@PostMapping("/{id}/reverse")
	ResponseEntity<TransferResponse> reverse(
			@Parameter(description = ApiDocs.IDEMPOTENCY_KEY_DESCRIPTION, example = ApiDocs.IDEMPOTENCY_KEY_EXAMPLE)
			@RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
			@PathVariable long id, Authentication authentication) {
		IdempotentResult<TransferResponse> result = reversals.reverse(Caller.from(authentication), idempotencyKey, id);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/transfers/{id}")
				.build(result.body().id());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status()).location(location);
		if (result.replayed()) {
			response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
		}
		return response.body(result.body());
	}

	@Operation(summary = "Get a transfer",
			description = "Visible to the owner of the source or target account, and to an ADMIN. Anyone else gets the same 404 as for a missing id.")
	@ApiResponse(responseCode = "200", description = "The transfer",
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.TRANSFER_RESPONSE)))
	@GetMapping("/{id}")
	TransferResponse get(@PathVariable long id, Authentication authentication) {
		return service.get(Caller.from(authentication), id);
	}

}
