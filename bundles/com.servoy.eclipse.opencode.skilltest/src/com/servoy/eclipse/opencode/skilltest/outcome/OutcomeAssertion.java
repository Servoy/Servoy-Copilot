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

package com.servoy.eclipse.opencode.skilltest.outcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.opencode.skilltest.ArgMatcher;

/**
 * A single expected outcome: a Servoy persist (of a given {@link PersistKind})
 * that must exist in the active solution after a skill run, optionally carrying
 * property matchers and nested child expectations (SVY-21366).
 * <p>
 * Assertions describe the <em>result</em> in the real in-memory model, not the
 * transcript: e.g. "a {@code FORM} named {@code customerDetail} with
 * {@code dataSource = db:/example_data/customers} and {@code useCssPosition =
 * true}, containing a {@code COMPONENT} whose {@code dataProviderID} is
 * {@code companyname} and another whose {@code dataProviderID} is
 * {@code contactname}". This is immune to tool reordering, extra exploration
 * calls, and prose rephrasing.
 * </p>
 *
 * @param kind     the persist kind that must exist (never {@code null})
 * @param name     the persist name to match; {@code null} matches by kind +
 *                 props alone (used for child components identified by a
 *                 property such as {@code dataProviderID})
 * @param scope    optional scope for scoped persists (e.g. {@code globals} for a
 *                 global method/variable); {@code null} = any/none
 * @param exists   {@code true} = the persist must be present (default);
 *                 {@code false} = it must be absent (a removal assertion)
 * @param props    property name -> matcher; each must hold on the found persist
 * @param children nested expectations that must each be satisfied by some child
 *                 of the found persist (recursively)
 */
public record OutcomeAssertion(PersistKind kind, String name, String scope, boolean exists,
		Map<String, ArgMatcher> props, List<OutcomeAssertion> children) {

	public OutcomeAssertion {
		props = props == null ? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(props));
		children = children == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(children));
	}

	/**
	 * @return a short human label like {@code form 'customerDetail'}. For a
	 *         component the concrete component type is shown when known (from a
	 *         {@code typeName} property matcher), e.g.
	 *         {@code bootstrapcomponents-button {dataProviderID}} instead of the
	 *         generic {@code component}.
	 */
	public String label() {
		StringBuilder sb = new StringBuilder(displayKind());
		if (scope != null && !scope.isBlank()) {
			sb.append(' ').append(scope).append('.');
		} else {
			sb.append(' ');
		}
		sb.append(name != null ? "'" + name + "'" : "(by props)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (!exists) {
			sb.append(" [must NOT exist]"); //$NON-NLS-1$
		}
		return sb.toString();
	}

	/**
	 * The kind shown in the label: for a {@link PersistKind#COMPONENT} with a
	 * {@code typeName} matcher, the concrete component type (e.g.
	 * {@code bootstrapcomponents-button}); otherwise the kind token.
	 *
	 * @return the display kind
	 */
	public String displayKind() {
		if (kind == PersistKind.COMPONENT) {
			ArgMatcher typeMatcher = props.get("typeName"); //$NON-NLS-1$
			if (typeMatcher != null && typeMatcher.getExpected() != null) {
				String tn = String.valueOf(typeMatcher.getExpected());
				if (!tn.isBlank()) {
					return tn;
				}
			}
		}
		return kind.token();
	}
}
