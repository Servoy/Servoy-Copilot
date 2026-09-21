package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link JSUnitRunnerService#isTerminalRun(int, int, int)}, the pure
 * terminal-condition decision used by the poll loop.
 * <p>
 * SVY-21414: the whole JSUnit run's authoritative "done" signal is that every planned test has
 * reported a result — i.e. {@code startedCount >= totalCount} (with {@code total > 0}) — the same
 * numbers the Script Unit Test view shows as "Runs: started/total". This was confirmed by a logged
 * 658-test run: {@code started} climbed 23 → 70 → … → 658 while {@code total} stayed 658, and the
 * run was truly done exactly when {@code started == 658}. Two earlier signals were proven wrong and
 * are deliberately NOT used: {@code ProgressState.COMPLETED} (flips true at the end of each
 * sub-suite batch → returned 243 of 667 mid-run) and {@code launch.isTerminated()} (stays false for
 * 20+ s after the run finished → just times out). The {@code childCount > 0} guard preserves the
 * SVY-21241 fix (a freshly-created empty session reports {@code total=0} and must not be "done").
 */
public class JSUnitRunnerServiceTerminalConditionTest {

	// --- terminal success: children present AND all planned tests reported ---

	@Test
	public void testAllTestsReported_isTerminal() {
		assertTrue(JSUnitRunnerService.isTerminalRun(1, 658, 658));
	}

	@Test
	public void testStartedExceedsTotal_isTerminal() {
		// Defensive: started strictly greater than total still counts as done.
		assertTrue(JSUnitRunnerService.isTerminalRun(1, 659, 658));
	}

	@Test
	public void testSmallSuiteAllReported_isTerminal() {
		assertTrue(JSUnitRunnerService.isTerminalRun(1, 2, 2));
	}

	// --- NOT terminal: mid-run, some planned tests not yet reported (the SVY-21414 race) ---

	@Test
	public void testPartialFirstBatch_notTerminal() {
		// The old bug's snapshot: 243 of 658 reported. Must keep polling, not return partial.
		assertFalse(JSUnitRunnerService.isTerminalRun(1, 243, 658));
	}

	@Test
	public void testEarlyBatch_notTerminal() {
		assertFalse(JSUnitRunnerService.isTerminalRun(1, 23, 658));
	}

	@Test
	public void testOneShortOfTotal_notTerminal() {
		assertFalse(JSUnitRunnerService.isTerminalRun(1, 657, 658));
	}

	// --- NOT terminal: total not known yet (session created, tree not bridged) ---

	@Test
	public void testZeroTotal_notTerminal() {
		// Freshly-created session: total=0, started=0. COMPLETED-with-0 trap — never "done".
		assertFalse(JSUnitRunnerService.isTerminalRun(1, 0, 0));
	}

	@Test
	public void testZeroTotalWithStarted_notTerminal() {
		// Guards against started>=total being trivially true when total is still 0.
		assertFalse(JSUnitRunnerService.isTerminalRun(1, 5, 0));
	}

	// --- NOT terminal: 0 children regardless of counts (SVY-21241 preserved) ---

	@Test
	public void testZeroChildren_notTerminal() {
		assertFalse(JSUnitRunnerService.isTerminalRun(0, 658, 658));
	}

	@Test
	public void testNegativeChildren_notTerminal() {
		assertFalse(JSUnitRunnerService.isTerminalRun(-1, 658, 658));
	}
}
