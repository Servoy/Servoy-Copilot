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
 * The skill4servoy pack catalog - the denominator against which coverage gaps
 * are flagged (SVY-21711 §3.2). Read from the extracted
 * {@code SERVOY_SKILLS_ZIP} tree under the opencode config directory:
 * <ul>
 * <li>{@code skills} - one per {@code skills/<id>/} directory (plural),</li>
 * <li>{@code agents} - one per {@code agents/<name>.md} file (plural), unioned
 * with the {@code opencode.json} top-level {@code agent} block keys,</li>
 * <li>{@code mcpTools} - the {@code opencode.json} {@code mcp} <b>server</b>
 * names only, as a hint. There is <b>no</b> offline per-tool MCP denominator:
 * the remote MCP servers the pack's agents call are not reflectable from the
 * extracted pack, so the real MCP denominator is the set of tool identities
 * <b>observed</b> across the suite - {@code CoverageReport.aggregate} uses that
 * union as the MCP catalog and ignores this hint set for the fold.</li>
 * </ul>
 * <p>
 * A missing catalog source degrades to an empty set for its dimension rather
 * than failing the whole report. Each set is defensively copied and
 * unmodifiable; a {@code null} set degrades to empty.
 * </p>
 *
 * @param skills   the catalog skill ids
 * @param agents   the catalog agent names
 * @param mcpTools the {@code opencode.json} MCP server names (a hint only; the
 *                 MCP dimension is observed-based)
 */
public record PackCatalog(Set<String> skills, Set<String> agents, Set<String> mcpTools) {

	public PackCatalog {
		skills = copyOf(skills);
		agents = copyOf(agents);
		mcpTools = copyOf(mcpTools);
	}

	/** @return an empty catalog (all three dimensions empty) */
	public static PackCatalog empty() {
		return new PackCatalog(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
	}

	private static Set<String> copyOf(Set<String> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(new LinkedHashSet<>(source));
	}
}
