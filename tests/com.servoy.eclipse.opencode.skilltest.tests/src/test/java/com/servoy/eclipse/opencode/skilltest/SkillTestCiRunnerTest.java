/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.headless.SkillTestHeadlessRunner;

/**
 * CI ignition for the REAL Servoy AI skill-test scenarios.
 * <p>
 * Not a unit test of the plugin: it is the trigger that lets a tycho-surefire PDE
 * launch boot a fully resolved Servoy workbench (from the target platform +
 * reactor, like the developer.mcp integration tests) and then run the real
 * {@link SkillTestHeadlessRunner} over the baseline scenarios cloned from the
 * servoy-ai-skilltest-baselines repository.
 * <p>
 * All the actual work - Cloud login, embedded web/opencode servers, loading the
 * baselines from {@code -Dservoy.skilltest.baselines}, per-baseline setup + replay
 * against the real LLM, outcome + JSUnit verification, and writing
 * {@code TEST-skilltest.xml} to {@code -Dservoy.skilltest.outputDir} - is done by
 * {@code SkillTestHeadlessRunner.runInWorkbench()}, the same engine the Servoy AI
 * Skill Tests view and the {@code -application} entry point use.
 * <p>
 * Guarded by {@code -Dservoy.skilltest.run=true} so it is inert in a normal build.
 */
public class SkillTestCiRunnerTest {

	@Test
	public void runSkillTestScenarios() {
		assumeTrue(Boolean.getBoolean("servoy.skilltest.run"), //$NON-NLS-1$
				"skipped: set -Dservoy.skilltest.run=true to run the real skill-test scenarios"); //$NON-NLS-1$

		int exit = new SkillTestHeadlessRunner().runInWorkbench();

		assertEquals(0, exit, "skill-test run exit code (0=pass, 1=diverged, 2=infra). " //$NON-NLS-1$
				+ "See TEST-skilltest.xml and the console for per-baseline detail."); //$NON-NLS-1$
	}
}
