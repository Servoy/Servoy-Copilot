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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.testgeneration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.Platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.util.ServoyLog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * MCP tool that reads the JSUnit coverage JSON report produced by JSUnitCoverageWriter
 * and exposes it to the AI assistant in two forms:
 * <ul>
 *   <li>{@link #getJSUnitCoverageReport} â compact markdown summary of covered/uncovered lines</li>
 *   <li>{@link #suggestTestsFromCoverage} â concrete test suggestions for uncovered lines</li>
 * </ul>
 */
public interface IJSUnitCoverageTool
{
	/** Default file name written by JSUnitCoverageWriter inside the workspace root. */
	String DEFAULT_COVERAGE_FILE = "jsunit-coverage.json";

	@Tool("Reads the latest JSUnit test coverage JSON report and returns a markdown summary of " +
		"covered and uncovered lines per scope and function. Run JSUnit tests first (in debug mode) to generate the report. " +
		"Use this before asking for test suggestions to understand the current coverage state.")
	default String getJSUnitCoverageReport(
		@P(value = "Absolute path to the coverage JSON file. If omitted, uses ${workspace}/jsunit-coverage.json.", required = false) String coveragePath)
	{
		try
		{
			File file = resolveCoverageFile(coveragePath);
			if (!file.exists())
			{
				return "No coverage report found at " + file.getAbsolutePath() +
					". Run JSUnit tests in debug mode first to generate the report.";
			}

			JsonNode root = readJson(file);
			if (root == null) return "Error: could not parse coverage JSON at " + file.getAbsolutePath();

			StringBuilder sb = new StringBuilder();
			sb.append("## JSUnit Coverage Report\n\n");
			sb.append("**Solution:** ").append(textOf(root, "solution")).append("\n");
			sb.append("**Timestamp:** ").append(textOf(root, "timestamp")).append("\n");

			JsonNode summary = root.path("summary");
			int covered = summary.path("coveredLines").asInt(0);
			int uncovered = summary.path("uncoveredLines").asInt(0);
			int total = covered + uncovered;
			double pct = total > 0 ? (100.0 * covered / total) : 0.0;
			sb.append(String.format("**Coverage:** %d/%d lines (%.1f%%)\n\n", covered, total, pct));

			appendSectionSummary(sb, root.path("scopes"), "Scopes");
			appendSectionSummary(sb, root.path("forms"), "Forms");

			sb.append("\n_File: ").append(file.getAbsolutePath()).append("_\n");
			return sb.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error reading JSUnit coverage report", e);
			return "Error reading coverage report: " + e.getMessage();
		}
	}

	@Tool("Reads the latest JSUnit coverage report and returns concrete suggestions for additional " +
		"test cases targeting the least-covered functions. Does NOT write any test files. " +
		"Run JSUnit tests first (in debug mode) to generate the report.")
	default String suggestTestsFromCoverage(
		@P(value = "Absolute path to the coverage JSON file. If omitted, uses ${workspace}/jsunit-coverage.json.", required = false) String coveragePath,
		@P(value = "Maximum number of functions to report on, sorted by uncovered line count descending. Default: 20.", required = false) int maxFunctions)
	{
		try
		{
			File file = resolveCoverageFile(coveragePath);
			if (!file.exists())
			{
				return "No coverage report found at " + file.getAbsolutePath() +
					". Run JSUnit tests in debug mode first to generate the report.";
			}

			JsonNode root = readJson(file);
			if (root == null) return "Error: could not parse coverage JSON at " + file.getAbsolutePath();

			int limit = maxFunctions > 0 ? maxFunctions : 20;

			// collect all functions with uncovered lines, across scopes and forms
			List<FunctionCoverage> candidates = new ArrayList<>();
			collectFunctions(root.path("scopes"), "scope", candidates);
			collectFunctions(root.path("forms"), "form", candidates);

			// sort by uncovered line count descending
			candidates.sort(Comparator.comparingInt(FunctionCoverage::uncoveredCount).reversed());

			if (candidates.isEmpty())
			{
				return "All functions have full coverage â no uncovered lines found.";
			}

			List<FunctionCoverage> top = candidates.subList(0, Math.min(limit, candidates.size()));

			StringBuilder sb = new StringBuilder();
			sb.append("## JSUnit Test Suggestions\n\n");
			sb.append("**Solution:** ").append(textOf(root, "solution")).append("\n");
			sb.append("Based on ").append(candidates.size()).append(" function(s) with uncovered lines");
			if (candidates.size() > limit) sb.append(" (showing top ").append(limit).append(")");
			sb.append(":\n\n");

			for (FunctionCoverage fc : top)
			{
				sb.append("### `").append(fc.scopeName).append(".").append(fc.functionName).append("`");
				sb.append(" (").append(fc.type).append(")\n");
				sb.append("- Uncovered lines: ").append(formatLineList(fc.uncoveredLines)).append("\n");
				sb.append("- Covered lines: ").append(fc.coveredLines.size()).append("\n");
				sb.append("- **Suggestion:** Write a test that exercises lines ")
					.append(formatLineList(fc.uncoveredLines))
					.append(" in `").append(fc.scopeName).append("`. ")
					.append("Consider edge cases, null inputs, and error paths that would reach those lines.\n\n");
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error generating JSUnit test suggestions from coverage", e);
			return "Error generating test suggestions: " + e.getMessage();
		}
	}

	// ---- helpers ----

	private static File resolveCoverageFile(String coveragePath)
	{
		if (coveragePath != null && !coveragePath.isBlank())
		{
			return new File(coveragePath);
		}
		return new File(Platform.getLocation().toOSString() + File.separator + DEFAULT_COVERAGE_FILE);
	}

	private static JsonNode readJson(File file) throws IOException
	{
		String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		return new ObjectMapper().readTree(content);
	}

	private static String textOf(JsonNode node, String field)
	{
		JsonNode f = node.path(field);
		return f.isMissingNode() ? "(unknown)" : f.asText("(unknown)");
	}

	private static void appendSectionSummary(StringBuilder sb, JsonNode section, String label)
	{
		if (section.isMissingNode() || !section.isArray() || section.size() == 0) return;

		sb.append("### ").append(label).append("\n\n");
		sb.append("| Scope | Functions | Covered | Uncovered |\n");
		sb.append("|-------|-----------|---------|----------|\n");

		for (JsonNode scope : section)
		{
			String name = scope.path("name").asText("?");
			int funcs = 0, cov = 0, uncov = 0;
			for (JsonNode fn : scope.path("functions"))
			{
				funcs++;
				cov += fn.path("coveredLines").size();
				uncov += fn.path("uncoveredLines").size();
			}
			sb.append("| `").append(name).append("` | ").append(funcs)
				.append(" | ").append(cov).append(" | ").append(uncov).append(" |\n");
		}
		sb.append("\n");
	}

	private static void collectFunctions(JsonNode section, String type, List<FunctionCoverage> out)
	{
		if (section.isMissingNode() || !section.isArray()) return;
		for (JsonNode scope : section)
		{
			String scopeName = scope.path("name").asText("?");
			for (JsonNode fn : scope.path("functions"))
			{
				List<Integer> uncovered = new ArrayList<>();
				List<Integer> covered = new ArrayList<>();
				for (JsonNode n : fn.path("uncoveredLines"))
					uncovered.add(n.asInt());
				for (JsonNode n : fn.path("coveredLines"))
					covered.add(n.asInt());
				if (!uncovered.isEmpty())
				{
					out.add(new FunctionCoverage(type, scopeName, fn.path("name").asText("?"), covered, uncovered));
				}
			}
		}
	}

	private static String formatLineList(List<Integer> lines)
	{
		if (lines.isEmpty()) return "(none)";
		if (lines.size() <= 10) return lines.toString();
		return lines.subList(0, 10).toString().replace("]", "") + ", ... +" + (lines.size() - 10) + " more]";
	}

	/** Simple data holder for a function's coverage data. */
	class FunctionCoverage
	{
		final String type;
		final String scopeName;
		final String functionName;
		final List<Integer> coveredLines;
		final List<Integer> uncoveredLines;

		FunctionCoverage(String type, String scopeName, String functionName,
			List<Integer> coveredLines, List<Integer> uncoveredLines)
		{
			this.type = type;
			this.scopeName = scopeName;
			this.functionName = functionName;
			this.coveredLines = coveredLines;
			this.uncoveredLines = uncoveredLines;
		}

		int uncoveredCount()
		{
			return uncoveredLines.size();
		}
	}
}
