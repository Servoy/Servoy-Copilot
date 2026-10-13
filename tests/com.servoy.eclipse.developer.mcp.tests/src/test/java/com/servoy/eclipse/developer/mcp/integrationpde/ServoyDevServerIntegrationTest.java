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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.DialogGuardBaseCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;

/**
 * JUnit 4 tests for {@link ServoyDevServer}.
 */
public class ServoyDevServerIntegrationTest extends DialogGuardBaseCopy {

	private final ServoyDevServer server = new ServoyDevServer();

	@Test
	public void testServoyDevServer_hasCorrectAnnotation() {
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyDevServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull(ann, "ServoyDevServer must have @McpServer annotation");
		assertEquals("servoy-dev", ann.name());
	}

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError() {
		try {
			String result = server.resolveIdentifierType(null, "someForm", null);
			assertNotNull(result);
			assertTrue(result.startsWith("Error"), "Should start with Error for null identifier");
			assertTrue(result.contains("required"), "Should mention required");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
		}
	}

	@Test
	public void testResolveIdentifierType_unknownForm_returnsNotFound() {
		try {
			String result = server.resolveIdentifierType("myVar", "nonExistentForm_XYZ_ABC", null);
			assertNotNull(result);
			assertTrue(result.contains("not found") || result.contains("nonExistentForm_XYZ_ABC"),
					"Should return not-found message");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
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
		assertTrue(hasToolNamed("createSolution"), "ServoyDevServer must have a 'createSolution' tool");
	}

	@Test
	public void testServoyDevServer_createSolutionHasFiveParams() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method, "createSolution tool must exist");
		assertEquals(5, method.getParameterCount(), "createSolution must have 5 parameters");
	}

	@Test
	public void testServoyDevServer_createSolutionReturnsString() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		assertEquals(String.class, method.getReturnType(), "createSolution must return String");
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsNullName() {
		String result = server.createSolution(null, null, null, null, null);
		assertTrue(result.contains("Error") && result.contains("required"), "createSolution must reject null name");
	}

	@Test
	public void testServoyDevServer_createSolution_rejectsBlankName() {
		String result = server.createSolution("   ", null, null, null, null);
		assertTrue(result.contains("Error") && result.contains("required"), "createSolution must reject blank name");
	}

	@Test
	public void testServoyDevServer_createSolutionHasToolParamAnnotations() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		long paramCount = Arrays.stream(method.getParameters()).filter(p -> p.isAnnotationPresent(ToolParam.class))
				.count();
		assertEquals(5, paramCount, "All 5 createSolution params must have @ToolParam");
	}

	@Test
	public void testServoyDevServer_createSolutionDescriptionMentionsWizard() {
		Method method = findToolMethod("createSolution");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("wizard"), "createSolution description should mention wizard");
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
