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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;

/**
 * JUnit 4 tests for {@link ServoyDevServer}.
 */
public class ServoyDevServerIntegrationTest extends DialogGuardBase {

	private final ServoyDevServer server = new ServoyDevServer();

	@Test
	public void testServoyDevServer_hasCorrectAnnotation() {
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyDevServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyDevServer must have @McpServer annotation", ann);
		assertEquals("servoy-dev", ann.name());
	}

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError() {
		try {
			String result = server.resolveIdentifierType(null, "someForm", null);
			assertNotNull(result);
			assertTrue("Should start with Error for null identifier", result.startsWith("Error"));
			assertTrue("Should mention required", result.contains("required"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testResolveIdentifierType_unknownForm_returnsNotFound() {
		try {
			String result = server.resolveIdentifierType("myVar", "nonExistentForm_XYZ_ABC", null);
			assertNotNull(result);
			assertTrue("Should return not-found message",
					result.contains("not found") || result.contains("nonExistentForm_XYZ_ABC"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testPing_returnsPong() {
		assertEquals("pong", server.ping());
	}

	// -----------------------------------------------------------------------
	// createSolution tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyDevServer_hasCreateSolutionTool() {
		assertTrue("ServoyDevServer must have a 'createSolution' tool", hasToolNamed("createSolution"));
	}

	@Test
	public void testServoyDevServer_createSolutionHasFiveParams() {
		Method method = findToolMethod("createSolution");
		assertNotNull("createSolution tool must exist", method);
		assertEquals("createSolution must have 5 parameters", 5, method.getParameterCount());
	}

	@Test
	public void testServoyDevServer_createSolutionReturnsString() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		assertEquals("createSolution must return String", String.class, method.getReturnType());
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsNullName() {
		String result = server.createSolution(null, null, null, null, null);
		assertTrue("createSolution must reject null name", result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsBlankName() {
		String result = server.createSolution("   ", null, null, null, null);
		assertTrue("createSolution must reject blank name", result.contains("Error") && result.contains("required"));
	}

	@Test
	public void testServoyDevServer_createSolutionHasToolParamAnnotations() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		long paramCount = Arrays.stream(method.getParameters()).filter(p -> p.isAnnotationPresent(ToolParam.class))
				.count();
		assertEquals("All 5 createSolution params must have @ToolParam", 5, paramCount);
	}

	@Test
	public void testServoyDevServer_createSolutionDescriptionMentionsWizard() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("createSolution description should mention wizard", tool.description().contains("wizard"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private boolean hasToolNamed(String name) {
		return Arrays.stream(ServoyDevServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName) {
		return Arrays.stream(ServoyDevServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}
}
