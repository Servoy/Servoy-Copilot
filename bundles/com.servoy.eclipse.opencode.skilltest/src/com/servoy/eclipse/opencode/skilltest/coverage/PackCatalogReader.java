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

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the {@link PackCatalog} from the extracted {@code SERVOY_SKILLS_ZIP}
 * tree (SVY-21711 §3.2, as corrected).
 * <p>
 * The config directory defaults to {@code ~/.servoy/opencode/} - the same
 * directory {@code SkillsZipExtractor.extractToConfigDir} writes into and
 * {@code OpencodeFolderCreatorJob} writes {@code opencode.json} into - and is
 * overridable via the {@code servoy.skilltest.configDir} system property (for
 * tests/CI). The real (confirmed) opencode pack layout uses <b>plural</b>
 * directory names:
 * </p>
 * <ul>
 * <li>skills from {@code skills/<id>/} directory names,</li>
 * <li>agents from {@code agents/<name>.md} basenames, unioned with the
 * top-level {@code agent} block keys of {@code opencode.json} (which add the
 * built-in {@code build}/{@code plan} and any config-only agents),</li>
 * <li><b>MCP tools</b>: this reader does <b>not</b> produce a per-tool MCP
 * denominator. The remote MCP servers the pack's agents call
 * ({@code servoy-workspace}, {@code servoy-model}, ...) are not reflectable
 * from the extracted pack offline, so the real MCP denominator is the set of
 * tool identities <b>observed</b> across the suite (see
 * {@code CoverageReport.aggregate}). {@link PackCatalog#mcpTools()} from this
 * reader therefore holds only the {@code opencode.json} {@code mcp}
 * <b>server</b> names as a hint - never a reflected per-tool list.</li>
 * </ul>
 * <p>
 * Each read is independently guarded; a missing or malformed source yields an
 * empty set for its dimension, mirroring {@code SkillTestPaths}' tolerant
 * fallbacks. The reader never throws.
 * </p>
 */
public final class PackCatalogReader {

	/** System property overriding the opencode config dir (for tests/CI). */
	public static final String CONFIG_DIR_PROPERTY = "servoy.skilltest.configDir"; //$NON-NLS-1$

	/** Skills live under the <b>plural</b> {@code skills/} directory. */
	private static final String SKILL_DIR = "skills"; //$NON-NLS-1$

	/** Agents live under the <b>plural</b> {@code agents/} directory. */
	private static final String AGENT_DIR = "agents"; //$NON-NLS-1$

	/**
	 * Internal tooling skills that drive the skill-test harness itself (authoring
	 * baselines, generating expectations). They are not part of the coverable pack
	 * catalog, so they are excluded from the skills denominator - counting them
	 * would permanently inflate NOT-COVERED-YET with skills no product run is meant
	 * to load. Matched case-insensitively.
	 */
	private static final Set<String> INTERNAL_SKILLS = Set.of("skilltest-authoring", //$NON-NLS-1$
			"skilltest-expectations"); //$NON-NLS-1$

	/**
	 * Internal harness agents that drive the skill-test machinery itself (authoring
	 * baselines, judging outcomes). Like {@link #INTERNAL_SKILLS}, they are not
	 * part of the coverable pack catalog and must not appear in the agents
	 * denominator or its NOT-COVERED-YET set. Matched case-insensitively.
	 */
	private static final Set<String> INTERNAL_AGENTS = Set.of("baseline-author", //$NON-NLS-1$
			"judge"); //$NON-NLS-1$

	private static final String OPENCODE_JSON = "opencode.json"; //$NON-NLS-1$
	private static final String AGENT_SUFFIX = ".md"; //$NON-NLS-1$
	private static final String MCP_KEY = "mcp"; //$NON-NLS-1$
	private static final String AGENT_KEY = "agent"; //$NON-NLS-1$

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PackCatalogReader() {
	}

	/**
	 * Resolves the opencode config directory: the
	 * {@code servoy.skilltest.configDir} system property when set, otherwise
	 * {@code ~/.servoy/opencode/}.
	 *
	 * @return the config directory path (may not exist)
	 */
	public static Path resolveConfigDir() {
		String override = System.getProperty(CONFIG_DIR_PROPERTY);
		if (override != null && !override.isBlank()) {
			return Path.of(override.trim());
		}
		return Path.of(System.getProperty("user.home"), ".servoy", "opencode"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Reads the catalog from the default/overridden config directory.
	 *
	 * @return a non-{@code null} catalog; empty dimensions where sources are absent
	 */
	public static PackCatalog read() {
		return read(resolveConfigDir());
	}

	/**
	 * Reads the catalog from a specific config directory.
	 *
	 * @param configDir the extracted opencode config directory (may be
	 *                  {@code null}/absent)
	 * @return a non-{@code null} catalog; empty dimensions where sources are absent
	 */
	public static PackCatalog read(Path configDir) {
		if (configDir == null) {
			return PackCatalog.empty();
		}
		File root = configDir.toFile();
		File opencodeJson = new File(root, OPENCODE_JSON);

		Set<String> skills = readSkills(new File(root, SKILL_DIR));

		Set<String> agents = readAgents(new File(root, AGENT_DIR));
		agents.addAll(readAgentsFromJson(opencodeJson));

		// The MCP denominator is observed-based (the remote servers the pack calls are
		// not reflectable offline), so the reader contributes only the opencode.json
		// mcp SERVER names as a hint; CoverageReport uses the union of observed MCP
		// identities as the real catalog.
		Set<String> mcpServers = readMcpServers(opencodeJson);
		return new PackCatalog(skills, agents, mcpServers);
	}

	/**
	 * Skills = the {@code skills/<id>/} directory names (a {@code SKILL.md} inside
	 * is not required).
	 */
	private static Set<String> readSkills(File skillDir) {
		Set<String> skills = new LinkedHashSet<>();
		try {
			File[] children = skillDir.listFiles();
			if (children == null) {
				return skills;
			}
			for (File child : children) {
				if (child.isDirectory()) {
					String id = child.getName();
					// Internal harness skills (authoring/expectations) are not part of the
					// coverable catalog - skip them so they never show as NOT-COVERED-YET.
					if (isInternalSkill(id)) {
						continue;
					}
					// A well-formed opencode skill folder holds a SKILL.md; accept the
					// directory as a skill either way so a slightly off layout still counts.
					skills.add(id);
				}
			}
		} catch (SecurityException ignored) {
			// Unreadable tree: degrade to whatever was collected so far.
		}
		return skills;
	}

	/**
	 * @return {@code true} if the skill id names an internal harness skill that is
	 *         excluded from the coverable catalog (matched case-insensitively)
	 */
	private static boolean isInternalSkill(String id) {
		if (id == null) {
			return false;
		}
		return INTERNAL_SKILLS.contains(id.toLowerCase());
	}

	/**
	 * @return {@code true} if the agent name is an internal harness agent that is
	 *         excluded from the coverable catalog (matched case-insensitively)
	 */
	private static boolean isInternalAgent(String name) {
		if (name == null) {
			return false;
		}
		return INTERNAL_AGENTS.contains(name.toLowerCase());
	}

	/**
	 * Agents = the {@code agents/<name>.md} file basenames (without the
	 * {@code .md}).
	 */
	private static Set<String> readAgents(File agentDir) {
		Set<String> agents = new LinkedHashSet<>();
		try {
			File[] children = agentDir.listFiles();
			if (children == null) {
				return agents;
			}
			for (File child : children) {
				if (child.isFile()) {
					String name = child.getName();
					if (name.length() > AGENT_SUFFIX.length() && name.toLowerCase().endsWith(AGENT_SUFFIX)) {
						String agent = name.substring(0, name.length() - AGENT_SUFFIX.length());
						// Internal harness agents (Baseline-Author/Judge) are not part of the
						// coverable catalog - skip them so they never show as NOT-COVERED-YET.
						if (!isInternalAgent(agent)) {
							agents.add(agent);
						}
					}
				}
			}
		} catch (SecurityException ignored) {
			// Unreadable tree: degrade to whatever was collected so far.
		}
		return agents;
	}

	/**
	 * Secondary agent source: the field names of the top-level {@code agent} object
	 * in {@code opencode.json} (the config adds the built-in
	 * {@code build}/{@code plan} and any config-only agents). Tolerant: a missing
	 * file or a non-object {@code agent} contributes nothing.
	 */
	private static Set<String> readAgentsFromJson(File opencodeJson) {
		Set<String> agents = new LinkedHashSet<>();
		if (opencodeJson == null || !opencodeJson.isFile()) {
			return agents;
		}
		try {
			JsonNode root = MAPPER.readTree(opencodeJson);
			JsonNode agentNode = root != null ? root.get(AGENT_KEY) : null;
			if (agentNode != null && agentNode.isObject()) {
				for (java.util.Iterator<String> it = agentNode.fieldNames(); it.hasNext();) {
					String name = it.next();
					if (name != null && !name.isBlank() && !isInternalAgent(name)) {
						agents.add(name);
					}
				}
			}
		} catch (Exception ignored) {
			// Malformed/unreadable opencode.json: contribute nothing.
		}
		return agents;
	}

	/**
	 * MCP server names = the keys under the {@code mcp} block of
	 * {@code opencode.json}. These are only a server-level <b>hint</b>: the real
	 * per-tool MCP denominator is observed-based (see the class Javadoc and
	 * {@code CoverageReport.aggregate}). Tolerant: a missing/malformed file or
	 * absent block yields an empty set.
	 */
	private static Set<String> readMcpServers(File opencodeJson) {
		Set<String> servers = new LinkedHashSet<>();
		if (opencodeJson == null || !opencodeJson.isFile()) {
			return servers;
		}
		try {
			JsonNode root = MAPPER.readTree(opencodeJson);
			JsonNode mcp = root != null ? root.get(MCP_KEY) : null;
			if (mcp != null && mcp.isObject()) {
				for (java.util.Iterator<String> it = mcp.fieldNames(); it.hasNext();) {
					String name = it.next();
					if (name != null && !name.isBlank()) {
						servers.add(name);
					}
				}
			}
		} catch (Exception ignored) {
			// Malformed/unreadable opencode.json: degrade to an empty server set.
		}
		return servers;
	}
}
