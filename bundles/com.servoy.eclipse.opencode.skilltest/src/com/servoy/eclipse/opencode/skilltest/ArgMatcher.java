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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches an actual argument value against an expectation, supporting several
 * strategies. Used by {@link TranscriptComparator} to compare MCP tool-call
 * arguments tolerantly by default and precisely where a baseline opts in.
 * <p>
 * The supported kinds are:
 * </p>
 * <ul>
 * <li>{@code TOLERANT} (default) — case-insensitive, whitespace-normalised
 * scalar compare; lists compared as sets. Volatile values that legitimately
 * differ between runs (UUIDs, timestamps/dates, absolute filesystem paths) are
 * masked to a placeholder before comparison, so a freshly generated id never
 * fails a run. Opt out per argument with {@code EXACT}.</li>
 * <li>{@code EXACT} — case-sensitive equality with NO volatile masking; use to
 * pin a value that would otherwise be masked (e.g. a deterministic id).</li>
 * <li>{@code EQUALS} — exact (case-sensitive) equality after string
 * conversion.</li>
 * <li>{@code CONTAINS} — the actual (stringified) value contains the expected
 * substring (case-insensitive).</li>
 * <li>{@code CONTAINS_ALL} — the actual collection/string contains all expected
 * elements (case-insensitive, order-independent).</li>
 * <li>{@code REGEX} — the actual stringified value fully matches the expected
 * regular expression.</li>
 * <li>{@code PRESENT} — the argument merely has to be present (non-null).</li>
 * </ul>
 */
public final class ArgMatcher {

	/** The comparison strategy. */
	public enum Kind {
		TOLERANT, EXACT, EQUALS, CONTAINS, CONTAINS_ALL, REGEX, PRESENT
	}

	/** Placeholder that all volatile values are masked to before comparison. */
	private static final String VOLATILE_MASK = "\u2039volatile\u203a"; //$NON-NLS-1$

	/** UUID (with or without hyphens). */
	private static final Pattern UUID_PATTERN = Pattern
			.compile("\\b[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}\\b"); //$NON-NLS-1$

	/** ISO-8601 date / date-time (e.g. 2026-09-04, 2026-09-04T12:30:00Z). */
	private static final Pattern ISO_DATE_PATTERN = Pattern
			.compile("\\b\\d{4}-\\d{2}-\\d{2}([t ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(z|[+-]\\d{2}:?\\d{2})?)?\\b"); //$NON-NLS-1$

	/** Long run of digits that looks like an epoch timestamp (10+ digits). */
	private static final Pattern EPOCH_PATTERN = Pattern.compile("\\b\\d{10,}\\b"); //$NON-NLS-1$

	/** Absolute filesystem path (Unix /... or Windows C:\...). */
	private static final Pattern ABS_PATH_PATTERN = Pattern.compile("(?:[A-Za-z]:\\\\|/)[^\\s\"']*"); //$NON-NLS-1$

	private final Kind kind;
	private final Object expected;

	private ArgMatcher(Kind kind, Object expected) {
		this.kind = kind;
		this.expected = expected;
	}

	public static ArgMatcher tolerant(Object expected) {
		return new ArgMatcher(Kind.TOLERANT, expected);
	}

	public static ArgMatcher exact(Object expected) {
		return new ArgMatcher(Kind.EXACT, expected);
	}

	public static ArgMatcher equalsMatcher(Object expected) {
		return new ArgMatcher(Kind.EQUALS, expected);
	}

	public static ArgMatcher contains(Object expected) {
		return new ArgMatcher(Kind.CONTAINS, expected);
	}

	public static ArgMatcher containsAll(Object expected) {
		return new ArgMatcher(Kind.CONTAINS_ALL, expected);
	}

	public static ArgMatcher regex(Object expected) {
		return new ArgMatcher(Kind.REGEX, expected);
	}

	public static ArgMatcher present() {
		return new ArgMatcher(Kind.PRESENT, null);
	}

	public Kind getKind() {
		return kind;
	}

	public Object getExpected() {
		return expected;
	}

	/**
	 * @return {@code true} if {@code actual} satisfies this matcher
	 */
	public boolean matches(Object actual) {
		switch (kind) {
		case PRESENT:
			return actual != null;
		case EXACT:
			return String.valueOf(expected).equals(String.valueOf(actual));
		case EQUALS:
			return String.valueOf(expected).equals(String.valueOf(actual));
		case CONTAINS:
			return norm(actual).contains(norm(expected));
		case CONTAINS_ALL:
			return matchesContainsAll(actual);
		case REGEX:
			return matchesRegex(actual);
		case TOLERANT:
		default:
			return tolerantEquals(expected, actual);
		}
	}

	private boolean matchesRegex(Object actual) {
		try {
			return Pattern.compile(String.valueOf(expected)).matcher(String.valueOf(actual)).matches();
		} catch (PatternSyntaxException e) {
			return false;
		}
	}

	private boolean matchesContainsAll(Object actual) {
		Set<String> actualSet = asNormalizedSet(actual);
		for (String want : asNormalizedList(expected)) {
			if (!actualSet.contains(want)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tolerant equality: scalar strings compared case-insensitively and
	 * whitespace-normalised; collections compared as sets.
	 */
	public static boolean tolerantEquals(Object expected, Object actual) {
		if (expected == null) {
			return true;
		}
		if (expected instanceof Collection || actual instanceof Collection) {
			return asNormalizedSet(expected).equals(asNormalizedSet(actual));
		}
		return norm(expected).equals(norm(actual));
	}

	private static String norm(Object value) {
		if (value == null) {
			return ""; //$NON-NLS-1$
		}
		String s = String.valueOf(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
		return maskVolatile(s);
	}

	/**
	 * Replaces values that legitimately differ between LLM runs (UUIDs,
	 * timestamps/dates, epoch numbers, absolute filesystem paths) with a fixed
	 * placeholder, so tolerant comparison treats "same shape, different generated
	 * value" as equal. Applied in {@link #norm(Object)}, which the {@code TOLERANT}
	 * / {@code CONTAINS} / {@code CONTAINS_ALL} paths use; {@code EXACT} and
	 * {@code EQUALS} bypass this to allow pinning a specific value.
	 *
	 * @param value the already lower-cased, whitespace-normalised value
	 * @return the value with volatile substrings masked
	 */
	static String maskVolatile(String value) {
		String out = replaceAllSafe(UUID_PATTERN, value);
		out = replaceAllSafe(ISO_DATE_PATTERN, out);
		out = replaceAllSafe(ABS_PATH_PATTERN, out);
		out = replaceAllSafe(EPOCH_PATTERN, out);
		return out;
	}

	private static String replaceAllSafe(Pattern pattern, String value) {
		Matcher m = pattern.matcher(value);
		return m.replaceAll(VOLATILE_MASK);
	}

	private static Set<String> asNormalizedSet(Object value) {
		return new LinkedHashSet<>(asNormalizedList(value));
	}

	private static List<String> asNormalizedList(Object value) {
		List<String> out = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				out.add(norm(element));
			}
		} else if (value != null) {
			out.add(norm(value));
		}
		return out;
	}
}
