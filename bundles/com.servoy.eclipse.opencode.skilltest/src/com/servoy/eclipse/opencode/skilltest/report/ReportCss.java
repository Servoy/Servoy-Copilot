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

package com.servoy.eclipse.opencode.skilltest.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * The shared, external stylesheet of the skill-test reports
 * ({@code coverage.html} and {@code trend.html}, SVY-21661).
 * <p>
 * Jenkins serves archived artifacts under a strict Content-Security-Policy
 * ({@code style-src 'self'} without {@code 'unsafe-inline'}) that blocks both
 * {@code <style>} blocks and {@code style="..."} attributes. A same-origin
 * external stylesheet is allowed, so each report links {@value #FILE_NAME}
 * <b>relatively</b> and the stylesheet is copied next to the HTML by
 * {@link #writeCss(Path, Consumer)}.
 * </p>
 */
public final class ReportCss {

	/** The stylesheet file name, next to the report HTML. */
	public static final String FILE_NAME = "skilltest-report.css"; //$NON-NLS-1$

	private ReportCss() {
	}

	/** @return the relative {@code <link>} tag every report emits in its head */
	public static String linkTag() {
		return "<link rel=\"stylesheet\" href=\"" + FILE_NAME + "\">"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Opens the bundled stylesheet resource.
	 *
	 * @return the stream, or {@code null} when the resource is not on the class
	 *         path
	 */
	public static InputStream open() {
		return ReportCss.class.getResourceAsStream(FILE_NAME);
	}

	/**
	 * Copies the stylesheet into {@code dir} (overwriting). Best-effort: never
	 * throws.
	 *
	 * @param dir the report directory (created if absent)
	 * @param log diagnostic sink (may be {@code null})
	 * @return {@code true} when written
	 */
	public static boolean writeCss(Path dir, Consumer<String> log) {
		if (dir == null) {
			return false;
		}
		try (InputStream in = open()) {
			if (in == null) {
				log(log, "Report stylesheet resource " + FILE_NAME + " not found on the class path."); //$NON-NLS-1$ //$NON-NLS-2$
				return false;
			}
			Files.createDirectories(dir);
			Files.copy(in, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException | RuntimeException e) {
			log(log, "Could not write report stylesheet into " + dir + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
	}

	private static void log(Consumer<String> log, String msg) {
		if (log != null) {
			try {
				log.accept(msg);
			} catch (RuntimeException ignored) {
				// never break the caller
			}
		}
	}
}
