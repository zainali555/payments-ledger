package com.zainali.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTests {

	private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

	@Test
	void mapsRealmRolesToRoleAuthorities() {
		Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of("USER", "ADMIN"))));

		assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
				.containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
	}

	@Test
	void noRealmAccessClaimGivesNoAuthorities() {
		assertThat(converter.convert(jwt(claims -> { }))).isEmpty();
	}

	@Test
	void malformedRealmAccessClaimGivesNoAuthorities() {
		assertThat(converter.convert(jwt(claims -> claims.put("realm_access", "ADMIN")))).isEmpty();
		assertThat(converter.convert(jwt(claims -> claims.put("realm_access", Map.of("roles", "ADMIN"))))).isEmpty();
	}

	@Test
	void ignoresNonStringRoles() {
		Jwt jwt = jwt(claims -> claims.put("realm_access", Map.of("roles", List.of("USER", 42))));

		assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
	}

	private static Jwt jwt(Consumer<Map<String, Object>> claims) {
		return Jwt.withTokenValue("token")
				.header("alg", "none")
				.subject("alice")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60))
				.claims(claims)
				.build();
	}

}
