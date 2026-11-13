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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the optional, best-effort Kiro usage estimate for a session from the
 * local {@code kiro-usage.json} snapshot written by the
 * {@code @servoy/opencode-kiro-auth} plugin (SVY-21660).
 * <p>
 * The reader resolves the usage file by probing a <b>cross-platform</b> ordered
 * list of candidate paths - the same set the OpenChamber panel uses - and uses
 * the first that exists, so the figure appears identically on a developer
 * machine and on Jenkins (Linux/macOS). First existing wins:
 * </p>
 * <ol>
 * <li>{@code $XDG_CONFIG_HOME/opencode/kiro-usage.json} when
 * {@code XDG_CONFIG_HOME} is set (Linux/macOS config override),</li>
 * <li>{@code ~/.config/opencode/kiro-usage.json} (Linux/macOS default, and the
 * panel's first probe),</li>
 * <li>{@code %APPDATA%\opencode\kiro-usage.json} (Windows; from
 * {@code APPDATA}, else
 * {@code ~/AppData/Roaming/opencode/kiro-usage.json}).</li>
 * </ol>
 * <p>
 * It parses tolerantly into the panel's shape
 * {@code { sessions: [{ sessionId, requests, estCredits }] }} and returns the
 * matching session's figures. It <b>never</b> reads a token/bearer and never
 * reaches AWS - only this local snapshot. File absent / unreadable / session
 * not found -&gt; {@link Optional#empty()} ("unknown"). Must never throw into
 * the run.
 * </p>
 */
public final class KiroUsageReader {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The ordered candidate files to probe; first existing wins. */
	private final List<File> candidates;

	/** Production reader: probes the cross-platform default candidate locations. */
	public KiroUsageReader() {
		this(defaultCandidates());
	}

	/**
	 * Test/seam constructor: probes exactly the given candidate files in order.
	 *
	 * @param candidates the ordered candidate files (first existing wins)
	 */
	public KiroUsageReader(List<File> candidates) {
		this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
	}

	/**
	 * Builds the cross-platform ordered candidate list from the environment and
	 * {@code user.home}, mirroring the panel's probe order. Kept package-visible so
	 * {@code KiroUsageReaderTest} can exercise the resolution against a temp
	 * environment.
	 *
	 * @return the ordered candidate files (never {@code null})
	 */
	static List<File> defaultCandidates() {
		List<File> files = new ArrayList<>();
		// The real Servoy AI opencode config dir (SVY-21660 B): probe
		// ~/.servoy/opencode/ FIRST - via the single source of truth, with a
		// user.home fallback for a no-OSGi test - so real runs stop reading
		// "unknown". Only then fall back to the panel's cross-platform defaults.
		File servoyConfig = KiroCreditReader.servoyOpencodeConfigDir();
		if (servoyConfig != null) {
			files.add(new File(servoyConfig, "kiro-usage.json")); //$NON-NLS-1$
		}
		String userHome = System.getProperty("user.home"); //$NON-NLS-1$
		if (servoyConfig == null && userHome != null && !userHome.isBlank()) {
			files.add(new File(new File(new File(new File(userHome), ".servoy"), "opencode"), //$NON-NLS-1$ //$NON-NLS-2$
					"kiro-usage.json")); //$NON-NLS-1$
		}
		String xdg = System.getenv("XDG_CONFIG_HOME"); //$NON-NLS-1$
		if (xdg != null && !xdg.isBlank()) {
			files.add(new File(new File(new File(xdg), "opencode"), "kiro-usage.json")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (userHome != null && !userHome.isBlank()) {
			files.add(new File(new File(new File(new File(userHome), ".config"), "opencode"), //$NON-NLS-1$ //$NON-NLS-2$
					"kiro-usage.json")); //$NON-NLS-1$
		}
		String appData = System.getenv("APPDATA"); //$NON-NLS-1$
		if (appData != null && !appData.isBlank()) {
			files.add(new File(new File(new File(appData), "opencode"), "kiro-usage.json")); //$NON-NLS-1$ //$NON-NLS-2$
		} else if (userHome != null && !userHome.isBlank()) {
			files.add(new File(new File(new File(new File(new File(userHome), "AppData"), "Roaming"), //$NON-NLS-1$ //$NON-NLS-2$
					"opencode"), "kiro-usage.json")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return files;
	}

	/**
	 * Reads the Kiro usage estimate for the given session id.
	 *
	 * @param sessionId the opencode session id to look up (may be {@code null})
	 * @return the session's estimate, or {@link Optional#empty()} when the file is
	 *         absent/unreadable, the session is not found, or {@code sessionId} is
	 *         blank. Never throws.
	 */
	public Optional<RunMetrics.KiroUsage> readForSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return Optional.empty();
		}
		File file = resolveUsageFile();
		if (file == null) {
			return Optional.empty();
		}
		try {
			JsonNode root = MAPPER.readTree(file);
			JsonNode sessions = root != null ? root.get("sessions") : null; //$NON-NLS-1$
			if (sessions == null || !sessions.isArray()) {
				return Optional.empty();
			}
			for (JsonNode session : sessions) {
				JsonNode idNode = session.get("sessionId"); //$NON-NLS-1$
				if (idNode != null && idNode.isValueNode() && sessionId.equals(idNode.asText())) {
					int requests = intValue(session, "requests"); //$NON-NLS-1$
					double estCredits = doubleValue(session, "estCredits"); //$NON-NLS-1$
					return Optional.of(new RunMetrics.KiroUsage(requests, estCredits));
				}
			}
		} catch (Exception e) {
			// Absent/unreadable/malformed: degrade to "unknown" rather than fail the run.
			return Optional.empty();
		}
		return Optional.empty();
	}

	/**
	 * @return the first existing candidate file, or {@code null} when none exists.
	 */
	private File resolveUsageFile() {
		for (File f : candidates) {
			if (f != null && f.isFile()) {
				return f;
			}
		}
		return null;
	}

	private static int intValue(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asInt() : 0;
	}

	private static double doubleValue(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? value.asDouble() : 0d;
	}
}
