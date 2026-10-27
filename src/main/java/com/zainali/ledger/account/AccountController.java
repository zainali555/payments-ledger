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

import com.zainali.ledger.common.Caller;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
class AccountController {

	private final AccountService service;

	AccountController(AccountService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request, Authentication authentication) {
		Account account = service.create(Caller.from(authentication), request.currency(), request.ownerId());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(account.getId());
		return ResponseEntity.created(location).body(AccountResponse.from(account));
	}

	@GetMapping("/{id}")
	AccountResponse get(@PathVariable long id, Authentication authentication) {
		return AccountResponse.from(service.get(Caller.from(authentication), id));
	}

	@GetMapping
	List<AccountResponse> list(Authentication authentication) {
		return service.list(Caller.from(authentication)).stream().map(AccountResponse::from).toList();
	}

}
