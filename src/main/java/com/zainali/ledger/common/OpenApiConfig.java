package com.zainali.ledger.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * The OpenAPI description: the bearer scheme, the problem schema and, for every operation, its error responses.
 * The error table below is the one place that says which stable codes an endpoint can return (DECISIONS 20).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String BEARER_SCHEME = "bearerAuth";
	static final String PROBLEM_SCHEMA = "ApiProblem";

	private static final String DESCRIPTION = """
			A double-entry payments ledger. Amounts are integer **minor units** (cents) with a currency code (`EUR` or `USD`).

			**Authentication.** Every endpoint needs a Keycloak access token (JWT) with the realm role `USER` or `ADMIN`. \
			Get one from Keycloak (see `api.http` for the local password-grant call), click **Authorize** and paste the \
			`access_token`. This page and the API description are public; the API itself is not.

			**Idempotency.** Deposits, transfers and reversals require an `Idempotency-Key` header. Repeating a request \
			with the same key and the same content returns the original response with `Idempotency-Replayed: true`. \
			**A failed request stores nothing under its key**: it rolls back completely, so a retry with the same key \
			runs again and can succeed (for example an `INSUFFICIENT_FUNDS` transfer once the account has been funded). \
			Do not assume a failure is replayed.

			**Errors.** Failures are RFC 7807 `application/problem+json` documents with a stable `code` field to branch on \
			(see the `ApiProblem` schema). Two responses come from the security filter chain and have an empty body \
			instead: `401` (missing or invalid token, see the `WWW-Authenticate` header) and a `403` for a missing role.""";

	/** Error codes per operation and status. Keep in step with the services; ApiDocsIntegrationTests checks the shape. */
	private static final Map<String, Map<Integer, List<String>>> ERRORS = Map.ofEntries(
			Map.entry("POST /accounts", Map.of(
					400, List.of("VALIDATION_FAILED"),
					403, List.of("FORBIDDEN"))),
			Map.entry("GET /accounts", Map.of()),
			Map.entry("GET /accounts/{id}", Map.of(
					400, List.of("VALIDATION_FAILED"),
					404, List.of("NOT_FOUND"))),
			Map.entry("GET /accounts/{id}/balance", Map.of(
					400, List.of("VALIDATION_FAILED"),
					404, List.of("NOT_FOUND"))),
			Map.entry("GET /accounts/{id}/transactions", Map.of(
					400, List.of("VALIDATION_FAILED"),
					404, List.of("NOT_FOUND"))),
			Map.entry("POST /accounts/{accountId}/deposits", Map.of(
					400, List.of("VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED"),
					403, List.of("FORBIDDEN"),
					404, List.of("NOT_FOUND"),
					422, List.of("EXTERNAL_ACCOUNT_NOT_ALLOWED", "CURRENCY_MISMATCH", "IDEMPOTENCY_KEY_REUSED"))),
			Map.entry("POST /transfers", Map.of(
					400, List.of("VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED"),
					404, List.of("NOT_FOUND"),
					422, List.of("SAME_ACCOUNT", "INSUFFICIENT_FUNDS", "CURRENCY_MISMATCH",
							"EXTERNAL_ACCOUNT_NOT_ALLOWED", "IDEMPOTENCY_KEY_REUSED"))),
			Map.entry("GET /transfers/{id}", Map.of(
					400, List.of("VALIDATION_FAILED"),
					404, List.of("NOT_FOUND"))),
			Map.entry("POST /transfers/{id}/reverse", Map.of(
					400, List.of("VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED"),
					403, List.of("FORBIDDEN"),
					404, List.of("NOT_FOUND"),
					422, List.of("NOT_REVERSIBLE", "ALREADY_REVERSED", "INSUFFICIENT_FUNDS",
							"IDEMPOTENCY_KEY_REUSED"))),
			Map.entry("POST /admin/reconciliation", Map.of(
					403, List.of("FORBIDDEN"))));

	/** One illustrative problem per code: its status and a typical detail text. */
	private static final Map<String, ProblemExample> PROBLEMS = Map.ofEntries(
			Map.entry("VALIDATION_FAILED", new ProblemExample(400, "Request validation failed")),
			Map.entry("IDEMPOTENCY_KEY_REQUIRED", new ProblemExample(400, "The Idempotency-Key header is required")),
			Map.entry("FORBIDDEN", new ProblemExample(403, "Access denied")),
			Map.entry("NOT_FOUND", new ProblemExample(404, "Resource not found")),
			Map.entry("IDEMPOTENCY_KEY_REUSED", new ProblemExample(422,
					"This Idempotency-Key was already used with a different request")),
			Map.entry("INSUFFICIENT_FUNDS", new ProblemExample(422, "Insufficient funds")),
			Map.entry("CURRENCY_MISMATCH", new ProblemExample(422, "The currency does not match the account")),
			Map.entry("EXTERNAL_ACCOUNT_NOT_ALLOWED", new ProblemExample(422,
					"The EXTERNAL account cannot be used here")),
			Map.entry("SAME_ACCOUNT", new ProblemExample(422, "Source and target must be different accounts")),
			Map.entry("NOT_REVERSIBLE", new ProblemExample(422, "Only a plain transfer can be reversed")),
			Map.entry("ALREADY_REVERSED", new ProblemExample(422, "This transfer has already been reversed")),
			Map.entry("INTERNAL_ERROR", new ProblemExample(500, "Unexpected error")));

	@Bean
	OpenAPI ledgerOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Payments Ledger API").version("v1").description(DESCRIPTION))
				.components(new Components()
						.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Keycloak access token (realm `ledger`). Paste the `access_token` only, "
										+ "without the `Bearer ` prefix.")));
	}

	@Bean
	OpenApiCustomizer operationDefaultsCustomizer() {
		return openApi -> {
			// Added here, after springdoc has built its own components, so it is not dropped.
			openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
			openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
				operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
				addErrorResponses(operation, method.name() + " " + path);
			}));
		};
	}

	private static void addErrorResponses(Operation operation, String key) {
		operation.getResponses().addApiResponse("401", new ApiResponse()
				.description("Missing, expired or invalid bearer token. The body is empty; the `WWW-Authenticate` "
						+ "header says why.")
				.addHeaderObject("WWW-Authenticate", new Header().description("`Bearer`, with an `error` for an "
						+ "invalid token").schema(new StringSchema())));
		ERRORS.getOrDefault(key, Map.of()).forEach((status, codes) -> operation.getResponses()
				.addApiResponse(String.valueOf(status), problemResponse(status, codes)));
		operation.getResponses().addApiResponse("500", problemResponse(500, List.of("INTERNAL_ERROR")));
	}

	private static ApiResponse problemResponse(int status, List<String> codes) {
		Map<String, Example> examples = new LinkedHashMap<>();
		for (String code : codes) {
			ProblemExample problem = PROBLEMS.get(code);
			if (problem == null || problem.status() != status) {
				throw new IllegalStateException("No " + status + " example for code " + code);
			}
			examples.put(code, new Example().summary(code).value(problem.asBody(code)));
		}
		io.swagger.v3.oas.models.media.MediaType mediaType = new io.swagger.v3.oas.models.media.MediaType()
				.schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA));
		mediaType.setExamples(examples);
		return new ApiResponse()
				.description(HttpStatus.valueOf(status).getReasonPhrase() + ": " + String.join(", ", codes))
				.content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType));
	}

	private static Schema<?> problemSchema() {
		List<String> codes = new ArrayList<>();
		for (ErrorCode code : ErrorCode.values()) {
			codes.add(code.name());
		}
		codes.addAll(List.of("NOT_FOUND", "FORBIDDEN", "INTERNAL_ERROR"));
		StringSchema code = new StringSchema();
		code.setDescription("Stable error code. Branch on this, not on `detail`.");
		code.setEnum(codes);

		ObjectSchema violation = new ObjectSchema();
		violation.addProperty("field", new StringSchema());
		violation.addProperty("message", new StringSchema());

		ObjectSchema problem = new ObjectSchema();
		problem.setDescription("RFC 7807 problem document returned for every error produced by the application.");
		problem.addProperty("type", new StringSchema().example("about:blank"));
		problem.addProperty("title", new StringSchema());
		problem.addProperty("status", new IntegerSchema());
		problem.addProperty("detail", new StringSchema());
		problem.addProperty("code", code);
		problem.addProperty("errors", new ArraySchema().items(violation)
				.description("Only on `VALIDATION_FAILED` for an invalid body: one entry per invalid field."));
		problem.setRequired(List.of("status", "code"));
		return problem;
	}

	private record ProblemExample(int status, String detail) {

		Map<String, Object> asBody(String code) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("type", "about:blank");
			body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
			body.put("status", status);
			body.put("detail", detail);
			body.put("code", code);
			if (code.equals("VALIDATION_FAILED")) {
				body.put("errors", List.of(Map.of("field", "amount", "message", "must be greater than 0")));
			}
			return body;
		}

	}

}
