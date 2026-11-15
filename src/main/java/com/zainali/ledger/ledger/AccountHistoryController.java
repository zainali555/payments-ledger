package com.zainali.ledger.ledger;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zainali.ledger.common.ApiDocs;
import com.zainali.ledger.common.Caller;
import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = ApiDocs.TAG_ACCOUNTS)
class AccountHistoryController {

	static final int DEFAULT_PAGE_SIZE = 20;
	static final int MAX_PAGE_SIZE = 100;

	private final HistoryService service;

	AccountHistoryController(HistoryService service) {
		this.service = service;
	}

	/**
	 * {@code from} and {@code to} are ISO-8601 instants (for example {@code 2026-01-31T00:00:00Z}); {@code from} is
	 * inclusive and {@code to} is exclusive. A {@code size} above the maximum is lowered to it.
	 */
	@Operation(summary = "List an account's ledger entries",
			description = "The account's own entries, newest first by default. Same visibility as `GET /accounts/{id}`. "
					+ "A page past the end is empty with the real totals.")
	@ApiResponse(responseCode = "200", description = "One page of entries",
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.HISTORY_RESPONSE)))
	@GetMapping("/accounts/{id}/transactions")
	HistoryPage transactions(@PathVariable long id,
			@Parameter(description = "Inclusive lower bound, ISO-8601 instant", example = "2026-11-01T00:00:00Z")
			@RequestParam(required = false) String from,
			@Parameter(description = "Exclusive upper bound, ISO-8601 instant", example = "2026-12-01T00:00:00Z")
			@RequestParam(required = false) String to,
			@Parameter(description = "`createdAt` or `amount`, optionally followed by `,asc` or `,desc`. Default `createdAt,desc`.",
					example = "amount,desc")
			@RequestParam(required = false) String sort,
			@Parameter(description = "Page number, from 0") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "Page size, default 20; a larger value is lowered to 100")
			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
			Authentication authentication) {
		if (page < 0) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "page must be 0 or greater");
		}
		if (size < 1) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "size must be 1 or greater");
		}
		Instant fromInstant = parseInstant("from", from);
		Instant toInstant = parseInstant("to", to);
		if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
		}
		return service.history(Caller.from(authentication), id, fromInstant, toInstant, HistorySort.parse(sort), page,
				Math.min(size, MAX_PAGE_SIZE));
	}

	/** Strict ISO-8601 only. Spring's own Instant binding also accepts bare epoch numbers, which are ambiguous. */
	private static Instant parseInstant(String name, String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED,
					name + " must be an ISO-8601 instant such as 2026-01-31T00:00:00Z");
		}
	}

}
