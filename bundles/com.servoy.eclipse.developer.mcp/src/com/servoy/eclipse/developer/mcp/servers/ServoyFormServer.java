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

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.McpToolResult;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService;
import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService.FormLayoutResult;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * MCP server for Servoy form tools. Home of the design-time form-rendering aids; its founding
 * tool is {@code getFormLayout}.
 */
@Creatable
@McpServer(name = "servoy-form")
public class ServoyFormServer
{
	/** Default seconds to wait for a form to render before reading. */
	private static final int DEFAULT_TIMEOUT_SECONDS = 15;

	private final FormLayoutInspectionService inspectionService = new FormLayoutInspectionService();

	@Tool(name = "getFormLayout", description = "SEE HOW A SERVOY FORM ACTUALLY LOOKS. This is the tool to use whenever you " +
		"have created or edited a form (its .frm, its components, style classes, or the solution .less) and want to verify the " +
		"result matches what you intended - layout, positioning, sizing, colours and styling. Prefer this over any other " +
		"screenshot/preview tool for design-time layout checks: it is fast, needs no running client and no login.\n" +
		"IMPORTANT - this is a DESIGN-TIME render: the form is rendered stateless from /formtemplate/<form>.html with NO DATA " +
		"(empty fields, no foundset rows, no running client, no calculated/related values). Do NOT expect any data in the HTML, " +
		"the appearance map or the screenshot - only the layout, structure, styling and empty component chrome. Use the " +
		"running-client tools instead when you need to see the form populated with live data.\n" +
		"By DEFAULT it returns the rendered form as TEXT, which is cheap and usually enough to verify a layout change:\n" +
		"  1. 'html' - the full rendered outerHTML of the requested subtree (the whole form, or the element matched by " +
		"'selector'): the real component tags (e.g. bootstrapcomponents-textbox), their id / data-svy-name identity attributes, " +
		"applied classes and exact nested structure;\n" +
		"  2. 'appearance' - a map keyed by element (id or CSS path) of the RESOLVED visual styles (color, background, border, " +
		"font, display, position, visibility) and on-screen bounding box (x/y/w/h). These are the browser's COMPUTED values " +
		"after applying the solution stylesheet + theme, which raw HTML alone cannot tell you - use them to know the actual " +
		"colour/position of something and to confirm a change landed.\n" +
		"OPTIONALLY, set 'screenshot=true' to ALSO get a PNG screenshot of the rendered form attached to the response (a " +
		"vision-capable model sees the actual pixels directly; also saved to disk, path in 'screenshotFile'). The screenshot is " +
		"OFF by default because a PNG is large and costs a lot of tokens - only ask for it when the html + appearance text is not " +
		"enough and you genuinely need to VISUALLY compare pixels (e.g. subtle alignment, overlap, spacing or colour nuances " +
		"you cannot judge from the computed values). The screenshot is only captured for the whole form, not for a 'selector'.\n" +
		"Typical loop: edit the form -> call getFormLayout -> read the html + appearance map -> adjust -> call again; add " +
		"'screenshot=true' only when you want to eyeball the actual pixels. It is READ-ONLY (it never changes the form). Pass a " +
		"CSS 'selector' to inspect just one component's subtree. Large output is written to a temp file whose path is returned " +
		"in 'resultFile'. If the form cannot be rendered it returns a message naming the action to take, never a timeout.", type = "object")
	public Object getFormLayout(
		@ToolParam(name = "formName", description = "The form to inspect, e.g. 'orderDetails'", required = true) String formName,
		@ToolParam(name = "selector", description = "Optional CSS selector of the subtree to return (e.g. a component's tag or '#<id>'). When omitted the whole .svy-form is returned. A screenshot is only captured for the whole form, not a selected subtree.", required = false) String selector,
		@ToolParam(name = "screenshot", description = "Whether to ALSO capture a PNG screenshot of the whole rendered form (saved + attached to the response). Default FALSE: the html + appearance text is returned either way and is usually enough. Set true only when you need to VISUALLY compare pixels - a PNG is large and token-expensive. Ignored when 'selector' is set.", type = "boolean", required = false) String screenshot,
		@ToolParam(name = "timeoutSeconds", description = "Max seconds to wait for the form to render before reading. Default 15.", type = "integer", required = false) String timeoutSeconds)
	{
		try
		{
			boolean captureScreenshot = parseBoolean(screenshot, false);
			int timeout = parseTimeout(timeoutSeconds);

			FormLayoutResult result = inspectionService.getFormLayout(formName, selector, captureScreenshot, timeout);
			if (result.error != null)
			{
				return result.error;
			}
			if (result.imageBase64 != null)
			{
				return McpToolResult.withImage(result.json, result.imageBase64, "image/png");
			}
			return result.json;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in getFormLayout", e);
			return "Error: " + e.getMessage();
		}
	}

	private static int parseTimeout(String value)
	{
		if (value == null || value.isBlank())
		{
			return DEFAULT_TIMEOUT_SECONDS;
		}
		try
		{
			int parsed = Integer.parseInt(value.trim());
			return parsed > 0 ? parsed : DEFAULT_TIMEOUT_SECONDS;
		}
		catch (NumberFormatException e)
		{
			return DEFAULT_TIMEOUT_SECONDS;
		}
	}

	/**
	 * Parses an optional boolean {@code @ToolParam} (all params arrive as String per the
	 * codebase convention), defaulting to {@code defaultValue} when blank/absent.
	 */
	private static boolean parseBoolean(String value, boolean defaultValue)
	{
		if (value == null || value.isBlank())
		{
			return defaultValue;
		}
		String v = value.trim();
		return "true".equalsIgnoreCase(v) || "1".equals(v);
	}
}
