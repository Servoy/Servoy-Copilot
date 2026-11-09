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
		"IMPORTANT - this is a DESIGN-TIME render: stateless, from /formtemplate/<form>.html, with no running client, no " +
		"login and no real database data. By default it is filled with SAMPLE DATA so you can actually judge the result: " +
		"each dataprovider shows a synthetic value of the right type (text/number/date, a form variable shows its real " +
		"initialiser value) and each foundset-based component (e.g. a data grid) shows 3 rows. The values are SYNTHETIC but " +
		"DETERMINISTIC - the same form always renders the same values - so you can render, change something, render again and " +
		"diff the two with confidence. Do NOT treat the values as the solution's real data, and do not 'fix' a form because a " +
		"sample value looks arbitrary; use the running-client tools when you need to see real records. Set sampleData=false to " +
		"get the bare component chrome with no values at all.\n" +
		"The two heavy outputs are ALWAYS written to TEMP FILES and never returned inline, so the call itself is cheap no " +
		"matter how large the form is - the response is just a small envelope of metadata plus two ABSOLUTE file paths you " +
		"read as needed:\n" +
		"  1. 'htmlFile' - a pretty-printed HTML file (one element per indented line) with the full rendered outerHTML of the " +
		"requested subtree (the whole form, or the element matched by 'selector'): the real component tags (e.g. " +
		"bootstrapcomponents-textbox), their id / data-svy-name identity attributes, applied classes and exact nested " +
		"structure. Read this for the rendered structure and identity;\n" +
		"  2. 'appearanceFile' - a pretty-printed JSON file (one node per line) holding a map keyed by element (id or CSS " +
		"path) of the RESOLVED visual styles (color, background, border, font, display, position, visibility, outline) and " +
		"on-screen bounding box (x/y/w/h), plus a 'disabled' and 'readonly' boolean per element. These are the browser's " +
		"COMPUTED values after the solution stylesheet + theme have been applied (the render waits for the solution " +
		"stylesheets to load first, so the values are the solution's, not framework defaults), which raw HTML alone cannot " +
		"tell you - use them to know the actual colour/position of something and to confirm a change landed.\n" +
		"Both files are greppable and line-addressable: grep for an element ref, id, class or style and read a small line " +
		"range around the match instead of the whole file. The envelope also carries 'form', 'selector', the root 'viewport' " +
		"box, the real 'window' size and an 'elementCount'. Pass each file path UNCHANGED to a file-reading tool - do not make " +
		"it project-relative (no ../).\n" +
		"OPTIONALLY, set 'screenshot=true' to ALSO get a PNG screenshot of the rendered form attached to the response (a " +
		"vision-capable model sees the actual pixels directly; also saved to disk, path in 'screenshotFile'). The screenshot is " +
		"OFF by default because a PNG is large and costs a lot of tokens - only ask for it when the html + appearance text is not " +
		"enough and you genuinely need to VISUALLY compare pixels (e.g. subtle alignment, overlap, spacing or colour nuances " +
		"you cannot judge from the computed values). The screenshot is only captured for the whole form, not for a 'selector'.\n" +
		"RESPONSIVE CHECKS: by default the form renders at a fixed 1280x1024 viewport. Pass 'viewportWidth' (and optionally " +
		"'viewportHeight') to render at another size, so the html, the computed appearance/boxes and the screenshot all reflect " +
		"that width - use it to verify media-query / breakpoint behaviour, e.g. call it at 1281, 768 and 375 and compare. The " +
		"result echoes the browser's actual viewport in 'window' ({width,height}); check it matches what you asked for. Accepted " +
		"range is 200-4000 px.\n" +
		"PSEUDO-STATES (focus / hover / active): pass 'targetSelector' + 'action' to put ONE element into a CSS interaction " +
		"state before the read, so its computed 'appearance' (and the screenshot) show that state - this is how you verify a " +
		"focus ring, a hover colour, an active style, etc. at design time. 'focus' is a REAL :focus/:focus-visible " +
		"(element.focus()); 'hover' and 'active' are APPROXIMATE (a headless browser cannot force a true :hover, so the matching " +
		":hover/:active rule's declarations are copied onto the target) - the result carries an 'interaction' object with " +
		"'approximate' (false for focus, true for hover/active), 'matched', and for hover/active a 'note' and 'matchedRules'. " +
		"A JS-built effect that needs an event handler to run (e.g. a tooltip created on mouseover) is NOT captured - this is " +
		"design-time CSS only.\n" +
		"Typical loop: edit the form -> call getFormLayout -> read 'htmlFile' (and 'appearanceFile' when you need the computed " +
		"colours/box) -> adjust -> call again; add 'screenshot=true' only when you want to eyeball the actual pixels. It is " +
		"READ-ONLY (it never changes the form). Pass a CSS 'selector' to inspect just one component's subtree. If the form " +
		"cannot be rendered it returns a message naming the action to take, never a timeout.", type = "object")
	public Object getFormLayout(
		@ToolParam(name = "formName", description = "The form to inspect, e.g. 'orderDetails'", required = true) String formName,
		@ToolParam(name = "selector", description = "Optional CSS selector of the subtree to return (e.g. a component's tag or '#<id>'). When omitted the whole .svy-form is returned. A screenshot is only captured for the whole form, not a selected subtree.", required = false) String selector,
		@ToolParam(name = "screenshot", description = "Whether to ALSO capture a PNG screenshot of the whole rendered form (saved + attached to the response). Default FALSE: the html + appearance text is returned either way and is usually enough. Set true only when you need to VISUALLY compare pixels - a PNG is large and token-expensive. Ignored when 'selector' is set.", type = "boolean", required = false) String screenshot,
		@ToolParam(name = "timeoutSeconds", description = "Max seconds to wait for the form to render before reading. Default 15.", type = "integer", required = false) String timeoutSeconds,
		@ToolParam(name = "sampleData", description = "Whether to fill the render with synthetic, deterministic sample data - a value per dataprovider and 3 rows per foundset - so fields and grids show content instead of empty chrome. Default TRUE, which is almost always what you want. Pass false only when you specifically want to inspect the empty component chrome.", type = "boolean", required = false) String sampleData,
		@ToolParam(name = "viewportWidth", description = "Optional viewport width in px (200-4000) to render the form at, for verifying responsive / media-query layouts (e.g. 1281, 768, 375). Default 1280. The result's 'window' field echoes the viewport actually used.", type = "integer", required = false) String viewportWidth,
		@ToolParam(name = "viewportHeight", description = "Optional viewport height in px (200-4000). Default 1024.", type = "integer", required = false) String viewportHeight,
		@ToolParam(name = "targetSelector", description = "Optional CSS selector of the ONE element to put into a pseudo-state (see 'action') before reading, so the returned 'appearance' and screenshot reflect that state. Independent of 'selector' (which only scopes what subtree is returned): you can scope the read to a container and still target one element inside it. Resolved with document.querySelector (first match). Requires 'action'.", required = false) String targetSelector,
		@ToolParam(name = "action", description = "Optional interaction to apply to 'targetSelector' before reading: 'focus' (REAL :focus/:focus-visible via element.focus()), 'hover' or 'active' (APPROXIMATE - the matching :hover/:active rule's declarations are copied onto the target, since a headless browser cannot force a true :hover; the result's 'interaction.approximate' is true and carries a note). Omit for the normal resting render. Use this to verify a focus ring, a hover colour, etc. Requires 'targetSelector'.", required = false) String action)
	{
		try
		{
			boolean captureScreenshot = parseBoolean(screenshot, false);
			int timeout = parseTimeout(timeoutSeconds);
			boolean withSampleData = parseBoolean(sampleData, true);
			Integer width = parseViewport(viewportWidth);
			Integer height = parseViewport(viewportHeight);
			if (width != null && width.intValue() == Integer.MIN_VALUE)
			{
				return "Error: viewportWidth must be an integer number of pixels, got '" + viewportWidth + "'.";
			}
			if (height != null && height.intValue() == Integer.MIN_VALUE)
			{
				return "Error: viewportHeight must be an integer number of pixels, got '" + viewportHeight + "'.";
			}

			String normalizedTarget = (targetSelector != null && !targetSelector.isBlank()) ? targetSelector.trim() : null;
			String normalizedAction = (action != null && !action.isBlank()) ? action.trim().toLowerCase() : null;
			String actionError = validatePseudoState(normalizedTarget, normalizedAction);
			if (actionError != null)
			{
				return actionError;
			}

			FormLayoutResult result = inspectionService.getFormLayout(formName, selector, captureScreenshot, timeout, withSampleData, width,
				height, normalizedTarget, normalizedAction);
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

	/**
	 * Backward-compatible overload without the SVY-21614 pseudo-state arguments: the render with no
	 * {@code targetSelector}/{@code action}. Kept so existing callers (and tests) that pass only the
	 * original seven arguments keep compiling and behave exactly as before.
	 */
	public Object getFormLayout(String formName, String selector, String screenshot, String timeoutSeconds, String sampleData,
		String viewportWidth, String viewportHeight)
	{
		return getFormLayout(formName, selector, screenshot, timeoutSeconds, sampleData, viewportWidth, viewportHeight, null, null);
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
	 * Validates the {@code targetSelector} / {@code action} pair of the pseudo-state feature (SVY-21614).
	 * Returns a named error message for a malformed request (an unknown action, or one of the two given
	 * without the other), or {@code null} when the pair is valid - including both absent, which is the
	 * normal resting render. An unmatched selector is NOT validated here: that is an inspection outcome
	 * (reported as {@code interaction.matched:false}), not a malformed request. The action is expected
	 * already lower-cased/trimmed.
	 */
	public static String validatePseudoState(String targetSelector, String action)
	{
		if (action == null && targetSelector == null)
		{
			return null;
		}
		if (action == null)
		{
			return "Error: targetSelector was given without an action (focus, hover or active).";
		}
		if (!FormLayoutInspectionService.ACTION_FOCUS.equals(action) &&
			!FormLayoutInspectionService.ACTION_HOVER.equals(action) &&
			!FormLayoutInspectionService.ACTION_ACTIVE.equals(action))
		{
			return "Error: action must be one of focus, hover, active (got '" + action + "').";
		}
		if (targetSelector == null)
		{
			return "Error: action '" + action + "' requires a targetSelector.";
		}
		return null;
	}

	/**
	 * Parses an optional viewport dimension. Returns {@code null} when absent/blank (use the default) and
	 * {@link Integer#MIN_VALUE} as a sentinel when the value is not an integer (so the caller can report it
	 * instead of silently falling back to the default). Range checking is done by the service.
	 */
	private static Integer parseViewport(String value)
	{
		if (value == null || value.isBlank())
		{
			return null;
		}
		try
		{
			return Integer.valueOf(Integer.parseInt(value.trim()));
		}
		catch (NumberFormatException e)
		{
			return Integer.valueOf(Integer.MIN_VALUE);
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
