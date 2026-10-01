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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertions;

/**
 * Loads {@link Baseline} fixtures from a baselines root directory. Each
 * baseline lives in its own sub-folder holding a {@code baseline.json} sidecar
 * and the golden {@code export.json}. Only sidecars with {@code active == true}
 * are returned by {@link #loadActive(File)}.
 */
public final class BaselineLoader {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int DEFAULT_MAX_ATTEMPTS = 3;
	private static final int DEFAULT_TIMEOUT_SECONDS = 180;

	/**
	 * The solution an imported baseline is set up against by default. Written into
	 * the new sidecar's {@code precondition} so an {@code empty} source has a
	 * solution name to create (SVY-21366): without it {@code SolutionSetup} throws
	 * "'empty' source requires 'precondition.solution'".
	 */
	static final String DEFAULT_SOLUTION = "aiTestSolution"; //$NON-NLS-1$

	private BaselineLoader() {
	}

	/**
	 * Loads every {@code active} baseline under {@code baselinesRoot}.
	 *
	 * @param baselinesRoot a directory whose immediate sub-folders each contain a
	 *                      {@code baseline.json} + {@code export.json}
	 * @return the active baselines (never {@code null})
	 */
	public static List<Baseline> loadActive(File baselinesRoot) {
		List<Baseline> all = loadAll(baselinesRoot);
		List<Baseline> active = new ArrayList<>();
		for (Baseline baseline : all) {
			if (baseline.active()) {
				active.add(baseline);
			}
		}
		return active;
	}

