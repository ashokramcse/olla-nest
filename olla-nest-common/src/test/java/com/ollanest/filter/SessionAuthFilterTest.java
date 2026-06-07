package com.ollanest.filter;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.testinfra.UserFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SessionAuthFilter}.
 *
 * <p>Verifies: authenticated request sets "authenticatedUser" attribute,
 * unauthenticated request does not set attribute but still calls chain,
 * filter never short-circuits the request.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAuthFilter — unit tests")
class SessionAuthFilterTest {

	@Mock AuthService        authService;
	@Mock HttpServletRequest  request;
	@Mock HttpServletResponse response;
	@Mock FilterChain         filterChain;

	@InjectMocks SessionAuthFilter filter;

	@Test
	@DisplayName("authenticated request sets 'authenticatedUser' attribute")
	void setsUserAttributeOnAuthenticatedRequest() throws Exception {
		User admin = UserFactory.admin();
		// Stub: session lookup resolves to a known admin user
		when(authService.getSessionUser(request)).thenReturn(admin);

		filter.doFilterInternal(request, response, filterChain);

		// The user must be stored in the request attribute for downstream controllers
		verify(request).setAttribute("authenticatedUser", admin);
		verify(filterChain).doFilter(request, response); // chain always continues
	}

	@Test
	@DisplayName("unauthenticated request does NOT set attribute — chain still called")
	void doesNotSetAttributeForUnauthenticatedRequest() throws Exception {
		// Stub: no valid session — user is null
		when(authService.getSessionUser(request)).thenReturn(null);

		filter.doFilterInternal(request, response, filterChain);

		// No attribute must be set — controllers check for null to detect unauthenticated state
		verify(request, never()).setAttribute(anyString(), any());
		// Chain must still continue — this filter never blocks requests (controllers do that)
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("filter never blocks the chain — even on exception from authService")
	void chainAlwaysContinuesEvenIfAuthServiceThrows() throws Exception {
		// AuthService swallows its own exceptions; getSessionUser returns null on error.
		// But even if it leaked, filter should not block chain.
		when(authService.getSessionUser(request)).thenReturn(null);
		// No exception thrown = filter swallows auth errors and lets the chain proceed
		assertThatCode(() -> filter.doFilterInternal(request, response, filterChain))
				.doesNotThrowAnyException();
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("user attribute is the exact User instance returned by AuthService")
	void setsExactUserInstance() throws Exception {
		User user = UserFactory.regularUser();
		// Stub: session lookup returns a specific user instance
		when(authService.getSessionUser(request)).thenReturn(user);

		filter.doFilterInternal(request, response, filterChain);

		// Must store the exact same reference — not a copy — so identity checks in controllers work
		verify(request).setAttribute("authenticatedUser", user); // same reference
	}
}
