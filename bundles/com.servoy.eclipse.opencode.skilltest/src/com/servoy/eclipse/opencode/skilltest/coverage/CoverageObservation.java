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

package com.servoy.eclipse.opencode.skilltest.coverage;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An immutable value capturing, per baseline, what that baseline's run actually
 * exercised across the three coverage dimensions (SVY-21711):
 * <ul>
 * <li>{@code skills} - skill ids loaded (from {@code skill} tool-calls),</li>
 * <li>{@code agents} - agents that ran: the builder's effective agent plus
 * every {@code task}-dispatched sub-agent,</li>
 * <li>{@code mcpTools} - fully-qualified tool identities ({@code server.tool},
 * or a bare {@code tool} when the name carried no server prefix).</li>
 * </ul>
 * <p>
 * Each set is defensively copied and unmodifiable; a {@code null} set degrades
 * to empty so callers never NPE.
 * </p>
 *
 * @param baselineId the baseline whose run produced this observation
 * @param skills     the skill ids loaded during the run
 * @param agents     the agents that ran (builder + dispatched)
 * @param mcpTools   the MCP tool identities called during the run
 */
public record CoverageObservation(String baselineId, Set<String> skills, Set<String> agents, Set<String> mcpTools) {

	public CoverageObservation {
		skills = copyOf(skills);
		agents = copyOf(agents);
		mcpTools = copyOf(mcpTools);
	}

	private static Set<String> copyOf(Set<String> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(new LinkedHashSet<>(source));
	}
}
