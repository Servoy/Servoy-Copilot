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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.IApplication;
import com.servoy.j2db.IDebugClient;
import com.servoy.j2db.IDebugClientHandler;
import com.servoy.j2db.dataprocessing.IDataSet;
import com.servoy.j2db.dataprocessing.IFoundSet;
import com.servoy.j2db.dataprocessing.IRecord;
import com.servoy.j2db.dataprocessing.JSDataSet;
import com.servoy.j2db.debug.DebugUtils;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.ScriptVariable;
import com.servoy.j2db.scripting.GlobalScope;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.Utils;

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
	 * Hard cap on the rendered result value; anything larger is truncated in the result and the full value is spilled to a temp file
	 * whose path is reported alongside.
	 */
	private static final int VALUE_SIZE_CAP = 8000;

	/**
	 * Number of rows shown when a foundset / record / dataset return is described rather than dumped whole.
	 */
	private static final int DESCRIBE_ROW_LIMIT = 10;

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
			// A very common reason a run "times out" is that the code it triggered hit a breakpoint and the debug session suspended it.
			// Detect that and tell the caller explicitly NOT to retry (a retry just re-hits the breakpoint) and to use the debug tools -
			// the return value of THIS call cannot be recovered once suspended, so debugEval the value in the frame instead.
			if (isDebugSessionSuspended())
			{
				return formatResult(clientDescription,
					"The code you triggered hit a BREAKPOINT and the debug session is now suspended - that is why this call did not return, " +
						"not because the client is unresponsive. Do NOT retry executeInRunningClient (it will just re-hit the breakpoint). " +
						"Use debugStatus to see where it stopped, debugGetVariables / debugEval to read values in the frame, and debugStep / " +
						"debugResume to drive it. The return value of this call is not recoverable once suspended - read it with debugEval instead.",
					partialOutput);
			}
			return formatResult(clientDescription,
				"Timed out after " + timeout + " second(s) waiting for the run to complete. The code may still be running in the client, or " +
					"the client may be blocked (e.g. a modal dialog). Check debugStatus in case it suspended at a breakpoint before retrying.",
				partialOutput);
		}

		String outputText;
		synchronized (capturedOutput)
		{
			outputText = capturedOutput.toString();
		}
		StringBuilder valueFileOut = new StringBuilder();
		String resultText = applyValueCap(serializeResult(result[0]), valueFileOut);
		String valueFile = valueFileOut.length() > 0 ? valueFileOut.toString() : null;
		return formatResult(clientDescription, resultText, valueFile, outputText);
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
			// Render Rhino/Servoy values the way the running client would print them (arrays as [1,2,3], objects as JSON-like), and
			// describe a foundset / record / dataset by datasource + first rows rather than dumping it whole, so a caller reads a usable
			// value instead of an opaque Java reference such as "[Ljava.lang.Object;@a6b0d09" that String.valueOf would produce.
			String rendered = renderValue(value);
			if (rendered != null)
			{
				return rendered;
			}
			return String.valueOf(value);
		}
		catch (Exception e)
		{
			return value.getClass().getName() + " (could not be converted to string: " + e.getMessage() + ")";
		}
	}

	/**
	 * Renders an evaluated value: a {@link IFoundSet} / {@link IRecord} / {@link IDataSet} / {@link JSDataSet} is described (datasource,
	 * size, first {@value #DESCRIBE_ROW_LIMIT} rows) rather than dumped whole; anything else is rendered the way the client would print it.
	 */
	private static String renderValue(Object value)
	{
		if (value instanceof IFoundSet foundSet)
		{
			return describeFoundSet(foundSet);
		}
		if (value instanceof IRecord record)
		{
			return describeRecord(record);
		}
		if (value instanceof JSDataSet dataSet)
		{
			return describeDataSet(dataSet.getDataSet());
		}
		if (value instanceof IDataSet dataSet)
		{
			return describeDataSet(dataSet);
		}
		return Utils.getScriptableString(value);
	}

	private static String describeFoundSet(IFoundSet foundSet)
	{
		StringBuilder sb = new StringBuilder();
		int size = foundSet.getSize();
		sb.append("JSFoundSet[dataSource=").append(foundSet.getDataSource()).append(", size=").append(size).append("]");
		int shown = Math.min(size, DESCRIBE_ROW_LIMIT);
		if (shown > 0)
		{
			sb.append("\nfirst ").append(shown).append(" record(s):");
			for (int i = 0; i < shown; i++)
			{
				IRecord record = foundSet.getRecord(i);
				sb.append("\n  [").append(i).append("] ").append(renderRecordRow(record));
			}
		}
		if (size > shown)
		{
			sb.append("\n  ... (").append(size - shown).append(" more)");
		}
		return sb.toString();
	}

	private static String describeRecord(IRecord record)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("JSRecord[dataSource=").append(record.getDataSource()).append("]");
		sb.append("\n  ").append(renderRecordRow(record));
		return sb.toString();
	}

	private static String renderRecordRow(IRecord record)
	{
		if (record == null)
		{
			return "null";
		}
		StringBuilder sb = new StringBuilder("{");
		try
		{
			IFoundSet parent = record.getParentFoundSet();
			String[] names = parent != null ? parent.getDataProviderNames(0) : null;
			if (names == null || names.length == 0)
			{
				names = record.getPK() != null ? new String[0] : null;
			}
			if (names != null && names.length > 0)
			{
				boolean first = true;
				for (String name : names)
				{
					if (!first)
					{
						sb.append(", ");
					}
					Object v = record.getValue(name);
					sb.append(name).append('=').append(Utils.getScriptableString(v));
					first = false;
				}
			}
			else
			{
				Object[] pk = record.getPK();
				sb.append("pk=").append(Utils.getScriptableString(pk));
			}
		}
		catch (Exception e)
		{
			sb.append("<error rendering record: ").append(e.getMessage()).append('>');
		}
		sb.append('}');
		return sb.toString();
	}

	private static String describeDataSet(IDataSet dataSet)
	{
		StringBuilder sb = new StringBuilder();
		int rowCount = dataSet.getRowCount();
		String[] cols = dataSet.getColumnNames();
		sb.append("JSDataSet[rowCount=").append(rowCount).append(", columns=");
		sb.append(cols != null ? String.join(",", cols) : "").append("]");
		int shown = Math.min(rowCount, DESCRIBE_ROW_LIMIT);
		if (shown > 0)
		{
			sb.append("\nfirst ").append(shown).append(" row(s):");
			for (int i = 0; i < shown; i++)
			{
				Object[] row = dataSet.getRow(i);
				sb.append("\n  [").append(i).append("] ").append(Utils.getScriptableString(row));
			}
		}
		if (rowCount > shown)
		{
			sb.append("\n  ... (").append(rowCount - shown).append(" more)");
		}
		return sb.toString();
	}

	/**
	 * Applies the {@value #VALUE_SIZE_CAP}-char cap to a rendered result: when it fits it is returned unchanged and {@code valueFile}
	 * stays null; when it does not, the full text is spilled to a temp file and a truncated form is returned. Package-private for tests.
	 */
	static String applyValueCap(String rendered, StringBuilder valueFileOut)
	{
		if (rendered == null || rendered.length() <= VALUE_SIZE_CAP)
		{
			return rendered;
		}
		String valueFile = spillToTempFile(rendered);
		if (valueFile != null && valueFileOut != null)
		{
			valueFileOut.append(valueFile);
		}
		return rendered.substring(0, VALUE_SIZE_CAP) + "... [truncated, " + rendered.length() + " chars]";
	}

	private static String spillToTempFile(String content)
	{
		try
		{
			Path file = Files.createTempFile("svy-debug-exec-", ".txt");
			Files.write(file, content.getBytes(StandardCharsets.UTF_8));
			return file.toAbsolutePath().toString();
		}
		catch (IOException e)
		{
			ServoyLog.logError("executeInRunningClient: failed to spill over-cap value to a temp file", e);
			return null;
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
			// Render solution-relative Servoy frames (<module>/<scope>.js:<line>) rather than the Rhino/Java trace, and elide the
			// internal wrapper frame we synthesise around the script.
			ScriptStackElement[] stack = null;
			try
			{
				stack = rhino.getScriptStack();
			}
			catch (Exception ignore)
			{
				// fall through to the plain script stack text
			}
			if (stack != null && stack.length > 0)
			{
				for (ScriptStackElement frame : stack)
				{
					if (frame.fileName != null && frame.fileName.startsWith("servoy-debug"))
					{
						continue;
					}
					sb.append('\n');
					frame.renderJavaStyle(sb);
				}
			}
			else
			{
				String scriptStack = rhino.getScriptStackTrace();
				if (scriptStack != null && !scriptStack.isBlank())
				{
					sb.append('\n').append(scriptStack.stripTrailing());
				}
			}
			String hint = bareIdentifierHint(rhinoMessage);
			if (hint != null)
			{
				sb.append('\n').append(hint);
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
	 * Enriches an error message only when it clearly names a bare form-context identifier used outside a form scope. Package-private for
	 * unit testing.
	 */
	static String bareIdentifierHint(String message)
	{
		if (message == null)
		{
			return null;
		}
		String lower = message.toLowerCase();
		boolean referenceLike = lower.contains("is not defined") || lower.contains("not found") || lower.contains("referenceerror");
		if (!referenceLike)
		{
			return null;
		}
		for (String id : new String[] { "foundset", "controller", "currentcontroller", "elements" })
		{
			if (namesIdentifier(message, id))
			{
				return "'foundset' is not addressable without a form context - use forms.<formName>.foundset. " +
					"Likewise controller / currentcontroller / elements require a forms.<formName>.<...> qualifier.";
			}
		}
		return null;
	}

	private static boolean namesIdentifier(String message, String identifier)
	{
		int idx = message.toLowerCase().indexOf(identifier.toLowerCase());
		while (idx >= 0)
		{
			boolean leftOk = idx == 0 || !Character.isLetterOrDigit(message.charAt(idx - 1));
			int end = idx + identifier.length();
			boolean rightOk = end >= message.length() || !Character.isLetterOrDigit(message.charAt(end));
			// Avoid matching a qualified form (forms.x.foundset): a preceding '.' means it is qualified.
			boolean notQualified = idx == 0 || message.charAt(idx - 1) != '.';
			if (leftOk && rightOk && notQualified)
			{
				return true;
			}
			idx = message.toLowerCase().indexOf(identifier.toLowerCase(), idx + 1);
		}
		return false;
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
	 * Formats the two-section markdown result without a spilled-value file. Package-private for unit testing.
	 */
	static String formatResult(String clientDescription, String resultText, String outputText)
	{
		return formatResult(clientDescription, resultText, null, outputText);
	}

	/**
	 * Formats the two-section markdown result. When {@code valueFile} is non-null the full (over-cap) value was spilled to that file and
	 * its path is reported under the truncated result. Package-private for unit testing.
	 */
	static String formatResult(String clientDescription, String resultText, String valueFile, String outputText)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("**servoy-debug: executeInRunningClient**\n\n");
		sb.append("Client: ").append(clientDescription != null ? clientDescription : "unknown").append("\n\n");
		sb.append("Result:\n").append(resultText == null || resultText.isBlank() ? NULL_MARKER : resultText).append("\n\n");
		if (valueFile != null)
		{
			sb.append("Full value written to: ").append(valueFile).append("\n\n");
		}
		sb.append("Console output:\n").append(outputText == null || outputText.isBlank() ? "(no output)" : outputText.stripTrailing());
		return sb.toString();
	}

	/**
	 * Best-effort check of Eclipse's debug model for a currently-suspended thread, used to explain a timeout as "hit a breakpoint" rather
	 * than "client unresponsive". Reads the model only; never throws.
	 */
	private static boolean isDebugSessionSuspended()
	{
		try
		{
			org.eclipse.debug.core.DebugPlugin dp = org.eclipse.debug.core.DebugPlugin.getDefault();
			if (dp == null)
			{
				return false;
			}
			for (org.eclipse.debug.core.ILaunch launch : dp.getLaunchManager().getLaunches())
			{
				for (org.eclipse.debug.core.model.IDebugTarget target : launch.getDebugTargets())
				{
					if (target.isTerminated() || target.isDisconnected())
					{
						continue;
					}
					for (org.eclipse.debug.core.model.IThread thread : target.getThreads())
					{
						if (thread.isSuspended())
						{
							return true;
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			// best-effort: on any failure just fall back to the generic timeout message
		}
		return false;
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
