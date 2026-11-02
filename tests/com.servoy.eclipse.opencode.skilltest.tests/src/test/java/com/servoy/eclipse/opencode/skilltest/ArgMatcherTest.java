/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ArgMatcher} — each matcher kind plus the tolerant
 * default equality used by {@link TranscriptComparator}.
 */
class ArgMatcherTest {

	@Nested
	class Present {

		@Test
		@DisplayName("present matches any non-null value")
		void matchesNonNull() {
			assertTrue(ArgMatcher.present().matches("anything"));
		}

		@Test
		@DisplayName("present fails on a missing (null) value")
		void failsOnNull() {
			assertFalse(ArgMatcher.present().matches(null));
		}
	}

	@Nested
	class Equals {

		@Test
		@DisplayName("equals is exact and case-sensitive")
		void exactMatch() {
			assertTrue(ArgMatcher.equalsMatcher("Colors").matches("Colors"));
		}

		@Test
		@DisplayName("equals fails on a case difference")
		void caseSensitiveMismatch() {
			assertFalse(ArgMatcher.equalsMatcher("Colors").matches("colors"));
		}
	}

	@Nested
	class Contains {

		@ParameterizedTest
		@CsvSource({ "hello world, WORLD, true", "hello world, world, true", "hello world, mars, false" })
		@DisplayName("contains is a case-insensitive substring check")
		void substring(String actual, String expected, boolean expectMatch) {
			assertTrue(ArgMatcher.contains(expected).matches(actual) == expectMatch);
		}
	}

	@Nested
	class ContainsAll {

		@Test
		@DisplayName("containsAll passes when the actual list holds every expected element")
		void allPresent() {
			assertTrue(ArgMatcher.containsAll(List.of("one", "two", "three")).matches(List.of("one", "two", "three")));
		}

		@Test
		@DisplayName("containsAll is order-independent and tolerates extras")
		void orderIndependentWithExtras() {
			assertTrue(ArgMatcher.containsAll(List.of("one", "two")).matches(List.of("three", "TWO", "one")));
		}

		@Test
		@DisplayName("containsAll fails when an expected element is missing")
		void missingElement() {
			assertFalse(ArgMatcher.containsAll(List.of("one", "two", "four")).matches(List.of("one", "two", "three")));
		}
	}

	@Nested
	class Regex {

		@Test
		@DisplayName("regex fully matches the stringified value")
		void validMatch() {
			assertTrue(ArgMatcher.regex("colou?rs").matches("colors"));
		}

		@Test
		@DisplayName("regex requires a full match, not a partial one")
		void requiresFullMatch() {
			assertFalse(ArgMatcher.regex("col").matches("colors"));
		}

		@Test
		@DisplayName("an invalid regex fails safely instead of throwing")
		void invalidRegexFailsSafely() {
			assertFalse(ArgMatcher.regex("[unclosed").matches("colors"));
		}
	}

	@Nested
	class Tolerant {

		@Test
		@DisplayName("tolerant scalar equality is case-insensitive and whitespace-normalised")
		void tolerantScalar() {
			assertTrue(ArgMatcher.tolerant("One").matches("one "));
		}

		@Test
		@DisplayName("tolerant equality treats lists as order-independent sets")
		void tolerantListsAsSets() {
			assertTrue(ArgMatcher.tolerant(List.of("one", "two", "three")).matches(List.of("three", "one", "two")));
		}

		@Test
		@DisplayName("tolerant scalar mismatch fails")
		void tolerantScalarMismatch() {
			assertFalse(ArgMatcher.tolerant("colors").matches("shapes"));
		}

		@Test
		@DisplayName("tolerantEquals: a null expectation matches anything (unconstrained)")
		void nullExpectedMatchesAnything() {
			assertTrue(ArgMatcher.tolerantEquals(null, "whatever"));
			assertTrue(ArgMatcher.tolerantEquals(null, null));
		}

		@Test
		@DisplayName("tolerantEquals: whitespace inside strings is collapsed")
		void whitespaceCollapsed() {
			assertTrue(ArgMatcher.tolerantEquals("a  b\tc", "a b c"));
		}
	}

	@Nested
	class TolerantLike {

		@Test
		@DisplayName("a bare %...% expectation is a SQL-LIKE pattern: %low%medium%high% matches the .val's low/medium/high")
		void customValuesLikePatternMatches() {
			// The .val stores "low\r\nmedium\r\nhigh"; the checker normalises that to
			// "low medium high", and the authored LIKE pattern must match across the
			// collapsed separators (SVY-21366).
			assertTrue(ArgMatcher.tolerant("%low%medium%high%").matches("low\r\nmedium\r\nhigh"));
			assertTrue(ArgMatcher.tolerant("%low%medium%high%").matches("low medium high"));
		}

