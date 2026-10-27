package com.zainali.ledger.common;

import org.springframework.security.core.Authentication;

/**
 * The authenticated caller: the JWT {@code sub} claim and whether they hold the ADMIN role.
 */
public record Caller(String subject, boolean admin) {

	public static Caller from(Authentication authentication) {
		boolean admin = authentication.getAuthorities().stream()
				.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
		return new Caller(authentication.getName(), admin);
	}

}