	/**
	 * Loads every baseline (active or not) under {@code baselinesRoot}.
	 *
	 * @param baselinesRoot the baselines root directory
	 * @return all parsed baselines (never {@code null})
	 */
	public static List<Baseline> loadAll(File baselinesRoot) {
		List<Baseline> result = new ArrayList<>();
		if (baselinesRoot == null || !baselinesRoot.isDirectory()) {
			return result;
		}
		File[] folders = baselinesRoot.listFiles(File::isDirectory);
		if (folders == null) {
			return result;
		}
		for (File folder : folders) {
			File sidecar = new File(folder, "baseline.json"); //$NON-NLS-1$
			if (!sidecar.isFile()) {
				continue;
			}
			try {
				result.add(load(folder));
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load baseline in " + folder + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return result;
	}

	/**
	 * A loaded baseline paired with the folder it was loaded from, needed by
	 * callers (e.g. a UI) that must locate the {@code baseline.json} /
	 * {@code export.json} files again to toggle {@code active}, delete, or
	 * otherwise manage the fixture on disk.
	 *
	 * @param folder   the baseline's folder
	 * @param baseline the parsed baseline
	 */
	public record Entry(File folder, Baseline baseline) {
	}

	/**
	 * Like {@link #loadAll(File)} but also returns each baseline's source folder.
	 *
	 * @param baselinesRoot the baselines root directory
	 * @return all parsed baselines with their folders (never {@code null})
	 */
	public static List<Entry> loadAllEntries(File baselinesRoot) {
		List<Entry> result = new ArrayList<>();
		if (baselinesRoot == null || !baselinesRoot.isDirectory()) {
			return result;
		}
		File[] folders = baselinesRoot.listFiles(File::isDirectory);
		if (folders == null) {
			return result;
		}
		for (File folder : folders) {
			File sidecar = new File(folder, "baseline.json"); //$NON-NLS-1$
			if (!sidecar.isFile()) {
				continue;
			}
			try {
				result.add(new Entry(folder, load(folder)));
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load baseline in " + folder + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return result;
	}

	/**
	 * Deletes a baseline by recursively removing its folder (the
	 * {@code baseline.json} + {@code export.json} and anything else under it).
	 *
	 * @param baselineFolder the baseline's folder
	 * @throws IOException if the folder is not a valid baseline folder or cannot be
	 *                     removed
	 */
	public static void deleteBaseline(File baselineFolder) throws IOException {
		if (baselineFolder == null || !baselineFolder.isDirectory()) {
			throw new IOException("not a baseline folder: " + baselineFolder); //$NON-NLS-1$
		}
		if (!new File(baselineFolder, "baseline.json").isFile()) { //$NON-NLS-1$
			throw new IOException("refusing to delete: no baseline.json in " + baselineFolder); //$NON-NLS-1$
		}
		java.nio.file.Path root = baselineFolder.toPath();
		try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
		} catch (java.io.UncheckedIOException e) {
			throw new IOException("failed to delete baseline folder " + baselineFolder + ": " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getCause().getMessage(), e.getCause());
		}
	}

	/**
	 * Flips the {@code active} flag in a baseline's {@code baseline.json} sidecar,
	 * preserving every other field (edits the JSON tree rather than
	 * re-serialising a {@link Baseline}, which would need the golden transcript).
	 *
	 * @param baselineFolder the baseline's folder
	 * @param active         the new {@code active} value
	 * @throws IOException if the sidecar cannot be read or written
	 */
	public static void setActive(File baselineFolder, boolean active) throws IOException {
		File sidecar = new File(baselineFolder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);
		if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
			throw new IOException("baseline.json is not a JSON object: " + sidecar); //$NON-NLS-1$
		}
		obj.put("active", active); //$NON-NLS-1$
		ObjectMapper writer = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		Files.writeString(sidecar.toPath(), writer.writeValueAsString(obj), StandardCharsets.UTF_8);
	}

	/**
	 * Sets the baseline's {@code maxAttempts} (the number of times the runner
	 * replays the prompt before giving up; it passes as soon as one attempt
	 * matches). Preserves every other sidecar field. Clamped to at least 1.
	 *
	 * @param baselineFolder the baseline's folder
	 * @param maxAttempts    the new attempt count (values below 1 are stored as 1)
	 * @throws IOException if the sidecar cannot be read or written
	 */
	public static void setMaxAttempts(File baselineFolder, int maxAttempts) throws IOException {
		File sidecar = new File(baselineFolder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);
		if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
			throw new IOException("baseline.json is not a JSON object: " + sidecar); //$NON-NLS-1$
		}
		obj.put("maxAttempts", Math.max(1, maxAttempts)); //$NON-NLS-1$
		ObjectMapper writer = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		Files.writeString(sidecar.toPath(), writer.writeValueAsString(obj), StandardCharsets.UTF_8);
	}

	/**
	 * Writes the baseline's JSUnit verification (SVY-21366 §3.4b): sets/replaces the
	 * {@code verify.jsunit} sidecar block and writes the injected test script into
	 * the baseline folder. When {@code verify} is {@code null} the {@code verify}
	 * block is removed (JSUnit verification disabled). Every other sidecar field is
	 * preserved.
	 *
	 * @param baselineFolder the baseline's folder
	 * @param verify         the JSUnit config to persist, or {@code null} to disable
	 * @param scriptName     solution-relative script file name (e.g.
	 *                       {@code scopes/skilltest_verify.js}); ignored when
	 *                       {@code verify} is {@code null}
	 * @param scriptSource   the test script source to store at
	 *                       {@code verify/<scriptName>}; ignored when {@code verify}
	 *                       is {@code null}
	 * @throws IOException if the sidecar or script cannot be written
	 */
	public static void writeJsUnitVerify(File baselineFolder, Baseline.JsUnitVerify verify, String scriptName,
			String scriptSource) throws IOException {
		File sidecar = new File(baselineFolder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);
		if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
			throw new IOException("baseline.json is not a JSON object: " + sidecar); //$NON-NLS-1$
		}
		if (verify == null) {
			obj.remove("verify"); //$NON-NLS-1$
		} else {
			com.fasterxml.jackson.databind.node.ObjectNode verifyNode = obj.putObject("verify"); //$NON-NLS-1$
			com.fasterxml.jackson.databind.node.ObjectNode jsunit = verifyNode.putObject("jsunit"); //$NON-NLS-1$
			com.fasterxml.jackson.databind.node.ArrayNode scripts = jsunit.putArray("scripts"); //$NON-NLS-1$
			for (String s : verify.scripts()) {
				scripts.add(s);
			}
			jsunit.put("scope", verify.scope()); //$NON-NLS-1$
			if (verify.method() != null && !verify.method().isBlank()) {
				jsunit.put("method", verify.method()); //$NON-NLS-1$
			} else {
				jsunit.putNull("method"); //$NON-NLS-1$
			}
			jsunit.put("timeoutSeconds", verify.timeoutSeconds()); //$NON-NLS-1$
			jsunit.put("required", verify.required()); //$NON-NLS-1$

			// Store the injected test script under the baseline folder at verify/<scriptName>.
			if (scriptName != null && !scriptName.isBlank()) {
				File scriptFile = new File(new File(baselineFolder, "verify"), scriptName); //$NON-NLS-1$
				File parent = scriptFile.getParentFile();
				if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
					throw new IOException("cannot create verify script folder: " + parent); //$NON-NLS-1$
				}
				Files.writeString(scriptFile.toPath(), scriptSource != null ? scriptSource : "", //$NON-NLS-1$
						StandardCharsets.UTF_8);
			}
		}
		ObjectMapper writer = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		Files.writeString(sidecar.toPath(), writer.writeValueAsString(obj), StandardCharsets.UTF_8);
	}

	/**
	 * Writes the baseline's {@code precondition} block: the solution name and the
	 * source (empty / folder / git) the runner materializes before replay.
	 * Preserves every other sidecar field (and the precondition's own
	 * {@code fixture}/{@code cleanProjects}, which are not edited here).
	 *
	 * @param baselineFolder the baseline's folder
	 * @param solution       the solution name to create/activate (required for
	 *                       {@code empty})
	 * @param source         the initial-state source to persist
	 * @throws IOException if the sidecar cannot be read or written
	 */
	public static void writePrecondition(File baselineFolder, String solution, Baseline.Source source)
			throws IOException {
		File sidecar = new File(baselineFolder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);
		if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
			throw new IOException("baseline.json is not a JSON object: " + sidecar); //$NON-NLS-1$
		}
		com.fasterxml.jackson.databind.node.ObjectNode precondition = obj.has("precondition") //$NON-NLS-1$
				&& obj.get("precondition").isObject() //$NON-NLS-1$
						? (com.fasterxml.jackson.databind.node.ObjectNode) obj.get("precondition") //$NON-NLS-1$
						: obj.putObject("precondition"); //$NON-NLS-1$
		if (solution != null && !solution.isBlank()) {
			precondition.put("solution", solution); //$NON-NLS-1$
		} else {
			precondition.remove("solution"); //$NON-NLS-1$
		}
		com.fasterxml.jackson.databind.node.ObjectNode src = precondition.putObject("source"); //$NON-NLS-1$
		Baseline.Source.Type type = source != null ? source.type() : Baseline.Source.Type.EMPTY;
		src.put("type", type.name().toLowerCase()); //$NON-NLS-1$
		if (type != Baseline.Source.Type.EMPTY && source != null) {
			if (source.location() != null && !source.location().isBlank()) {
				src.put("location", source.location()); //$NON-NLS-1$
			}
			if (type == Baseline.Source.Type.GIT && source.ref() != null && !source.ref().isBlank()) {
				src.put("ref", source.ref()); //$NON-NLS-1$
			}
		}
		ObjectMapper writer = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		Files.writeString(sidecar.toPath(), writer.writeValueAsString(obj), StandardCharsets.UTF_8);
	}

	/**
	 * Reads the current injected verify-script source for a baseline (the first
	 * declared {@code verify.jsunit.scripts} entry), or {@code null} if there is
	 * none. Used by the view's JSUnit editor to pre-fill the script.
	 *
	 * @param baselineFolder the baseline's folder
	 * @param verify         the parsed JSUnit config (may be {@code null})
	 * @return the script source, or {@code null} if none exists on disk
	 */
	public static String readVerifyScript(File baselineFolder, Baseline.JsUnitVerify verify) {
		if (verify == null || verify.scripts().isEmpty()) {
			return null;
		}
		File scriptFile = new File(baselineFolder, verify.scripts().get(0));
		if (!scriptFile.isFile()) {
			return null;
		}
		try {
			return Files.readString(scriptFile.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Loads a single baseline from its folder ({@code baseline.json} +
	 * {@code export.json}).
	 *
	 * @param folder the baseline folder
	 * @return the parsed baseline
	 * @throws IOException if the sidecar or export cannot be read/parsed
	 */
	public static Baseline load(File folder) throws IOException {
		File sidecar = new File(folder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);

		String id = text(root, "id", folder.getName()); //$NON-NLS-1$
		String title = text(root, "title", id); //$NON-NLS-1$
		boolean active = root.path("active").asBoolean(true); //$NON-NLS-1$
		String promptOverride = optText(root, "promptOverride"); //$NON-NLS-1$
		String agent = optText(root, "agent"); //$NON-NLS-1$
		String model = optText(root, "model"); //$NON-NLS-1$
		int maxAttempts = root.path("maxAttempts").asInt(DEFAULT_MAX_ATTEMPTS); //$NON-NLS-1$
		int timeoutSeconds = root.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$

		String solution = null;
		Baseline.Fixture fixture = null;
		Baseline.Source source = null;
		List<String> cleanProjects = List.of();
		JsonNode precondition = root.get("precondition"); //$NON-NLS-1$
		if (precondition != null) {
			solution = optText(precondition, "solution"); //$NON-NLS-1$
			fixture = parseFixture(precondition, solution);
			source = parseSource(precondition.get("source")); //$NON-NLS-1$
			cleanProjects = stringArray(precondition.get("cleanProjects")); //$NON-NLS-1$
		}

		Baseline.Compare compare = parseCompare(root.get("compare")); //$NON-NLS-1$
		List<OutcomeAssertion> expected = OutcomeAssertions.parse(root.get("expect")); //$NON-NLS-1$
		Baseline.JsUnitVerify jsUnitVerify = parseJsUnitVerify(root.get("verify")); //$NON-NLS-1$

		String exportName = text(root, "export", "export.json"); //$NON-NLS-1$ //$NON-NLS-2$
		SessionTranscript golden = loadExport(new File(folder, exportName));

		return new Baseline(id, title, active, promptOverride, agent, model, maxAttempts, timeoutSeconds, solution,
				source, cleanProjects, fixture, compare, expected, jsUnitVerify, golden);
	}

	/**
	 * Parses the {@code verify.jsunit} block that declares an optional Servoy
	 * JSUnit behavioural verification to run after the skill run + outcome check
	 * (SVY-21366 §3.4b):
	 *
	 * <pre>
	 * "verify": { "jsunit": {
	 *   "scripts": [ "verify/scopes/skilltest_verify.js" ],
	 *   "scope": "skilltest_verify", "method": null,
	 *   "timeoutSeconds": 180, "required": true } }
	 * </pre>
	 *
	 * @param verifyNode the {@code verify} JSON node (may be {@code null})
	 * @return the parsed verification, or {@code null} when none is declared
	 */
	private static Baseline.JsUnitVerify parseJsUnitVerify(JsonNode verifyNode) {
		if (verifyNode == null || verifyNode.isNull()) {
			return null;
		}
		JsonNode jsunit = verifyNode.get("jsunit"); //$NON-NLS-1$
		if (jsunit == null || jsunit.isNull()) {
			return null;
		}
		List<String> scripts = stringArray(jsunit.get("scripts")); //$NON-NLS-1$
		String scope = optText(jsunit, "scope"); //$NON-NLS-1$
		if (scope == null || scope.isBlank()) {
			scope = "ALL"; //$NON-NLS-1$
		}
		String method = optText(jsunit, "method"); //$NON-NLS-1$
		int timeoutSeconds = jsunit.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$
		boolean required = jsunit.path("required").asBoolean(true); //$NON-NLS-1$
		return new Baseline.JsUnitVerify(scripts, scope, method, timeoutSeconds, required);
	}

	/**
	 * Parses the {@code precondition.source} block that declares where the
	 * baseline's initial-state solution(s) come from:
	 *
	 * <pre>
	 * "source": { "type": "git",    "location": "https://...", "ref": "main" }
	 * "source": { "type": "folder", "location": "C:/path/to/solutions" }
	 * "source": { "type": "empty" }
	 * </pre>
	 *
	 * @param sourceNode the {@code source} JSON node (may be {@code null})
	 * @return the parsed source, or {@code null} when none is declared (the runner
	 *         then leaves the workspace as-is)
	 */
	private static Baseline.Source parseSource(JsonNode sourceNode) {
		if (sourceNode == null || sourceNode.isNull()) {
			return null;
		}
		String typeText = optText(sourceNode, "type"); //$NON-NLS-1$
		Baseline.Source.Type type = Baseline.Source.Type.EMPTY;
		if (typeText != null) {
			try {
				type = Baseline.Source.Type.valueOf(typeText.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignore) {
				// unknown type -> treat as empty
			}
		}
		String location = optText(sourceNode, "location"); //$NON-NLS-1$
		String ref = optText(sourceNode, "ref"); //$NON-NLS-1$
		return new Baseline.Source(type, location, ref);
	}

	/**
	 * Imports an existing {@code opencode export} JSON file as a new baseline: it
	 * validates the JSON, extracts the prompt, and writes
	 * {@code <baselinesRoot>/<id>/export.json} + a starter {@code baseline.json}
	 * for human review. The picked file is normalised (a leading non-JSON status
	 * line is stripped).
	 *
	 * @param exportFile    the export JSON file the user picked from disk
	 * @param baselinesRoot the baselines root to create the new folder under
	 * @param id            the baseline id / folder name
	 * @param title         an optional title (defaults to {@code id})
	 * @return the created baseline folder
	 * @throws IOException if the export is unreadable/invalid or the target exists
	 */
	public static File importExport(File exportFile, File baselinesRoot, String id, String title) throws IOException {
		return importExport(exportFile, baselinesRoot, id, title, null);
	}

	/**
	 * Like {@link #importExport(File, File, String, String)} but also writes the
	 * given expected-outcome assertions into the new {@code baseline.json}'s
	 * {@code expect} block. Used by the import flow after the user has confirmed /
	 * edited the assertions inferred from the export (SVY-21366).
	 *
	 * @param exportFile    the export JSON file the user picked from disk
	 * @param baselinesRoot the baselines root to create the new folder under
	 * @param id            the baseline id / folder name
	 * @param title         an optional title (defaults to {@code id})
	 * @param expected      the confirmed expected outcomes (may be {@code null} /
	 *                      empty)
	 * @return the created baseline folder
	 * @throws IOException if the export is unreadable/invalid or the target exists
	 */
	public static File importExport(File exportFile, File baselinesRoot, String id, String title,
			List<OutcomeAssertion> expected) throws IOException {
		if (exportFile == null || !exportFile.isFile()) {
			throw new IOException("export file not found: " + exportFile); //$NON-NLS-1$
		}
		if (id == null || !id.matches("[A-Za-z0-9._-]+")) { //$NON-NLS-1$
			throw new IOException(
					"invalid baseline id '" + id + "': only letters, digits, '.', '_' and '-' are allowed"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String raw = Files.readString(exportFile.toPath(), StandardCharsets.UTF_8);
		String normalised = stripLeadingNonJson(raw);
		JsonNode node = MAPPER.readTree(normalised); // throws if invalid JSON
		SessionTranscript transcript = SessionTranscript.fromExport(node);

		File targetFolder = new File(baselinesRoot, id);
		if (targetFolder.exists()) {
			throw new IOException("baseline folder already exists: " + targetFolder); //$NON-NLS-1$
		}
		if (!targetFolder.mkdirs()) {
			throw new IOException("cannot create baseline folder: " + targetFolder); //$NON-NLS-1$
		}
		Files.writeString(new File(targetFolder, "export.json").toPath(), normalised, StandardCharsets.UTF_8); //$NON-NLS-1$
		Files.writeString(new File(targetFolder, "baseline.json").toPath(), //$NON-NLS-1$
				starterSidecar(id, title != null && !title.isBlank() ? title : id, transcript.getPrompt(), expected),
				StandardCharsets.UTF_8);
		return targetFolder;
	}

	/**
	 * Overwrites an existing baseline's {@code expect} block with the given
	 * assertions, preserving every other sidecar field. Used by the view's
	 * "edit assertions" action.
	 *
	 * @param baselineFolder the baseline's folder
	 * @param expected       the assertions to persist (may be empty)
	 * @throws IOException if the sidecar cannot be read or written
	 */
	public static void writeExpected(File baselineFolder, List<OutcomeAssertion> expected) throws IOException {
		File sidecar = new File(baselineFolder, "baseline.json"); //$NON-NLS-1$
		JsonNode root = MAPPER.readTree(sidecar);
		if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
			throw new IOException("baseline.json is not a JSON object: " + sidecar); //$NON-NLS-1$
		}
		// Preserve all other sidecar fields; only replace the expect block.
		obj.set("expect", OutcomeAssertions.toJson(expected)); //$NON-NLS-1$
		ObjectMapper writer = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		Files.writeString(sidecar.toPath(), writer.writeValueAsString(obj), StandardCharsets.UTF_8);
	}

	private static String starterSidecar(String id, String title, String prompt, List<OutcomeAssertion> expected)
			throws IOException {
		ObjectMapper mapper = MAPPER.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
		root.put("id", id); //$NON-NLS-1$
		root.put("title", title); //$NON-NLS-1$
		root.put("active", true); //$NON-NLS-1$
		root.put("export", "export.json"); //$NON-NLS-1$ //$NON-NLS-2$
		if (prompt != null && !prompt.isBlank()) {
			root.put("promptOverride", prompt); //$NON-NLS-1$
		} else {
			root.putNull("promptOverride"); //$NON-NLS-1$
		}
		root.putNull("agent"); //$NON-NLS-1$
		root.putNull("model"); //$NON-NLS-1$
		root.put("maxAttempts", DEFAULT_MAX_ATTEMPTS); //$NON-NLS-1$
		root.put("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$
		// Precondition: an 'empty' source must know which solution to create, so seed
		// a default solution + empty source. The team edits this per baseline.
		com.fasterxml.jackson.databind.node.ObjectNode precondition = root.putObject("precondition"); //$NON-NLS-1$
		precondition.put("solution", DEFAULT_SOLUTION); //$NON-NLS-1$
		precondition.putObject("source").put("type", "empty"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		com.fasterxml.jackson.databind.node.ObjectNode compare = root.putObject("compare"); //$NON-NLS-1$
		compare.put("ordered", false); //$NON-NLS-1$
		compare.put("strict", false); //$NON-NLS-1$
		compare.putArray("forbidden"); //$NON-NLS-1$
		compare.putObject("argOverrides"); //$NON-NLS-1$
		// Expected outcomes (the authoritative "focus on the outcome" check): write
		// the confirmed/inferred assertions so the runner checks the real persist
		// tree rather than the transcript.
		root.set("expect", OutcomeAssertions.toJson(expected != null ? expected : java.util.List.of())); //$NON-NLS-1$
		return mapper.writeValueAsString(root);
	}

	/**
	 * Parses an {@code export.json} file (tolerating a leading non-JSON status
	 * line) into a {@link SessionTranscript}.
	 *
	 * @param exportFile the golden export file
	 * @return the parsed transcript (empty transcript if the file is absent)
	 * @throws IOException if the file cannot be read or parsed
	 */
	public static SessionTranscript loadExport(File exportFile) throws IOException {
		if (exportFile == null || !exportFile.isFile()) {
			return new SessionTranscript(null, List.of());
		}
		String raw = Files.readString(exportFile.toPath(), StandardCharsets.UTF_8);
		JsonNode node = MAPPER.readTree(stripLeadingNonJson(raw));
		return SessionTranscript.fromExport(node);
	}

	/**
	 * Strips a leading non-JSON line if present (opencode #12130: {@code opencode
	 * export} may prefix a status line to stdout, producing invalid JSON).
	 *
	 * @param raw the raw stdout captured from {@code opencode export}
	 * @return a string that starts at the first {@code &#123;} or {@code [}
	 */
	public static String stripLeadingNonJson(String raw) {
		if (raw == null) {
			return "{}"; //$NON-NLS-1$
		}
		String trimmed = raw.stripLeading();
		if (trimmed.startsWith("{") || trimmed.startsWith("[")) { //$NON-NLS-1$ //$NON-NLS-2$
			return trimmed;
		}
		int brace = trimmed.indexOf('{');
		int bracket = trimmed.indexOf('[');
		int start;
		if (brace < 0) {
			start = bracket;
		} else if (bracket < 0) {
			start = brace;
		} else {
			start = Math.min(brace, bracket);
		}
		return start >= 0 ? trimmed.substring(start) : "{}"; //$NON-NLS-1$
	}

	/**
	 * Parses the fixture reset manifest from a {@code precondition} block:
	 * {@code delete} (files to remove) and {@code restore} (tracked files to
	 * revert to git {@code HEAD}). Returns {@code null} when there is nothing to
	 * reset, so the runner skips the setup step entirely.
	 *
	 * @param precondition the {@code precondition} node (non-null)
	 * @param solution     the resolved solution/project name (may be {@code null})
	 * @return the parsed fixture, or {@code null} if empty
	 */
	private static Baseline.Fixture parseFixture(JsonNode precondition, String solution) {
		List<String> delete = stringArray(precondition.get("delete")); //$NON-NLS-1$
		List<String> restore = stringArray(precondition.get("restore")); //$NON-NLS-1$
		if (delete.isEmpty() && restore.isEmpty()) {
			return null;
		}
		return new Baseline.Fixture(solution, delete, restore);
	}

	private static List<String> stringArray(JsonNode node) {
		List<String> result = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode entry : node) {
				if (entry != null && entry.isValueNode() && !entry.isNull()) {
					String value = entry.asText();
					if (value != null && !value.isBlank()) {
						result.add(value);
					}
				}
			}
		}
		return result;
	}

	private static Baseline.Compare parseCompare(JsonNode compareNode) {
		if (compareNode == null) {
			return Baseline.Compare.defaults();
		}
		boolean ordered = compareNode.path("ordered").asBoolean(false); //$NON-NLS-1$
		boolean strict = compareNode.path("strict").asBoolean(false); //$NON-NLS-1$

		List<Baseline.ToolRef> forbidden = new ArrayList<>();
		JsonNode forbiddenNode = compareNode.get("forbidden"); //$NON-NLS-1$
		if (forbiddenNode != null && forbiddenNode.isArray()) {
			for (JsonNode entry : forbiddenNode) {
				forbidden.add(new Baseline.ToolRef(optText(entry, "server"), optText(entry, "tool"))); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		Map<String, Map<String, ArgMatcher>> argOverrides = new LinkedHashMap<>();
		JsonNode overridesNode = compareNode.get("argOverrides"); //$NON-NLS-1$
		if (overridesNode != null && overridesNode.isObject()) {
			for (Map.Entry<String, JsonNode> toolEntry : overridesNode.properties()) {
				Map<String, ArgMatcher> perArg = new LinkedHashMap<>();
				JsonNode argsNode = toolEntry.getValue();
				if (argsNode.isObject()) {
					for (Map.Entry<String, JsonNode> argEntry : argsNode.properties()) {
						ArgMatcher matcher = parseMatcher(argEntry.getValue());
						if (matcher != null) {
							perArg.put(argEntry.getKey(), matcher);
						}
					}
				}
				argOverrides.put(toolEntry.getKey(), perArg);
			}
		}
		return new Baseline.Compare(ordered, strict, forbidden, argOverrides);
	}

	private static ArgMatcher parseMatcher(JsonNode matcherNode) {
		if (matcherNode == null) {
			return null;
		}
		if (matcherNode.isObject()) {
			if (matcherNode.has("exact")) { //$NON-NLS-1$
				return ArgMatcher.exact(toValue(matcherNode.get("exact"))); //$NON-NLS-1$
			}
			if (matcherNode.has("equals")) { //$NON-NLS-1$
				return ArgMatcher.equalsMatcher(toValue(matcherNode.get("equals"))); //$NON-NLS-1$
			}
			if (matcherNode.has("contains")) { //$NON-NLS-1$
				return ArgMatcher.contains(toValue(matcherNode.get("contains"))); //$NON-NLS-1$
			}
			if (matcherNode.has("containsAll")) { //$NON-NLS-1$
				return ArgMatcher.containsAll(toValue(matcherNode.get("containsAll"))); //$NON-NLS-1$
			}
			if (matcherNode.has("regex")) { //$NON-NLS-1$
				return ArgMatcher.regex(toValue(matcherNode.get("regex"))); //$NON-NLS-1$
			}
			if (matcherNode.has("present")) { //$NON-NLS-1$
				return ArgMatcher.present();
			}
		}
		return ArgMatcher.tolerant(toValue(matcherNode));
	}

	private static Object toValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		if (node.isBoolean()) {
			return node.asBoolean();
		}
		if (node.isInt() || node.isLong()) {
			return node.asLong();
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		if (node.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode child : node) {
				list.add(toValue(child));
			}
			return list;
		}
		return node.asText();
	}

	private static String text(JsonNode node, String field, String fallback) {
		String value = optText(node, field);
		return value != null ? value : fallback;
	}

	private static String optText(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
	}
}
