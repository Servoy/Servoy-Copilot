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

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IValueModification;
import org.eclipse.debug.core.model.IVariable;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.dltk.debug.core.model.IScriptLineBreakpoint;
import org.eclipse.dltk.debug.core.eval.IScriptEvaluationEngine;
import org.eclipse.dltk.debug.core.eval.IScriptEvaluationResult;
import org.eclipse.dltk.debug.core.model.IScriptStackFrame;
import org.eclipse.dltk.debug.core.model.IScriptThread;
import org.eclipse.dltk.internal.debug.core.model.ScriptLineBreakpoint;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.debug.handlers.StartNGClientHandler;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.IApplication;
import com.servoy.j2db.IDebugClientHandler;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Read-only inspection of a suspended Servoy debug session through Eclipse's own debug model ({@code org.eclipse.debug.core}).
 *
 * <p>
 * This does NOT open a second DBGP connection to the running client; it piggybacks on the debug session the IDE already owns. When a
 * solution is launched in Debug mode and a line breakpoint is hit (for example by triggering the method through {@code executeInRunningClient}),
 * the DLTK debug session suspends the script thread and populates an {@link IThread}/{@link IStackFrame}/{@link IVariable} tree in the
 * Eclipse debug model. This service walks that tree in-process, so it never competes with the IDE for the single DBGP client socket and
 * never blocks the client's event thread.
 * </p>
 *
 * <p>
 * Prototype scope (SVY-21443): report whether a client is suspended and render the stack + variables of the suspended frame, with the
 * variable tree expanded lazily to a bounded depth. Stepping/resume and set-value are reachable through the same model
 * ({@link IThread} is an {@code IStep}/{@code ISuspendResume}, {@link IVariable} an {@link IValueModification}) and can be added on top.
 * </p>
 */
public class DebugInspectionService
{
	/** Bounded depth for the lazy variable tree, so a deeply nested / self-referential object graph cannot be dumped whole. */
	private static final int DEFAULT_MAX_DEPTH = 2;

	/** Maximum children rendered per variable node, so a large collection does not flood the response. */
	private static final int MAX_CHILDREN_PER_NODE = 50;

	/** The DLTK debug model id for Servoy JavaScript; a line breakpoint must carry it to be honoured by the running JS debug target. */
	private static final String JS_DEBUG_MODEL_ID = "org.eclipse.dltk.debug.javascriptModel";

	/**
	 * Marker attribute stamped on breakpoints THIS tool creates, so cleanup (debugEnd / clearBreakpoint) only ever removes the agent's own
	 * breakpoints and never a breakpoint the developer set by hand in the IDE.
	 */
	private static final String AI_CREATED_ATTR = "com.servoy.eclipse.developer.mcp.aiCreated";

	/** Resolves a form/scope name to its .js file within the active solution and its modules (never an unrelated workspace project). */
	private final ServoyScriptResolver scriptResolver = new ServoyScriptResolver();

	/** Finds a function's declaration line inside a resolved .js file. */
	private final WorkspaceService workspaceService = new WorkspaceService();

	/**
	 * How long resume/step wait for the requested state transition to actually happen before reporting the observed state. A local,
	 * in-process DLTK step/resume settles in a few ms, so this only needs to cover a brief transition window - kept short so stepping
	 * feels responsive rather than padding every call.
	 */
	private static final long TRANSITION_TIMEOUT_MS = 750;

	/** Poll interval while waiting for a resume/step transition. */
	private static final long TRANSITION_POLL_MS = 15;

	/**
	 * How long a step must stay not-suspended before it is treated as the method having returned / resumed (rather than the brief
	 * not-suspended gap in the middle of a normal step that re-suspends at the next line).
	 */
	private static final long STEP_RETURN_GRACE_MS = 250;

