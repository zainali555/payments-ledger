package com.zainali.ledger.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the Keycloak realm roles in the {@code realm_access.roles} claim to {@code ROLE_*} authorities,
 * so {@code hasRole("ADMIN")} matches the realm role {@code ADMIN}.
 */
class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	private static final String REALM_ACCESS_CLAIM = "realm_access";
	private static final String ROLES_KEY = "roles";
	private static final String ROLE_PREFIX = "ROLE_";

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		if (!(jwt.getClaim(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)
				|| !(realmAccess.get(ROLES_KEY) instanceof Collection<?> roles)) {
			return List.of();
		}
		return roles.stream()
				.filter(String.class::isInstance)
				.map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
				.toList();
	}

}
