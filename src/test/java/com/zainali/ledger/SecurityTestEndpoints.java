package com.zainali.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints and a stub {@link JwtDecoder}, so the security rules can be exercised
 * before the real endpoints exist and without a running Keycloak.
 */
@TestConfiguration(proxyBeanMethods = false)
class SecurityTestEndpoints {

	static final String USER_TOKEN = "user-token";
	static final String ADMIN_TOKEN = "admin-token";

	@RestController
	static class Endpoints {

		@GetMapping("/test/secured")
		Map<String, Object> secured(Authentication authentication) {
			return Map.of("subject", authentication.getName());
		}

		@GetMapping("/admin/test")
		Map<String, Object> admin(Authentication authentication) {
			return Map.of("subject", authentication.getName());
		}

	}

	/** Accepts the two fixed tokens above and rejects everything else, like a decoder that fails validation. */
	@Bean
	@Primary
	JwtDecoder stubJwtDecoder() {
		return token -> switch (token) {
			case USER_TOKEN -> jwt(token, "alice", "USER");
			case ADMIN_TOKEN -> jwt(token, "admin", "ADMIN");
			default -> throw new BadJwtException("Invalid token");
		};
	}

	private static Jwt jwt(String token, String subject, String role) {
		return Jwt.withTokenValue(token)
				.header("alg", "none")
				.subject(subject)
				.claim("realm_access", Map.of("roles", List.of(role)))
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300))
				.build();
	}

}