	/**
	 * Reports whether any debug target has a suspended thread, and where it is suspended. Returns a human/agent-readable status line, never
	 * throws.
	 */
	public String status()
	{
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			if (!hasAnyDebugTarget())
			{
				return "No debug session is active (the client is not in debug mode). Launch the solution in Debug mode and set a breakpoint, then trigger the code (e.g. via executeInRunningClient) so it suspends.";
			}
			return "A debug session is ACTIVE (the client is in debug mode) but nothing is currently suspended. Set a breakpoint and trigger the code so it stops. Note: clearing breakpoints does not end the debug session - stop it from the IDE's Debug view when you are done.";
		}
		try
		{
			IStackFrame top = suspended.getTopStackFrame();
			StringBuilder sb = new StringBuilder("Suspended");
			if (top != null)
			{
				sb.append(" at ").append(top.getName());
				int line = top.getLineNumber();
				if (line >= 0)
				{
					sb.append(" (line ").append(line).append(')');
				}
			}
			IBreakpoint[] bps = suspended.getBreakpoints();
			if (bps != null && bps.length > 0)
			{
				sb.append(" on breakpoint");
			}
			sb.append(". Use debugGetVariables to inspect the frame, or step/resume.");
			return sb.toString();
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugStatus: failed to read suspended thread", e);
			return "A thread is suspended but its state could not be read: " + e.getMessage();
		}
	}

	/**
	 * Renders the stack of the suspended thread and the variables of the requested frame, expanding the variable tree lazily to
	 * {@code maxDepth} levels. Returns a human/agent-readable block, never throws.
	 *
	 * @param frameIndex 0-based frame to inspect (0 = the top/current frame).
	 * @param maxDepth how many levels of the nested variable tree to expand (bounded); 0 or less falls back to the default.
	 */
	public String getVariables(int frameIndex, int maxDepth)
	{
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			return status();
		}
		int depth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
		try
		{
			IStackFrame[] frames = suspended.getStackFrames();
			if (frames == null || frames.length == 0)
			{
				return "The suspended thread has no stack frames.";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("Suspended thread stack (").append(frames.length).append(" frame(s)):\n");
			for (int i = 0; i < frames.length; i++)
			{
				IStackFrame f = frames[i];
				sb.append("  [").append(i).append("] ").append(f.getName());
				int line = f.getLineNumber();
				if (line >= 0)
				{
					sb.append("  (line ").append(line).append(')');
				}
				sb.append('\n');
			}

			int idx = frameIndex >= 0 && frameIndex < frames.length ? frameIndex : 0;
			IStackFrame frame = frames[idx];
			sb.append("\nVariables of frame [").append(idx).append("] ").append(frame.getName()).append(':').append('\n');
			IVariable[] vars = frame.getVariables();
			if (vars == null || vars.length == 0)
			{
				sb.append("  (no visible variables)");
			}
			else
			{
				for (IVariable v : vars)
				{
					renderVariable(sb, v, 1, depth);
				}
			}
			return sb.toString();
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugGetVariables: failed to read frame variables", e);
			return "Could not read the suspended frame's variables: " + e.getMessage();
		}
	}

	/**
	 * Resumes the suspended thread (equivalent to the Debug perspective's Resume / F8). This also releases a {@code executeInRunningClient}
	 * call that timed out because the code it triggered hit a breakpoint. Returns a human/agent-readable status line, never throws.
	 */
	public String resume()
	{
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			return "Nothing is suspended, so there is nothing to resume. " + status();
		}
		try
		{
			if (!suspended.canResume())
			{
				return "The suspended thread cannot be resumed in its current state.";
			}
			String where = describeLocation(suspended);
			suspended.resume();
			// resume() is asynchronous: report the state we actually observe, not an optimistic "Resumed", so the caller can tell
			// "ran on / breakpoint cleared" from "re-suspended at the next breakpoint" without a separate debugStatus round-trip.
			IThread now = waitForTransition(suspended);
			String from = where != null ? " (was at " + where + ")" : "";
			if (now == null)
			{
				return "Resumed" + from + " - execution is now running (no longer suspended).";
			}
			String reSuspended = describeLocation(now);
			// The resume itself succeeded (DLTK setSuspended(false)+engine.resume()); a suspend that is present again afterwards is a
			// FRESH hit - DLTK models each suspend as a new thread element, so a re-hit of the SAME line is a new invocation, not a failed
			// resume. Say so, so the caller does not read "same line again" as "resume did not work" and retry in a loop.
			boolean sameSpot = reSuspended != null && reSuspended.equals(where);
			if (sameSpot)
			{
				return "Resumed" + from + ". The breakpoint was hit again at the same location - this is a NEW call reaching the same " +
					"breakpoint, not a failed resume. If you did not expect another call, stop re-triggering the method; otherwise resume " +
					"again or remove the breakpoint.";
			}
			return "Resumed" + from + ", and execution re-suspended" + (reSuspended != null ? " at " + reSuspended : "") +
				" (a different breakpoint). Inspect with debugGetVariables or resume again.";
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugResume: failed to resume", e);
			return "Could not resume the suspended thread: " + e.getMessage();
		}
	}

	/**
	 * Steps the suspended thread. {@code mode} is {@code into}, {@code over} or {@code out}. Returns a human/agent-readable status line,
	 * never throws. After the step the thread suspends again at the new location; call debugStatus / debugGetVariables to inspect it.
	 */
	public String step(String mode)
	{
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			return "Nothing is suspended, so there is nothing to step. " + status();
		}
		String normalized = mode == null ? "over" : mode.trim().toLowerCase();
		try
		{
			switch (normalized)
			{
				case "into" :
				case "in" :
					if (!suspended.canStepInto()) return "Cannot step into from the current location.";
					suspended.stepInto();
					break;
				case "out" :
				case "return" :
					if (!suspended.canStepReturn()) return "Cannot step out from the current location.";
					suspended.stepReturn();
					break;
				case "over" :
				case "next" :
				default :
					if (!suspended.canStepOver()) return "Cannot step over from the current location.";
					suspended.stepOver();
					break;
			}
			// A step is asynchronous and almost always re-suspends at the NEXT line - during the step the thread briefly goes
			// not-suspended and then suspends again. Wait specifically for that re-suspend (tolerating the transient gap) instead of
			// declaring "completed" on the first not-suspended reading, which was wrongly reporting every ordinary step as "execution
			// completed". Only when it stays unsuspended past a grace window has the method genuinely returned / resumed.
			IThread now = waitForStepLanding(suspended);
			if (now == null)
			{
				return "Stepped " + normalized + " - the method returned / execution is no longer suspended.";
			}
			String landed = describeLocation(now);
			return "Stepped " + normalized + (landed != null ? ", now at " + landed : "") +
				". Inspect with debugGetVariables.";
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugStep: failed to step " + normalized, e);
			return "Could not step " + normalized + ": " + e.getMessage();
		}
	}

	/**
	 * Waits for a step to LAND. A step suspends the current thread, so the thread momentarily reads as not-suspended before it re-suspends
	 * at the next line; this method tolerates that transient gap. It returns the re-suspended thread as soon as a (new) suspended location
	 * is visible, and returns {@code null} only when the thread stays unsuspended for a full grace window - meaning the method actually
	 * returned / execution resumed. This avoids misreporting an ordinary step as "execution completed".
	 */
	private IThread waitForStepLanding(IThread previous)
	{
		long deadline = System.currentTimeMillis() + TRANSITION_TIMEOUT_MS;
		long notSuspendedSince = -1;
		while (System.currentTimeMillis() < deadline)
		{
			IThread current = findFirstSuspendedThread();
			if (current != null)
			{
				// Re-suspended somewhere: if it is a new location (or a fresh thread), the step has landed - report it.
				if (current != previous || !sameLocation(previous, current))
				{
					return current;
				}
				// Still showing the old location: keep waiting for the step to take effect.
				notSuspendedSince = -1;
			}
			else
			{
				// Not suspended right now. Only conclude "returned/resumed" if it STAYS that way for the grace window; otherwise this is
				// just the transient gap mid-step and a re-suspend is coming.
				long now = System.currentTimeMillis();
				if (notSuspendedSince < 0)
				{
					notSuspendedSince = now;
				}
				else if (now - notSuspendedSince >= STEP_RETURN_GRACE_MS)
				{
					return null;
				}
			}
			sleep(TRANSITION_POLL_MS);
		}
		// Deadline hit: report whatever is suspended now (if anything).
		return findFirstSuspendedThread();
	}

	private IThread waitForTransition(IThread previous)
	{
		long deadline = System.currentTimeMillis() + TRANSITION_TIMEOUT_MS;
		IThread last = findFirstSuspendedThread();
		while (System.currentTimeMillis() < deadline)
		{
			IThread current = findFirstSuspendedThread();
			// Settled to "running" (nothing suspended): done.
			if (current == null)
			{
				return null;
			}
			// Re-suspended at a different location than where we started: the transition happened, report it immediately. No extra
			// confirm-sleep - the model is consistent once a new suspended location is visible, and the extra poll only added latency.
			if (current != previous || !sameLocation(previous, current))
			{
				return current;
			}
			last = current;
			sleep(TRANSITION_POLL_MS);
		}
		return last;
	}

	private boolean sameLocation(IThread a, IThread b)
	{
		if (a == null || b == null)
		{
			return a == b;
		}
		try
		{
			IStackFrame fa = a.getTopStackFrame();
			IStackFrame fb = b.getTopStackFrame();
			if (fa == null || fb == null)
			{
				return fa == fb;
			}
			return fa.getLineNumber() == fb.getLineNumber() && java.util.Objects.equals(fa.getName(), fb.getName());
		}
		catch (CoreException e)
		{
			return false;
		}
	}

	private static void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Evaluates a JavaScript expression in the context of the suspended frame and returns its value. This is how the value of the code at
	 * a breakpoint is obtained: rather than waiting for a triggering {@code executeInRunningClient} run (whose return is lost once it times
	 * out at the breakpoint), ask the frame directly - e.g. evaluate {@code Math.ceil(diff / msPerDay)} or the whole return expression, or
	 * inspect any in-scope variable. Returns a human/agent-readable line, never throws.
	 *
	 * @param expression the JavaScript expression to evaluate in the frame.
	 * @param frameIndex 0-based frame to evaluate in (0 = the top/current frame).
	 */
	public String eval(String expression, int frameIndex)
	{
		if (expression == null || expression.isBlank())
		{
			return "Error: an expression to evaluate is required.";
		}
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			return "Nothing is suspended, so there is no frame to evaluate in. " + status();
		}
		if (!(suspended instanceof IScriptThread scriptThread))
		{
			return "The suspended thread is not a Servoy script thread; cannot evaluate an expression in it.";
		}
		try
		{
			IStackFrame[] frames = suspended.getStackFrames();
			if (frames == null || frames.length == 0)
			{
				return "The suspended thread has no stack frames to evaluate in.";
			}
			int idx = frameIndex >= 0 && frameIndex < frames.length ? frameIndex : 0;
			if (!(frames[idx] instanceof IScriptStackFrame scriptFrame))
			{
				return "Frame [" + idx + "] is not a script frame; cannot evaluate in it.";
			}
			IScriptEvaluationEngine engine = scriptThread.getEvaluationEngine();
			if (engine == null)
			{
				return "The suspended thread has no evaluation engine available.";
			}
			IScriptEvaluationResult result = engine.syncEvaluate(expression, scriptFrame);
			if (result == null)
			{
				return "The evaluation returned no result.";
			}
			if (result.hasErrors())
			{
				String[] msgs = result.getErrorMessages();
				String joined = msgs != null && msgs.length > 0 ? String.join("; ", msgs) : "unknown error";
				return "Evaluation of `" + expression + "` failed: " + joined;
			}
			Object value = result.getValue();
			String rendered;
			if (value == null)
			{
				rendered = "(null)";
			}
			else
			{
				try
				{
					rendered = value instanceof org.eclipse.debug.core.model.IValue v ? v.getValueString() : String.valueOf(value);
				}
				catch (CoreException ce)
				{
					rendered = String.valueOf(value);
				}
			}
			return "`" + expression + "` = " + rendered;
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugEval: failed to evaluate expression", e);
			return "Could not evaluate `" + expression + "`: " + e.getMessage();
		}
	}

	/**
	 * Sets a new value on a named variable of the suspended frame (equivalent to editing a value in the Debug perspective's Variables
	 * view). {@code value} is a source expression as the debug engine accepts it (e.g. {@code 5}, {@code "text"}, {@code true}). Returns a
	 * human/agent-readable line, never throws.
	 *
	 * @param variableName the name of the variable in the frame to change.
	 * @param value the new value expression.
	 * @param frameIndex 0-based frame (0 = the top/current frame).
	 */
	public String setVariable(String variableName, String value, int frameIndex)
	{
		if (variableName == null || variableName.isBlank())
		{
			return "Error: a variableName is required.";
		}
		IThread suspended = findFirstSuspendedThread();
		if (suspended == null)
		{
			return "Nothing is suspended, so there is no frame whose variable can be set. " + status();
		}
		try
		{
			IStackFrame[] frames = suspended.getStackFrames();
			if (frames == null || frames.length == 0)
			{
				return "The suspended thread has no stack frames.";
			}
			int idx = frameIndex >= 0 && frameIndex < frames.length ? frameIndex : 0;
			IVariable target = null;
			for (IVariable v : frames[idx].getVariables())
			{
				if (variableName.equals(v.getName()))
				{
					target = v;
					break;
				}
			}
			if (target == null)
			{
				return "No variable named '" + variableName + "' is visible in frame [" + idx + "].";
			}
			// Assign by EVALUATING "<name> = <value>" in the frame rather than IVariable.setValue(...). setValue - whether given the raw
			// string or an evaluated IValue - round-trips through the DBGP property_set path, which stringifies the value: setting c to 0
			// stored the STRING "0", silently breaking later arithmetic/comparisons. An in-frame assignment eval runs with real JS
			// semantics, so 0 becomes the NUMBER 0, "text" a string, true a boolean - exactly as the code itself would assign it.
			IStackFrame frame = frames[idx];
			String assignResult = evalAssignment(suspended, frame, variableName, value);
			if (assignResult != null)
			{
				return assignResult; // an error from the eval path
			}
			// Read the value back for confirmation (re-fetch the variable; the old handle may be stale after the assignment).
			String now = readVariableValue(frame, variableName);
			return "Set '" + variableName + "' = " + now + " in frame [" + idx + "] (typed via in-frame assignment).";
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugSetVariable: failed to set variable", e);
			return "Could not set variable '" + variableName + "': " + e.getMessage();
		}
	}

	/**
	 * Ensures a debug-launched Servoy NG client is running, launching one if needed. This is the same action as the IDE's toolbar
	 * "Launch NG Client": it attaches the DBGP debugger ({@code testAndStartDebugger}) and opens the active solution in the configured
	 * browser, so the resulting client is debuggable (break/step/inspect work against it). When a debug client is already running it does
	 * nothing. Returns a human/agent-readable line, never throws.
	 *
	 * <p>
	 * Use this first for "execute and debug &lt;method&gt;" when nothing is running yet, then debugBreakOnMethod + executeInRunningClient.
	 * </p>
	 *
	 * @param timeoutSeconds how long to wait for the launched client to become debug-ready (bounded).
	 */
	public String launchDebugClient(int timeoutSeconds)
	{
		// Already have a debug-ready client? Then there is nothing to launch.
		try
		{
			if (ApplicationServerRegistry.get() != null && ApplicationServerRegistry.get().getDebugClientHandler() != null)
			{
				IApplication ready = ApplicationServerRegistry.get().getDebugClientHandler().getDebugReadyClient();
				if (ready != null)
				{
					return "A debug client is already running (" + describeSolution(ready) + "). No launch needed - set a breakpoint with debugBreakOnMethod and trigger it.";
				}
			}
		}
		catch (Exception e)
		{
			// fall through to launch
		}

		ServoyProject activeProject = null;
		try
		{
			activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		}
		catch (Exception e)
		{
			ServoyLog.logError("debugLaunchClient: failed to read active project", e);
		}
		if (activeProject == null || activeProject.getSolution() == null)
		{
			return "No active solution to launch. Open/activate a solution first, then try again.";
		}
		String solutionName = activeProject.getProject().getName();

		// Launch via the same handler the IDE's "Launch NG Client" toolbar button uses, so the DBGP debugger is attached and the client
		// is debuggable. startNGClient opens the solution in the configured browser; it must run off the UI thread.
		try
		{
			StartNGClientHandler handler = new StartNGClientHandler();
			Thread launcher = new Thread(() -> {
				try
				{
					handler.startNGClient(new NullProgressMonitor());
				}
				catch (Exception ex)
				{
					ServoyLog.logError("debugLaunchClient: startNGClient failed", ex);
				}
			}, "svy-debug-launch");
			launcher.setDaemon(true);
			launcher.start();
		}
		catch (Throwable t)
		{
			ServoyLog.logError("debugLaunchClient: could not start the NG client launcher", t);
			return "Could not launch the debug client: " + t.getMessage() + ". Launch it from the IDE (Launch NG Client) instead.";
		}

		// Wait for the client to become debug-ready.
		int timeout = timeoutSeconds > 0 ? timeoutSeconds : 60;
		long deadline = System.currentTimeMillis() + timeout * 1000L;
		while (System.currentTimeMillis() < deadline)
		{
			try
			{
				IDebugClientHandler dch = ApplicationServerRegistry.get() != null ? ApplicationServerRegistry.get().getDebugClientHandler() : null;
				if (dch != null && dch.getDebugReadyClient() != null)
				{
					return "Launched a debug NG client for solution '" + solutionName + "' (opened in the browser, DBGP debugger attached). " +
						"Now set a breakpoint with debugBreakOnMethod and trigger it with executeInRunningClient.";
				}
			}
			catch (Exception e)
			{
				// keep waiting
			}
			sleep(250);
		}
		return "Started launching a debug NG client for '" + solutionName + "', but it was not debug-ready within " + timeout +
			"s. It may still be starting (check the browser); re-check with debugStatus shortly.";
	}

	private static String describeSolution(IApplication client)
	{
		try
		{
			return client.getSolution() != null ? "solution: " + client.getSolution().getName() : "no solution loaded";
		}
		catch (Exception e)
		{
			return "unknown solution";
		}
	}

	/**
	 * Resolves a Servoy method address ({@code forms.<form>.<method>} or {@code scopes.<scope>.<fn>}) to its source file and first body
	 * line and sets a breakpoint there, so a caller can then trigger the method (e.g. via executeInRunningClient) and have it suspend.
	 *
	 * <p>
	 * Resolution is scoped to the ACTIVE solution and its modules via {@link ServoyScriptResolver} - it will NOT match a same-named form in
	 * an unrelated solution that the running client is not using. The function's declaration line is found with
	 * {@link WorkspaceService#readFunction}, and the breakpoint is placed on the first line inside the body (declaration + 1), matching
	 * where a first-statement breakpoint lands.
	 * </p>
	 *
	 * @param methodAddress e.g. {@code forms.main.daysLeftInMonth} or {@code scopes.rating.computeUValue}.
	 */
	public String breakOnMethod(String methodAddress)
	{
		if (methodAddress == null || methodAddress.isBlank())
		{
			return "Error: a method address like 'forms.main.daysLeftInMonth' is required.";
		}
		String[] parts = methodAddress.trim().split("\\.");
		if (parts.length != 3 || !("forms".equals(parts[0]) || "scopes".equals(parts[0])))
		{
			return "Error: expected 'forms.<form>.<method>' or 'scopes.<scope>.<function>', got '" + methodAddress + "'.";
		}
		String container = parts[1];
		String methodName = parts[2];

		// Resolve the .js file within the active solution + its modules (never an unrelated workspace project).
		IFile file = scriptResolver.resolveScript(container, null);
		if (file == null || !file.exists())
		{
			return "Could not resolve '" + methodAddress + "'. " + scriptResolver.buildNotFoundMessage(container, null);
		}

		String projectName = file.getProject().getName();
		String resourcePath = file.getProjectRelativePath().toString();
		try
		{
			WorkspaceService.FunctionResult fn = workspaceService.readFunction(projectName, resourcePath, methodName);
			// Break on the first line inside the body (one past the 'function' declaration line), where the first statement runs.
			int breakLine = fn.startLine() + 1;
			String result = setBreakpoint(file.getFullPath().toString(), breakLine);
			return result + "\nResolved '" + methodAddress + "' to " + file.getFullPath() + " (function '" + methodName +
				"' declared at line " + fn.startLine() + "). Now trigger it, e.g. executeInRunningClient(methodName='" + methodAddress + "').";
		}
		catch (Exception e)
		{
			ServoyLog.logError("debugBreakOnMethod: failed to resolve method line", e);
			return "Resolved the file " + file.getFullPath() + " but could not locate method '" + methodName + "': " + e.getMessage();
		}
	}

	/**
	 * Sets a Servoy JavaScript line breakpoint at {@code line} of the workspace file {@code filePath} (project-relative or workspace-path).
	 * The breakpoint carries the JavaScript debug model id so the running debug client honours it. Idempotent: if a breakpoint already
	 * exists at that file+line it is not duplicated. Returns a human/agent-readable line, never throws.
	 *
	 * <p>
	 * Use this to implement "run and debug method X": resolve the method's source file and its first body line, call this, then trigger
	 * the method (e.g. via executeInRunningClient) so it suspends there. Remember to clearBreakpoint afterwards so the breakpoint does not
	 * keep trapping later normal runs.
	 * </p>
	 */
	public String setBreakpoint(String filePath, int line)
	{
		if (filePath == null || filePath.isBlank())
		{
			return "Error: a filePath is required.";
		}
		if (line <= 0)
		{
			return "Error: a positive 1-based line number is required.";
		}
		IFile file = resolveFile(filePath);
		if (file == null || !file.exists())
		{
			return "Error: could not resolve a workspace file for '" + filePath + "'.";
		}
		try
		{
			IBreakpoint existing = findLineBreakpoint(file, line);
			if (existing != null)
			{
				return "A breakpoint already exists at " + file.getFullPath() + ":" + line + ".";
			}
			// add=true registers it with the breakpoint manager so the running JS debug target picks it up.
			ScriptLineBreakpoint created = new ScriptLineBreakpoint(JS_DEBUG_MODEL_ID, file, (IPath)null, line, -1, -1, true);
			// Stamp it as agent-created so cleanup never removes a breakpoint the developer set by hand.
			try
			{
				if (created.getMarker() != null)
				{
					created.getMarker().setAttribute(AI_CREATED_ATTR, true);
				}
			}
			catch (CoreException markEx)
			{
				ServoyLog.logError("debugSetBreakpoint: could not mark breakpoint as agent-created", markEx);
			}
			return "Breakpoint set at " + file.getFullPath() + ":" + line + ". Trigger the code (e.g. executeInRunningClient) so it suspends there, then inspect with debugStatus / debugGetVariables / debugEval.";
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugSetBreakpoint: failed to set breakpoint", e);
			return "Could not set a breakpoint at " + file.getFullPath() + ":" + line + ": " + e.getMessage();
		}
	}

	/**
	 * Removes a Servoy JavaScript line breakpoint at {@code line} of {@code filePath}, or - when {@code line <= 0} - every JS line
	 * breakpoint in that file. Use after a "run and debug" flow so an agent-set breakpoint does not keep trapping later runs. Returns a
	 * human/agent-readable line, never throws.
	 */
	public String clearBreakpoint(String filePath, int line)
	{
		if (filePath == null || filePath.isBlank())
		{
			return "Error: a filePath is required.";
		}
		IFile file = resolveFile(filePath);
		if (file == null)
		{
			return "Error: could not resolve a workspace file for '" + filePath + "'.";
		}
		try
		{
			IBreakpointManager mgr = DebugPlugin.getDefault().getBreakpointManager();
			int removed = 0;
			for (IBreakpoint bp : mgr.getBreakpoints(JS_DEBUG_MODEL_ID))
			{
				if (!(bp instanceof IScriptLineBreakpoint slb))
				{
					continue;
				}
				if (!file.equals(bp.getMarker() != null ? bp.getMarker().getResource() : null))
				{
					continue;
				}
				if (line <= 0 || slb.getLineNumber() == line)
				{
					// Only remove breakpoints THIS tool created - never one the developer set by hand.
					if (!isAiCreated(bp))
					{
						continue;
					}
					mgr.removeBreakpoint(bp, true);
					removed++;
				}
			}
			if (removed == 0)
			{
				return line > 0 ? "No breakpoint found at " + file.getFullPath() + ":" + line + "." : "No breakpoints found in " + file.getFullPath() + ".";
			}
			// Removing a breakpoint does NOT release a thread already parked ON it - the client event thread stays frozen until resumed,
			// which is the "stuck in debug" trap. So resume any suspended thread now, as part of clearing. Report whether we did.
			boolean resumed = resumeAllSuspended();
			String note = resumed
				? " Resumed the thread that was parked there, so the client is no longer frozen."
				: (hasAnyDebugTarget() ? " The debug session is still active (the client remains in debug mode); stop it from the IDE's Debug view when done." : "");
			return "Removed " + removed + " breakpoint(s)" + (line > 0 ? " at line " + line : "") + " in " + file.getFullPath() + "." + note;
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugClearBreakpoint: failed to clear breakpoint", e);
			return "Could not clear breakpoint(s) in " + file.getFullPath() + ": " + e.getMessage();
		}
	}

	private IBreakpoint findLineBreakpoint(IFile file, int line) throws CoreException
	{
		IBreakpointManager mgr = DebugPlugin.getDefault().getBreakpointManager();
		for (IBreakpoint bp : mgr.getBreakpoints(JS_DEBUG_MODEL_ID))
		{
			if (bp instanceof IScriptLineBreakpoint slb && file.equals(bp.getMarker() != null ? bp.getMarker().getResource() : null) &&
				slb.getLineNumber() == line)
			{
				return bp;
			}
		}
		return null;
	}

	/** True when a breakpoint carries the agent-created marker attribute, i.e. this tool set it (not the developer by hand). */
	private static boolean isAiCreated(IBreakpoint bp)
	{
		try
		{
			return bp.getMarker() != null && bp.getMarker().getAttribute(AI_CREATED_ATTR, false);
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Resolves {@code filePath} to a workspace file. Accepts a workspace full path (e.g. {@code /mySmp/forms/main.js}); if that does not
	 * exist and the path is project-relative (e.g. {@code forms/main.js}), it is resolved against the active solution and its modules.
	 * Returns {@code null} when no existing file matches.
	 */
	private IFile resolveFile(String filePath)
	{
		try
		{
			IPath path = new Path(filePath);
			// Try as a workspace full path first (e.g. /mySmp/forms/main.js).
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
			if (file != null && file.exists())
			{
				return file;
			}
			// Fallback: treat it as project-relative and look in the active solution and its modules.
			IFile resolved = findInActiveSolution(path);
			return resolved != null ? resolved : file;
		}
		catch (Exception e)
		{
			ServoyLog.logError("debug breakpoint: failed to resolve file '" + filePath + "'", e);
			return null;
		}
	}

	/** Looks up a project-relative path in the active solution project and each of its modules, returning the first existing file. */
	private IFile findInActiveSolution(IPath projectRelative)
	{
		try
		{
			ServoyProject active = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (active == null)
			{
				return null;
			}
			IFile inActive = tryProjectFile(active.getProject(), projectRelative);
			if (inActive != null)
			{
				return inActive;
			}
			ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
			if (modules != null)
			{
				for (ServoyProject mod : modules)
				{
					IFile inModule = tryProjectFile(mod.getProject(), projectRelative);
					if (inModule != null)
					{
						return inModule;
					}
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("debug breakpoint: failed to resolve '" + projectRelative + "' in the active solution", e);
		}
		return null;
	}

	private static IFile tryProjectFile(IProject project, IPath projectRelative)
	{
		if (project == null || !project.isOpen())
		{
			return null;
		}
		IFile file = project.getFile(projectRelative);
		return file.exists() ? file : null;
	}

	/**
	 * Assigns {@code value} to {@code variableName} by evaluating the assignment {@code "<name> = <value>"} in the frame, so the result
	 * keeps its real JS type (unlike {@code IVariable.setValue}, which stringifies through DBGP property_set). Returns {@code null} on
	 * success, or a ready-to-return error message when the eval path is unavailable or the assignment failed.
	 */
	private String evalAssignment(IThread thread, IStackFrame frame, String variableName, String value)
	{
		if (!(thread instanceof IScriptThread scriptThread) || !(frame instanceof IScriptStackFrame scriptFrame))
		{
			return "Cannot set '" + variableName + "': the suspended frame is not a Servoy script frame.";
		}
		IScriptEvaluationEngine engine = scriptThread.getEvaluationEngine();
		if (engine == null)
		{
			return "Cannot set '" + variableName + "': no evaluation engine is available on the suspended thread.";
		}
		IScriptEvaluationResult result = engine.syncEvaluate(variableName + " = " + value, scriptFrame);
		if (result == null)
		{
			return "Setting '" + variableName + "' returned no result.";
		}
		if (result.hasErrors())
		{
			String[] msgs = result.getErrorMessages();
			String joined = msgs != null && msgs.length > 0 ? String.join("; ", msgs) : "unknown error";
			return "Could not set '" + variableName + "' = " + value + ": " + joined;
		}
		return null;
	}

	/** Re-reads a named variable's value string from the frame (fresh lookup, since a handle from before an assignment may be stale). */
	private String readVariableValue(IStackFrame frame, String variableName)
	{
		try
		{
			for (IVariable v : frame.getVariables())
			{
				if (variableName.equals(v.getName()))
				{
					IValue val = v.getValue();
					return val != null ? val.getValueString() : "null";
				}
			}
		}
		catch (CoreException e)
		{
			// fall through
		}
		return "(unknown)";
	}

	private String describeLocation(IThread thread)
	{
		try
		{
			IStackFrame top = thread.getTopStackFrame();
			if (top == null)
			{
				return null;
			}
			int line = top.getLineNumber();
			return top.getName() + (line >= 0 ? " line " + line : "");
		}
		catch (CoreException e)
		{
			return null;
		}
	}

	private void renderVariable(StringBuilder sb, IVariable variable, int level, int maxDepth)
	{
		indent(sb, level);
		try
		{
			String name = variable.getName();
			IValue value = variable.getValue();
			String type = value != null ? value.getReferenceTypeName() : null;
			String valueString = value != null ? value.getValueString() : "null";
			sb.append(name);
			if (type != null && !type.isBlank())
			{
				sb.append(" (").append(type).append(')');
			}
			sb.append(" = ").append(valueString).append('\n');

			if (value != null && level < maxDepth && value.hasVariables())
			{
				IVariable[] children = value.getVariables();
				int shown = Math.min(children.length, MAX_CHILDREN_PER_NODE);
				for (int i = 0; i < shown; i++)
				{
					renderVariable(sb, children[i], level + 1, maxDepth);
				}
				if (children.length > shown)
				{
					indent(sb, level + 1);
					sb.append("... (").append(children.length - shown).append(" more)\n");
				}
			}
			else if (value != null && value.hasVariables())
			{
				// Not expanded because the depth cap was reached; tell the caller it can drill deeper.
				indent(sb, level + 1);
				sb.append("... (expandable; raise maxDepth to see children)\n");
			}
		}
		catch (CoreException e)
		{
			sb.append("<error reading variable: ").append(e.getMessage()).append(">\n");
		}
	}

	private static void indent(StringBuilder sb, int level)
	{
		for (int i = 0; i < level; i++)
		{
			sb.append("  ");
		}
	}

	private boolean hasAnyDebugTarget()
	{
		ILaunchManager mgr = DebugPlugin.getDefault() != null ? DebugPlugin.getDefault().getLaunchManager() : null;
		if (mgr == null)
		{
			return false;
		}
		for (ILaunch launch : mgr.getLaunches())
		{
			IDebugTarget[] targets = launch.getDebugTargets();
			if (targets != null && targets.length > 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Walks every launch's debug targets and returns the first thread that reports {@link IThread#isSuspended()}, or {@code null} when
	 * nothing is suspended. Reading the model is safe from a tool thread; the suspended client event thread is parked independently.
	 */
	/**
	 * Ends a debug inspection cleanly in one call: resumes every suspended thread (so a client parked at a breakpoint is no longer frozen)
	 * and removes every Servoy JavaScript breakpoint (so none keeps trapping the user's later runs). This is the single "I am done
	 * debugging" action - preferred over remembering a separate resume + clear, which is the common way a client is left stuck. The debug
	 * SESSION itself (the client in debug mode) is left running, as the IDE owns it; stop that from the Debug view. Never throws.
	 */
	public String end()
	{
		int resumed = 0;
		for (int i = 0; i < 20; i++) // bounded: resume each suspended thread, re-checking since each resume may reveal another
		{
			IThread t = findFirstSuspendedThread();
			if (t == null)
			{
				break;
			}
			try
			{
				if (t.canResume())
				{
					t.resume();
					resumed++;
					waitForTransition(t);
				}
				else
				{
					break;
				}
			}
			catch (CoreException e)
			{
				ServoyLog.logError("debugEnd: failed to resume a suspended thread", e);
				break;
			}
		}

		int cleared = 0;
		try
		{
			IBreakpointManager mgr = DebugPlugin.getDefault().getBreakpointManager();
			for (IBreakpoint bp : mgr.getBreakpoints(JS_DEBUG_MODEL_ID))
			{
				// Only remove breakpoints THIS tool created - leave the developer's own breakpoints in place.
				if (bp instanceof IScriptLineBreakpoint && isAiCreated(bp))
				{
					mgr.removeBreakpoint(bp, true);
					cleared++;
				}
			}
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debugEnd: failed to clear breakpoints", e);
		}

		if (resumed == 0 && cleared == 0)
		{
			return "Nothing to end: no thread was suspended and no Servoy breakpoints were set.";
		}
		StringBuilder sb = new StringBuilder("Debug inspection ended.");
		if (resumed > 0)
		{
			sb.append(" Resumed ").append(resumed).append(" suspended thread(s), so the client is no longer frozen.");
		}
		if (cleared > 0)
		{
			sb.append(" Cleared ").append(cleared).append(" breakpoint(s).");
		}
		sb.append(" The debug session (client in debug mode) is left running; stop it from the IDE's Debug view when you no longer need it.");
		return sb.toString();
	}

	/** Resumes every currently-suspended thread (bounded). Returns true if at least one was resumed. Used by clearBreakpoint/end. */
	private boolean resumeAllSuspended()
	{
		boolean any = false;
		for (int i = 0; i < 20; i++)
		{
			IThread t = findFirstSuspendedThread();
			if (t == null)
			{
				break;
			}
			try
			{
				if (!t.canResume())
				{
					break;
				}
				t.resume();
				any = true;
				waitForTransition(t);
			}
			catch (CoreException e)
			{
				ServoyLog.logError("debug: failed to resume a suspended thread", e);
				break;
			}
		}
		return any;
	}

	private IThread findFirstSuspendedThread()
	{
		try
		{
			ILaunchManager mgr = DebugPlugin.getDefault() != null ? DebugPlugin.getDefault().getLaunchManager() : null;
			if (mgr == null)
			{
				return null;
			}
			for (ILaunch launch : mgr.getLaunches())
			{
				for (IDebugTarget target : launch.getDebugTargets())
				{
					if (target.isTerminated() || target.isDisconnected())
					{
						continue;
					}
					for (IThread thread : target.getThreads())
					{
						if (thread.isSuspended())
						{
							return thread;
						}
					}
				}
			}
		}
		catch (CoreException e)
		{
			ServoyLog.logError("debug inspection: failed to enumerate debug targets", e);
		}
		return null;
	}
}
