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

import com.zainali.ledger.common.ApiHeaders;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.idempotency.IdempotentResult;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/transfers")
class TransferController {

	private final TransferService service;

	TransferController(TransferService service) {
		this.service = service;
	}

	/** The header comes first so a missing Idempotency-Key is reported before the body is read. */
	@PostMapping
	ResponseEntity<TransferResponse> transfer(@RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
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

	@GetMapping("/{id}")
	TransferResponse get(@PathVariable long id, Authentication authentication) {
		return service.get(Caller.from(authentication), id);
	}

}
