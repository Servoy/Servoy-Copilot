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
package com.servoy.eclipse.developer.mcp.junit;

import org.junit.jupiter.api.Assertions;

/**
 * Message-first assertion facade that delegates to JUnit 5/6 (Jupiter)
 * {@link org.junit.jupiter.api.Assertions}.
 * <p>
 * The whole {@code com.servoy.eclipse.developer.mcp.tests} bundle runs on the
 * Jupiter engine; there is no JUnit 4 on the classpath. This class exists only
 * so that the (very large) body of existing assertion call sites, which were
 * written with the JUnit 4 <em>message-first</em> argument order
 * ({@code assertEquals("message", expected, actual)}), keep compiling and
 * behaving identically after the migration without having to reorder ~1500
 * call sites by hand.
 * <p>
 * Each method simply forwards to the equivalent Jupiter assertion, moving the
 * message argument to the last position that Jupiter expects. New tests should
 * prefer {@link org.junit.jupiter.api.Assertions} directly with the
 * message-last order.
 */
public final class Assert
{
	private Assert()
	{
		// static utility
	}

	// ---- assertTrue / assertFalse -------------------------------------------

	public static void assertTrue(boolean condition)
	{
		Assertions.assertTrue(condition);
	}

	public static void assertTrue(String message, boolean condition)
	{
		Assertions.assertTrue(condition, message);
	}

	public static void assertFalse(boolean condition)
	{
		Assertions.assertFalse(condition);
	}

	public static void assertFalse(String message, boolean condition)
	{
		Assertions.assertFalse(condition, message);
	}

	// ---- assertNull / assertNotNull -----------------------------------------

	public static void assertNull(Object actual)
	{
		Assertions.assertNull(actual);
	}

	public static void assertNull(String message, Object actual)
	{
		Assertions.assertNull(actual, message);
	}

	public static void assertNotNull(Object actual)
	{
		Assertions.assertNotNull(actual);
	}

	public static void assertNotNull(String message, Object actual)
	{
		Assertions.assertNotNull(actual, message);
	}

	// ---- assertEquals -------------------------------------------------------

	public static void assertEquals(Object expected, Object actual)
	{
		Assertions.assertEquals(expected, actual);
	}

	public static void assertEquals(String message, Object expected, Object actual)
	{
		Assertions.assertEquals(expected, actual, message);
	}

	public static void assertEquals(long expected, long actual)
	{
		Assertions.assertEquals(expected, actual);
	}

	public static void assertEquals(String message, long expected, long actual)
	{
		Assertions.assertEquals(expected, actual, message);
	}

	public static void assertEquals(double expected, double actual, double delta)
	{
		Assertions.assertEquals(expected, actual, delta);
	}

	public static void assertEquals(String message, double expected, double actual, double delta)
	{
		Assertions.assertEquals(expected, actual, delta, message);
	}

	// ---- assertNotEquals ----------------------------------------------------

	public static void assertNotEquals(Object unexpected, Object actual)
	{
		Assertions.assertNotEquals(unexpected, actual);
	}

	public static void assertNotEquals(String message, Object unexpected, Object actual)
	{
		Assertions.assertNotEquals(unexpected, actual, message);
	}

	// ---- assertSame ---------------------------------------------------------

	public static void assertSame(Object expected, Object actual)
	{
		Assertions.assertSame(expected, actual);
	}

	public static void assertSame(String message, Object expected, Object actual)
	{
		Assertions.assertSame(expected, actual, message);
	}

	// ---- assertArrayEquals --------------------------------------------------

	public static void assertArrayEquals(Object[] expected, Object[] actual)
	{
		Assertions.assertArrayEquals(expected, actual);
	}

	public static void assertArrayEquals(String message, Object[] expected, Object[] actual)
	{
		Assertions.assertArrayEquals(expected, actual, message);
	}

	// ---- fail ---------------------------------------------------------------

	public static void fail()
	{
		Assertions.fail();
	}

	public static void fail(String message)
	{
		Assertions.fail(message);
	}
}
