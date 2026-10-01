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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.ArgMatcher;
import com.servoy.eclipse.opencode.skilltest.BaselineLoader;

/**
 * Infers a starting set of {@link OutcomeAssertion}s from an opencode session
 * export, so importing a baseline pre-fills the persist-assertion editor rather
 * than asking the user to hand-author everything (SVY-21366).
 * <p>
 * Inference is deliberately <b>workspace-independent</b> - the golden workspace
 * the session ran in is usually gone by import time - so it draws only on data
 * inside the export:
 * </p>
 * <ol>
 * <li><b>Persist skeletons</b> from every {@code task} tool output's
 * {@code Changed files:} list (and any direct file-write tool calls): a
 * {@code forms/X.frm} &rarr; a {@code FORM} named {@code X}, {@code
 * valuelists/X.val} &rarr; a {@code VALUELIST} named {@code X}, {@code
 * relations/X.rel} &rarr; a {@code RELATION}, {@code X.js} under {@code
 * scopes/} &rarr; global methods, etc.</li>
 * <li><b>Property suggestions</b> from the prompt text: a form's
 * {@code dataSource} ({@code db:/server/table}), layout mode
 * ({@code useCssPosition} vs responsive), and bound {@code dataProviderID}s
 * (quoted column names) become {@code props}/{@code children} on the matching
 * form.</li>
 * </ol>
 * <p>
 * Everything produced here is a <em>suggestion</em> the user reviews and edits
 * in the {@code PersistAssertionEditorDialog}; the committed {@code baseline.json}
 * is the reviewed truth.
 * </p>
 */
public final class OutcomeInference {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Placeholder written for a component's {@code typeName} when it cannot be
	 * inferred (the orchestrator export carries no component detail). The user
	 * replaces it in the editor with the real component type, e.g.
	 * {@code bootstrapcomponents-textbox}.
	 */
	public static final String TYPE_NAME_PLACEHOLDER = "<replace-with-component-type>"; //$NON-NLS-1$

	/** Matches a "Changed files:" bullet path like {@code - tst/forms/customerDetail.frm - ...}. */
	private static final Pattern CHANGED_FILE = Pattern
			.compile("(?m)^\\s*[-*]?\\s*([\\w./-]+\\.(?:frm|val|rel|js|dbi|obj|less|css|menu|med))\\b"); //$NON-NLS-1$

	/** {@code db:/server/table} datasource reference. */
	private static final Pattern DATASOURCE = Pattern.compile("db:/[\\w$]+/[\\w$]+"); //$NON-NLS-1$

