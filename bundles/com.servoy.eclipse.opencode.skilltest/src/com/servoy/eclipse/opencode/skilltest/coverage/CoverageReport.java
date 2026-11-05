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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.servoy.eclipse.opencode.skilltest.McpToolCall;

/**
 * Folds the per-baseline {@link CoverageObservation}s across a whole suite
 * execution and diffs the union against the {@link PackCatalog} (SVY-21711
 * §3.3).
 * <p>
 * For each of the three dimensions (skills, agents, MCP tools) it computes:
 * </p>
 * <ul>
 * <li><b>covered</b> = the observed entries that are also in the catalog, each
 * attributed to the set of baseline ids that exercised it,</li>
 * <li><b>not-covered-yet</b> = catalog &minus; observed,</li>
 * <li><b>off-catalog</b> = observed &minus; catalog (informational "unexpected"
 * bucket, never a failure),</li>
 * <li><b>per-baseline breakdown</b> = what each baseline exercised.</li>
 * </ul>
 * <p>
 * Coverage is purely informational: this class computes a report and never
 * influences any PASS/FAIL verdict.
 * </p>
 */
public final class CoverageReport {

	/**
	 * The folded result for one dimension.
	 *
	 * @param name               the dimension's display name (e.g.
	 *                           {@code "Skills"})
	 * @param catalog            the full catalog set for this dimension (sorted)
	 * @param coveredToBaselines covered catalog item -&gt; the baseline ids that
	 *                           exercised it (insertion order = catalog order)
	 * @param notCovered         catalog items no baseline drove (sorted)
	 * @param offCatalog         observed items not in the catalog (sorted)
	 */
	public record Dimension(String name, Set<String> catalog, Map<String, Set<String>> coveredToBaselines,
			Set<String> notCovered, Set<String> offCatalog) {

		/** @return the number of catalog items that were covered */
		public int coveredCount() {
			return coveredToBaselines.size();
		}

		/** @return the total number of catalog items (the denominator) */
		public int catalogCount() {
			return catalog.size();
		}

		/**
		 * @return the covered-over-catalog percentage as a whole number 0..100; 100
		 *         when the catalog is empty (nothing left uncovered)
		 */
		public int percentage() {
			int total = catalog.size();
			if (total == 0) {
				return 100;
			}
			return (int) Math.round((coveredToBaselines.size() * 100.0) / total);
		}
	}

	/**
	 * What one baseline contributed across the three dimensions.
	 *
	 * @param baselineId the baseline id
	 * @param skills     the skill ids it loaded
	 * @param agents     the agents it ran
	 * @param mcpTools   the MCP tool identities it called
	 */
	public record BaselineBreakdown(String baselineId, Set<String> skills, Set<String> agents, Set<String> mcpTools) {
	}

	private final String timestamp;
	private final Dimension skills;
	private final Dimension agents;
	private final Dimension mcpTools;
	private final List<BaselineBreakdown> breakdowns;

	private CoverageReport(String timestamp, Dimension skills, Dimension agents, Dimension mcpTools,
			List<BaselineBreakdown> breakdowns) {
		this.timestamp = timestamp;
		this.skills = skills;
		this.agents = agents;
		this.mcpTools = mcpTools;
		this.breakdowns = List.copyOf(breakdowns);
	}

	/**
	 * Aggregates the observations against the catalog.
	 *
	 * @param timestamp    the suite timestamp label (may be {@code null})
	 * @param observations the per-baseline observations (may be {@code null}/empty)
	 * @param catalog      the pack catalog denominator (may be {@code null})
	 * @return a non-{@code null} aggregated report
	 */
	public static CoverageReport aggregate(String timestamp, List<CoverageObservation> observations,
			PackCatalog catalog) {
		PackCatalog cat = catalog != null ? catalog : PackCatalog.empty();
		List<CoverageObservation> obs = observations != null ? observations : Collections.emptyList();

		// Skills and agents are catalog-based: they have a real offline denominator
		// (the extracted pack), so NOT-COVERED-YET is meaningful. They compare as
		// plain ids (identity canonicalizer).
		Dimension skills = foldDimension("Skills", cat.skills(), obs, CoverageObservation::skills, s -> s); //$NON-NLS-1$
		Dimension agents = foldDimension("Agents", cat.agents(), obs, CoverageObservation::agents, s -> s); //$NON-NLS-1$

		// The MCP dimension is OBSERVED-based: the remote MCP servers the pack's agents
		// call (servoy-workspace, servoy-model, ...) are not reflectable from the
		// extracted pack offline, so there is no offline per-tool denominator. The
		// catalog for the MCP fold is therefore the union of the MCP identities
		// observed across all baselines (each already a "server_tool" string from
		// CoverageExtractor.identity), canonicalized so a "server.tool" and a
		// "server_tool" form collapse to one entry. Consequence: every observed MCP
		// tool is covered (100%), NOT-COVERED-YET for MCP is empty, and nothing is
		// off-catalog. The tool-name format in the report is the verbatim export
		// "server_tool" form.
		Set<String> observedMcp = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (CoverageObservation o : obs) {
			if (o.mcpTools() != null) {
				for (String id : o.mcpTools()) {
					String canonical = canonicalMcpId(id);
					if (canonical != null) {
						observedMcp.add(canonical);
					}
				}
			}
		}
		Dimension mcpTools = foldDimension("MCP tools", observedMcp, obs, CoverageObservation::mcpTools, //$NON-NLS-1$
				CoverageReport::canonicalMcpId);

		List<BaselineBreakdown> breakdowns = new ArrayList<>();
		for (CoverageObservation o : obs) {
			breakdowns.add(new BaselineBreakdown(o.baselineId(), o.skills(), o.agents(), o.mcpTools()));
		}
		return new CoverageReport(timestamp, skills, agents, mcpTools, breakdowns);
	}

