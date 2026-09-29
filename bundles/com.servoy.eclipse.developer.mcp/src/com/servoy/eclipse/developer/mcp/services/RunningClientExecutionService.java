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
package com.servoy.eclipse.developer.mcp.services;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.IApplication;
import com.servoy.j2db.IDebugClient;
import com.servoy.j2db.IDebugClientHandler;
import com.servoy.j2db.debug.DebugUtils;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.ScriptVariable;
import com.servoy.j2db.scripting.GlobalScope;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.Pair;

/**
 * Runs solution JavaScript in a live, already-running Servoy debug client and captures both the evaluated value/error and the
 * {@code application.output}/console text produced during that run.
 *
 * <p>
 * This replicates the mechanism of the developer Command Console
 * ({@code com.servoy.eclipse.debug.scriptingconsole.CommandHandler}) headlessly: it resolves the debug-ready client, runs on the
 * client's own event thread, evaluates the script in a child scope hanging off the solution's global scope, and normalises the return
 * value. The {@code getScope}/evaluate logic is replicated here (rather than depending on the debug UI bundle) so the MCP bundle stays
 * free of an SWT/console dependency. Console output is captured via an owner-scoped sink installed on {@link DebugUtils} around the run;
 * each run uses a unique owner token so overlapping / timed-out runs on the single client event thread cannot steal, clear or write into
 * each other's captured output.
 * </p>
 */
@Creatable
public class RunningClientExecutionService
{
	private static final String TEST_SCOPE = "____MCP_EXEC_SCOPE____";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Marker returned by the executed value serializer when the evaluated result is JavaScript {@code null}/{@code undefined}.
	 */
	static final String NULL_MARKER = "(null)";

