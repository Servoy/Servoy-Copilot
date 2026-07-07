/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.McpServerBuiltins;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;

public class TimeServerTest {
	private final TimeServer server = new TimeServer();

	@Test
	public void testTimeServer_hasCorrectAnnotation() {
		McpServer ann = TimeServer.class.getAnnotation(McpServer.class);
		assertNotNull("TimeServer must have @McpServer annotation", ann);
		assertEquals("time", ann.name());
	}

	@Test
	public void testTimeServer_hasTwoToolMethods() {
		long toolCount = Arrays.stream(TimeServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.count();
		assertEquals(2, toolCount);
	}

	@Test
	public void testTimeServer_registeredInBuiltins() {
		boolean found = false;
		for (Class<?> cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
			if (cls == TimeServer.class) {
				found = true;
				break;
			}
		}
		assertTrue("TimeServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testGetCurrentTime_returnsValidFormat() {
		String result = server.getCurrentTime();
		assertNotNull(result);
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
		ZonedDateTime parsed = ZonedDateTime.parse(result, formatter);
		assertNotNull(parsed);
	}

	@Test
	public void testGetCurrentTime_returnsCurrentYear() {
		String result = server.getCurrentTime();
		int currentYear = ZonedDateTime.now().getYear();
		assertTrue("Result should contain current year", result.startsWith(String.valueOf(currentYear)));
	}

	@Test
	public void testConvertTimeZone_knownConversion() {
		String timeString = "2026-01-15 10:30:00";
		String result = server.convertTimeZone(timeString, "Europe/Paris", "UTC");
		assertEquals("2026-01-15 09:30:00 UTC", result);
	}

	@Test
	public void testConvertTimeZone_sameZoneReturnsSameTime() {
		String timeString = "2026-06-15 14:00:00";
		String result = server.convertTimeZone(timeString, "UTC", "UTC");
		assertEquals(timeString, result);
	}

	@Test
	public void testConvertTimeZone_nullSourceUsesSystemDefault() {
		String timeString = "2026-03-20 12:00:00";
		String result = server.convertTimeZone(timeString, null, "America/New_York");
		assertNotNull(result);
		assertTrue("Should contain target zone abbreviation in result",
				result.contains("EDT") || result.contains("EST"));
	}

	@Test
	public void testConvertTimeZone_nullTargetUsesUTC() {
		String timeString = "2026-03-20 12:00:00";
		String result = server.convertTimeZone(timeString, "Europe/London", null);
		assertNotNull(result);
		assertTrue("Should contain UTC zone", result.contains("UTC"));
	}

	@Test
	public void testConvertTimeZone_invalidSourceZoneReturnsError() {
		String result = server.convertTimeZone("2026-01-01 10:00:00", "Invalid/Zone", "UTC");
		assertTrue("Should return error message", result.startsWith("Error converting time zone:"));
	}

	@Test
	public void testConvertTimeZone_invalidTargetZoneReturnsError() {
		String result = server.convertTimeZone("2026-01-01 10:00:00", "UTC", "Invalid/Zone");
		assertTrue("Should return error message", result.startsWith("Error converting time zone:"));
	}

	@Test
	public void testConvertTimeZone_invalidTimeStringReturnsError() {
		String result = server.convertTimeZone("not-a-time", "UTC", "Europe/Paris");
		assertTrue("Should return error message", result.startsWith("Error converting time zone:"));
	}

	@Test
	public void testConvertTimeZone_nullTimeStringReturnsError() {
		String result = server.convertTimeZone(null, "UTC", "Europe/Paris");
		assertTrue("Should return error message", result.startsWith("Error converting time zone:"));
	}

	@Test
	public void testConvertTimeZone_usToEurope() {
		String result = server.convertTimeZone("2026-07-04 09:00:00", "America/New_York", "Europe/Berlin");
		assertEquals("2026-07-04 15:00:00 CEST", result);
	}
}
