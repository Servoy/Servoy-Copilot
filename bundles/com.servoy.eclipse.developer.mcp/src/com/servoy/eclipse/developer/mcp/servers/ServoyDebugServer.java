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
import com.servoy.eclipse.developer.mcp.services.DebugInspectionService;
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
	private final DebugInspectionService inspectionService = new DebugInspectionService();

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
		"The reported result is a human-readable string representation of what the script returned, for you to read and interpret - it is NOT valid JSON and is not meant to be JSON.parse'd (e.g. a number may read as 42.0 and an object as {a:1,b:[2,3]} without quotes). " +
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

	@Tool(name = "debugStatus", description = "Reports whether the Servoy debug session is currently SUSPENDED at a breakpoint, and where. " +
		"This reads Eclipse's own debug model of the session the IDE already owns; it does not open a second debugger connection and never blocks the client. " +
		"Use it after setting a breakpoint and triggering the code (e.g. via executeInRunningClient) to check whether execution has stopped so you can inspect variables with debugGetVariables. " +
		"Requires the solution to be launched in Debug mode; if nothing is suspended it returns an actionable message.", type = "object")
	public String debugStatus()
	{
		try
		{
			return inspectionService.status();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugStatus tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugGetVariables", description = "Reads the call stack and the variables of a SUSPENDED Servoy debug session through Eclipse's debug model. " +
		"The nested variable tree is expanded lazily to 'maxDepth' levels: an object that still has children beyond the cap is marked expandable, so you can re-call with a higher 'maxDepth' to drill into it rather than dumping a whole object graph. " +
		"Only meaningful when debugStatus reports a suspended thread; otherwise it returns the same actionable status message.", type = "object")
	public String debugGetVariables(
		@ToolParam(name = "frameIndex", description = "0-based stack frame to inspect (0 = the current/top frame). Defaults to 0.", type = "integer", required = false) int frameIndex,
		@ToolParam(name = "maxDepth", description = "How many levels of the nested variable tree to expand (bounded). Defaults to 2. Raise it to drill into a node marked 'expandable'.", type = "integer", required = false) int maxDepth)
	{
		try
		{
			return inspectionService.getVariables(frameIndex, maxDepth);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugGetVariables tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugResume", description = "Resumes a SUSPENDED Servoy debug session (equivalent to the Debug perspective's Resume / F8), letting execution continue until the next breakpoint or completion. " +
		"This also releases an executeInRunningClient call that timed out because the code it triggered hit a breakpoint. Reads/drives Eclipse's own debug model; no second debugger connection. " +
		"Returns an actionable message when nothing is suspended.", type = "object")
	public String debugResume()
	{
		try
		{
			return inspectionService.resume();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugResume tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugStep", description = "Steps a SUSPENDED Servoy debug session by one statement and lets it suspend again at the new location. " +
		"'mode' is 'into' (step into a called function), 'over' (execute the current line without descending) or 'out' (run to the end of the current function and stop in the caller). " +
		"After stepping, call debugStatus / debugGetVariables to inspect the new location. Drives Eclipse's own debug model; returns an actionable message when nothing is suspended.", type = "object")
	public String debugStep(
		@ToolParam(name = "mode", description = "The step kind: 'into', 'over' (default) or 'out'.", required = false) String mode)
	{
		try
		{
			return inspectionService.step(mode);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugStep tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugEval", description = "Evaluates a JavaScript expression in the context of the SUSPENDED frame and returns its value. " +
		"This is how you get a value while stopped at a breakpoint: the return of a triggering executeInRunningClient call is lost once it times out at the breakpoint, so instead ask the frame directly - " +
		"evaluate the method's return expression (e.g. 'Math.ceil(diff / msPerDay)'), any in-scope variable, or an arbitrary expression using the frame's locals. Drives Eclipse's own debug model; " +
		"returns an actionable message when nothing is suspended.", type = "object")
	public String debugEval(
		@ToolParam(name = "expression", description = "The JavaScript expression to evaluate in the suspended frame (e.g. 'diff', 'Math.ceil(diff / msPerDay)').", required = true) String expression,
		@ToolParam(name = "frameIndex", description = "0-based stack frame to evaluate in (0 = the current/top frame). Defaults to 0.", type = "integer", required = false) int frameIndex)
	{
		try
		{
			return inspectionService.eval(expression, frameIndex);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugEval tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugSetVariable", description = "Changes the value of a named variable in the SUSPENDED frame (equivalent to editing a value in the Debug perspective's Variables view). " +
		"'value' is a JavaScript source expression the debug engine accepts (e.g. 5, \"text\", true). Only meaningful when a thread is suspended; returns an actionable message otherwise.", type = "object")
	public String debugSetVariable(
		@ToolParam(name = "variableName", description = "The name of the variable in the frame to change.", required = true) String variableName,
		@ToolParam(name = "value", description = "The new value as a JS source expression (e.g. 5, \"text\", true).", required = true) String value,
		@ToolParam(name = "frameIndex", description = "0-based stack frame (0 = the current/top frame). Defaults to 0.", type = "integer", required = false) int frameIndex)
	{
		try
		{
			return inspectionService.setVariable(variableName, value, frameIndex);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugSetVariable tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugLaunchClient", description = "Ensures a debuggable Servoy NG client is running, launching one if needed - the same action as the IDE's 'Launch NG Client' toolbar button, so the DBGP debugger is attached and the client can be broken/stepped/inspected. " +
		"Use this FIRST for 'execute and debug <method>' when nothing is running yet: launch the client, then debugBreakOnMethod, then executeInRunningClient. " +
		"If a debug client is already running it does nothing. Opens the active solution in the configured browser; requires an active solution.", type = "object")
	public String debugLaunchClient(
		@ToolParam(name = "timeoutSeconds", description = "How long to wait for the launched client to become debug-ready. Default 60.", type = "integer", required = false) int timeoutSeconds)
	{
		try
		{
			return inspectionService.launchDebugClient(timeoutSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugLaunchClient tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugBreakOnMethod", description = "Sets a breakpoint on a Servoy method addressed the way you script it - 'forms.<form>.<method>' or 'scopes.<scope>.<function>' - by resolving it to its source file and first body line. " +
		"This is the recommended way to 'run and debug a method': call this with the address the user gave (e.g. 'forms.main.daysLeftInMonth'), then trigger it with executeInRunningClient(methodName='forms.main.daysLeftInMonth') - it will suspend at the first line so you can inspect with debugStatus / debugGetVariables / debugEval and drive with debugStep / debugResume. " +
		"Resolution is scoped to the ACTIVE solution and its modules (a same-named form in an unrelated, non-running solution is NOT matched), so you do not need to know the file path. " +
		"IMPORTANT: call debugClearBreakpoint afterwards so the breakpoint does not keep trapping the user's later runs. Requires the solution to be launched in Debug mode.", type = "object")
	public String debugBreakOnMethod(
		@ToolParam(name = "methodAddress", description = "The Servoy method address, e.g. 'forms.main.daysLeftInMonth' or 'scopes.rating.computeUValue'.", required = true) String methodAddress)
	{
		try
		{
			return inspectionService.breakOnMethod(methodAddress);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugBreakOnMethod tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugSetBreakpoint", description = "Sets a Servoy JavaScript line breakpoint at a file+line so the running debug client suspends there when that line runs. " +
		"This is how you 'run and debug a method': read the method's source, find its first body line, set a breakpoint there with this tool, then trigger the method (e.g. executeInRunningClient) - it will suspend at the breakpoint and you can inspect with debugStatus / debugGetVariables / debugEval and drive with debugStep / debugResume. " +
		"Idempotent (no duplicate at the same file+line). IMPORTANT: call debugClearBreakpoint afterwards so an agent-set breakpoint does not keep trapping the user's later normal runs. Requires the solution to be launched in Debug mode.", type = "object")
	public String debugSetBreakpoint(
		@ToolParam(name = "filePath", description = "Workspace path of the .js file, e.g. '/mySmp/forms/main.js'.", required = true) String filePath,
		@ToolParam(name = "line", description = "1-based line number to break on (the first executable line of the target function).", type = "integer", required = true) int line)
	{
		try
		{
			return inspectionService.setBreakpoint(filePath, line);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugSetBreakpoint tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugClearBreakpoint", description = "Removes a Servoy JavaScript line breakpoint set with debugSetBreakpoint. " +
		"Pass a line to remove just that one, or omit/pass 0 to remove every JS breakpoint in the file. Always clear breakpoints an agent set once the debug flow is done, so they do not trap the user's later runs.", type = "object")
	public String debugClearBreakpoint(
		@ToolParam(name = "filePath", description = "Workspace path of the .js file, e.g. '/mySmp/forms/main.js'.", required = true) String filePath,
		@ToolParam(name = "line", description = "1-based line to clear; 0 or omitted clears all JS breakpoints in the file.", type = "integer", required = false) int line)
	{
		try
		{
			return inspectionService.clearBreakpoint(filePath, line);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugClearBreakpoint tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "debugEnd", description = "Ends a debug inspection cleanly in ONE call: resumes every suspended thread (so a client parked at a breakpoint is no longer frozen) and removes every Servoy JavaScript breakpoint (so none keeps trapping later runs). " +
		"ALWAYS call this when you are done debugging - leaving a thread suspended freezes the client and a leftover breakpoint traps the user's next run. Prefer it over remembering a separate debugResume + debugClearBreakpoint. " +
		"The debug session itself (the client in debug mode) is left running; the user stops that from the IDE's Debug view.", type = "object")
	public String debugEnd()
	{
		try
		{
			return inspectionService.end();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in debugEnd tool", e);
			return "Error: " + e.getMessage();
		}
	}
}
