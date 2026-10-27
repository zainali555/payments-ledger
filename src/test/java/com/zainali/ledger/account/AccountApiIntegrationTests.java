package com.zainali.ledger.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;

import com.zainali.ledger.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountApiIntegrationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	private static String newSubject() {
		return "user-" + UUID.randomUUID();
	}

	private static RequestPostProcessor user(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().jwt(jwt -> jwt.subject("admin-" + UUID.randomUUID())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private ResultActions createAccount(RequestPostProcessor caller, String body) throws Exception {
		return mockMvc.perform(post("/accounts").with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** Creates an account through the API and returns its id. */
	private long createAccountFor(String subject, String currency) throws Exception {
		String location = createAccount(user(subject), "{\"currency\":\"" + currency + "\"}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	// --- POST /accounts ---

	@Test
	void createAccountOwnedByCallerWithZeroBalance() throws Exception {
		String subject = newSubject();

		createAccount(user(subject), "{\"currency\":\"EUR\"}")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/accounts/")))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.ownerId", is(subject)))
				.andExpect(jsonPath("$.type", is("CUSTOMER")))
				.andExpect(jsonPath("$.currency", is("EUR")))
				.andExpect(jsonPath("$.balance", is(0)))
				.andExpect(jsonPath("$.createdAt", notNullValue()));
	}

	@Test
	void createAccountInUsd() throws Exception {
		createAccount(user(newSubject()), "{\"currency\":\"USD\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency", is("USD")));
	}

	@Test
	void unsupportedCurrencyIsRejected() throws Exception {
		createAccount(user(newSubject()), "{\"currency\":\"GBP\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void missingCurrencyIsRejectedWithFieldError() throws Exception {
		createAccount(user(newSubject()), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
				.andExpect(jsonPath("$.errors[0].field", is("currency")));
	}

	@Test
	void malformedJsonIsRejected() throws Exception {
		createAccount(user(newSubject()), "{not json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void externalAccountCannotBeCreatedThroughTheApi() throws Exception {
		createAccount(admin(), "{\"currency\":\"EUR\",\"type\":\"EXTERNAL\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type", is("CUSTOMER")))
				.andExpect(jsonPath("$.ownerId", notNullValue()));
	}

	@Test
	void userCannotCreateAccountForSomeoneElse() throws Exception {
		createAccount(user(newSubject()), "{\"currency\":\"EUR\",\"ownerId\":\"someone-else\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code", is("FORBIDDEN")));
	}

	@Test
	void userMayNameThemselvesAsOwner() throws Exception {
		String subject = newSubject();
		createAccount(user(subject), "{\"currency\":\"EUR\",\"ownerId\":\"" + subject + "\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerId", is(subject)));
	}

	@Test
	void adminCanCreateAccountForAnotherOwner() throws Exception {
		createAccount(admin(), "{\"currency\":\"USD\",\"ownerId\":\"customer-42\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerId", is("customer-42")));
	}

	@Test
	void createWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutUserOrAdminRoleReturns403() throws Exception {
		createAccount(jwt().jwt(jwt -> jwt.subject(newSubject())), "{\"currency\":\"EUR\"}")
				.andExpect(status().isForbidden());
	}

	// --- GET /accounts/{id} ---

	@Test
	void ownerCanReadOwnAccount() throws Exception {
		String subject = newSubject();
		long id = createAccountFor(subject, "EUR");

		mockMvc.perform(get("/accounts/{id}", id).with(user(subject)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id", is((int) id)))
				.andExpect(jsonPath("$.ownerId", is(subject)))
				.andExpect(jsonPath("$.currency", is("EUR")));
	}

	@Test
	void otherUsersAccountReturns404NotFound() throws Exception {
		long id = createAccountFor(newSubject(), "EUR");

		mockMvc.perform(get("/accounts/{id}", id).with(user(newSubject())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	@Test
	void hiddenAccountIsIndistinguishableFromMissingAccount() throws Exception {
		String caller = newSubject();
		long hiddenId = createAccountFor(newSubject(), "EUR");
		long missingId = Long.MAX_VALUE;

		String hidden = mockMvc.perform(get("/accounts/{id}", hiddenId).with(user(caller)))
				.andReturn().getResponse().getContentAsString();
		String missing = mockMvc.perform(get("/accounts/{id}", missingId).with(user(caller)))
				.andReturn().getResponse().getContentAsString();

		// Same body apart from the request path in "instance".
		org.assertj.core.api.Assertions.assertThat(hidden.replace("/accounts/" + hiddenId, "/accounts/X"))
				.isEqualTo(missing.replace("/accounts/" + missingId, "/accounts/X"));
	}

	@Test
	void userCannotSeeExternalAccount() throws Exception {
		long externalId = jdbc.sql("SELECT id FROM accounts WHERE type = 'EXTERNAL' AND currency = 'EUR'")
				.query(Long.class).single();

		mockMvc.perform(get("/accounts/{id}", externalId).with(user(newSubject())))
				.andExpect(status().isNotFound());
	}

	@Test
	void adminCanReadAnyAccountIncludingExternal() throws Exception {
		long id = createAccountFor(newSubject(), "USD");
		long externalId = jdbc.sql("SELECT id FROM accounts WHERE type = 'EXTERNAL' AND currency = 'USD'")
				.query(Long.class).single();

		mockMvc.perform(get("/accounts/{id}", id).with(admin()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/accounts/{id}", externalId).with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("EXTERNAL")))
				.andExpect(jsonPath("$.ownerId").doesNotExist());
	}

	@Test
	void missingAccountReturns404ForAdmin() throws Exception {
		mockMvc.perform(get("/accounts/{id}", Long.MAX_VALUE).with(admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

	@Test
	void nonNumericIdIsRejected() throws Exception {
		mockMvc.perform(get("/accounts/abc").with(user(newSubject())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void readWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/accounts/1")).andExpect(status().isUnauthorized());
	}

	// --- GET /accounts ---

	@Test
	void userListsOnlyOwnAccounts() throws Exception {
		String subject = newSubject();
		long first = createAccountFor(subject, "EUR");
		long second = createAccountFor(subject, "USD");
		long othersId = createAccountFor(newSubject(), "EUR");

		mockMvc.perform(get("/accounts").with(user(subject)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].id", hasItem((int) first)))
				.andExpect(jsonPath("$[*].id", hasItem((int) second)))
				.andExpect(jsonPath("$[*].id", not(hasItem((int) othersId))));
	}

	@Test
	void userWithNoAccountsGetsEmptyList() throws Exception {
		mockMvc.perform(get("/accounts").with(user(newSubject())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void adminListsAllAccounts() throws Exception {
		long a = createAccountFor(newSubject(), "EUR");
		long b = createAccountFor(newSubject(), "USD");

		mockMvc.perform(get("/accounts").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", hasItem((int) a)))
				.andExpect(jsonPath("$[*].id", hasItem((int) b)))
				.andExpect(jsonPath("$[?(@.type == 'EXTERNAL')]", hasSize(2)));
	}

}