		@Test
		@DisplayName("a LIKE pattern is anchored and case-insensitive")
		void likeIsAnchoredAndCaseInsensitive() {
			assertTrue(ArgMatcher.tolerant("%LOW%").matches("low medium high"));
			assertTrue(ArgMatcher.tolerant("low%").matches("low medium high"));
			assertFalse(ArgMatcher.tolerant("%medium").matches("low medium high")); // not anchored at end
		}

		@Test
		@DisplayName("underscore matches exactly one character")
		void underscoreMatchesOne() {
			assertTrue(ArgMatcher.tolerant("l_w").matches("low"));
			assertFalse(ArgMatcher.tolerant("l_w").matches("loow"));
		}

		@Test
		@DisplayName("an escaped \\% is a literal percent, not a wildcard")
		void escapedPercentIsLiteral() {
			assertTrue(ArgMatcher.tolerant("50\\%").matches("50%"));
			assertFalse(ArgMatcher.tolerant("50\\%").matches("50 percent off"));
		}

		@Test
		@DisplayName("a wildcard LIKE still fails when the stable tokens are absent")
		void likeFailsWhenTokenMissing() {
			assertFalse(ArgMatcher.tolerant("%low%medium%high%").matches("low high"));
		}

		@Test
		@DisplayName("a no-wildcard tolerant string keeps the literal normalised compare")
		void noWildcardKeepsLiteralCompare() {
			assertTrue(ArgMatcher.tolerant("low medium high").matches("low  medium\thigh"));
			assertFalse(ArgMatcher.tolerant("low medium high").matches("low medium"));
		}
	}

	@Nested
	class VolatileMasking {

		@Test
		@DisplayName("tolerant equality ignores a differing UUID in the value")
		void differingUuidMatches() {
			assertTrue(ArgMatcher.tolerantEquals("form with id 550e8400-e29b-41d4-a716-446655440000",
					"form with id 123e4567-e89b-12d3-a456-426614174000"));
		}

		@Test
		@DisplayName("tolerant equality ignores a differing ISO timestamp")
		void differingTimestampMatches() {
			assertTrue(ArgMatcher.tolerantEquals("created at 2026-09-04T12:30:00Z", "created at 2026-01-01T00:00:00Z"));
		}

		@Test
		@DisplayName("tolerant equality ignores a differing absolute path")
		void differingAbsolutePathMatches() {
			assertTrue(ArgMatcher.tolerantEquals("saved to C:\\Users\\alice\\ws\\sol\\forms\\a.frm",
					"saved to C:\\Users\\bob\\workspace\\sol\\forms\\a.frm"));
		}

		@Test
		@DisplayName("a Servoy db:/ datasource is NOT masked as a volatile path (SVY-21366)")
		void datasourceIsNotMaskedAsPath() {
			// db:/example_data/customers is deterministic and spec-meaningful; the
			// path masker must not swallow the '/...' after the 'db:' scheme.
			assertTrue(ArgMatcher.tolerantEquals("db:/example_data/customers", "db:/example_data/customers"),
					"identical datasources must match");
			assertFalse(ArgMatcher.tolerantEquals("db:/example_data/customers", "db:/example_data/orders"),
					"different datasources must NOT match (not both masked away)");
			assertFalse(ArgMatcher.tolerantEquals("mem:cache", "mem:other"),
					"a mem: datasource is likewise not masked");
		}

		@Test
		@DisplayName("tolerant equality ignores a differing epoch number")
		void differingEpochMatches() {
			assertTrue(ArgMatcher.tolerantEquals("ts=1725448200000", "ts=1700000000000"));
		}

		@Test
		@DisplayName("tolerant equality still fails on a real, non-volatile difference")
		void realDifferenceStillFails() {
			assertFalse(ArgMatcher.tolerantEquals("value list colors id 550e8400-e29b-41d4-a716-446655440000",
					"value list shapes id 123e4567-e89b-12d3-a456-426614174000"));
		}

		@Test
		@DisplayName("exact matcher does NOT mask volatile values (pins the id)")
		void exactPinsVolatile() {
			assertTrue(ArgMatcher.exact("550e8400-e29b-41d4-a716-446655440000")
					.matches("550e8400-e29b-41d4-a716-446655440000"));
			assertFalse(ArgMatcher.exact("550e8400-e29b-41d4-a716-446655440000")
					.matches("123e4567-e89b-12d3-a456-426614174000"));
		}

		@Test
		@DisplayName("maskVolatile collapses a bare UUID to the placeholder")
		void maskBareUuid() {
			assertTrue(ArgMatcher.maskVolatile("550e8400-e29b-41d4-a716-446655440000")
					.equals(ArgMatcher.maskVolatile("123e4567-e89b-12d3-a456-426614174000")));
		}
	}
}
