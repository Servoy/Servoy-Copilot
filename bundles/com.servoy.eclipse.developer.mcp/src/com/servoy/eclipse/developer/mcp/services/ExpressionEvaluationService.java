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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.TextConsole;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.IDebugClient;
import com.servoy.j2db.dataprocessing.IDataSet;
import com.servoy.j2db.dataprocessing.IFoundSet;
import com.servoy.j2db.dataprocessing.IRecord;
import com.servoy.j2db.dataprocessing.JSDataSet;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.ScriptVariable;
import com.servoy.j2db.scripting.GlobalScope;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.Utils;

/**
 * Headless reuse of the Servoy Command Console evaluation engine. Evaluates a JavaScript expression in the developer's
 * already-running debug client for the active solution and returns its value, console output and any error.
 * <p>
 * This does not reference any SWT/UI class from {@code com.servoy.eclipse.debug} (in particular not
 * {@code ScriptConsole}). The ~15 lines of scope-acquisition logic from {@code ScriptConsole.getScope/getGlobalScope}
 * are lifted into {@link #getScope(IDebugClient, boolean)} / {@link #resolveGlobalScopePair()} so no console UI class is
 * ever loaded on the headless MCP path.
 */
public class ExpressionEvaluationService
{
	/** Persistent nested scope object created under the active solution's {@code GlobalScope}, so multi-line evaluations can share state. */
	private static final String TEST_SCOPE = "____TEST_SCOPE____";

	/** Default timeout when the caller does not supply one, in seconds. */
	public static final int DEFAULT_TIMEOUT_SECONDS = 15;

	/** Hard cap on the inline {@code value}; anything larger is truncated inline and the full value is spilled to a temp file. */
	private static final int VALUE_SIZE_CAP = 8000;

	/** Number of rows shown when a foundset / dataset return is described rather than dumped. */
	private static final int DESCRIBE_ROW_LIMIT = 10;

	/**
	 * Small result holder read back on the tool thread after the {@code invokeAndWait} hop returns.
	 */
	public static final class EvaluationResult
	{
		public final String value;
		public final String valueFile;
		public final String console;
		public final String error;

		public EvaluationResult(String value, String valueFile, String console, String error)
		{
			this.value = value;
			this.valueFile = valueFile;
			this.console = console;
			this.error = error;
		}

		static EvaluationResult ofError(String error)
		{
			return new EvaluationResult(null, null, "", error);
		}
	}

	/**
	 * Evaluates {@code expression} in the developer's running debug client for the resolved target solution.
	 *
	 * @param expression the JavaScript expression / multi-statement script; the value of the last expression is returned
	 * @param solutionName optional solution to target; when blank the active project's solution is used
	 * @param timeoutSeconds bounded wait for the event-thread hop before returning a timeout error
	 * @return an {@link EvaluationResult}; never {@code null}
	 */
	public EvaluationResult evaluate(String expression, String solutionName, int timeoutSeconds)
	{
		if (expression == null || expression.isBlank())
		{
			return EvaluationResult.ofError("expression is required");
		}

		String resolvedTarget = resolveTargetSolution(solutionName);

		List<IDebugClient> clients = getActiveDebugClients();
		IDebugClient client = selectClient(clients, resolvedTarget);
		if (client == null)
		{
			return EvaluationResult.ofError(buildNoClientMessage(resolvedTarget, clients));
		}

		// Marker/diff console capture (spec 3.4): record the console end position before, read the delta after.
		int consoleMarker = getConsoleLength();

		final AtomicReference<Object> valueRef = new AtomicReference<>();
		final AtomicReference<Exception> scriptErrorRef = new AtomicReference<>();
		final AtomicReference<Thread> eventThreadRef = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);

		Runnable runnable = () -> {
			eventThreadRef.set(Thread.currentThread());
			Context cx = Context.enter();
			try
			{
				Scriptable scope = getScope(client, true);
				if (scope == null)
				{
					scriptErrorRef.set(new IllegalStateException(
						"Could not obtain a script scope; the client's solution is not loaded."));
					return;
				}
				Object eval = cx.evaluateString(scope, expression, "internal_anon", 1, null);
				if (eval instanceof Wrapper)
				{
					eval = ((Wrapper)eval).unwrap();
				}
				if (eval == Scriptable.NOT_FOUND || eval == Undefined.instance)
				{
					eval = null;
				}
				valueRef.set(eval);
			}
			catch (Exception ex)
			{
				scriptErrorRef.set(ex);
			}
			finally
			{
				Context.exit();
				done.countDown();
			}
		};

