package com.zainali.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, SecurityTestEndpoints.class })
class SecurityIntegrationTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void healthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("UP")));
	}

	@Test
	void noTokenReturns401() throws Exception {
		mockMvc.perform(get("/test/secured"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
	}

	@Test
	void noTokenOnAdminPathReturns401() throws Exception {
		mockMvc.perform(get("/admin/test"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidTokenReturns401() throws Exception {
		mockMvc.perform(get("/test/secured").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
	}

	@Test
	void unknownPathWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/does-not-exist"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void userRoleOnAdminPathReturns403() throws Exception {
		mockMvc.perform(get("/admin/test")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void validUserTokenReturns200() throws Exception {
		mockMvc.perform(get("/test/secured")
						.with(jwt().jwt(jwt -> jwt.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subject", is("alice")));
	}

	@Test
	void adminRoleOnAdminPathReturns200() throws Exception {
		mockMvc.perform(get("/admin/test")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk());
	}

	@Test
	void keycloakRealmRolesInBearerTokenAreMappedToAuthorities() throws Exception {
		mockMvc.perform(get("/admin/test").header(HttpHeaders.AUTHORIZATION, "Bearer " + SecurityTestEndpoints.USER_TOKEN))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/test").header(HttpHeaders.AUTHORIZATION, "Bearer " + SecurityTestEndpoints.ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subject", is("admin")));
	}

}