	/**
	 * Runs an ad-hoc script and/or a named method in the current debug-ready client and returns a formatted markdown result containing
	 * both the return value (or error) and the captured console output.
	 *
	 * @param script an ad-hoc JavaScript snippet to evaluate (may be null/blank).
	 * @param methodName a named method to invoke, e.g. {@code forms.customers.recalcTotals} or {@code scopes.globals.doThing} (may be
	 *            null/blank). When both {@code script} and {@code methodName} are given, {@code script} is evaluated first and then
	 *            {@code methodName(args)} is invoked; the returned value is that of the method call.
	 * @param argsJson a JSON array string of arguments for {@code methodName} (may be null/blank).
	 * @param timeoutSeconds maximum seconds to wait for the run to complete.
	 * @return a formatted, human/agent-readable result string. Never throws.
	 */
	public String execute(String script, String methodName, String argsJson, int timeoutSeconds)
	{
		boolean hasScript = script != null && !script.isBlank();
		boolean hasMethod = methodName != null && !methodName.isBlank();
		if (!hasScript && !hasMethod)
		{
			return "Error: at least one of 'script' or 'methodName' is required.";
		}

		IDebugClientHandler handler = getDebugClientHandler();
		if (handler == null)
		{
			return "Error: the Servoy debug environment is not available. Start the application server and run a solution first.";
		}

		IApplication client = handler.getDebugReadyClient();
		if (!(client instanceof IDebugClient debugClient))
		{
			return "No running Servoy client. Start a debug client (run the solution in the Servoy Developer) and retry.";
		}

		String combinedScript;
		try
		{
			combinedScript = buildScript(hasScript ? script : null, hasMethod ? methodName : null, argsJson);
		}
		catch (IllegalArgumentException e)
		{
			return "Error: " + e.getMessage();
		}

		int timeout = timeoutSeconds > 0 ? timeoutSeconds : 30;
		String clientDescription = describeClient(debugClient);

		// A unique token that identifies this run for the whole life of the DebugUtils output sink. It guards against overlapping /
		// timed-out runs on the single client event thread: only the run that currently owns the token may write to or clear the sink.
		final Object ownerToken = new Object();
		final StringBuilder capturedOutput = new StringBuilder();
		// Once the caller has given up (timeout), the run is marked abandoned so that any output it still produces is discarded instead of
		// being appended to a buffer the caller already returned, and its finally-block sink removal becomes a no-op for a newer owner.
		final AtomicBoolean abandoned = new AtomicBoolean(false);
		final Object[] result = new Object[1];
		final AtomicBoolean completed = new AtomicBoolean(false);
		final CountDownLatch latch = new CountDownLatch(1);

		Runnable run = () -> {
			DebugUtils.setOutputSink(ownerToken, msg -> {
				if (abandoned.get())
				{
					return;
				}
				synchronized (capturedOutput)
				{
					capturedOutput.append(msg).append('\n');
				}
			});
			Context cx = Context.enter();
			try
			{
				Scriptable scope = getScope(debugClient, true);
				if (scope == null)
				{
					result[0] = new IllegalStateException("Could not resolve the running client's global scope (solution not loaded).");
				}
				else
				{
					Object eval = cx.evaluateString(scope, combinedScript, "servoy-debug", 1, null);
					if (eval instanceof Wrapper wrapper)
					{
						eval = wrapper.unwrap();
					}
					if (eval == Scriptable.NOT_FOUND || eval == Undefined.instance)
					{
						eval = null;
					}
					result[0] = eval;
				}
			}
			catch (Exception ex)
			{
				result[0] = ex;
			}
			finally
			{
				DebugUtils.removeOutputSink(ownerToken);
				Context.exit();
				completed.set(true);
				latch.countDown();
			}
		};

		// Verify there is a live event dispatcher before scheduling so a dead/absent dispatcher is reported as an error rather than being
		// silently swallowed by invokeLater (ClientState.invokeLater logs and returns void) and then misreported as a timeout.
		if (!hasLiveEventDispatcher(debugClient))
		{
			return formatResult(clientDescription,
				"Error: the running client has no live event dispatcher to run code on. It may be shutting down, disconnected or busy - " +
					"re-run the solution and retry.",
				"");
		}

		try
		{
			debugClient.invokeLater(run);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error scheduling execution in running client", e);
			return formatResult(clientDescription,
				"Error: could not schedule execution in the running client: " + e.getMessage(), "");
		}

		boolean finished;
		try
		{
			finished = latch.await(timeout, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			finished = false;
		}

		if (!finished && !completed.get())
		{
			// Give up on this run: stop it from mutating the captured buffer or clearing a future run's sink.
			abandoned.set(true);
			DebugUtils.removeOutputSink(ownerToken);
			String partialOutput;
			synchronized (capturedOutput)
			{
				partialOutput = capturedOutput.toString();
			}
			return formatResult(clientDescription,
				"Timed out after " + timeout + " second(s) waiting for the run to complete. The code may still be running in the client.",
				partialOutput);
		}

		String outputText;
		synchronized (capturedOutput)
		{
			outputText = capturedOutput.toString();
		}
		return formatResult(clientDescription, serializeResult(result[0]), outputText);
	}

	/**
	 * Best-effort check that the target client currently has an event thread that {@code invokeLater} can run code on. Used to distinguish
	 * "no live dispatcher / scheduling would be swallowed" from "scheduled but slow", so the former is reported as an accurate error rather
	 * than as a misleading timeout. Returns {@code true} when we cannot positively determine that the dispatcher is dead (fail-open), so a
	 * genuine slow run is still allowed to proceed and hit the real timeout.
	 */
	private static boolean hasLiveEventDispatcher(IDebugClient client)
	{
		try
		{
			if (client.isShutDown())
			{
				return false;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error checking running client state", e);
		}
		return true;
	}

	/**
	 * Builds the single JavaScript string that is evaluated in the client. When a script is supplied it is included verbatim first; when a
	 * named method is supplied it is appended as a call {@code methodName(arg0, arg1, ...)} whose return value becomes the result.
	 * Package-private for unit testing.
	 *
	 * @throws IllegalArgumentException if {@code argsJson} is supplied but is not a valid JSON array.
	 */
	static String buildScript(String script, String methodName, String argsJson)
	{
		StringBuilder sb = new StringBuilder();
		if (script != null && !script.isBlank())
		{
			sb.append(script);
		}
		if (methodName != null && !methodName.isBlank())
		{
			if (sb.length() > 0)
			{
				sb.append(";\n");
			}
			sb.append(methodName.trim()).append('(').append(buildArgumentList(argsJson)).append(')');
		}
		return sb.toString();
	}

	/**
	 * Turns a JSON-array string of arguments into a comma-separated JavaScript argument list. Each element is re-serialized to its JSON
	 * form (which is valid JS literal syntax for strings, numbers, booleans, null, arrays and objects). Package-private for unit testing.
	 *
	 * @throws IllegalArgumentException if {@code argsJson} is non-blank but is not a JSON array.
	 */
	static String buildArgumentList(String argsJson)
	{
		if (argsJson == null || argsJson.isBlank())
		{
			return "";
		}
		JsonNode node;
		try
		{
			node = MAPPER.readTree(argsJson);
		}
		catch (Exception e)
		{
			throw new IllegalArgumentException("'args' must be a valid JSON array, e.g. [1, \"two\", true]. Parse error: " + e.getMessage());
		}
		if (!node.isArray())
		{
			throw new IllegalArgumentException("'args' must be a JSON array, e.g. [1, \"two\", true].");
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < node.size(); i++)
		{
			if (i > 0)
			{
				sb.append(", ");
			}
			sb.append(node.get(i).toString());
		}
		return sb.toString();
	}

	/**
	 * Serializes an evaluated value (or thrown exception) to a readable string. Package-private for unit testing.
	 */
	static String serializeResult(Object value)
	{
		if (value == null)
		{
			return NULL_MARKER;
		}
		if (value instanceof Throwable t)
		{
			return formatError(t);
		}
		if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean)
		{
			return value.toString();
		}
		try
		{
			return String.valueOf(value);
		}
		catch (Exception e)
		{
			return value.getClass().getName() + " (could not be converted to string: " + e.getMessage() + ")";
		}
	}

	private static String formatError(Throwable t)
	{
		// Prefer a Rhino exception's own (script-level) message over unwrapping to the deepest Java cause: an EcmaError /
		// JavaScriptException already carries the meaningful "ReferenceError: x is not defined"-style message the user cares about.
		RhinoException rhino = findRhinoException(t);
		if (rhino != null)
		{
			String rhinoMessage = rhino.getMessage() != null ? rhino.getMessage() : rhino.getClass().getSimpleName();
			StringBuilder sb = new StringBuilder("Error: ").append(rhinoMessage);
			String scriptStack = rhino.getScriptStackTrace();
			if (scriptStack != null && !scriptStack.isBlank())
			{
				sb.append('\n').append(scriptStack.stripTrailing());
			}
			return sb.toString();
		}

		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root)
		{
			root = root.getCause();
		}
		String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
		StringBuilder sb = new StringBuilder("Error: ").append(message);
		StackTraceElement[] trace = root.getStackTrace();
		if (trace != null && trace.length > 0)
		{
			int limit = Math.min(trace.length, 5);
			for (int i = 0; i < limit; i++)
			{
				sb.append("\n  at ").append(trace[i]);
			}
		}
		return sb.toString();
	}