		try
		{
			// invokeAndWait blocks; drive it from a worker thread so we can enforce a bounded wait (spec 3.3).
			Thread worker = new Thread(() -> {
				try
				{
					client.invokeAndWait(runnable);
				}
				catch (Exception ex)
				{
					scriptErrorRef.set(ex);
					done.countDown();
				}
			}, "svy-evaluate");
			worker.setDaemon(true);
			worker.start();

			boolean finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished)
			{
				String consoleDelta = readConsoleDelta(consoleMarker);
				// We do NOT stop the expression; it may still be running on the Servoy event thread.
				// TODO (SVY-21473): once ServoyContextFactory.getScriptStackForThread is backported to the 26.03 LTS,
				// attach the event thread's script stack to this timeout message. The event-thread reference is
				// captured in eventThreadRef for exactly that future one-liner.
				Thread eventThread = eventThreadRef.get();
				String where = eventThread != null ? " (event thread: " + eventThread.getName() + ")" : "";
				return new EvaluationResult(null, null, consoleDelta,
					"Evaluation timed out after " + timeoutSeconds + "s; the expression is still running on the debug client's event thread" + where + ".");
			}
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
			return EvaluationResult.ofError("Evaluation was interrupted: " + ie.getMessage());
		}

		String consoleDelta = readConsoleDelta(consoleMarker);

		Exception scriptError = scriptErrorRef.get();
		if (scriptError != null)
		{
			return new EvaluationResult(null, null, consoleDelta, formatScriptError(scriptError));
		}

		return describeValue(valueRef.get(), consoleDelta);
	}

	// -------------------------------------------------------------------------
	// Client selection
	// -------------------------------------------------------------------------

	private List<IDebugClient> getActiveDebugClients()
	{
		try
		{
			if (ApplicationServerRegistry.get() != null && ApplicationServerRegistry.get().getDebugClientHandler() != null)
			{
				return ApplicationServerRegistry.get().getDebugClientHandler().getActiveDebugClients();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("evaluate: failed to obtain active debug clients", e);
		}
		return List.of();
	}

	private IDebugClient selectClient(List<IDebugClient> clients, String resolvedTarget)
	{
		if (clients == null)
		{
			return null;
		}
		for (IDebugClient c : clients)
		{
			try
			{
				if (!c.isSolutionLoaded())
				{
					continue;
				}
				String name = c.getSolutionName();
				if (resolvedTarget == null || resolvedTarget.equals(name))
				{
					return c;
				}
			}
			catch (Exception e)
			{
				// ignore an individual client that fails to answer and try the next
			}
		}
		return null;
	}

	private String buildNoClientMessage(String resolvedTarget, List<IDebugClient> clients)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("No running debug client for solution '").append(resolvedTarget)
			.append("'. Start it in Servoy Developer (Run/Debug the solution) and try again.");
		if (clients != null && !clients.isEmpty())
		{
			sb.append(" Running clients: ");
			boolean first = true;
			for (IDebugClient c : clients)
			{
				String name;
				try
				{
					name = c.getSolutionName();
				}
				catch (Exception e)
				{
					name = "<unknown>";
				}
				if (name == null)
				{
					continue;
				}
				if (!first)
				{
					sb.append(", ");
				}
				sb.append(name);
				first = false;
			}
			sb.append('.');
		}
		return sb.toString();
	}

	private String resolveTargetSolution(String solutionName)
	{
		if (solutionName != null && !solutionName.isBlank())
		{
			return solutionName.trim();
		}
		try
		{
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			if (activeProject != null)
			{
				return activeProject.getProject().getName();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("evaluate: failed to resolve active solution", e);
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Scope acquisition (lifted from ScriptConsole.getScope/getGlobalScope, headless)
	// -------------------------------------------------------------------------

	private Scriptable getScope(IDebugClient client, boolean create)
	{
		if (!client.isSolutionLoaded())
		{
			return null;
		}
		Pair<String, IRootObject> scopePair = resolveGlobalScopePair();
		String scopeName = scopePair != null ? scopePair.getLeft() : ScriptVariable.GLOBAL_SCOPE;
		GlobalScope ss = client.getScriptEngine().getScopesScope().getGlobalScope(scopeName);
		if (ss == null)
		{
			return null;
		}
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

	/**
	 * Reproduces {@code ScriptConsole.getGlobalScope()} headlessly: pick the globals scope of the active solution (or any
	 * global scope), so that top-level {@code scopes} / {@code forms} / {@code globals} / {@code databaseManager} resolve.
	 */
	private Pair<String, IRootObject> resolveGlobalScopePair()
	{
		try
		{
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject servoyProject = model.getActiveProject();
			if (servoyProject != null)
			{
				Pair<String, IRootObject> selectedScope = null;
				FlattenedSolution flattenedSolution = servoyProject.getEditingFlattenedSolution();
				for (Pair<String, IRootObject> currentScope : flattenedSolution.getScopes())
				{
					if (selectedScope == null)
					{
						selectedScope = currentScope;
					}
					if (ScriptVariable.GLOBAL_SCOPE.equals(currentScope.getLeft()))
					{
						if (servoyProject.getSolution().getName().equals(currentScope.getRight().getName()))
						{
							return currentScope;
						}
						selectedScope = currentScope;
					}
				}
				return selectedScope;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("evaluate: failed to resolve global scope", e);
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Console diff capture (spec 3.4)
	// -------------------------------------------------------------------------

	private TextConsole findServoyConsole()
	{
		try
		{
			IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
			IConsole[] consoles = manager.getConsoles();
			TextConsole last = null;
			for (IConsole c : consoles)
			{
				if (c instanceof TextConsole tc)
				{
					last = tc;
				}
			}
			return last;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private int getConsoleLength()
	{
		TextConsole console = findServoyConsole();
		if (console == null)
		{
			return -1;
		}
		try
		{
			return console.getDocument().getLength();
		}
		catch (Exception e)
		{
			return -1;
		}
	}

	private String readConsoleDelta(int marker)
	{
		if (marker < 0)
		{
			return "";
		}
		TextConsole console = findServoyConsole();
		if (console == null)
		{
			return "";
		}
		try
		{
			String full = console.getDocument().get();
			if (full.length() <= marker)
			{
				return "";
			}
			return full.substring(marker);
		}
		catch (Exception e)
		{
			return "";
		}
	}

	// -------------------------------------------------------------------------
	// Value description + size cap + temp-file spill (spec 3.6)
	// -------------------------------------------------------------------------

	private EvaluationResult describeValue(Object value, String consoleDelta)
	{
		if (value == null)
		{
			return new EvaluationResult(null, null, consoleDelta, null);
		}

		String rendered = renderValue(value);
		if (rendered.length() <= VALUE_SIZE_CAP)
		{
			return new EvaluationResult(rendered, null, consoleDelta, null);
		}

		String valueFile = spillToTempFile(rendered);
		String truncated = rendered.substring(0, VALUE_SIZE_CAP) + "... [truncated, " + rendered.length() + " chars]";
		return new EvaluationResult(truncated, valueFile, consoleDelta, null);
	}

	private String renderValue(Object value)
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

	private String describeFoundSet(IFoundSet foundSet)
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

	private String describeRecord(IRecord record)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("JSRecord[dataSource=").append(record.getDataSource()).append("]");
		sb.append("\n  ").append(renderRecordRow(record));
		return sb.toString();
	}

	private String renderRecordRow(IRecord record)
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

	private String describeDataSet(IDataSet dataSet)
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

	private String spillToTempFile(String content)
	{
		try
		{
			Path file = Files.createTempFile("svy-evaluate-", ".txt");
			Files.write(file, content.getBytes(StandardCharsets.UTF_8));
			return file.toAbsolutePath().toString();
		}
		catch (IOException e)
		{
			ServoyLog.logError("evaluate: failed to spill over-cap value to a temp file", e);
			return null;
		}
	}

	// -------------------------------------------------------------------------
	// Error formatting: Servoy stack with solution-relative frames (spec 3.5 / 3.7)
	// -------------------------------------------------------------------------

	private String formatScriptError(Exception scriptError)
	{
		StringBuilder sb = new StringBuilder();
		String message = scriptError.getMessage();
		sb.append(message != null ? message : scriptError.toString());

		if (scriptError instanceof RhinoException rhino)
		{
			ScriptStackElement[] stack = null;
			try
			{
				stack = rhino.getScriptStack();
			}
			catch (Exception ignore)
			{
				// fall through to no stack
			}
			if (stack != null && stack.length > 0)
			{
				for (ScriptStackElement frame : stack)
				{
					// Elide the internal wrapper frame we synthesised around the expression.
					if (frame.fileName != null && frame.fileName.startsWith("internal_anon"))
					{
						continue;
					}
					sb.append('\n');
					frame.renderJavaStyle(sb);
				}
			}
		}

		String hint = bareIdentifierHint(message);
		if (hint != null)
		{
			sb.append('\n').append(hint);
		}
		return sb.toString();
	}

	/**
	 * Enriches the error only when it clearly names a bare form-context identifier (spec 3.7). No source pre-scan.
	 */
	private String bareIdentifierHint(String message)
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

	private boolean namesIdentifier(String message, String identifier)
	{
		int idx = message.toLowerCase().indexOf(identifier.toLowerCase());
		while (idx >= 0)
		{
			boolean leftOk = idx == 0 || !Character.isLetterOrDigit(message.charAt(idx - 1));
			int end = idx + identifier.length();
			boolean rightOk = end >= message.length() || !Character.isLetterOrDigit(message.charAt(end));
			// Avoid matching a qualified form (forms.x.foundset) — a preceding '.' means it is qualified.
			boolean notQualified = idx == 0 || message.charAt(idx - 1) != '.';
			if (leftOk && rightOk && notQualified)
			{
				return true;
			}
			idx = message.toLowerCase().indexOf(identifier.toLowerCase(), idx + 1);
		}
		return false;
	}
}
