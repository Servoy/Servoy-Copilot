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

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import com.servoy.eclipse.opencode.skilltest.headless.JUnitXmlReporterTest;
import com.servoy.eclipse.opencode.skilltest.headless.SkillTestArgumentChestTest;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertionsTest;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeInferenceTest;

/**
 * Aggregate suite of the <b>plain unit</b> skill-test tests — pure logic, no
 * workbench, no LLM. Run this one class to run them all (JUnit Platform
 * {@code @Suite}).
 * <p>
 * The opt-in, real-LLM integration tests ({@code SkillScenarioIntegrationTest} /
 * {@code AbstractSkillScenarioTest}) are deliberately NOT included: they need the
 * running workbench + embedded Servoy AI server and only fire when
 * {@code -Dservoy.ai.skilltests=true} (+ the GENAI/skills env) is set. Keeping
 * this suite unit-only makes it a fast, deterministic, free "run all".
 * <p>
 * Run in the IDE via the JUnit launcher. (A JUnit Platform {@code @Suite} cannot
 * be expanded by Tycho surefire, so if these are ever wired into a Maven build
 * the pom must enumerate the concrete classes in {@code <test>} rather than this
 * suite — same convention as {@code com.servoy.eclipse.developer.mcp.tests}.)
 */
@Suite
@SelectClasses({
	ArgMatcherTest.class,
	BaselineLoaderTest.class,
	JsUnitVerifierTest.class,
	McpToolCallTest.class,
	ScopeForSolutionPathTest.class,
	SessionTranscriptTest.class,
	SkillTestResultTest.class,
	TranscriptComparatorTest.class,
	OutcomeAssertionsTest.class,
	OutcomeInferenceTest.class,
	SkillTestArgumentChestTest.class,
	JUnitXmlReporterTest.class
})
public class AllSkillTestUnitTests {
}