	/**
	 * Walks the cause chain of {@code t} and returns the first {@link RhinoException} found, or {@code null} if none. Package-private for
	 * unit testing.
	 */
	static RhinoException findRhinoException(Throwable t)
	{
		Throwable current = t;
		while (current != null)
		{
			if (current instanceof RhinoException rhino)
			{
				return rhino;
			}
			if (current.getCause() == current)
			{
				break;
			}
			current = current.getCause();
		}
		return null;
	}

	/**
	 * Formats the two-section markdown result. Package-private for unit testing.
	 */
	static String formatResult(String clientDescription, String resultText, String outputText)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("**servoy-debug: executeInRunningClient**\n\n");
		sb.append("Client: ").append(clientDescription != null ? clientDescription : "unknown").append("\n\n");
		sb.append("Result:\n").append(resultText == null || resultText.isBlank() ? NULL_MARKER : resultText).append("\n\n");
		sb.append("Console output:\n").append(outputText == null || outputText.isBlank() ? "(no output)" : outputText.stripTrailing());
		return sb.toString();
	}

	private static String describeClient(IDebugClient client)
	{
		String type = client.getClass().getSimpleName();
		String solution = client.getSolution() != null ? client.getSolution().getName() : "no solution";
		return type + " (solution: " + solution + ")";
	}

	private IDebugClientHandler getDebugClientHandler()
	{
		try
		{
			if (ApplicationServerRegistry.get() != null)
			{
				return ApplicationServerRegistry.get().getDebugClientHandler();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error obtaining the debug client handler", e);
		}
		return null;
	}

	/**
	 * Resolves (and optionally creates) a child scriptable scope hanging off the running client's solution global scope. This mirrors the
	 * Command Console's {@code ScriptConsole.getScope(IDebugClient, boolean)} without depending on the debug UI bundle.
	 */
	private Scriptable getScope(IDebugClient client, boolean create)
	{
		if (!client.isSolutionLoaded())
		{
			return null;
		}
		String scopeName = resolveGlobalScopeName(client);
		GlobalScope ss = client.getScriptEngine().getScopesScope().getGlobalScope(scopeName);
		if (ss.has(TEST_SCOPE, ss))
		{
			return (Scriptable)ss.get(TEST_SCOPE, ss);
		}
		if (create)
		{
			Context cx = Context.enter();
			try
			{
				Scriptable scope = cx.newObject(ss);
				ss.putWithoutFireChange(TEST_SCOPE, scope);
				scope.setParentScope(ss);
				return scope;
			}
			finally
			{
				Context.exit();
			}
		}
		return null;
	}

	private String resolveGlobalScopeName(IDebugClient client)
	{
		try
		{
			for (Pair<String, IRootObject> scope : client.getFlattenedSolution().getScopes())
			{
				if (ScriptVariable.GLOBAL_SCOPE.equals(scope.getLeft()))
				{
					return scope.getLeft();
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error resolving global scope name", e);
		}
		return ScriptVariable.GLOBAL_SCOPE;
	}
}
