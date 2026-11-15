package com.zainali.ledger.account;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.zainali.ledger.common.ApiDocs;
import com.zainali.ledger.common.Caller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
@Tag(name = ApiDocs.TAG_ACCOUNTS)
class AccountController {

	private final AccountService service;

	AccountController(AccountService service) {
		this.service = service;
	}

	@Operation(summary = "Open an account",
			description = "Creates a CUSTOMER account in EUR or USD, owned by the caller. Only an ADMIN may set `ownerId` to someone else.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "Own account", value = ApiDocs.CREATE_ACCOUNT_REQUEST),
					@ExampleObject(name = "Admin opens for another owner", value = ApiDocs.CREATE_ACCOUNT_FOR_OWNER_REQUEST) })))
	@ApiResponse(responseCode = "201", description = "Account created",
			headers = @Header(name = "Location", description = "URL of the new account"),
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.ACCOUNT_RESPONSE)))
	@PostMapping
	ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request, Authentication authentication) {
		Account account = service.create(Caller.from(authentication), request.currency(), request.ownerId());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(account.getId());
		return ResponseEntity.created(location).body(AccountResponse.from(account));
	}

	@Operation(summary = "Get an account",
			description = "Owner or ADMIN. A missing account and one that is not yours both return 404 with the same body.")
	@ApiResponse(responseCode = "200", description = "The account",
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.ACCOUNT_RESPONSE)))
	@GetMapping("/{id}")
	AccountResponse get(@PathVariable long id, Authentication authentication) {
		return AccountResponse.from(service.get(Caller.from(authentication), id));
	}

	@Operation(summary = "Get the balance",
			description = "The cached balance in minor units. It always equals the sum of the account's ledger entries; reconciliation checks that.")
	@ApiResponse(responseCode = "200", description = "The balance",
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.BALANCE_RESPONSE)))
	@GetMapping("/{id}/balance")
	BalanceResponse balance(@PathVariable long id, Authentication authentication) {
		return BalanceResponse.from(service.get(Caller.from(authentication), id));
	}

	@Operation(summary = "List accounts", description = "Your accounts; an ADMIN sees all accounts.")
	@ApiResponse(responseCode = "200", description = "The accounts",
			content = @Content(mediaType = "application/json", examples = @ExampleObject(value = ApiDocs.ACCOUNT_LIST_RESPONSE)))
	@GetMapping
	List<AccountResponse> list(Authentication authentication) {
		return service.list(Caller.from(authentication)).stream().map(AccountResponse::from).toList();
	}

}
