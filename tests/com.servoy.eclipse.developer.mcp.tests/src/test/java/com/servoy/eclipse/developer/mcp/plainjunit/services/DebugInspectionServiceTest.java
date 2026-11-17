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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.dltk.debug.core.eval.IScriptEvaluationEngine;
import org.eclipse.dltk.debug.core.eval.IScriptEvaluationResult;
import org.eclipse.dltk.debug.core.model.IScriptStackFrame;
import org.eclipse.dltk.debug.core.model.IScriptThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.DebugInspectionService;
import com.servoy.j2db.IApplication;
import com.servoy.j2db.persistence.Solution;

/**
 * Plain-JUnit unit tests for {@link DebugInspectionService}'s <em>own</em> logic -
 * the variable-tree rendering, location/value/solution formatting, breakpoint and
 * suspended-thread predicates, and the guards/marshalling of the public tool methods
 * that run <em>before/around</em> the live-debug calls - with the Eclipse debug model
 * boundary stubbed via Mockito.
 * <p>
 * The service reaches the live debug session through its {@code getLaunches()} seam and
 * the workbench through its {@code getServoyModel()} seam; both are overridden here to
 * return mocked {@link ILaunch}/{@link IDebugTarget}/{@link IThread}/{@link IStackFrame}/
 * {@link IVariable} trees. We assert only on what <em>our</em> code renders/decides given
 * those mocks - never on real debug behaviour. The genuinely live parts (actual
 * suspend/resume/step transitions, DBGP eval-against-VM, launching an NG client) stay for
 * the integration tests; here only their guard and result-formatting branches reachable
 * with a mocked model are covered.
 * </p>
 *
 * @since 2026.06
 */
class DebugInspectionServiceTest {
	/**
	 * A {@link DebugInspectionService} whose seams return the supplied launches and an empty
	 * (never-null) model, so no static workbench lookup is ever hit.
	 */
	private static DebugInspectionService serviceWith(ILaunch... launches) {
		return new DebugInspectionService() {
			@Override
			protected ILaunch[] getLaunches() {
				return launches;
			}

			@Override
			protected com.servoy.eclipse.core.IDeveloperServoyModel getServoyModel() {
				// An empty model: no active project. Only resolveFile()/launchDebugClient() touch it,
				// and those tests that need it override this again.
				return mock(com.servoy.eclipse.core.IDeveloperServoyModel.class);
			}
		};
	}

	// ---------------------------------------------------------------------------------------------
	// Mock builders for the debug model tree
	// ---------------------------------------------------------------------------------------------

	/** An {@link IValue} with the given type/valueString and no children. */
	private static IValue leafValue(String type, String valueString) throws Exception {
		IValue value = mock(IValue.class);
		doReturn(type).when(value).getReferenceTypeName();
		doReturn(valueString).when(value).getValueString();
		doReturn(false).when(value).hasVariables();
		return value;
	}

	/** An {@link IValue} that exposes the given children. */
	private static IValue parentValue(String type, String valueString, IVariable... children) throws Exception {
		IValue value = mock(IValue.class);
		doReturn(type).when(value).getReferenceTypeName();
		doReturn(valueString).when(value).getValueString();
		doReturn(children != null && children.length > 0).when(value).hasVariables();
		doReturn(children).when(value).getVariables();
		return value;
	}

	/** An {@link IVariable} named {@code name} carrying {@code value}. */
	private static IVariable variable(String name, IValue value) throws Exception {
		IVariable v = mock(IVariable.class);
		doReturn(name).when(v).getName();
		doReturn(value).when(v).getValue();
		return v;
	}

	/** An {@link IStackFrame} with a name and line, exposing the given variables. */
	private static IStackFrame frame(String name, int line, IVariable... vars) throws Exception {
		IStackFrame f = mock(IStackFrame.class);
		doReturn(name).when(f).getName();
		doReturn(line).when(f).getLineNumber();
		doReturn(vars).when(f).getVariables();
		return f;
	}

	/** A suspended {@link IThread} whose stack is the given frames (frame[0] is the top). */
	private static IThread suspendedThread(IStackFrame... frames) throws Exception {
		IThread t = mock(IThread.class);
		doReturn(true).when(t).isSuspended();
		doReturn(frames).when(t).getStackFrames();
		doReturn(frames != null && frames.length > 0 ? frames[0] : null).when(t).getTopStackFrame();
		doReturn(new IBreakpoint[0]).when(t).getBreakpoints();
		return t;
	}

	/** A live (non-terminated/-disconnected) debug target exposing the given threads. */
	private static IDebugTarget target(IThread... threads) throws Exception {
		IDebugTarget dt = mock(IDebugTarget.class);
		doReturn(false).when(dt).isTerminated();
		doReturn(false).when(dt).isDisconnected();
		doReturn(threads).when(dt).getThreads();
		return dt;
	}

	/** A launch carrying the given targets. */
	private static ILaunch launch(IDebugTarget... targets) {
		ILaunch l = mock(ILaunch.class);
		doReturn(targets).when(l).getDebugTargets();
		return l;
	}

	// ---------------------------------------------------------------------------------------------
	// Reflection helpers for the private rendering/formatting helpers
	// ---------------------------------------------------------------------------------------------

