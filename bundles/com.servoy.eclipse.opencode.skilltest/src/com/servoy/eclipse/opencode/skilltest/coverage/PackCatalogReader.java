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
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
 * <li><b>MCP tools</b>: {@link PackCatalog#mcpTools()} from {@link #read} holds
 * only the {@code opencode.json} {@code mcp} <b>server</b> names (a hint).
 * {@link #readMcpToolDenominator(Path)} produces the real per-tool MCP
 * denominator, derived purely from code: the {@code opencode.json} {@code mcp}
 * rename map (each entry's {@code url} last path segment is the developer.mcp
 * {@code @McpServer} name) joined with developer.mcp {@code @McpServer}/
 * {@code @Tool} reflection over
 * {@code McpServerBuiltins.BUILT_IN_SERVER_CLASSES}, emitting each identity in
 * the {@code <packServerName>_<toolName>} form seen in session exports (e.g.
 * {@code servoy-workspace_listProjects}). It needs no live endpoint; on any
 * reflective failure it returns an empty set so {@code CoverageReport.aggregate}
 * falls back to the identities <b>observed</b> across the suite.</li>
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
	private static final String URL_KEY = "url"; //$NON-NLS-1$

	/** The developer.mcp registry of built-in server classes. */
	private static final String BUILTINS_CLASS = "com.servoy.eclipse.developer.mcp.McpServerBuiltins"; //$NON-NLS-1$
	private static final String BUILTINS_FIELD = "BUILT_IN_SERVER_CLASSES"; //$NON-NLS-1$

	/** The developer.mcp {@code @McpServer}/{@code @Tool} annotation types. */
	private static final String MCP_SERVER_ANNOTATION = "com.servoy.eclipse.developer.mcp.annotations.McpServer"; //$NON-NLS-1$
	private static final String TOOL_ANNOTATION = "com.servoy.eclipse.developer.mcp.annotations.Tool"; //$NON-NLS-1$

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

	/**
	 * Builds the <b>MCP tool-identity denominator</b> for the coverage report from
	 * the default/overridden config directory. See
	 * {@link #readMcpToolDenominator(Path)}.
	 *
	 * @return the pack's MCP tool identities in {@code <packServer>_<toolName>}
	 *         form, or an empty set on any failure
	 */
	public static Set<String> readMcpToolDenominator() {
		return readMcpToolDenominator(resolveConfigDir());
	}

	/**
	 * Builds the <b>MCP tool-identity denominator</b> for the coverage report: the
	 * full set of tool identities the pack's configured MCP servers expose, each in
	 * the {@code <packServerName>_<toolName>} form that appears in opencode session
	 * exports (e.g. {@code servoy-workspace_listProjects}).
	 * <p>
	 * The set is derived purely from code - never from a live endpoint - by joining
	 * two authoritative sources already in the workspace:
	 * </p>
	 * <ol>
	 * <li>the extracted {@code opencode.json} {@code mcp} block, which renames each
	 * developer.mcp server: each {@code mcp.<packServer>} entry has a {@code url}
	 * whose last path segment is the developer.mcp {@code @McpServer} name (e.g.
	 * {@code .../dev_mcp/servoy-ide}), giving the {@code packServer -> devServer}
	 * map;</li>
	 * <li>the developer.mcp {@code @McpServer}/{@code @Tool} reflection over
	 * {@code McpServerBuiltins.BUILT_IN_SERVER_CLASSES}: for the server class whose
	 * {@code @McpServer.name()} equals a {@code devServer}, every
	 * {@code @Tool.name()} method yields the identity
	 * {@code <packServer>_<toolName>}.</li>
	 * </ol>
	 * <p>
	 * Only pack servers actually present in {@code opencode.json} contribute, so
	 * the denominator reflects what THIS pack ships. A pack server whose url maps
	 * to no known developer.mcp server, or a devServer with no reflectable class,
	 * contributes nothing. Built-in / skill / task / subagent tools are naturally
	 * excluded - they are not developer.mcp {@code @Tool}s.
	 * </p>
	 * <p>
	 * All reflection is guarded: on any failure (the developer.mcp bundle absent
	 * from the classpath, a missing/malformed {@code opencode.json}, any reflective
	 * error) this returns an <b>empty</b> set so the caller falls back to the
	 * observed-union denominator. This method never throws.
	 * </p>
	 *
	 * @param configDir the extracted opencode config directory (may be
	 *                  {@code null}/absent)
	 * @return the pack's MCP tool identities in {@code <packServer>_<toolName>}
	 *         form, or an empty set on any failure
	 */
	public static Set<String> readMcpToolDenominator(Path configDir) {
		Set<String> identities = new LinkedHashSet<>();
		try {
			if (configDir == null) {
				return identities;
			}
			File opencodeJson = new File(configDir.toFile(), OPENCODE_JSON);
			// packServer -> devServer (the last url path segment).
			Map<String, String> packToDev = readPackServerToDevServer(opencodeJson);
			if (packToDev.isEmpty()) {
				return identities;
			}

			// devServer -> list of @Tool names, reflected from the developer.mcp registry.
			Map<String, Set<String>> devToTools = reflectDevServerTools();
			if (devToTools.isEmpty()) {
				return identities;
			}

			for (Map.Entry<String, String> entry : packToDev.entrySet()) {
				String packServer = entry.getKey();
				String devServer = entry.getValue();
				Set<String> tools = devToTools.get(devServer);
				if (tools == null) {
					// The url mapped to no known developer.mcp server: contribute nothing.
					continue;
				}
				for (String tool : tools) {
					identities.add(packServer + "_" + tool); //$NON-NLS-1$
				}
			}
		} catch (Throwable ignored) {
			// Any failure (bundle absent, reflection error, malformed json): empty set so
			// the caller falls back to the observed-union denominator. Never throws.
			return new LinkedHashSet<>();
		}
		return identities;
	}

	/**
	 * Reads the {@code mcp} block of {@code opencode.json} into a
	 * {@code packServerName -> devServerName} map, where the devServer is the last
	 * path segment of each entry's {@code url} (e.g. {@code servoy-workspace} ->
	 * {@code servoy-ide} for {@code .../dev_mcp/servoy-ide}). An entry without a
	 * usable {@code url} contributes nothing. Tolerant: a missing/malformed file or
	 * absent block yields an empty map.
	 */
	private static Map<String, String> readPackServerToDevServer(File opencodeJson) {
		Map<String, String> packToDev = new LinkedHashMap<>();
		if (opencodeJson == null || !opencodeJson.isFile()) {
			return packToDev;
		}
		try {
			JsonNode root = MAPPER.readTree(opencodeJson);
			JsonNode mcp = root != null ? root.get(MCP_KEY) : null;
			if (mcp == null || !mcp.isObject()) {
				return packToDev;
			}
			for (java.util.Iterator<String> it = mcp.fieldNames(); it.hasNext();) {
				String packServer = it.next();
				if (packServer == null || packServer.isBlank()) {
					continue;
				}
				JsonNode entry = mcp.get(packServer);
				if (entry == null || !entry.isObject()) {
					continue;
				}
				JsonNode urlNode = entry.get(URL_KEY);
				if (urlNode == null || !urlNode.isTextual()) {
					continue;
				}
				String devServer = lastPathSegment(urlNode.asText());
				if (devServer != null && !devServer.isBlank()) {
					packToDev.put(packServer, devServer);
				}
			}
		} catch (Exception ignored) {
			// Malformed/unreadable opencode.json: degrade to whatever was collected.
		}
		return packToDev;
	}

	/**
	 * @return the last {@code /}-separated path segment of the url, ignoring any
	 *         query/fragment and a trailing slash; {@code null} when none
	 */
	private static String lastPathSegment(String url) {
		if (url == null) {
			return null;
		}
		String s = url.trim();
		if (s.isEmpty()) {
			return null;
		}
		// Drop any query/fragment so a trailing ?x / #y never becomes the segment.
		int cut = s.length();
		int q = s.indexOf('?');
		if (q >= 0 && q < cut) {
			cut = q;
		}
		int h = s.indexOf('#');
		if (h >= 0 && h < cut) {
			cut = h;
		}
		s = s.substring(0, cut);
		// Trim a trailing slash so ".../servoy-ide/" still yields "servoy-ide".
		while (s.endsWith("/")) { //$NON-NLS-1$
			s = s.substring(0, s.length() - 1);
		}
		int slash = s.lastIndexOf('/');
		String segment = slash >= 0 ? s.substring(slash + 1) : s;
		return segment.isBlank() ? null : segment;
	}

	/**
	 * Reflects {@code McpServerBuiltins.BUILT_IN_SERVER_CLASSES} and builds a
	 * {@code devServerName -> @Tool names} map: for each built-in server class its
	 * {@code @McpServer.name()} is the devServer and every {@code @Tool.name()}
	 * method it declares is a tool name (falling back to the method name when the
	 * {@code @Tool.name()} is blank, matching the annotation's documented default).
	 * <p>
	 * The developer.mcp bundle Require-Bundles make these classes and annotations
	 * resolve on the skilltest bundle's classpath. Everything is guarded by the
	 * caller's {@code try/catch(Throwable)}; a reflective failure simply yields an
	 * empty map and the observed-union fallback.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Set<String>> reflectDevServerTools() throws Exception {
		Map<String, Set<String>> devToTools = new LinkedHashMap<>();
		ClassLoader loader = PackCatalogReader.class.getClassLoader();

		Class<? extends Annotation> mcpServerAnn = (Class<? extends Annotation>) Class.forName(MCP_SERVER_ANNOTATION,
				false, loader);
		Class<? extends Annotation> toolAnn = (Class<? extends Annotation>) Class.forName(TOOL_ANNOTATION, false,
				loader);

		Class<?> builtins = Class.forName(BUILTINS_CLASS, false, loader);
		Object serverClassesObj = builtins.getField(BUILTINS_FIELD).get(null);
		if (!(serverClassesObj instanceof Class<?>[])) {
			return devToTools;
		}
		Class<?>[] serverClasses = (Class<?>[]) serverClassesObj;

		for (Class<?> serverClass : serverClasses) {
			if (serverClass == null) {
				continue;
			}
			Annotation serverAnnotation = serverClass.getAnnotation(mcpServerAnn);
			if (serverAnnotation == null) {
				continue;
			}
			String devServer = annotationName(serverAnnotation);
			if (devServer == null || devServer.isBlank()) {
				continue;
			}
			Set<String> tools = devToTools.computeIfAbsent(devServer, k -> new LinkedHashSet<>());
			for (Method method : serverClass.getDeclaredMethods()) {
				Annotation toolAnnotation = method.getAnnotation(toolAnn);
				if (toolAnnotation == null) {
					continue;
				}
				String toolName = annotationName(toolAnnotation);
				// @Tool.name() defaults to the method name when blank (see the annotation).
				if (toolName == null || toolName.isBlank()) {
					toolName = method.getName();
				}
				tools.add(toolName);
			}
		}
		return devToTools;
	}

	/**
	 * @return the {@code String name()} value of an
	 *         {@code @McpServer}/{@code @Tool} annotation, reflected by method
	 *         name, or {@code null} when absent
	 */
	private static String annotationName(Annotation annotation) throws Exception {
		Method nameMethod = annotation.annotationType().getMethod("name"); //$NON-NLS-1$
		Object value = nameMethod.invoke(annotation);
		return value instanceof String ? (String) value : null;
	}
}