	/**
	 * Folds one dimension: attributes each covered catalog item to the baselines
	 * that drove it, and computes the not-covered-yet and off-catalog sets.
	 */
	private static Dimension foldDimension(String name, Set<String> catalog, List<CoverageObservation> observations,
			java.util.function.Function<CoverageObservation, Set<String>> selector,
			java.util.function.UnaryOperator<String> canonicalizer) {
		Set<String> sortedCatalog = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		sortedCatalog.addAll(catalog);

		// Covered-item -> contributing baselines, in catalog (sorted) order. Matching
		// compares the canonical form of each side (identity for skills/agents, the
		// separator-normalized server/tool form for MCP tools) so an observed
		// "server.tool" still matches a catalog "server_tool".
		Map<String, Set<String>> coveredToBaselines = new LinkedHashMap<>();
		for (String item : sortedCatalog) {
			String canonicalItem = canonical(canonicalizer, item);
			Set<String> contributors = new LinkedHashSet<>();
			for (CoverageObservation o : observations) {
				if (o.baselineId() != null && containsCanonical(selector.apply(o), canonicalItem, canonicalizer)) {
					contributors.add(o.baselineId());
				}
			}
			if (!contributors.isEmpty()) {
				coveredToBaselines.put(item, Collections.unmodifiableSet(contributors));
			}
		}

		// Not covered = catalog items no baseline drove.
		Set<String> notCovered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (String item : sortedCatalog) {
			if (!coveredToBaselines.containsKey(item)) {
				notCovered.add(item);
			}
		}

		// Off-catalog = observed items whose canonical form is not in the catalog.
		Set<String> canonicalCatalog = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (String item : sortedCatalog) {
			canonicalCatalog.add(canonical(canonicalizer, item));
		}
		Set<String> offCatalog = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (CoverageObservation o : observations) {
			for (String observed : selector.apply(o)) {
				if (!containsIgnoreCase(canonicalCatalog, canonical(canonicalizer, observed))) {
					offCatalog.add(observed);
				}
			}
		}

		return new Dimension(name, Collections.unmodifiableSet(sortedCatalog),
				Collections.unmodifiableMap(coveredToBaselines), Collections.unmodifiableSet(notCovered),
				Collections.unmodifiableSet(offCatalog));
	}

	private static String canonical(java.util.function.UnaryOperator<String> canonicalizer, String value) {
		return value == null ? null : canonicalizer.apply(value);
	}

	/**
	 * @return {@code true} if any entry's canonical form equals
	 *         {@code canonicalItem} (case-insensitively)
	 */
	private static boolean containsCanonical(Set<String> observed, String canonicalItem,
			java.util.function.UnaryOperator<String> canonicalizer) {
		if (observed == null || canonicalItem == null) {
			return false;
		}
		for (String entry : observed) {
			String canonicalEntry = canonical(canonicalizer, entry);
			if (canonicalEntry != null && canonicalEntry.equalsIgnoreCase(canonicalItem)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsIgnoreCase(Set<String> set, String value) {
		if (set == null || value == null) {
			return false;
		}
		for (String entry : set) {
			if (value.equalsIgnoreCase(entry)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Canonicalizes an MCP tool identity so a {@code server.tool} and a
	 * {@code server_tool} form compare equal: the first separator ({@code .} or
	 * {@code _}) between the server and tool is normalized to {@code _}. A bare
	 * tool name (no separator) is returned unchanged.
	 *
	 * @param id the observed or catalog MCP identity (may be {@code null})
	 * @return the canonical form, or {@code null} when {@code id} is {@code null}
	 */
	static String canonicalMcpId(String id) {
		if (id == null) {
			return null;
		}
		String[] parts = McpToolCall.splitToolName(id);
		if (parts[0] == null) {
			// No server prefix: a bare tool name, left as-is.
			return parts[1] != null ? parts[1] : id;
		}
		return parts[0] + "_" + parts[1]; //$NON-NLS-1$
	}

	/** @return the suite timestamp label, or {@code null} */
	public String getTimestamp() {
		return timestamp;
	}

	/** @return the skills dimension */
	public Dimension getSkills() {
		return skills;
	}

	/** @return the agents dimension */
	public Dimension getAgents() {
		return agents;
	}

	/** @return the MCP-tools dimension */
	public Dimension getMcpTools() {
		return mcpTools;
	}

	/**
	 * @return the three dimensions in header/section order (skills, agents, MCP
	 *         tools)
	 */
	public List<Dimension> getDimensions() {
		return List.of(skills, agents, mcpTools);
	}

	/** @return the per-baseline breakdown, in run order */
	public List<BaselineBreakdown> getBreakdowns() {
		return breakdowns;
	}
}
