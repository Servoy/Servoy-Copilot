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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;

/**
 * Jupiter (JUnit 6) unit tests guarding the MCP JSON-schema types published by
 * {@link ServoyIdeServer} tools, for SVY-21618.
 * <p>
 * An agent naturally passes a line count as a JSON number ({@code maxLines: 30})
 * and a flag as a JSON boolean. When the published schema declares such a
 * parameter as {@code "string"}, the MCP JSON-schema validator rejects the call
 * with "Expected string" before the tool method ever runs - which silently
 * dropped the Reviewer's console check. These tests pin {@code getConsoleOutput}'s
 * {@code maxLines} to an {@code integer} schema type and its two flags to
 * {@code boolean}, and pin {@code getSource}'s {@code name} description to spell
 * out the argument key so agents stop guessing {@code formName}.
 * </p>
 */
public class ServoyIdeServerSchemaTest {

	@Test
	@DisplayName("getConsoleOutput maxLines is declared as an integer JSON-schema type")
	void getConsoleOutput_maxLinesIsInteger() {
		assertEquals("integer", paramType("getConsoleOutput", "maxLines"),
				"maxLines must be an integer schema type so a numeric arg is accepted (SVY-21618)");
	}

	@Test
	@DisplayName("getConsoleOutput includeAllConsoles and clear are declared as boolean JSON-schema types")
	void getConsoleOutput_flagsAreBoolean() {
		assertEquals("boolean", paramType("getConsoleOutput", "includeAllConsoles"),
				"includeAllConsoles must be a boolean schema type");
		assertEquals("boolean", paramType("getConsoleOutput", "clear"),
				"clear must be a boolean schema type");
	}

	@Test
	@DisplayName("getSource name description names the 'name' key and warns off 'formName'")
	void getSource_nameDescriptionNamesTheKey() {
		String desc = paramDescription("getSource", "name");
		assertTrue(desc.contains("name"), "description must mention the actual key 'name': " + desc);
		assertTrue(desc.contains("formName"),
				"description must warn that the key is not 'formName' (SVY-21618): " + desc);
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private static Method findToolMethod(String toolName) {
		return Arrays.stream(ServoyIdeServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}

	private static ToolParam param(String toolName, String paramName) {
		Method m = findToolMethod(toolName);
		assertNotNull(m, "tool not found: " + toolName);
		ToolParam p = Arrays.stream(m.getParameters()).map(par -> par.getAnnotation(ToolParam.class))
				.filter(par -> par != null && paramName.equals(par.name())).findFirst().orElse(null);
		assertNotNull(p, "param not found: " + toolName + "." + paramName);
		return p;
	}

	private static String paramType(String toolName, String paramName) {
		return param(toolName, paramName).type();
	}

	private static String paramDescription(String toolName, String paramName) {
		return param(toolName, paramName).description();
	}
}
