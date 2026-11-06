/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.auth;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.developer.mcp.auth.BearerTokenAuthenticationFilter;
import com.servoy.eclipse.opencode.ProviderConfigWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JUnit 5/6 (Jupiter) plain unit tests for
 * {@link BearerTokenAuthenticationFilter} — SVY-21688.
 *
 * <p>
 * Covers the per-request gate added in SVY-21688: the ordered <em>loopback
 * &rarr; {@code @servoy.com} internal bypass &rarr; {@code GENAI_API_KEY}
 * entitlement &rarr; bearer token</em> evaluation, where every deny emits a
 * bare {@code 404} ("look absent": no {@code WWW-Authenticate}/{@code Allow}
 * header, no JSON body) and both {@code service(...)} and {@code doFilter(...)}
 * enforce it identically.
 * </p>
 *
 * <p>
 * No OSGi / workbench: the {@code @servoy.com} identity is faked through the
 * package-visible
 * {@link BearerTokenAuthenticationFilter#isServoyInternalUser()} seam
 * (overridden in a test subclass) rather than touching
 * {@code ISecurePreferences}.
 * </p>
 *
 * <p>
 * SVY-21750 moved the svyAI key out of the {@code GENAI_API_KEY} system
 * property and into the Servoy login secure-store node; the gate's entitlement
 * check (R4) now sources the value through
 * {@link ProviderConfigWriter#getApiKey()}, which reads the secure-store seam
 * first and only falls back to the {@code -D GENAI_API_KEY} system property.
 * Entitlement is therefore driven here through the same accessor:
 * {@link #setEntitled()} substitutes the package-private
 * {@code ProviderConfigWriter.secureStoreReader} supplier seam (reached
 * reflectively - this test is in a different package from the seam) so the
 * whole secure-store-first path is exercised without a live keyring. Both the
 * seam and the {@code -D GENAI_API_KEY} property are saved and restored around
 * every test so no static state leaks.
 * </p>
 */
class BearerTokenAuthenticationFilterTest {
	private static final String VALID_TOKEN = "test-token-12345";
	private static final String WRONG_TOKEN = "wrong-token";
	private static final String GENAI_KEY = "GENAI_API_KEY";
	private static final String VALID_API_KEY = "svy-ai-key-abcdef";

	private static final String LOOPBACK_V4 = "127.0.0.1";
	private static final String OFF_BOX = "10.0.0.5";

	private boolean delegateCalled;
	private String savedApiKey;
	private Supplier<String> savedSecureStoreReader;

	private final HttpServlet delegate = new HttpServlet() {
		private static final long serialVersionUID = 1L;

		@Override
		public void service(ServletRequest req, ServletResponse res) {
			delegateCalled = true;
		}
	};

	@BeforeEach
	void saveApiKeyState() throws Exception {
		delegateCalled = false;
		savedApiKey = System.getProperty(GENAI_KEY);
		savedSecureStoreReader = getSecureStoreReader();
		// Start every test from a known "not entitled" baseline: both entitlement
		// sources (secure-store seam and -D property) report no value. Individual tests
		// set them as needed via setEntitled().
		setSecureStoreReader(() -> null);
		System.clearProperty(GENAI_KEY);
	}

	@AfterEach
	void restoreApiKeyState() throws Exception {
		setSecureStoreReader(savedSecureStoreReader);
		if (savedApiKey == null) {
			System.clearProperty(GENAI_KEY);
		} else {
			System.setProperty(GENAI_KEY, savedApiKey);
		}
	}

	// --- secure-store seam (SVY-21750) -------------------------------------
	// The entitlement value flows through ProviderConfigWriter.getApiKey(), which
	// reads the package-private secureStoreReader supplier first. This test lives
	// in a different package, so the seam is reached reflectively.

	@SuppressWarnings("unchecked")
	private static Supplier<String> getSecureStoreReader() throws Exception {
		Field field = ProviderConfigWriter.class.getDeclaredField("secureStoreReader");
		field.setAccessible(true);
		return (Supplier<String>) field.get(null);
	}

	private static void setSecureStoreReader(Supplier<String> reader) throws Exception {
		Field field = ProviderConfigWriter.class.getDeclaredField("secureStoreReader");
		field.setAccessible(true);
		field.set(null, reader);
	}

	// --- Helpers -----------------------------------------------------------

	/**
	 * Builds a filter whose
	 * {@link BearerTokenAuthenticationFilter#isServoyInternalUser()} seam is faked,
	 * so the gate's internal-user branch can be exercised without secure storage.
	 */
	private BearerTokenAuthenticationFilter newFilter(String expectedToken, boolean internalUser) {
		return new BearerTokenAuthenticationFilter(expectedToken, delegate) {
			@Override
			protected boolean isServoyInternalUser() {
				return internalUser;
			}
		};
	}

	/**
	 * Marks the login as entitled by supplying the svyAI key through the
	 * secure-store seam - the production source after SVY-21750. The gate reads it
	 * via {@link ProviderConfigWriter#getApiKey()}. The {@code -D GENAI_API_KEY}
	 * fallback path is covered separately in {@link EntitlementSourceFallback}.
	 */
	private void setEntitled() throws Exception {
		setSecureStoreReader(() -> VALID_API_KEY);
	}

	/**
	 * Asserts a deny: 404, no body written, no auth/allow headers, delegate
	 * untouched.
	 */
	private void assertLooksAbsent(FakeHttpServletResponse response) {
		assertAll("deny must be indistinguishable from an unmapped path",
				() -> assertEquals(404, response.getStatus(), "deny must be HTTP 404"),
				() -> assertTrue(response.wasSendErrorCalled(), "deny must go through sendError"),
				() -> assertFalse(delegateCalled, "delegate must not be invoked on deny"),
				() -> assertFalse(response.bodyWasWritten(), "deny must not write a response body"),
				() -> assertFalse(response.hasHeader("WWW-Authenticate"), "deny must carry no WWW-Authenticate header"),
				() -> assertFalse(response.hasHeader("Allow"), "deny must carry no Allow header"),
				() -> assertFalse(response.getBody().contains("\"error\""), "deny must carry no JSON error body"));
	}

	// --- 1. Off-box ⇒ 404 --------------------------------------------------

	@Nested
	class OffBox {
		@Test
		@DisplayName("Non-loopback remote ⇒ 404, delegate not invoked, even with valid token + entitlement")
		void offBoxEntitledValidToken_denied() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(OFF_BOX);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Non-loopback remote ⇒ 404 even for an internal user (loopback check is first)")
		void offBoxInternalUser_denied() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, true);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(OFF_BOX);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 2. Internal user, no key, no token ⇒ allowed ----------------------

	@Nested
	class InternalUser {
		@Test
		@DisplayName("@servoy.com internal user from loopback, no GENAI_API_KEY, no token ⇒ allowed")
		void internalUserNoKeyNoToken_allowed() throws Exception {
			// Baseline @BeforeEach already cleared GENAI_API_KEY.
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, true);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertAll(() -> assertTrue(delegateCalled, "internal user must reach the delegate"),
					() -> assertFalse(response.wasSendErrorCalled(), "internal user must not be denied"));
		}
	}

	// --- 3. Entitled + valid token ⇒ allowed -------------------------------

	@Nested
	class EntitledValidToken {
		@Test
		@DisplayName("Loopback + GENAI_API_KEY set + correct bearer token, non-internal ⇒ allowed")
		void entitledValidToken_allowed() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertAll(() -> assertTrue(delegateCalled, "entitled + valid token must reach the delegate"),
					() -> assertFalse(response.wasSendErrorCalled(), "must not be denied"));
		}

		@ParameterizedTest
		@ValueSource(strings = { "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" })
		@DisplayName("Every loopback form is accepted (entitled + valid token ⇒ allowed)")
		void loopbackFormsAccepted(String loopbackAddr) throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(loopbackAddr);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertAll(() -> assertTrue(delegateCalled, "loopback '" + loopbackAddr + "' must be accepted"),
					() -> assertFalse(response.wasSendErrorCalled(),
							"loopback '" + loopbackAddr + "' must not be denied"));
		}
	}

	// --- 4. Unentitled non-internal ⇒ 404 ---------------------------------

	@Nested
	class Unentitled {
		@Test
		@DisplayName("Loopback non-internal with no GENAI_API_KEY ⇒ 404")
		void noKey_denied() throws Exception {
			// GENAI_API_KEY cleared by @BeforeEach.
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "\t" })
		@DisplayName("Loopback non-internal with blank GENAI_API_KEY ⇒ 404")
		void blankKey_denied(String blankKey) throws Exception {
			if (blankKey == null) {
				System.clearProperty(GENAI_KEY);
			} else {
				System.setProperty(GENAI_KEY, blankKey);
			}
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 5. Wrong/missing token ⇒ 404 (not 401) ---------------------------

	@Nested
	class WrongOrMissingToken {
		@Test
		@DisplayName("Entitled non-internal from loopback, missing Authorization header ⇒ 404 (not 401)")
		void missingHeader_denied404() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertEquals(404, response.getStatus(), "missing token must be 404, never 401");
			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Entitled non-internal from loopback, wrong bearer token ⇒ 404 (not 401)")
		void wrongToken_denied404() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + WRONG_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertEquals(404, response.getStatus(), "wrong token must be 404, never 401");
			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Entitled non-internal from loopback, non-Bearer scheme ⇒ 404")
		void nonBearerScheme_denied404() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Basic dXNlcjpwYXNz");
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Blank expected token ⇒ 404 even when entitled and a token is presented")
		void blankExpectedToken_denied404() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter("", false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer anything");
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Null expected token ⇒ 404 even when entitled and a token is presented")
		void nullExpectedToken_denied404() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(null, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer anything");
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 6. Deny carries nothing ------------------------------------------

	@Nested
	class DenyResponseShape {
		@Test
		@DisplayName("Off-box deny carries no WWW-Authenticate / Allow header and no JSON body")
		void offBoxDeny_hasNoHeadersNoBody() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(OFF_BOX);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Unentitled deny carries no WWW-Authenticate / Allow header and no JSON body")
		void unentitledDeny_hasNoHeadersNoBody() throws Exception {
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Wrong-token deny carries no WWW-Authenticate / Allow header and no JSON body")
		void wrongTokenDeny_hasNoHeadersNoBody() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + WRONG_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 7. Forwarded headers ignored -------------------------------------

	@Nested
	class ForwardedHeadersIgnored {
		@Test
		@DisplayName("X-Forwarded-For/Host spoofed to loopback but non-loopback peer ⇒ still 404")
		void spoofedForwardedHeaders_stillDenied() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(OFF_BOX);
			request.setHeader("X-Forwarded-For", LOOPBACK_V4);
			request.setHeader("Host", LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 8. Fail closed ----------------------------------------------------

	@Nested
	class FailClosed {
		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "not-an-address", "999.999.999.999", "gibberish::x" })
		@DisplayName("Unresolvable / null / blank remote address ⇒ treated as non-loopback ⇒ 404")
		void unresolvableRemoteAddr_denied(String remoteAddr) throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(remoteAddr);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- 9. Both entry points enforce the same gate ------------------------

	@Nested
	class BothEntryPoints {
		@Test
		@DisplayName("Loopback + entitled + valid token ⇒ allowed via service() AND doFilter()")
		void allowedViaBothEntryPoints() throws Exception {
			setEntitled();

			// service()
			BearerTokenAuthenticationFilter serviceFilter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest serviceReq = new FakeHttpServletRequest();
			serviceReq.setRemoteAddr(LOOPBACK_V4);
			serviceReq.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse serviceResp = new FakeHttpServletResponse();
			delegateCalled = false;
			serviceFilter.service(serviceReq, serviceResp);
			boolean serviceAllowed = delegateCalled && !serviceResp.wasSendErrorCalled();

			// doFilter()
			BearerTokenAuthenticationFilter filterFilter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest filterReq = new FakeHttpServletRequest();
			filterReq.setRemoteAddr(LOOPBACK_V4);
			filterReq.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse filterResp = new FakeHttpServletResponse();
			RecordingFilterChain chain = new RecordingFilterChain();
			filterFilter.doFilter(filterReq, filterResp, chain);
			boolean filterAllowed = chain.wasCalled() && !filterResp.wasSendErrorCalled();

			assertAll(() -> assertTrue(serviceAllowed, "service() must allow"),
					() -> assertTrue(filterAllowed, "doFilter() must allow"),
					() -> assertEquals(serviceAllowed, filterAllowed, "both entry points must agree"));
		}

		@Test
		@DisplayName("Off-box ⇒ denied via service() AND doFilter(), chain/delegate untouched")
		void deniedViaBothEntryPoints() throws Exception {
			setEntitled();

			// service()
			BearerTokenAuthenticationFilter serviceFilter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest serviceReq = new FakeHttpServletRequest();
			serviceReq.setRemoteAddr(OFF_BOX);
			serviceReq.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse serviceResp = new FakeHttpServletResponse();
			delegateCalled = false;
			serviceFilter.service(serviceReq, serviceResp);
			boolean serviceDenied = !delegateCalled && serviceResp.getStatus() == 404;

			// doFilter()
			BearerTokenAuthenticationFilter filterFilter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest filterReq = new FakeHttpServletRequest();
			filterReq.setRemoteAddr(OFF_BOX);
			filterReq.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse filterResp = new FakeHttpServletResponse();
			RecordingFilterChain chain = new RecordingFilterChain();
			filterFilter.doFilter(filterReq, filterResp, chain);
			boolean filterDenied = !chain.wasCalled() && filterResp.getStatus() == 404;

			assertAll(() -> assertTrue(serviceDenied, "service() must deny off-box with 404"),
					() -> assertTrue(filterDenied, "doFilter() must deny off-box with 404"),
					() -> assertFalse(chain.wasCalled(), "doFilter deny must not call the chain"),
					() -> assertEquals(serviceDenied, filterDenied, "both entry points must agree"));
		}

		@Test
		@DisplayName("doFilter(): internal user from loopback with no key/token ⇒ chain invoked")
		void doFilterInternalUser_allowed() throws Exception {
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, true);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			FakeHttpServletResponse response = new FakeHttpServletResponse();
			RecordingFilterChain chain = new RecordingFilterChain();

			filter.doFilter(request, response, chain);

			assertAll(() -> assertTrue(chain.wasCalled(), "internal user must pass the chain"),
					() -> assertFalse(response.wasSendErrorCalled(), "internal user must not be denied"));
		}

		@Test
		@DisplayName("doFilter(): unentitled non-internal from loopback ⇒ 404, chain not invoked")
		void doFilterUnentitled_denied() throws Exception {
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();
			RecordingFilterChain chain = new RecordingFilterChain();

			filter.doFilter(request, response, chain);

			assertAll(() -> assertEquals(404, response.getStatus(), "unentitled must be 404"),
					() -> assertFalse(chain.wasCalled(), "unentitled deny must not call the chain"),
					() -> assertFalse(response.bodyWasWritten(), "deny must not write a body"));
		}
	}

	// --- 9b. Entitlement source: -D GENAI_API_KEY fallback (SVY-21750) ----
	// The default setEntitled()/@BeforeEach drive entitlement through the
	// secure-store seam (the post-SVY-21750 production source). These tests pin the
	// other arm of ProviderConfigWriter.getApiKey(): with the seam reporting no
	// value, the -D GENAI_API_KEY system property still entitles the gate (the
	// headless/CI fallback), and a blank/absent property with an empty seam denies.

	@Nested
	class EntitlementSourceFallback {
		@Test
		@DisplayName("Seam empty but -D GENAI_API_KEY set ⇒ entitled via the accessor's -D fallback ⇒ allowed")
		void propertyFallbackEntitles_allowed() throws Exception {
			// @BeforeEach already set the seam to report no value.
			System.setProperty(GENAI_KEY, VALID_API_KEY);
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertAll(() -> assertTrue(delegateCalled, "the -D GENAI_API_KEY fallback must entitle the gate"),
					() -> assertFalse(response.wasSendErrorCalled(), "entitled via -D fallback must not be denied"));
		}

		@Test
		@DisplayName("Seam empty and no -D GENAI_API_KEY ⇒ not entitled ⇒ 404")
		void noSeamNoProperty_denied() throws Exception {
			// @BeforeEach cleared both the seam and the -D property.
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}

		@Test
		@DisplayName("Seam throws and no -D GENAI_API_KEY ⇒ accessor yields null ⇒ 404 (no propagation)")
		void seamThrowsNoProperty_denied() throws Exception {
			setSecureStoreReader(() -> {
				throw new RuntimeException("simulated locked keyring");
			});
			BearerTokenAuthenticationFilter filter = newFilter(VALID_TOKEN, false);
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(request, response);

			assertLooksAbsent(response);
		}
	}

	// --- Delegate exception wrapping (SVY-21124) — allow path reaches delegate ---

	@Nested
	class DelegateExceptionWrapping {
		private BearerTokenAuthenticationFilter errorFilter(HttpServlet throwingDelegate) {
			return new BearerTokenAuthenticationFilter(VALID_TOKEN, throwingDelegate) {
				@Override
				protected boolean isServoyInternalUser() {
					return false;
				}
			};
		}

		private FakeHttpServletRequest allowedRequest() {
			FakeHttpServletRequest request = new FakeHttpServletRequest();
			request.setRemoteAddr(LOOPBACK_V4);
			request.setHeader("Authorization", "Bearer " + VALID_TOKEN);
			return request;
		}

		@Test
		@DisplayName("Delegate ServletException ⇒ JSON-RPC -32700 parse error")
		void delegateThrowsServletException_returnsJsonRpc32700() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException("Unexpected char 0x0A at line 3\ncol 15: was expecting comma");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			assertEquals("application/json", response.getContentType());
			String body = response.getBody();
			assertTrue(body.contains("\"code\":-32700"), "Response must contain error code -32700");
			assertTrue(body.contains("Please ensure the request body is valid JSON."),
					"Response must contain human-readable message");
			new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		}

		@Test
		@DisplayName("Delegate RuntimeException ⇒ JSON-RPC -32700 parse error")
		void delegateThrowsRuntimeException_returnsJsonRpc32700() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) {
					throw new RuntimeException("Unexpected end of JSON input");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			assertEquals("application/json", response.getContentType());
			String body = response.getBody();
			assertTrue(body.contains("\"code\":-32700"), "Response must contain error code -32700");
			assertTrue(body.contains("Please ensure the request body is valid JSON."),
					"Response must contain human-readable message");
		}

		@Test
		@DisplayName("Delegate IOException ⇒ JSON-RPC -32700 parse error")
		void delegateThrowsIOException_returnsJsonRpc32700() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws IOException {
					throw new IOException("Stream closed unexpectedly while parsing JSON");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			assertEquals("application/json", response.getContentType());
			assertTrue(response.getBody().contains("\"code\":-32700"), "Response must contain error code -32700");
		}

		@Test
		@DisplayName("Delegate exception with null message ⇒ falls back to class name")
		void delegateThrowsNullMessage_usesClassName() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) {
					throw new NullPointerException();
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			assertEquals("application/json", response.getContentType());
			String body = response.getBody();
			assertTrue(body.contains("\"code\":-32700"), "Response must contain error code -32700");
			assertTrue(body.contains("NullPointerException"), "Response should contain class name as fallback");
			new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		}

		@Test
		@DisplayName("Delegate message with quotes ⇒ valid JSON")
		void delegateThrowsMessageWithQuotes_escapedInJson() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException("Expected '\"' but got 'x' at position 42");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(response.getBody());
			assertNotNull(node, "Response must be valid JSON even with quotes in message");
			assertTrue(node.has("error"));
			assertEquals(-32700, node.get("error").get("code").asInt());
		}

		@Test
		@DisplayName("Delegate message with newlines ⇒ valid JSON")
		void delegateThrowsMessageWithNewlines_escapedInJson() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException("Line 1\nLine 2\r\nLine 3\ttab");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(response.getBody());
			assertNotNull(node, "Response must be valid JSON even with newlines in message");
			assertEquals(-32700, node.get("error").get("code").asInt());
		}

		@Test
		@DisplayName("Delegate message with unicode ⇒ valid JSON")
		void delegateThrowsMessageWithUnicode_escapedInJson() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException(
							"Unexpected character '\u00e9' (\u00c3\u00a9) at offset 0x1F \u2014 invalid");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(response.getBody());
			assertNotNull(node, "Response must be valid JSON even with unicode in message");
			assertEquals(-32700, node.get("error").get("code").asInt());
		}

		@Test
		@DisplayName("Delegate very long message ⇒ valid JSON")
		void delegateThrowsVeryLongMessage_producesValidJson() throws Exception {
			setEntitled();
			String longMsg = "A".repeat(10000) + " at position 99999";
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException(longMsg);
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(response.getBody());
			assertNotNull(node, "Response must be valid JSON even with very long message");
			assertEquals(-32700, node.get("error").get("code").asInt());
		}

		@Test
		@DisplayName("Delegate message with backslashes ⇒ valid JSON")
		void delegateThrowsMessageWithBackslashes_escapedInJson() throws Exception {
			setEntitled();
			BearerTokenAuthenticationFilter filter = errorFilter(new HttpServlet() {
				private static final long serialVersionUID = 1L;

				@Override
				public void service(ServletRequest req, ServletResponse res) throws ServletException {
					throw new ServletException("Path: C:\\Users\\test\\file.json parse error");
				}
			});
			FakeHttpServletResponse response = new FakeHttpServletResponse();

			filter.service(allowedRequest(), response);

			assertEquals(200, response.getStatus());
			com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(response.getBody());
			assertNotNull(node, "Response must be valid JSON even with backslashes in message");
			assertEquals(-32700, node.get("error").get("code").asInt());
		}
	}

	// --- Minimal fake implementations -------------------------------------

	private static class RecordingFilterChain implements FilterChain {
		private boolean called;

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			this.called = true;
		}

		boolean wasCalled() {
			return called;
		}
	}

	private static class FakeHttpServletRequest extends FakeServletRequest implements HttpServletRequest {
		private final Map<String, String> headers = new HashMap<>();

		void setHeader(String name, String value) {
			headers.put(name, value);
		}

		@Override
		public String getHeader(String name) {
			return headers.get(name);
		}

		@Override
		public String getAuthType() {
			return null;
		}

		@Override
		public jakarta.servlet.http.Cookie[] getCookies() {
			return null;
		}

		@Override
		public long getDateHeader(String name) {
			return 0;
		}

		@Override
		public java.util.Enumeration<String> getHeaders(String name) {
			return null;
		}

		@Override
		public java.util.Enumeration<String> getHeaderNames() {
			return null;
		}

		@Override
		public int getIntHeader(String name) {
			return 0;
		}

		@Override
		public String getMethod() {
			return "POST";
		}

		@Override
		public String getPathInfo() {
			return null;
		}

		@Override
		public String getPathTranslated() {
			return null;
		}

		@Override
		public String getContextPath() {
			return "";
		}

		@Override
		public String getQueryString() {
			return null;
		}

		@Override
		public String getRemoteUser() {
			return null;
		}

		@Override
		public boolean isUserInRole(String role) {
			return false;
		}

		@Override
		public java.security.Principal getUserPrincipal() {
			return null;
		}

		@Override
		public String getRequestedSessionId() {
			return null;
		}

		@Override
		public String getRequestURI() {
			return "/dev_mcp/test";
		}

		@Override
		public StringBuffer getRequestURL() {
			return new StringBuffer("http://localhost:8183/dev_mcp/test");
		}

		@Override
		public String getServletPath() {
			return "/dev_mcp/test";
		}

		@Override
		public jakarta.servlet.http.HttpSession getSession(boolean create) {
			return null;
		}

		@Override
		public jakarta.servlet.http.HttpSession getSession() {
			return null;
		}

		@Override
		public String changeSessionId() {
			return null;
		}

		@Override
		public boolean isRequestedSessionIdValid() {
			return false;
		}

		@Override
		public boolean isRequestedSessionIdFromCookie() {
			return false;
		}

		@Override
		public boolean isRequestedSessionIdFromURL() {
			return false;
		}

		@Override
		public boolean authenticate(HttpServletResponse response) {
			return false;
		}

		@Override
		public void login(String username, String password) {
		}

		@Override
		public void logout() {
		}

		@Override
		public java.util.Collection<jakarta.servlet.http.Part> getParts() {
			return null;
		}

		@Override
		public jakarta.servlet.http.Part getPart(String name) {
			return null;
		}

		@Override
		public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
			return null;
		}
	}

	private static class FakeServletRequest implements ServletRequest {
		// Default to loopback so a test that does not care about the address still
		// passes the gate;
		// tests that exercise loopback/off-box/fail-closed set this explicitly.
		private String remoteAddr = "127.0.0.1";

		void setRemoteAddr(String remoteAddr) {
			this.remoteAddr = remoteAddr;
		}

		@Override
		public String getRemoteAddr() {
			return remoteAddr;
		}

		@Override
		public Object getAttribute(String name) {
			return null;
		}

		@Override
		public java.util.Enumeration<String> getAttributeNames() {
			return null;
		}

		@Override
		public String getCharacterEncoding() {
			return null;
		}

		@Override
		public void setCharacterEncoding(String env) {
		}

		@Override
		public int getContentLength() {
			return 0;
		}

		@Override
		public long getContentLengthLong() {
			return 0;
		}

		@Override
		public String getContentType() {
			return null;
		}

		@Override
		public jakarta.servlet.ServletInputStream getInputStream() {
			return null;
		}

		@Override
		public String getParameter(String name) {
			return null;
		}

		@Override
		public java.util.Enumeration<String> getParameterNames() {
			return null;
		}

		@Override
		public String[] getParameterValues(String name) {
			return null;
		}

		@Override
		public java.util.Map<String, String[]> getParameterMap() {
			return null;
		}

		@Override
		public String getProtocol() {
			return "HTTP/1.1";
		}

		@Override
		public String getScheme() {
			return "http";
		}

		@Override
		public String getServerName() {
			return "localhost";
		}

		@Override
		public int getServerPort() {
			return 8183;
		}

		@Override
		public java.io.BufferedReader getReader() {
			return null;
		}

		@Override
		public String getRemoteHost() {
			return "localhost";
		}

		@Override
		public void setAttribute(String name, Object o) {
		}

		@Override
		public void removeAttribute(String name) {
		}

		@Override
		public java.util.Locale getLocale() {
			return java.util.Locale.getDefault();
		}

		@Override
		public java.util.Enumeration<java.util.Locale> getLocales() {
			return null;
		}

		@Override
		public boolean isSecure() {
			return false;
		}

		@Override
		public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) {
			return null;
		}

		@Override
		public int getRemotePort() {
			return 0;
		}

		@Override
		public String getLocalName() {
			return "localhost";
		}

		@Override
		public String getLocalAddr() {
			return "127.0.0.1";
		}

		@Override
		public int getLocalPort() {
			return 8183;
		}

		@Override
		public jakarta.servlet.ServletContext getServletContext() {
			return null;
		}

		@Override
		public jakarta.servlet.AsyncContext startAsync() {
			return null;
		}

		@Override
		public jakarta.servlet.AsyncContext startAsync(ServletRequest req, ServletResponse res) {
			return null;
		}

		@Override
		public boolean isAsyncStarted() {
			return false;
		}

		@Override
		public boolean isAsyncSupported() {
			return false;
		}

		@Override
		public jakarta.servlet.AsyncContext getAsyncContext() {
			return null;
		}

		@Override
		public jakarta.servlet.DispatcherType getDispatcherType() {
			return jakarta.servlet.DispatcherType.REQUEST;
		}

		@Override
		public String getRequestId() {
			return "";
		}

		@Override
		public String getProtocolRequestId() {
			return "";
		}

		@Override
		public jakarta.servlet.ServletConnection getServletConnection() {
			return null;
		}
	}

	private static class FakeHttpServletResponse implements HttpServletResponse {
		private int status = 200;
		private String contentType;
		private boolean sendErrorCalled;
		private final Map<String, String> headers = new HashMap<>();
		private final StringWriter bodyWriter = new StringWriter();
		private final PrintWriter writer = new PrintWriter(bodyWriter);

		@Override
		public int getStatus() {
			return status;
		}

		String getBody() {
			writer.flush();
			return bodyWriter.toString();
		}

		boolean bodyWasWritten() {
			writer.flush();
			return !bodyWriter.toString().isEmpty();
		}

		boolean wasSendErrorCalled() {
			return sendErrorCalled;
		}

		boolean hasHeader(String name) {
			return headers.containsKey(name);
		}

		@Override
		public void setStatus(int sc) {
			this.status = sc;
		}

		@Override
		public PrintWriter getWriter() {
			return writer;
		}

		@Override
		public void setContentType(String type) {
			this.contentType = type;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public void addCookie(jakarta.servlet.http.Cookie cookie) {
		}

		@Override
		public boolean containsHeader(String name) {
			return headers.containsKey(name);
		}

		@Override
		public String encodeURL(String url) {
			return url;
		}

		@Override
		public String encodeRedirectURL(String url) {
			return url;
		}

		@Override
		public void sendError(int sc, String msg) {
			this.status = sc;
			this.sendErrorCalled = true;
		}

		@Override
		public void sendError(int sc) {
			this.status = sc;
			this.sendErrorCalled = true;
		}

		@Override
		public void sendRedirect(String location) {
		}

		@Override
		public void sendRedirect(String location, int sc, boolean clearBuffer) {
		}

		@Override
		public void setDateHeader(String name, long date) {
		}

		@Override
		public void addDateHeader(String name, long date) {
		}

		@Override
		public void setHeader(String name, String value) {
			headers.put(name, value);
		}

		@Override
		public void addHeader(String name, String value) {
			headers.put(name, value);
		}

		@Override
		public void setIntHeader(String name, int value) {
			headers.put(name, String.valueOf(value));
		}

		@Override
		public void addIntHeader(String name, int value) {
			headers.put(name, String.valueOf(value));
		}

		@Override
		public java.util.Collection<String> getHeaders(String name) {
			return java.util.List.of();
		}

		@Override
		public java.util.Collection<String> getHeaderNames() {
			return headers.keySet();
		}

		@Override
		public String getHeader(String name) {
			return headers.get(name);
		}

		@Override
		public String getCharacterEncoding() {
			return "UTF-8";
		}

		@Override
		public jakarta.servlet.ServletOutputStream getOutputStream() {
			return null;
		}

		@Override
		public void setCharacterEncoding(String charset) {
		}

		@Override
		public void setContentLength(int len) {
		}

		@Override
		public void setContentLengthLong(long len) {
		}

		@Override
		public void setBufferSize(int size) {
		}

		@Override
		public int getBufferSize() {
			return 0;
		}

		@Override
		public void flushBuffer() {
		}

		@Override
		public void resetBuffer() {
		}

		@Override
		public boolean isCommitted() {
			return false;
		}

		@Override
		public void reset() {
		}

		@Override
		public void setLocale(java.util.Locale loc) {
		}

		@Override
		public java.util.Locale getLocale() {
			return java.util.Locale.getDefault();
		}
	}
}
