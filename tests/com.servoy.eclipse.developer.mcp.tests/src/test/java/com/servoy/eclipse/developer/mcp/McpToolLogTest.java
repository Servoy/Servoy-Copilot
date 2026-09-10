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
package com.servoy.eclipse.developer.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.InvocationTargetException;

import org.junit.Test;

/**
 * JUnit 4 tests for {@link McpToolLog}'s classification of expected
 * "not found" outcomes versus genuine errors, and for
 * {@link ResourceNotFoundException} propagation through wrapping causes.
 */
public class McpToolLogTest
{
	@Test
	public void testFindNotFound_directResourceNotFoundException()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("Error: File 'x.js' does not exist in project 'p'.");
		assertSame(tnf, McpToolLog.findNotFound(tnf));
	}

	@Test
	public void testFindNotFound_wrappedInRuntimeException()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("Error: File 'x.js' does not exist in project 'p'.");
		RuntimeException wrapper = new RuntimeException(tnf);
		assertSame(tnf, McpToolLog.findNotFound(wrapper));
	}

	@Test
	public void testFindNotFound_wrappedInInvocationTargetException()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("Error: Function 'foo' not found in file 'x.js'.");
		InvocationTargetException ite = new InvocationTargetException(tnf);
		assertSame(tnf, McpToolLog.findNotFound(ite));
	}

	@Test
	public void testFindNotFound_deeplyNested()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("Error: Project 'p' not found.");
		Throwable nested = new RuntimeException(new InvocationTargetException(new IllegalStateException(tnf)));
		assertSame(tnf, McpToolLog.findNotFound(nested));
	}

	@Test
	public void testFindNotFound_genuineErrorReturnsNull()
	{
		RuntimeException real = new RuntimeException("Error: Center line 999 is out of bounds.");
		assertNull(McpToolLog.findNotFound(real));
	}

	@Test
	public void testFindNotFound_wrappedGenuineErrorReturnsNull()
	{
		Throwable real = new InvocationTargetException(new IllegalStateException("boom"));
		assertNull(McpToolLog.findNotFound(real));
	}

	@Test
	public void testFindNotFound_nullReturnsNull()
	{
		assertNull(McpToolLog.findNotFound(null));
	}

	@Test
	public void testFindNotFound_selfReferentialCauseDoesNotLoop()
	{
		RuntimeException selfRef = new RuntimeException("loop")
		{
			private static final long serialVersionUID = 1L;

			@Override
			public synchronized Throwable getCause()
			{
				return this;
			}
		};
		assertNull(McpToolLog.findNotFound(selfRef));
	}

	@Test
	public void testLogError_notFound_doesNotThrow()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("Error: File 'x.js' does not exist in project 'p'.");
		McpToolLog.logError("readProjectResource", 2L, new InvocationTargetException(tnf));
	}

	@Test
	public void testLogError_genuineError_doesNotThrow()
	{
		McpToolLog.logError("someTool", 5L, new RuntimeException("boom"));
	}

	@Test
	public void testResourceNotFoundException_isRuntimeException()
	{
		ResourceNotFoundException tnf = new ResourceNotFoundException("nope");
		assertNotNull(tnf.getMessage());
		RuntimeException asRuntime = tnf;
		assertNotNull(asRuntime);
	}
}
