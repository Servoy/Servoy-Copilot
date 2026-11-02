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

import java.util.regex.Pattern;

/**
 * The single SQL-{@code LIKE} engine shared by every place a skill-test
 * expectation matches a string pattern: the {@code .frm} structure leaves
 * ({@link com.servoy.eclipse.opencode.skilltest.outcome.StructureMatcher}) and
 * the persist/argument {@code props} ({@link ArgMatcher}). Centralised here so
 * there is one definition of what {@code %} / {@code _} mean - a tester who
 * writes {@code %low%medium%high%} in a baseline gets the same semantics
 * whether that value lands on a form leaf or on a valuelist {@code
 * customValues} prop (SVY-21366).
 * <p>
 * Grammar: {@code %} matches any run of characters (including none), {@code _}
 * matches exactly one character, matched case-insensitively and anchored over
 * the whole value. {@code \%} / {@code \_} / {@code \\} escape a literal
 * {@code %} / {@code _} / backslash. A pattern with no unescaped wildcard
 * reduces to a case-insensitive equals.
 * </p>
 */
public final class SqlLike {

	private SqlLike() {
	}

	/**
	 * SQL-{@code LIKE} match, anchored over the whole value and case-insensitive.
	 *
	 * @param pattern the expected string, possibly carrying {@code %} / {@code _}
	 * @param value   the actual string
	 * @return {@code true} if {@code value} matches {@code pattern}
	 */
	public static boolean matches(String pattern, String value) {
		if (pattern == null || value == null) {
			return pattern == value;
		}
		StringBuilder re = new StringBuilder("(?is)^"); //$NON-NLS-1$ - case-insensitive, dotall
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\\' && i + 1 < pattern.length()) {
				char next = pattern.charAt(++i);
				if (next == '%' || next == '_' || next == '\\') {
					re.append(Pattern.quote(String.valueOf(next)));
				} else {
					re.append(Pattern.quote("\\")).append(Pattern.quote(String.valueOf(next))); //$NON-NLS-1$
				}
			} else if (c == '%') {
				re.append(".*"); //$NON-NLS-1$
			} else if (c == '_') {
				re.append('.');
			} else {
				re.append(Pattern.quote(String.valueOf(c)));
			}
		}
		re.append('$');
		return value.matches(re.toString());
	}

	/**
	 * @param pattern a candidate pattern (may be {@code null})
	 * @return {@code true} when {@code pattern} is meant to be matched as a LIKE
	 *         pattern rather than compared literally - either it carries an
	 *         <b>unescaped</b> {@code %}/{@code _} wildcard, or it carries a
	 *         {@code \%}/{@code \_}/{@code \\} escape (which only {@link #matches}
	 *         resolves to the intended literal). A plain backslash not forming one
	 *         of those escapes, and a {@code null} pattern, are not patterns.
	 */
	public static boolean looksLikePattern(String pattern) {
		if (pattern == null) {
			return false;
		}
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\\' && i + 1 < pattern.length()) {
				char next = pattern.charAt(i + 1);
				if (next == '%' || next == '_' || next == '\\') {
					return true; // an escape SqlLike must resolve
				}
				i++; // some other escaped char - skip the pair, it is literal
				continue;
			}
			if (c == '%' || c == '_') {
				return true;
			}
		}
		return false;
	}
}