	/** A double-quoted token (candidate dataprovider / column name) in the prompt. */
	private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z][A-Za-z0-9_]{1,60})\""); //$NON-NLS-1$

	private OutcomeInference() {
	}

	/**
	 * Infers assertions from an export file on disk.
	 *
	 * @param exportFile the opencode export JSON (may be {@code null}/absent)
	 * @return inferred assertions (never {@code null}; empty when nothing found)
	 */
	public static List<OutcomeAssertion> infer(File exportFile) {
		if (exportFile == null || !exportFile.isFile()) {
			return List.of();
		}
		try {
			String raw = Files.readString(exportFile.toPath(), StandardCharsets.UTF_8);
			JsonNode root = MAPPER.readTree(BaselineLoader.stripLeadingNonJson(raw));
			return infer(root);
		} catch (Exception ex) {
			return List.of();
		}
	}

	/**
	 * Infers assertions from a parsed export tree.
	 *
	 * @param exportRoot the export root JSON node
	 * @return inferred assertions (never {@code null})
	 */
	public static List<OutcomeAssertion> infer(JsonNode exportRoot) {
		if (exportRoot == null) {
			return List.of();
		}
		String allText = collectText(exportRoot);
		Set<String> changedPaths = new LinkedHashSet<>();
		Matcher m = CHANGED_FILE.matcher(allText);
		while (m.find()) {
			changedPaths.add(m.group(1));
		}

		String prompt = firstUserPrompt(exportRoot);
		FormHints hints = promptHints(prompt);

		// De-duplicate by kind+name; a form skeleton gets enriched with the prompt hints.
		Map<String, OutcomeAssertion> byKey = new LinkedHashMap<>();
		for (String path : changedPaths) {
			OutcomeAssertion a = assertionForPath(path, hints);
			if (a == null) {
				continue;
			}
			String key = a.kind().token() + "/" + (a.name() != null ? a.name().toLowerCase() : "?"); //$NON-NLS-1$ //$NON-NLS-2$
			byKey.putIfAbsent(key, a);
		}
		return new ArrayList<>(byKey.values());
	}

	/**
	 * Maps a changed-file path to a persist-skeleton assertion, enriching a form
	 * with the prompt-derived datasource/layout/dataprovider hints.
	 */
	private static OutcomeAssertion assertionForPath(String path, FormHints hints) {
		String lower = path.toLowerCase();
		String base = fileBaseName(path);
		if (lower.endsWith(".frm")) { //$NON-NLS-1$
			Map<String, ArgMatcher> props = new LinkedHashMap<>();
			if (hints.dataSource != null) {
				props.put("dataSource", ArgMatcher.tolerant(hints.dataSource)); //$NON-NLS-1$
			}
			if (hints.cssPosition != null) {
				props.put("useCssPosition", ArgMatcher.tolerant(hints.cssPosition)); //$NON-NLS-1$
			}
			List<OutcomeAssertion> children = new ArrayList<>();
			for (String dp : hints.dataProviders) {
				// The concrete component type cannot be inferred from the orchestrator
				// export (it has no component detail), so emit a TYPE_NAME_PLACEHOLDER
				// the user replaces in the editor with the real type (e.g.
				// bootstrapcomponents-textbox). An ordered map keeps typeName first.
				Map<String, ArgMatcher> childProps = new LinkedHashMap<>();
				childProps.put("typeName", ArgMatcher.tolerant(TYPE_NAME_PLACEHOLDER)); //$NON-NLS-1$
				childProps.put("dataProviderID", ArgMatcher.tolerant(dp)); //$NON-NLS-1$
				children.add(new OutcomeAssertion(PersistKind.COMPONENT, null, null, true, childProps, List.of()));
			}
			return new OutcomeAssertion(PersistKind.FORM, base, null, true, props, children);
		}
		if (lower.endsWith(".val")) { //$NON-NLS-1$
			return new OutcomeAssertion(PersistKind.VALUELIST, base, null, true, Map.of(), List.of());
		}
		if (lower.endsWith(".rel")) { //$NON-NLS-1$
			return new OutcomeAssertion(PersistKind.RELATION, base, null, true, Map.of(), List.of());
		}
		if (lower.endsWith(".menu")) { //$NON-NLS-1$
			return new OutcomeAssertion(PersistKind.MENU, base, null, true, Map.of(), List.of());
		}
		if (lower.endsWith(".med")) { //$NON-NLS-1$
			return new OutcomeAssertion(PersistKind.MEDIA, base, null, true, Map.of(), List.of());
		}
		if (lower.contains("/scopes/") && lower.endsWith(".js")) { //$NON-NLS-1$ //$NON-NLS-2$
			// a scope .js file holds global methods/variables; assert the scope exists as
			// a script method container is too coarse - leave a generic OTHER skeleton the
			// user can refine into specific method/variable assertions.
			return new OutcomeAssertion(PersistKind.SCRIPTMETHOD, null, base, true, Map.of(), List.of());
		}
		// companion .js/.less/.css/.obj for a form etc. are IDE-generated; skip - the
		// .frm assertion already covers that outcome.
		return null;
	}

	private static String fileBaseName(String path) {
		String name = path;
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** Prompt-derived hints for a form outcome. */
	private static final class FormHints {
		String dataSource;
		Boolean cssPosition;
		final List<String> dataProviders = new ArrayList<>();
	}

	private static FormHints promptHints(String prompt) {
		FormHints h = new FormHints();
		if (prompt == null) {
			return h;
		}
		Matcher ds = DATASOURCE.matcher(prompt);
		if (ds.find()) {
			h.dataSource = ds.group();
		}
		String lower = prompt.toLowerCase();
		if (lower.contains("css-position") || lower.contains("css position")) { //$NON-NLS-1$ //$NON-NLS-2$
			h.cssPosition = Boolean.TRUE;
		} else if (lower.contains("responsive")) { //$NON-NLS-1$
			h.cssPosition = Boolean.FALSE;
		}
		// quoted tokens that look like dataproviders (exclude a quoted form name that
		// is immediately preceded by "named")
		Matcher q = QUOTED.matcher(prompt);
		while (q.find()) {
			String token = q.group(1);
			int start = q.start();
			String before = prompt.substring(Math.max(0, start - 8), start).toLowerCase();
			if (before.contains("named")) { //$NON-NLS-1$
				continue; // that's the form/persist name, not a dataprovider
			}
			if (!h.dataProviders.contains(token)) {
				h.dataProviders.add(token);
			}
		}
		return h;
	}

	private static String firstUserPrompt(JsonNode exportRoot) {
		JsonNode messages = exportRoot.get("messages"); //$NON-NLS-1$
		if (messages != null && messages.isArray()) {
			for (JsonNode msg : messages) {
				String role = textValue(msg, "role"); //$NON-NLS-1$
				if (role == null || role.equalsIgnoreCase("user")) { //$NON-NLS-1$
					JsonNode parts = msg.get("parts"); //$NON-NLS-1$
					if (parts != null && parts.isArray()) {
						for (JsonNode part : parts) {
							if ("text".equalsIgnoreCase(textValue(part, "type"))) { //$NON-NLS-1$ //$NON-NLS-2$
								String text = textValue(part, "text"); //$NON-NLS-1$
								if (text != null && !text.isBlank()) {
									return text;
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	/** Concatenates every textual node in the export (tool outputs, texts) for scanning. */
	private static String collectText(JsonNode node) {
		StringBuilder sb = new StringBuilder();
		collectText(node, sb);
		return sb.toString();
	}

	private static void collectText(JsonNode node, StringBuilder sb) {
		if (node == null) {
			return;
		}
		if (node.isTextual()) {
			sb.append(node.asText()).append('\n');
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				collectText(child, sb);
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> e : node.properties()) {
				collectText(e.getValue(), sb);
			}
		}
	}

	private static String textValue(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isValueNode() ? value.asText() : null;
	}
}