	private static Object invoke(DebugInspectionService svc, String method, Class<?>[] types, Object... args) throws Exception {
		Method m = DebugInspectionService.class.getDeclaredMethod(method, types);
		m.setAccessible(true);
		return m.invoke(svc, args);
	}

	// =============================================================================================
	// status()
	// =============================================================================================

	@Nested
	class Status {
		@Test
		@DisplayName("status reports no debug session when there are no launches")
		void noDebugSession() {
			String r = serviceWith().status();
			assertTrue(r.startsWith("No debug session is active"), r);
		}

		@Test
		@DisplayName("status reports active-but-not-suspended when a target exists with no suspended thread")
		void activeButNotSuspended() throws Exception {
			IThread running = mock(IThread.class);
			doReturn(false).when(running).isSuspended();
			String r = serviceWith(launch(target(running))).status();
			assertTrue(r.startsWith("A debug session is ACTIVE"), r);
		}

		@Test
		@DisplayName("status reports the suspended location with frame name and line")
		void suspendedWithLocation() throws Exception {
			IStackFrame top = frame("myForm.doIt", 42);
			IThread t = suspendedThread(top);
			String r = serviceWith(launch(target(t))).status();
			assertTrue(r.startsWith("Suspended at myForm.doIt (line 42)"), r);
			assertTrue(r.contains("debugGetVariables"), r);
		}

		@Test
		@DisplayName("status omits the line when the top frame has no line number")
		void suspendedNoLine() throws Exception {
			IStackFrame top = frame("myForm.doIt", -1);
			IThread t = suspendedThread(top);
			String r = serviceWith(launch(target(t))).status();
			assertTrue(r.startsWith("Suspended at myForm.doIt."), r);
			assertFalse(r.contains("(line"), r);
		}

		@Test
		@DisplayName("status mentions the breakpoint when the suspended thread carries one")
		void suspendedOnBreakpoint() throws Exception {
			IStackFrame top = frame("myForm.doIt", 10);
			IThread t = suspendedThread(top);
			doReturn(new IBreakpoint[] { mock(IBreakpoint.class) }).when(t).getBreakpoints();
			String r = serviceWith(launch(target(t))).status();
			assertTrue(r.contains("on breakpoint"), r);
		}

