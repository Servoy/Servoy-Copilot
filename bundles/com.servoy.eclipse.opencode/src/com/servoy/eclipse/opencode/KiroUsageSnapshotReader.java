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

package com.servoy.eclipse.opencode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Reads the Kiro credit snapshot ({@code kiro-usage.json}) from the Servoy
 * opencode config directory and turns it into a small, whitelisted JSON answer
 * for the chat UI's {@code GET /servoy_ai/usage} route.
 * <p>
 * Only the fixed file name {@value #FILE_NAME} is ever read, size-capped, and
 * the output is built field by field (never a copy of the parsed tree), so
 * e-mail, ids, region, directories etc. can not leak. Every failure (absent,
 * unreadable, oversize, malformed, wrong shape, no healthy account) is answered
 * as {@code {"available":false}}.
 * </p>
 */
public class KiroUsageSnapshotReader {

	/** The one and only file this reader opens. */
	public static final String FILE_NAME = "kiro-usage.json"; //$NON-NLS-1$

	/** Maximum size of the snapshot file that is parsed. */
	static final long MAX_BYTES = 1024L * 1024L;

	/** A snapshot older than this is considered stale. */
	static final long STALE_AFTER_MS = 24L * 60L * 60L * 1000L;

	/** The answer when nothing usable is available. */
	public static final String UNAVAILABLE_JSON = "{\"available\":false}"; //$NON-NLS-1$

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Supplier<Path> configDirSupplier;

	/**
	 * @param configDirSupplier resolves the Servoy opencode config directory (may return {@code null})
	 */
	public KiroUsageSnapshotReader(Supplier<Path> configDirSupplier) {
		this.configDirSupplier = configDirSupplier;
	}

	/**
	 * @return the whitelisted JSON answer; never {@code null} and never throws
	 */
	public String readJson(long nowMillis) {
		try {
			Path dir = configDirSupplier != null ? configDirSupplier.get() : null;
			return read(dir, nowMillis);
		}
		catch (RuntimeException e) {
			logWarning(e);
			return UNAVAILABLE_JSON;
		}
	}

	/**
	 * @param configDir the config directory (may be {@code null})
	 * @return the whitelisted JSON answer; never {@code null}
	 */
	public String read(Path configDir, long nowMillis) {
		if (configDir == null) {
			return UNAVAILABLE_JSON;
		}
		try {
			Path file = configDir.resolve(FILE_NAME);
			// a symlinked kiro-usage.json is never followed
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
				return UNAVAILABLE_JSON;
			}
			JsonNode root;
			try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
				byte[] bytes = in.readNBytes((int)MAX_BYTES + 1);
				if (bytes.length > MAX_BYTES) {
					return UNAVAILABLE_JSON;
				}
				root = MAPPER.readTree(bytes);
			}
			return toJson(root, nowMillis);
		}
		catch (IOException | RuntimeException e) {
			logWarning(e);
			return UNAVAILABLE_JSON;
		}
	}

	private static String toJson(JsonNode root, long nowMillis) {
		if (root == null || !root.isObject()) {
			return UNAVAILABLE_JSON;
		}
		JsonNode accounts = root.get("accounts"); //$NON-NLS-1$
		if (accounts == null || !accounts.isArray()) {
			return UNAVAILABLE_JSON;
		}
		JsonNode account = null;
		for (JsonNode candidate : accounts) {
			if (candidate != null && candidate.isObject() && candidate.path("isHealthy").isBoolean() //$NON-NLS-1$
				&& candidate.path("isHealthy").asBoolean()) { //$NON-NLS-1$
				account = candidate;
				break;
			}
		}
		if (account == null) {
			return UNAVAILABLE_JSON;
		}

		ObjectNode out = MAPPER.createObjectNode();
		out.put("available", true); //$NON-NLS-1$
		Double used = number(account.get("used")); //$NON-NLS-1$
		if (used != null) {
			out.put("used", used.doubleValue()); //$NON-NLS-1$
		}
		Double limit = number(account.get("limit")); //$NON-NLS-1$
		if (limit != null && limit.doubleValue() > 0) {
			out.put("limit", limit.doubleValue()); //$NON-NLS-1$
		}
		Double pct = number(account.get("pct")); //$NON-NLS-1$
		if (pct != null) {
			out.put("pct", pct.doubleValue()); //$NON-NLS-1$
		}
		Double resetAtValue = number(account.get("resetAt")); //$NON-NLS-1$
		Long resetAt = resetAtValue != null ? Long.valueOf(resetAtValue.longValue()) : null;
		if (resetAt != null) {
			out.put("resetAt", resetAt.longValue()); //$NON-NLS-1$
		}
		Long updatedAt = parseTime(account.get("updatedAt")); //$NON-NLS-1$
		if (updatedAt == null) {
			updatedAt = parseTime(root.get("writtenAt")); //$NON-NLS-1$
		}
		if (updatedAt != null) {
			out.put("updatedAt", updatedAt.longValue()); //$NON-NLS-1$
		}
		boolean stale = (resetAt != null && resetAt.longValue() < nowMillis) ||
			updatedAt == null || nowMillis - updatedAt.longValue() > STALE_AFTER_MS;
		out.put("stale", stale); //$NON-NLS-1$
		return out.toString();
	}

	private static Double number(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		double d = node.asDouble();
		return Double.isFinite(d) ? Double.valueOf(d) : null;
	}

	/** Parses epoch millis (number) or an ISO-8601-like string; {@code null} when unparsable. */
	static Long parseTime(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			double d = node.asDouble();
			return Double.isFinite(d) ? Long.valueOf((long)d) : null;
		}
		if (!node.isTextual()) {
			return null;
		}
		String text = node.asText().trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Long.valueOf(Long.parseLong(text));
		}
		catch (NumberFormatException e) {
			// not epoch millis, try date-time formats below
		}
		String iso = text.replace(' ', 'T');
		try {
			return Long.valueOf(Instant.parse(iso).toEpochMilli());
		}
		catch (DateTimeParseException e) {
			// try the next format
		}
		try {
			return Long.valueOf(OffsetDateTime.parse(iso).toInstant().toEpochMilli());
		}
		catch (DateTimeParseException e) {
			// try the next format
		}
		// e.g. "2026-10-02T14:12Z": Instant.parse needs seconds, so add them
		if (iso.endsWith("Z") && iso.length() > 1) { //$NON-NLS-1$
			String body = iso.substring(0, iso.length() - 1);
			try {
				return Long.valueOf(LocalDateTime.parse(body).toInstant(ZoneOffset.UTC).toEpochMilli());
			}
			catch (DateTimeParseException e) {
				// unparsable
			}
		}
		try {
			return Long.valueOf(LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli());
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	private static void logWarning(Exception e) {
		try {
			// never log file content, only the failure type
			ServoyLog.logInfo("KiroUsageSnapshotReader: cannot read usage snapshot (" + e.getClass().getSimpleName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (Throwable t) { // NOSONAR logging failure is intentionally ignored
			// ignored on purpose
		}
	}
}
