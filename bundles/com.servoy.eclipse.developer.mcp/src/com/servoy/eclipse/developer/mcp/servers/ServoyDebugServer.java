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

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.RunningClientExecutionService;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * MCP server for runtime debugging: executing solution JavaScript in the currently running Servoy debug client.
 *
 * <p>
 * This is the runtime counterpart to the static tools of {@code servoy-dev} / {@code servoy-test}: instead of creating, editing or
 * JSUnit-testing code, it triggers and inspects real runtime behaviour in a live client, returning both the evaluated value/error and the
 * {@code application.output}/console text produced during that run.
 * </p>
 */
@Creatable
@McpServer(name = "servoy-debug")
public class ServoyDebugServer
{
	private final RunningClientExecutionService executionService = new RunningClientExecutionService();

	public ServoyDebugServer()
	{
	}

	@Tool(name = "ping", description = "Returns a simple pong response to verify the servoy-debug endpoint is alive.", type = "object")
	public String ping()
	{
		return "pong";
	}

	@Tool(name = "executeInRunningClient", description = "Runs solution JavaScript in the CURRENTLY RUNNING Servoy debug client and returns BOTH the evaluated result (or thrown error) AND the application.output/console text produced during that run. " +
		"Use this to trigger and inspect real runtime behaviour: run a form or global method, evaluate an ad-hoc snippet, or add application.output(...) traces to code and read them back. " +
		"Provide 'script' (an ad-hoc snippet like \"forms.myForm.myMethod(1,2); application.output('done')\") and/or 'methodName' (a named method like 'forms.customers.recalcTotals' or 'scopes.globals.doThing' with optional JSON 'args'). At least one is required. " +
		"When both are given, 'script' is evaluated first and then methodName(args) is invoked, and the method's return value is reported. " +
		"Note: 'methodName' is evaluated as JavaScript (it is turned into a methodName(args) call), so a 'methodName' that contains ';' or other statements is by-design treated as script. " +
		"A returned JSFoundSet / JSRecord / JSDataSet is described (datasource, size and the first rows) rather than dumped whole, and a very large value is truncated with the full text written to a temp file whose path is reported. " +
		"A thrown JS error is returned as a readable message with a solution-relative script stack, not a tool failure; a bare 'foundset'/'controller'/'elements' used outside a form is answered with the qualified rewrite to use. " +
		"Requires a running debug client: if none is running, this returns a message asking you to run the solution first (it will NOT start a client for you).", type = "object")
	public String executeInRunningClient(
		@ToolParam(name = "script", description = "An ad-hoc JavaScript snippet to evaluate in the running client's global scope, exactly like the Servoy Command Console (e.g. \"application.output('hello'); forms.myForm.refresh()\"). Optional if 'methodName' is provided.", required = false) String script,
		@ToolParam(name = "methodName", description = "A named method to invoke, e.g. 'forms.customers.recalcTotals' or 'scopes.globals.doThing'. Optional if 'script' is provided. If both are given, it runs after 'script'.", required = false) String methodName,
		@ToolParam(name = "args", description = "JSON array of arguments for 'methodName', e.g. [1, \"two\", true]. Ignored when 'methodName' is not provided.", required = false) String args,
		@ToolParam(name = "timeoutSeconds", description = "Maximum seconds to wait for the run to complete. Use 30 for a quick call, more for long-running code.", type = "integer", required = false) int timeoutSeconds)
	{
		try
		{
			return executionService.execute(script, methodName, args, timeoutSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in executeInRunningClient tool", e);
			return "Error: " + e.getMessage();
		}
	}
}