		@Test
		@DisplayName("a terminated target is skipped when scanning for a suspended thread")
		void terminatedTargetSkipped() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			IDebugTarget dead = target(t);
			doReturn(true).when(dead).isTerminated();
			String r = serviceWith(launch(dead)).status();
			// no live target and no suspended thread but a launch exists -> "ACTIVE but nothing suspended"
			assertTrue(r.startsWith("A debug session is ACTIVE"), r);
		}
	}

	// =============================================================================================
	// getVariables()
	// =============================================================================================

	@Nested
	class GetVariables {
		@Test
		@DisplayName("getVariables falls back to status() when nothing is suspended")
		void nothingSuspended() {
			String r = serviceWith().getVariables(0, 2);
			assertTrue(r.startsWith("No debug session is active"), r);
		}

		@Test
		@DisplayName("getVariables renders the stack and the requested frame's variables")
		void rendersStackAndVariables() throws Exception {
			IVariable count = variable("count", leafValue("Number", "3"));
			IStackFrame top = frame("myForm.doIt", 42, count);
			IThread t = suspendedThread(top);
			String r = serviceWith(launch(target(t))).getVariables(0, 2);
			assertTrue(r.contains("Suspended thread stack (1 frame(s)):"), r);
			assertTrue(r.contains("[0] myForm.doIt  (line 42)"), r);
			assertTrue(r.contains("Variables of frame [0] myForm.doIt:"), r);
			assertTrue(r.contains("count (Number) = 3"), r);
		}

		@Test
		@DisplayName("getVariables reports no visible variables when the frame has none")
		void noVisibleVariables() throws Exception {
			IStackFrame top = frame("myForm.doIt", 42);
			IThread t = suspendedThread(top);
			String r = serviceWith(launch(target(t))).getVariables(0, 2);
			assertTrue(r.contains("(no visible variables)"), r);
		}

		@Test
		@DisplayName("getVariables clamps an out-of-range frame index back to 0")
		void outOfRangeFrameIndexClampedToTop() throws Exception {
			IStackFrame top = frame("top", 1, variable("a", leafValue("Number", "1")));
			IThread t = suspendedThread(top);
			String r = serviceWith(launch(target(t))).getVariables(99, 2);
			assertTrue(r.contains("Variables of frame [0] top:"), r);
		}
	}

	// =============================================================================================
	// renderVariable() + indent() - the recursive tree rendering
	// =============================================================================================

	@Nested
	class RenderVariable {
		private String render(DebugInspectionService svc, IVariable v, int level, int maxDepth) throws Exception {
			StringBuilder sb = new StringBuilder();
			invoke(svc, "renderVariable", new Class<?>[] { StringBuilder.class, IVariable.class, int.class, int.class }, sb, v, level, maxDepth);
			return sb.toString();
		}

		@Test
		@DisplayName("a leaf variable renders name, type and value with one indent level")
		void leaf() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable v = variable("name", leafValue("String", "\"Bob\""));
			assertEquals("  name (String) = \"Bob\"\n", render(svc, v, 1, 2));
		}

		@Test
		@DisplayName("a null type is omitted from the rendered line")
		void nullTypeOmitted() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable v = variable("x", leafValue(null, "7"));
			assertEquals("  x = 7\n", render(svc, v, 1, 2));
		}

		@Test
		@DisplayName("a null value renders as the literal null with no type")
		void nullValue() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable v = mock(IVariable.class);
			doReturn("v").when(v).getName();
			doReturn(null).when(v).getValue();
			assertEquals("  v = null\n", render(svc, v, 1, 2));
		}

		@Test
		@DisplayName("children are expanded with increasing indentation up to maxDepth")
		void childrenExpanded() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable child = variable("field", leafValue("Number", "1"));
			IVariable parent = variable("obj", parentValue("Object", "[object]", child));
			String r = render(svc, parent, 1, 2);
			assertTrue(r.contains("  obj (Object) = [object]\n"), r);
			assertTrue(r.contains("    field (Number) = 1\n"), r);
		}

		@Test
		@DisplayName("at the depth cap an expandable node is annotated instead of expanded")
		void depthCapAnnotated() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable child = variable("field", leafValue("Number", "1"));
			IVariable parent = variable("obj", parentValue("Object", "[object]", child));
			// level == maxDepth: the node itself renders but its children are NOT expanded
			String r = render(svc, parent, 2, 2);
			assertTrue(r.contains("obj (Object) = [object]"), r);
			assertTrue(r.contains("... (expandable; raise maxDepth to see children)"), r);
			assertFalse(r.contains("field (Number) = 1"), r);
		}

		@Test
		@DisplayName("more than MAX_CHILDREN_PER_NODE children are truncated with a '... (n more)' line")
		void childrenTruncated() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable[] kids = new IVariable[60];
			for (int i = 0; i < kids.length; i++) {
				kids[i] = variable("k" + i, leafValue("Number", String.valueOf(i)));
			}
			IVariable parent = variable("list", parentValue("Array", "[60]", kids));
			String r = render(svc, parent, 1, 2);
			assertTrue(r.contains("k0 (Number) = 0"), r);
			assertTrue(r.contains("k49 (Number) = 49"), r);
			assertFalse(r.contains("k50 (Number) = 50"), r);
			assertTrue(r.contains("... (10 more)"), r);
		}

		@Test
		@DisplayName("a CoreException while reading a variable is rendered inline, not thrown")
		void readErrorRenderedInline() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable v = mock(IVariable.class);
			doReturn("bad").when(v).getName();
			// IVariable.getValue() declares DebugException (a CoreException); throw that so renderVariable's
			// catch(CoreException) branch renders the error inline instead of propagating.
			doThrow(new org.eclipse.debug.core.DebugException(
				new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR, "test", "boom"))).when(v).getValue();
			String r = render(svc, v, 1, 2);
			assertTrue(r.contains("<error reading variable: boom>"), r);
		}
	}

	// =============================================================================================
	// describeLocation() / sameLocation() / readVariableValue() / describeSolution() / isAiCreated()
	// =============================================================================================

	@Nested
	class Helpers {
		@Test
		@DisplayName("describeLocation formats the top frame name and line")
		void describeLocationWithLine() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread t = suspendedThread(frame("myForm.doIt", 42));
			Object r = invoke(svc, "describeLocation", new Class<?>[] { IThread.class }, t);
			assertEquals("myForm.doIt line 42", r);
		}

		@Test
		@DisplayName("describeLocation omits the line when the frame has none")
		void describeLocationNoLine() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread t = suspendedThread(frame("myForm.doIt", -1));
			Object r = invoke(svc, "describeLocation", new Class<?>[] { IThread.class }, t);
			assertEquals("myForm.doIt", r);
		}

		@Test
		@DisplayName("describeLocation returns null when there is no top frame")
		void describeLocationNoFrame() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread t = mock(IThread.class);
			doReturn(null).when(t).getTopStackFrame();
			Object r = invoke(svc, "describeLocation", new Class<?>[] { IThread.class }, t);
			assertNull(r);
		}

		@Test
		@DisplayName("sameLocation is true for two threads stopped at the same name and line")
		void sameLocationTrue() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread a = suspendedThread(frame("myForm.doIt", 42));
			IThread b = suspendedThread(frame("myForm.doIt", 42));
			Object r = invoke(svc, "sameLocation", new Class<?>[] { IThread.class, IThread.class }, a, b);
			assertEquals(Boolean.TRUE, r);
		}

		@Test
		@DisplayName("sameLocation is false when the line differs")
		void sameLocationDifferentLine() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread a = suspendedThread(frame("myForm.doIt", 42));
			IThread b = suspendedThread(frame("myForm.doIt", 43));
			Object r = invoke(svc, "sameLocation", new Class<?>[] { IThread.class, IThread.class }, a, b);
			assertEquals(Boolean.FALSE, r);
		}

		@Test
		@DisplayName("sameLocation treats two nulls as the same and one null as different")
		void sameLocationNulls() throws Exception {
			DebugInspectionService svc = serviceWith();
			IThread a = suspendedThread(frame("x", 1));
			Object both = invoke(svc, "sameLocation", new Class<?>[] { IThread.class, IThread.class }, null, null);
			Object one = invoke(svc, "sameLocation", new Class<?>[] { IThread.class, IThread.class }, a, null);
			assertEquals(Boolean.TRUE, both);
			assertEquals(Boolean.FALSE, one);
		}

		@Test
		@DisplayName("readVariableValue returns the matching variable's value string")
		void readVariableValueFound() throws Exception {
			DebugInspectionService svc = serviceWith();
			IStackFrame f = frame("fr", 1, variable("count", leafValue("Number", "5")));
			Object r = invoke(svc, "readVariableValue", new Class<?>[] { IStackFrame.class, String.class }, f, "count");
			assertEquals("5", r);
		}

		@Test
		@DisplayName("readVariableValue returns '(unknown)' when no variable matches")
		void readVariableValueNotFound() throws Exception {
			DebugInspectionService svc = serviceWith();
			IStackFrame f = frame("fr", 1, variable("count", leafValue("Number", "5")));
			Object r = invoke(svc, "readVariableValue", new Class<?>[] { IStackFrame.class, String.class }, f, "other");
			assertEquals("(unknown)", r);
		}

		@Test
		@DisplayName("readVariableValue returns 'null' when the matched variable's value is null")
		void readVariableValueNullValue() throws Exception {
			DebugInspectionService svc = serviceWith();
			IVariable v = mock(IVariable.class);
			doReturn("v").when(v).getName();
			doReturn(null).when(v).getValue();
			IStackFrame f = frame("fr", 1, v);
			Object r = invoke(svc, "readVariableValue", new Class<?>[] { IStackFrame.class, String.class }, f, "v");
			assertEquals("null", r);
		}

		@Test
		@DisplayName("describeSolution names the loaded solution")
		void describeSolutionNamed() throws Exception {
			DebugInspectionService svc = serviceWith();
			IApplication client = mock(IApplication.class);
			Solution sol = mock(Solution.class);
			doReturn("mySolution").when(sol).getName();
			doReturn(sol).when(client).getSolution();
			Object r = invoke(svc, "describeSolution", new Class<?>[] { IApplication.class }, client);
			assertEquals("solution: mySolution", r);
		}

		@Test
		@DisplayName("describeSolution reports no solution when none is loaded")
		void describeSolutionNone() throws Exception {
			DebugInspectionService svc = serviceWith();
			IApplication client = mock(IApplication.class);
			doReturn(null).when(client).getSolution();
			Object r = invoke(svc, "describeSolution", new Class<?>[] { IApplication.class }, client);
			assertEquals("no solution loaded", r);
		}

		@Test
		@DisplayName("isAiCreated is true only when the breakpoint's marker carries the agent attribute")
		void isAiCreatedTrue() throws Exception {
			DebugInspectionService svc = serviceWith();
			IBreakpoint bp = mock(IBreakpoint.class);
			IMarker marker = mock(IMarker.class);
			doReturn(marker).when(bp).getMarker();
			doReturn(true).when(marker).getAttribute("com.servoy.eclipse.developer.mcp.aiCreated", false);
			Object r = invoke(svc, "isAiCreated", new Class<?>[] { IBreakpoint.class }, bp);
			assertEquals(Boolean.TRUE, r);
		}

		@Test
		@DisplayName("isAiCreated is false when the attribute is absent")
		void isAiCreatedFalse() throws Exception {
			DebugInspectionService svc = serviceWith();
			IBreakpoint bp = mock(IBreakpoint.class);
			IMarker marker = mock(IMarker.class);
			doReturn(marker).when(bp).getMarker();
			doReturn(false).when(marker).getAttribute("com.servoy.eclipse.developer.mcp.aiCreated", false);
			Object r = invoke(svc, "isAiCreated", new Class<?>[] { IBreakpoint.class }, bp);
			assertEquals(Boolean.FALSE, r);
		}

		@Test
		@DisplayName("isAiCreated is false when the breakpoint has no marker")
		void isAiCreatedNoMarker() throws Exception {
			DebugInspectionService svc = serviceWith();
			IBreakpoint bp = mock(IBreakpoint.class);
			doReturn(null).when(bp).getMarker();
			Object r = invoke(svc, "isAiCreated", new Class<?>[] { IBreakpoint.class }, bp);
			assertEquals(Boolean.FALSE, r);
		}
	}

	// =============================================================================================
	// resume() / step() guards reachable without a live transition
	// =============================================================================================

	@Nested
	class ResumeStepGuards {
		@Test
		@DisplayName("resume reports nothing-to-resume when nothing is suspended")
		void resumeNothing() {
			String r = serviceWith().resume();
			assertTrue(r.startsWith("Nothing is suspended, so there is nothing to resume."), r);
		}

		@Test
		@DisplayName("resume refuses when the suspended thread cannot be resumed")
		void resumeCannotResume() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			doReturn(false).when(t).canResume();
			String r = serviceWith(launch(target(t))).resume();
			assertEquals("The suspended thread cannot be resumed in its current state.", r);
		}

		@Test
		@DisplayName("step reports nothing-to-step when nothing is suspended")
		void stepNothing() {
			String r = serviceWith().step("over");
			assertTrue(r.startsWith("Nothing is suspended, so there is nothing to step."), r);
		}

		@Test
		@DisplayName("step over refuses when the thread cannot step over")
		void stepOverCannot() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			doReturn(false).when(t).canStepOver();
			String r = serviceWith(launch(target(t))).step("over");
			assertEquals("Cannot step over from the current location.", r);
		}

		@Test
		@DisplayName("step into refuses when the thread cannot step into")
		void stepIntoCannot() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			doReturn(false).when(t).canStepInto();
			String r = serviceWith(launch(target(t))).step("into");
			assertEquals("Cannot step into from the current location.", r);
		}

		@Test
		@DisplayName("step out refuses when the thread cannot step return")
		void stepOutCannot() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			doReturn(false).when(t).canStepReturn();
			String r = serviceWith(launch(target(t))).step("out");
			assertEquals("Cannot step out from the current location.", r);
		}
	}

	// =============================================================================================
	// eval() / setVariable() argument + state guards (before the live script-eval cast)
	// =============================================================================================

	@Nested
	class EvalSetVariableGuards {
		@Test
		@DisplayName("eval rejects a blank expression")
		void evalBlankExpression() {
			assertEquals("Error: an expression to evaluate is required.", serviceWith().eval("   ", 0));
		}

		@Test
		@DisplayName("eval reports nothing-suspended when no frame is available")
		void evalNothingSuspended() {
			String r = serviceWith().eval("1 + 1", 0);
			assertTrue(r.startsWith("Nothing is suspended, so there is no frame to evaluate in."), r);
		}

		@Test
		@DisplayName("eval rejects a plain (non-script) suspended thread before touching the VM")
		void evalNonScriptThread() throws Exception {
			IThread t = suspendedThread(frame("x", 1));
			String r = serviceWith(launch(target(t))).eval("1 + 1", 0);
			assertEquals("The suspended thread is not a Servoy script thread; cannot evaluate an expression in it.", r);
		}

		@Test
		@DisplayName("setVariable rejects a blank variable name")
		void setVariableBlankName() {
			assertEquals("Error: a variableName is required.", serviceWith().setVariable("  ", "5", 0));
		}

		@Test
		@DisplayName("setVariable reports nothing-suspended when no frame is available")
		void setVariableNothingSuspended() {
			String r = serviceWith().setVariable("count", "5", 0);
			assertTrue(r.startsWith("Nothing is suspended, so there is no frame whose variable can be set."), r);
		}

		@Test
		@DisplayName("setVariable reports a missing variable before attempting the assignment eval")
		void setVariableNotFound() throws Exception {
			IStackFrame f = frame("fr", 1, variable("count", leafValue("Number", "5")));
			IThread t = suspendedThread(f);
			String r = serviceWith(launch(target(t))).setVariable("other", "7", 0);
			assertEquals("No variable named 'other' is visible in frame [0].", r);
		}
	}

	// =============================================================================================
	// setBreakpoint() / clearBreakpoint() / breakOnMethod() argument guards
	// =============================================================================================

	@Nested
	class BreakpointGuards {
		@Test
		@DisplayName("setBreakpoint rejects a blank file path")
		void setBreakpointBlankPath() {
			assertEquals("Error: a filePath is required.", serviceWith().setBreakpoint("  ", 5));
		}

		@Test
		@DisplayName("setBreakpoint rejects a non-positive line")
		void setBreakpointBadLine() {
			assertEquals("Error: a positive 1-based line number is required.", serviceWith().setBreakpoint("/sol/forms/main.js", 0));
		}

		@Test
		@DisplayName("clearBreakpoint rejects a blank file path")
		void clearBreakpointBlankPath() {
			assertEquals("Error: a filePath is required.", serviceWith().clearBreakpoint("", 5));
		}

		@Test
		@DisplayName("breakOnMethod rejects a blank address")
		void breakOnMethodBlank() {
			assertTrue(serviceWith().breakOnMethod("   ").startsWith("Error: a method address"), "blank address guard");
		}

		@Test
		@DisplayName("breakOnMethod rejects an address that is not forms/scopes.<x>.<y>")
		void breakOnMethodBadShape() {
			String r = serviceWith().breakOnMethod("forms.main");
			assertTrue(r.startsWith("Error: expected 'forms.<form>.<method>'"), r);
		}

		@Test
		@DisplayName("breakOnMethod rejects an unknown top-level segment")
		void breakOnMethodBadRoot() {
			String r = serviceWith().breakOnMethod("widgets.main.doIt");
			assertTrue(r.startsWith("Error: expected 'forms.<form>.<method>'"), r);
		}
	}

	// =============================================================================================
	// hasAnyDebugTarget() seam-driven predicate + end() when idle
	// =============================================================================================

	@Nested
	class TargetPredicates {
		@Test
		@DisplayName("hasAnyDebugTarget is false with no launches and true when a target exists")
		void hasAnyDebugTarget() throws Exception {
			assertEquals(Boolean.FALSE, invoke(serviceWith(), "hasAnyDebugTarget", new Class<?>[] {}), "no launches");
			IThread running = mock(IThread.class);
			doReturn(false).when(running).isSuspended();
			assertEquals(Boolean.TRUE, invoke(serviceWith(launch(target(running))), "hasAnyDebugTarget", new Class<?>[] {}), "one target");
		}

	}

	// ---------------------------------------------------------------------------------------------
	// SUCCESS-path support: a service whose findFirstSuspendedThread() seam is driven directly, so
	// resume/step/eval/setVariable run their full bodies against mocked threads without a live debug
	// session. The sleep()/deadline seams are overridden so no observation loop ever blocks on real
	// time; findFirstSuspendedThread() returns a programmable sequence so re-observation can land at
	// a new location, re-hit the same location, or run on (null).
	// ---------------------------------------------------------------------------------------------

	/**
	 * A {@link DebugInspectionService} whose suspended-thread seam returns successive values from a
	 * supplied sequence (the last entry sticks once exhausted), and whose timing seams are zeroed so
	 * the resume/step re-observation loops resolve on the first poll and never block on real time.
	 */
	private static final class SequencedService extends DebugInspectionService {
		private final IThread[] sequence;
		private int call = 0;

		private SequencedService(IThread... sequence) {
			this.sequence = sequence;
		}

		@Override
		protected IThread findFirstSuspendedThread() {
			int i = call < sequence.length ? call : sequence.length - 1;
			call++;
			return i >= 0 ? sequence[i] : null;
		}

		@Override
		protected void sleep(long ms) {
			// never block on real time in a unit test
		}

		@Override
		protected long transitionTimeoutMs() {
			return 0; // let the observation loop exit immediately when it would otherwise spin to the deadline
		}

		@Override
		protected long stepReturnGraceMs() {
			return 0; // conclude "returned/resumed" on the first not-suspended reading
		}
	}

	/** A suspended script thread with the given frames; frame[0] is the top. Mocks the concrete {@link IScriptThread}. */
	private static IScriptThread suspendedScriptThread(IStackFrame... frames) throws Exception {
		IScriptThread t = mock(IScriptThread.class);
		doReturn(true).when(t).isSuspended();
		doReturn(frames).when(t).getStackFrames();
		doReturn(frames != null && frames.length > 0 ? frames[0] : null).when(t).getTopStackFrame();
		doReturn(new IBreakpoint[0]).when(t).getBreakpoints();
		return t;
	}

	/** A script stack frame with a name and line, exposing the given variables. Mocks the concrete {@link IScriptStackFrame}. */
	private static IScriptStackFrame scriptFrame(String name, int line, IVariable... vars) throws Exception {
		IScriptStackFrame f = mock(IScriptStackFrame.class);
		doReturn(name).when(f).getName();
		doReturn(line).when(f).getLineNumber();
		doReturn(vars).when(f).getVariables();
		return f;
	}

	/**
	 * A script value leaf with the given value string. The engine's result exposes an {@link IScriptValue}
	 * (not a plain {@link IValue}), which production renders through its {@code instanceof IValue} branch.
	 */
	private static org.eclipse.dltk.debug.core.model.IScriptValue scriptLeafValue(String valueString) throws Exception {
		org.eclipse.dltk.debug.core.model.IScriptValue value = mock(org.eclipse.dltk.debug.core.model.IScriptValue.class);
		doReturn(valueString).when(value).getValueString();
		return value;
	}

	/** An evaluation result that succeeds, exposing the given script value (or null). */
	private static IScriptEvaluationResult okResult(org.eclipse.dltk.debug.core.model.IScriptValue value) throws Exception {
		IScriptEvaluationResult r = mock(IScriptEvaluationResult.class);
		doReturn(false).when(r).hasErrors();
		doReturn(value).when(r).getValue();
		return r;
	}

	/** An evaluation result that fails with the given messages. */
	private static IScriptEvaluationResult errorResult(String... messages) throws Exception {
		IScriptEvaluationResult r = mock(IScriptEvaluationResult.class);
		doReturn(true).when(r).hasErrors();
		doReturn(messages).when(r).getErrorMessages();
		return r;
	}

	// =============================================================================================
	// resume() success paths - the thread genuinely resumes, then is re-observed
	// =============================================================================================

	@Nested
	class ResumeSuccess {
		@Test
		@DisplayName("resume runs on when nothing is suspended afterwards")
		void resumeRunsOn() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canResume();
			// first observe the suspended thread, then the re-observation sees nothing suspended
			SequencedService svc = new SequencedService(first, null);
			String r = svc.resume();
			verify(first).resume();
			assertTrue(r.startsWith("Resumed (was at myForm.doIt line 42)"), r);
			assertTrue(r.contains("execution is now running (no longer suspended)"), r);
		}

		@Test
		@DisplayName("resume reports a fresh re-suspend at a different location")
		void resumeReSuspendsElsewhere() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canResume();
			IThread reSuspended = suspendedThread(frame("myForm.other", 99));
			SequencedService svc = new SequencedService(first, reSuspended);
			String r = svc.resume();
			verify(first).resume();
			assertTrue(r.contains("re-suspended at myForm.other line 99"), r);
			assertTrue(r.contains("a different breakpoint"), r);
		}

		@Test
		@DisplayName("resume reports a re-hit at the same location as a new call, not a failed resume")
		void resumeReHitsSameSpot() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canResume();
			// A fresh thread element at the SAME location (DLTK models each suspend as a new element).
			IThread reHit = suspendedThread(frame("myForm.doIt", 42));
			SequencedService svc = new SequencedService(first, reHit);
			String r = svc.resume();
			verify(first).resume();
			assertTrue(r.contains("hit again at the same location"), r);
			assertTrue(r.contains("NEW call"), r);
		}
	}

	// =============================================================================================
	// step() success paths - each mode calls the right stepX() and lands at the new location
	// =============================================================================================

	@Nested
	class StepSuccess {
		@Test
		@DisplayName("step into calls stepInto and reports the landed location")
		void stepIntoLands() throws Exception {
			IThread first = suspendedScriptThread(scriptFrame("myForm.doIt", 42));
			doReturn(true).when(first).canStepInto();
			IThread landed = suspendedThread(frame("myForm.inner", 7));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("into");
			verify(first).stepInto();
			assertTrue(r.startsWith("Stepped into, now at myForm.inner line 7"), r);
		}

		@Test
		@DisplayName("step in (alias) calls stepInto and lands")
		void stepInAliasLands() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepInto();
			IThread landed = suspendedThread(frame("myForm.inner", 8));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("in");
			verify(first).stepInto();
			assertTrue(r.startsWith("Stepped in, now at myForm.inner line 8"), r);
		}

		@Test
		@DisplayName("step over calls stepOver and reports the landed location")
		void stepOverLands() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepOver();
			IThread landed = suspendedThread(frame("myForm.doIt", 43));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("over");
			verify(first).stepOver();
			assertTrue(r.startsWith("Stepped over, now at myForm.doIt line 43"), r);
		}

		@Test
		@DisplayName("a null mode defaults to step over")
		void nullModeDefaultsToOver() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepOver();
			IThread landed = suspendedThread(frame("myForm.doIt", 43));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step(null);
			verify(first).stepOver();
			assertTrue(r.startsWith("Stepped over, now at"), r);
		}

		@Test
		@DisplayName("an unknown mode falls through to step over")
		void unknownModeDefaultsToOver() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepOver();
			IThread landed = suspendedThread(frame("myForm.doIt", 43));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("sideways");
			verify(first).stepOver();
			assertTrue(r.startsWith("Stepped sideways, now at"), r);
		}

		@Test
		@DisplayName("step out calls stepReturn and reports the landed location")
		void stepOutLands() throws Exception {
			IThread first = suspendedThread(frame("myForm.inner", 7));
			doReturn(true).when(first).canStepReturn();
			IThread landed = suspendedThread(frame("myForm.doIt", 43));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("out");
			verify(first).stepReturn();
			assertTrue(r.startsWith("Stepped out, now at myForm.doIt line 43"), r);
		}

		@Test
		@DisplayName("step return (alias) calls stepReturn and lands")
		void stepReturnAliasLands() throws Exception {
			IThread first = suspendedThread(frame("myForm.inner", 7));
			doReturn(true).when(first).canStepReturn();
			IThread landed = suspendedThread(frame("myForm.doIt", 44));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("return");
			verify(first).stepReturn();
			assertTrue(r.startsWith("Stepped return, now at myForm.doIt line 44"), r);
		}

		@Test
		@DisplayName("step next (alias) calls stepOver and lands")
		void stepNextAliasLands() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepOver();
			IThread landed = suspendedThread(frame("myForm.doIt", 43));
			SequencedService svc = new SequencedService(first, landed);
			String r = svc.step("next");
			verify(first).stepOver();
			assertTrue(r.startsWith("Stepped next, now at myForm.doIt line 43"), r);
		}

		@Test
		@DisplayName("a step that stays unsuspended reports the method returned")
		void stepReturnsOutOfMethod() throws Exception {
			IThread first = suspendedThread(frame("myForm.doIt", 42));
			doReturn(true).when(first).canStepOver();
			// re-observation stays null -> stayed not-suspended past the (zeroed) grace window
			SequencedService svc = new SequencedService(first, null);
			String r = svc.step("over");
			verify(first).stepOver();
			assertTrue(r.startsWith("Stepped over - the method returned / execution is no longer suspended."), r);
		}
	}

	// =============================================================================================
	// eval() success paths - a real script frame + evaluation engine
	// =============================================================================================

	@Nested
	class EvalSuccess {
		@Test
		@DisplayName("eval renders the evaluated value string")
		void evalRendersValue() throws Exception {
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42);
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(okResult(scriptLeafValue("17"))).when(engine).syncEvaluate("8 + 9", frame);
			String r = new SequencedService(thread).eval("8 + 9", 0);
			assertEquals("`8 + 9` = 17", r);
		}

		@Test
		@DisplayName("eval reports the engine's error messages when the evaluation fails")
		void evalReportsError() throws Exception {
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42);
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(errorResult("ReferenceError: x is not defined")).when(engine).syncEvaluate("x", frame);
			String r = new SequencedService(thread).eval("x", 0);
			assertEquals("Evaluation of `x` failed: ReferenceError: x is not defined", r);
		}

		@Test
		@DisplayName("eval reports no evaluation engine when the thread has none")
		void evalNoEngine() throws Exception {
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42);
			IScriptThread thread = suspendedScriptThread(frame);
			doReturn(null).when(thread).getEvaluationEngine();
			String r = new SequencedService(thread).eval("1 + 1", 0);
			assertEquals("The suspended thread has no evaluation engine available.", r);
		}

		@Test
		@DisplayName("eval reports a non-script frame before touching the engine")
		void evalNonScriptFrame() throws Exception {
			// a script THREAD but a plain (non-script) frame at the top
			IStackFrame plainFrame = frame("myForm.doIt", 42);
			IScriptThread thread = mock(IScriptThread.class);
			doReturn(true).when(thread).isSuspended();
			doReturn(new IStackFrame[] { plainFrame }).when(thread).getStackFrames();
			doReturn(plainFrame).when(thread).getTopStackFrame();
			String r = new SequencedService(thread).eval("1 + 1", 0);
			assertEquals("Frame [0] is not a script frame; cannot evaluate in it.", r);
		}

		@Test
		@DisplayName("eval clamps an out-of-range frame index back to the top frame")
		void evalClampsFrameIndex() throws Exception {
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42);
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(okResult(scriptLeafValue("1"))).when(engine).syncEvaluate("1", frame);
			String r = new SequencedService(thread).eval("1", 99);
			assertEquals("`1` = 1", r);
		}

		@Test
		@DisplayName("eval renders a null value as (null)")
		void evalNullValue() throws Exception {
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42);
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(okResult(null)).when(engine).syncEvaluate("undefinedThing", frame);
			String r = new SequencedService(thread).eval("undefinedThing", 0);
			assertEquals("`undefinedThing` = (null)", r);
		}
	}

	// =============================================================================================
	// setVariable() success paths - the in-frame assignment eval + readback
	// =============================================================================================

	@Nested
	class SetVariableSuccess {
		@Test
		@DisplayName("setVariable assigns via an in-frame '<name> = <value>' eval and confirms the readback")
		void setVariableAssignsAndConfirms() throws Exception {
			// the frame first reports count=5, then (after assignment) count=0 on re-read
			IVariable before = variable("count", leafValue("Number", "5"));
			IVariable after = variable("count", leafValue("Number", "0"));
			IScriptStackFrame frame = mock(IScriptStackFrame.class);
			doReturn("myForm.doIt").when(frame).getName();
			doReturn(42).when(frame).getLineNumber();
			doReturn(new IVariable[] { before }).doReturn(new IVariable[] { after }).when(frame).getVariables();
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(okResult(scriptLeafValue("0"))).when(engine).syncEvaluate("count = 0", frame);

			String r = new SequencedService(thread).setVariable("count", "0", 0);
			verify(engine).syncEvaluate("count = 0", frame);
			assertEquals("Set 'count' = 0 in frame [0] (typed via in-frame assignment).", r);
		}

		@Test
		@DisplayName("setVariable reports no evaluation engine without attempting the assignment")
		void setVariableNoEngine() throws Exception {
			IVariable count = variable("count", leafValue("Number", "5"));
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42, count);
			IScriptThread thread = suspendedScriptThread(frame);
			doReturn(null).when(thread).getEvaluationEngine();
			String r = new SequencedService(thread).setVariable("count", "0", 0);
			assertEquals("Cannot set 'count': no evaluation engine is available on the suspended thread.", r);
		}

		@Test
		@DisplayName("setVariable reports the assignment eval's error messages")
		void setVariableAssignmentError() throws Exception {
			IVariable count = variable("count", leafValue("Number", "5"));
			IScriptStackFrame frame = scriptFrame("myForm.doIt", 42, count);
			IScriptThread thread = suspendedScriptThread(frame);
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			doReturn(errorResult("SyntaxError: bad value")).when(engine).syncEvaluate("count = @@@", frame);
			String r = new SequencedService(thread).setVariable("count", "@@@", 0);
			assertEquals("Could not set 'count' = @@@: SyntaxError: bad value", r);
		}

		@Test
		@DisplayName("setVariable reports a non-script frame and never evaluates")
		void setVariableNonScriptFrame() throws Exception {
			// a script THREAD whose top frame is a plain (non-script) frame carrying the variable
			IVariable count = variable("count", leafValue("Number", "5"));
			IStackFrame plainFrame = frame("myForm.doIt", 42, count);
			IScriptThread thread = mock(IScriptThread.class);
			doReturn(true).when(thread).isSuspended();
			doReturn(new IStackFrame[] { plainFrame }).when(thread).getStackFrames();
			doReturn(plainFrame).when(thread).getTopStackFrame();
			IScriptEvaluationEngine engine = mock(IScriptEvaluationEngine.class);
			doReturn(engine).when(thread).getEvaluationEngine();
			String r = new SequencedService(thread).setVariable("count", "0", 0);
			assertEquals("Cannot set 'count': the suspended frame is not a Servoy script frame.", r);
			verify(engine, never()).syncEvaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
		}
	}
}
