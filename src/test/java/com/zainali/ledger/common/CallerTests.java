package com.zainali.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerTests {

	private static JwtAuthenticationToken token(String subject, String... authorities) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		List<GrantedAuthority> granted = java.util.Arrays.stream(authorities)
				.<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
		return new JwtAuthenticationToken(jwt, granted);
	}

	@Test
	void subjectComesFromTheJwtSubClaim() {
		assertThat(Caller.from(token("alice", "ROLE_USER")).subject()).isEqualTo("alice");
	}

	@Test
	void adminRoleMakesTheCallerAdmin() {
		assertThat(Caller.from(token("root", "ROLE_ADMIN")).admin()).isTrue();
	}

	@Test
	void userRoleAloneIsNotAdmin() {
		assertThat(Caller.from(token("alice", "ROLE_USER")).admin()).isFalse();
	}

}
