package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JSUnitCoverageServiceTest {
	private JSUnitCoverageService service;
	private Path tempDir;

	@Before
	public void setUp() throws Exception {
		service = new JSUnitCoverageService();
		tempDir = Files.createTempDirectory("coverageTest");
	}

	@After
	public void tearDown() throws Exception {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception e) {
				}
			});
		}
	}

	private Path writeCoverageJson(String content) throws IOException {
		Path file = tempDir.resolve("jsunit-coverage.json");
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	// --- getCoverageReport ---

	@Test
	public void testGetCoverageReport_fileNotFound_returnsErrorMessage() {
		String result = service.getCoverageReport("/nonexistent/path/coverage.json");
		assertNotNull(result);
		assertTrue(result.contains("No coverage report found"));
	}

	@Test
	public void testGetCoverageReport_nullPath_usesDefault() {
		String result = service.getCoverageReport(null);
		assertNotNull(result);
		assertTrue(result.contains("No coverage report found") || result.contains("JSUnit Coverage Report")
				|| result.contains("Error reading coverage report"));
	}

	@Test
	public void testGetCoverageReport_emptyPath_usesDefault() {
		String result = service.getCoverageReport("  ");
		assertNotNull(result);
		assertTrue(result.contains("No coverage report found") || result.contains("JSUnit Coverage Report")
				|| result.contains("Error reading coverage report"));
	}

	@Test
	public void testGetCoverageReport_validJson_returnsSummary() throws IOException {
		String json = """
				{
				  "solution": "testSolution",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 10, "uncoveredLines": 5 },
				  "scopes": [
				    {
				      "name": "myScope",
				      "functions": [
				        { "name": "doStuff", "coveredLines": [1,2,3], "uncoveredLines": [4,5] }
				      ]
				    }
				  ],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.getCoverageReport(file.toString());

		assertTrue(result.contains("## JSUnit Coverage Report"));
		assertTrue(result.contains("testSolution"));
		assertTrue(result.contains("10/15 lines"));
		assertTrue(result.contains("66.7%"));
		assertTrue(result.contains("myScope"));
	}

	@Test
	public void testGetCoverageReport_zeroCoverage() throws IOException {
		String json = """
				{
				  "solution": "emptySolution",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 0, "uncoveredLines": 0 },
				  "scopes": [],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.getCoverageReport(file.toString());

		assertTrue(result.contains("0/0 lines"));
		assertTrue(result.contains("0.0%"));
	}

	@Test
	public void testGetCoverageReport_invalidJson_returnsError() throws IOException {
		Path file = writeCoverageJson("not valid json {{{");
		String result = service.getCoverageReport(file.toString());

		assertTrue(result.contains("Error"));
	}

	@Test
	public void testGetCoverageReport_formsSection() throws IOException {
		String json = """
				{
				  "solution": "formSolution",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 5, "uncoveredLines": 3 },
				  "scopes": [],
				  "forms": [
				    {
				      "name": "myForm",
				      "functions": [
				        { "name": "onLoad", "coveredLines": [1,2,3,4,5], "uncoveredLines": [6,7,8] }
				      ]
				    }
				  ]
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.getCoverageReport(file.toString());

		assertTrue(result.contains("### Forms"));
		assertTrue(result.contains("myForm"));
	}

	// --- suggestTests ---

	@Test
	public void testSuggestTests_fileNotFound_returnsErrorMessage() {
		String result = service.suggestTests("/nonexistent/path/coverage.json", 10);
		assertNotNull(result);
		assertTrue(result.contains("No coverage report found"));
	}

	@Test
	public void testSuggestTests_allCovered_returnsNothingToSuggest() throws IOException {
		String json = """
				{
				  "solution": "fullCoverage",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 10, "uncoveredLines": 0 },
				  "scopes": [
				    {
				      "name": "myScope",
				      "functions": [
				        { "name": "fn1", "coveredLines": [1,2,3], "uncoveredLines": [] }
				      ]
				    }
				  ],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 10);

		assertTrue(result.contains("All functions have full coverage"));
	}

	@Test
	public void testSuggestTests_uncoveredFunctions_returnsSuggestions() throws IOException {
		String json = """
				{
				  "solution": "partialCoverage",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 5, "uncoveredLines": 10 },
				  "scopes": [
				    {
				      "name": "scopeA",
				      "functions": [
				        { "name": "fnX", "coveredLines": [1,2], "uncoveredLines": [3,4,5,6,7] },
				        { "name": "fnY", "coveredLines": [10], "uncoveredLines": [11,12,13] }
				      ]
				    }
				  ],
				  "forms": [
				    {
				      "name": "formB",
				      "functions": [
				        { "name": "onShow", "coveredLines": [], "uncoveredLines": [1,2] }
				      ]
				    }
				  ]
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 20);

		assertTrue(result.contains("## JSUnit Test Suggestions"));
		assertTrue(result.contains("scopeA.fnX"));
		assertTrue(result.contains("scopeA.fnY"));
		assertTrue(result.contains("formB.onShow"));
		assertTrue(result.contains("Suggestion:"));
	}

	@Test
	public void testSuggestTests_sortedByUncoveredCountDescending() throws IOException {
		String json = """
				{
				  "solution": "sortTest",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 0, "uncoveredLines": 10 },
				  "scopes": [
				    {
				      "name": "s1",
				      "functions": [
				        { "name": "small", "coveredLines": [], "uncoveredLines": [1,2] },
				        { "name": "large", "coveredLines": [], "uncoveredLines": [1,2,3,4,5,6,7,8] }
				      ]
				    }
				  ],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 20);

		int largeIdx = result.indexOf("s1.large");
		int smallIdx = result.indexOf("s1.small");
		assertTrue("large should appear before small", largeIdx < smallIdx);
	}

	@Test
	public void testSuggestTests_respectsMaxFunctions() throws IOException {
		String json = """
				{
				  "solution": "maxTest",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 0, "uncoveredLines": 10 },
				  "scopes": [
				    {
				      "name": "s1",
				      "functions": [
				        { "name": "fn1", "coveredLines": [], "uncoveredLines": [1,2,3] },
				        { "name": "fn2", "coveredLines": [], "uncoveredLines": [4,5] },
				        { "name": "fn3", "coveredLines": [], "uncoveredLines": [6] }
				      ]
				    }
				  ],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 1);

		assertTrue(result.contains("s1.fn1"));
		assertTrue(result.contains("showing top 1"));
		assertTrue(!result.contains("s1.fn3"));
	}

	@Test
	public void testSuggestTests_defaultMaxIs20() throws IOException {
		String json = """
				{
				  "solution": "defaultMax",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 0, "uncoveredLines": 5 },
				  "scopes": [
				    {
				      "name": "s1",
				      "functions": [
				        { "name": "fn1", "coveredLines": [], "uncoveredLines": [1,2] }
				      ]
				    }
				  ],
				  "forms": []
				}
				""";
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 0);

		assertTrue(result.contains("s1.fn1"));
	}

	@Test
	public void testSuggestTests_moreLineTruncation() throws IOException {
		StringBuilder uncoveredArray = new StringBuilder("[");
		for (int i = 1; i <= 15; i++) {
			if (i > 1) uncoveredArray.append(",");
			uncoveredArray.append(i);
		}
		uncoveredArray.append("]");

		String json = """
				{
				  "solution": "truncTest",
				  "timestamp": "2026-06-09T10:00:00Z",
				  "summary": { "coveredLines": 0, "uncoveredLines": 15 },
				  "scopes": [
				    {
				      "name": "s1",
				      "functions": [
				        { "name": "bigFn", "coveredLines": [], "uncoveredLines": %s }
				      ]
				    }
				  ],
				  "forms": []
				}
				""".formatted(uncoveredArray.toString());
		Path file = writeCoverageJson(json);
		String result = service.suggestTests(file.toString(), 10);

		assertTrue(result.contains("... +5 more"));
	}
}
