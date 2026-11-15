package com.zainali.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.zainali.ledger.common.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Milestone 12: the generated OpenAPI description is public (DECISIONS 20) and documents every endpoint with
 * examples, the Idempotency-Key header, the bearer scheme and the problem-format errors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ApiDocsIntegrationTests {

	/** Every endpoint of the API and the error statuses it documents besides the 401 and 500 every endpoint has. */
	private static final Map<String, List<String>> ENDPOINTS = Map.ofEntries(
			Map.entry("POST /accounts", List.of("400", "403")),
			Map.entry("GET /accounts", List.of()),
			Map.entry("GET /accounts/{id}", List.of("400", "404")),
			Map.entry("GET /accounts/{id}/balance", List.of("400", "404")),
			Map.entry("GET /accounts/{id}/transactions", List.of("400", "404")),
			Map.entry("POST /accounts/{accountId}/deposits", List.of("400", "403", "404", "422")),
			Map.entry("POST /transfers", List.of("400", "404", "422")),
			Map.entry("GET /transfers/{id}", List.of("400", "404")),
			Map.entry("POST /transfers/{id}/reverse", List.of("400", "403", "404", "422")),
			Map.entry("POST /admin/reconciliation", List.of("403")));

	private static final Set<String> IDEMPOTENT_ENDPOINTS = Set.of("POST /accounts/{accountId}/deposits",
			"POST /transfers", "POST /transfers/{id}/reverse");

	private static final Set<String> ENDPOINTS_WITH_REQUEST_BODY = Set.of("POST /accounts",
			"POST /accounts/{accountId}/deposits", "POST /transfers");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	private static JsonNode docs;

	@BeforeAll
	static void clearDocs() {
		docs = null;
	}

	private JsonNode docs() throws Exception {
		if (docs == null) {
			String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsString();
			docs = JSON.readTree(body);
		}
		return docs;
	}

	@Test
	void apiDocsArePublicAndAreOpenApi3() throws Exception {
		// No token at all: the description is public by decision (DECISIONS 20).
		assertThat(docs().path("openapi").asString()).startsWith("3.");
		assertThat(docs().path("info").path("title").asString()).isNotBlank();
	}

	@Test
	void swaggerUiIsPublic() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
	}

	@Test
	void descriptionListsEveryEndpoint() throws Exception {
		assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(ENDPOINTS.keySet());
	}

	/** Catches an endpoint added later without being described: the description must match what Spring MVC serves. */
	@Test
	void descriptionMatchesTheEndpointsTheApplicationServes() throws Exception {
		Set<String> served = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			if (!entry.getValue().getBeanType().getName().startsWith("com.zainali.ledger.")) {
				continue;
			}
			for (HttpMethod method : httpMethods(entry.getKey())) {
				for (String pattern : entry.getKey().getPatternValues()) {
					served.add(method.name() + " " + pattern);
				}
			}
		}
		assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(served);
	}

	@Test
	void everyEndpointRequiresTheBearerScheme() throws Exception {
		JsonNode scheme = docs().path("components").path("securitySchemes").path("bearerAuth");
		assertThat(scheme.path("type").asString()).isEqualTo("http");
		assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
		assertThat(scheme.path("bearerFormat").asString()).isEqualTo("JWT");
		operations().forEach((name, operation) -> assertThat(operation.path("security").toString())
				.as(name).contains("bearerAuth"));
	}

	@Test
	void idempotentEndpointsDocumentTheRequiredHeaderAndTheFailureRule() throws Exception {
		for (String name : IDEMPOTENT_ENDPOINTS) {
			JsonNode header = null;
			for (JsonNode parameter : operations().get(name).path("parameters")) {
				if ("Idempotency-Key".equals(parameter.path("name").asString())) {
					header = parameter;
				}
			}
			assertThat(header).as(name + " Idempotency-Key parameter").isNotNull();
			assertThat(header.path("in").asString()).isEqualTo("header");
			assertThat(header.path("required").asBoolean()).isTrue();
			assertThat(header.path("description").asString()).as(name).contains("stores nothing");
		}
		operations().forEach((name, operation) -> {
			if (!IDEMPOTENT_ENDPOINTS.contains(name)) {
				assertThat(operation.path("parameters").toString()).as(name).doesNotContain("Idempotency-Key");
			}
		});
	}

	@Test
	void requestBodiesAndSuccessResponsesHaveExamples() throws Exception {
		operations().forEach((name, operation) -> {
			if (ENDPOINTS_WITH_REQUEST_BODY.contains(name)) {
				assertThat(examples(operation.path("requestBody").path("content").path("application/json")))
						.as(name + " request examples").isNotEmpty();
			}
			JsonNode success = firstSuccess(operation);
			assertThat(success).as(name + " success response").isNotNull();
			assertThat(examples(success.path("content").path("application/json")))
					.as(name + " success examples").isNotEmpty();
		});
	}

	@Test
	void errorResponsesUseTheProblemFormatWithStableCodes() throws Exception {
		Set<String> knownCodes = new TreeSet<>();
		for (ErrorCode code : ErrorCode.values()) {
			knownCodes.add(code.name());
		}
		knownCodes.addAll(Set.of("NOT_FOUND", "FORBIDDEN", "INTERNAL_ERROR"));

		JsonNode codeSchema = docs().path("components").path("schemas").path("ApiProblem").path("properties")
				.path("code");
		List<String> documented = new ArrayList<>();
		codeSchema.path("enum").forEach(value -> documented.add(value.asString()));
		assertThat(documented).containsExactlyInAnyOrderElementsOf(knownCodes);

		ENDPOINTS.forEach((name, statuses) -> {
			JsonNode responses = operations().get(name).path("responses");
			List<String> expected = new ArrayList<>(statuses);
			expected.add("401");
			expected.add("500");
			for (String statusCode : expected) {
				JsonNode response = responses.path(statusCode);
				assertThat(response.isMissingNode()).as(name + " documents " + statusCode).isFalse();
				if (statusCode.equals("401")) {
					continue; // produced by the security filter chain, which has no problem body yet
				}
				JsonNode problem = response.path("content").path(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				assertThat(problem.isMissingNode()).as(name + " " + statusCode + " is application/problem+json")
						.isFalse();
				assertThat(problem.path("schema").toString()).as(name + " " + statusCode).contains("ApiProblem");
				Map<String, JsonNode> examples = examples(problem);
				assertThat(examples).as(name + " " + statusCode + " examples").isNotEmpty();
				examples.forEach((exampleName, example) -> {
					assertThat(example.path("value").path("status").asInt()).as(name + " " + exampleName)
							.isEqualTo(Integer.parseInt(statusCode));
					assertThat(knownCodes).as(name + " " + exampleName).contains(example.path("value").path("code")
							.asString());
				});
			}
		});
	}

	@Test
	void transfersDocumentEveryBusinessRuleCode() throws Exception {
		assertThat(exampleCodes("POST /transfers", "422")).containsExactlyInAnyOrder("SAME_ACCOUNT",
				"INSUFFICIENT_FUNDS", "CURRENCY_MISMATCH", "EXTERNAL_ACCOUNT_NOT_ALLOWED", "IDEMPOTENCY_KEY_REUSED");
		assertThat(exampleCodes("POST /transfers/{id}/reverse", "422")).containsExactlyInAnyOrder("NOT_REVERSIBLE",
				"ALREADY_REVERSED", "INSUFFICIENT_FUNDS", "IDEMPOTENCY_KEY_REUSED");
		assertThat(exampleCodes("POST /accounts/{accountId}/deposits", "422")).containsExactlyInAnyOrder(
				"EXTERNAL_ACCOUNT_NOT_ALLOWED", "CURRENCY_MISMATCH", "IDEMPOTENCY_KEY_REUSED");
		assertThat(exampleCodes("POST /transfers", "400")).containsExactlyInAnyOrder("VALIDATION_FAILED",
				"IDEMPOTENCY_KEY_REQUIRED");
	}

	@Test
	void documentationExplainsThatFailedRequestsStoreNothingUnderTheKey() throws Exception {
		assertThat(docs().path("info").path("description").asString()).contains("Idempotency-Key")
				.contains("stores nothing");
	}

	// --- helpers ---

	private Map<String, JsonNode> operations() {
		try {
			Map<String, JsonNode> operations = new TreeMap<>();
			for (Map.Entry<String, JsonNode> path : docs().path("paths").properties()) {
				for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
					operations.put(operation.getKey().toUpperCase() + " " + path.getKey(), operation.getValue());
				}
			}
			return operations;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Set<String> exampleCodes(String endpoint, String statusCode) {
		Set<String> codes = new TreeSet<>();
		JsonNode problem = operations().get(endpoint).path("responses").path(statusCode).path("content")
				.path(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		examples(problem).values().forEach(example -> codes.add(example.path("value").path("code").asString()));
		return codes;
	}

	private static Map<String, JsonNode> examples(JsonNode mediaType) {
		Map<String, JsonNode> examples = new TreeMap<>();
		for (Map.Entry<String, JsonNode> example : mediaType.path("examples").properties()) {
			examples.put(example.getKey(), example.getValue());
		}
		if (examples.isEmpty() && !mediaType.path("example").isMissingNode()) {
			examples.put("example", JSON.createObjectNode().set("value", mediaType.path("example")));
		}
		return examples;
	}

	private static JsonNode firstSuccess(JsonNode operation) {
		for (Map.Entry<String, JsonNode> response : operation.path("responses").properties()) {
			if (response.getKey().startsWith("2")) {
				return response.getValue();
			}
		}
		return null;
	}

	private static Set<HttpMethod> httpMethods(RequestMappingInfo info) {
		Set<HttpMethod> methods = new TreeSet<>((a, b) -> a.name().compareTo(b.name()));
		info.getMethodsCondition().getMethods().forEach(method -> methods.add(HttpMethod.valueOf(method.name())));
		return methods;
	}

}
