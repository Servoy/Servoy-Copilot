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

package com.servoy.eclipse.opencode;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.ngclient.ui.IConsole;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

class ExportSessionJobTest {

	// ── static-helper tests (unchanged) ──────────────────────────────────

	@Nested
	class BuildExportCommandArgs {
		@Test
		@DisplayName("returns the correct argument list with session ID and --sanitize")
		void returnsCorrectArgs() {
			List<String> args = ExportSessionJob.buildExportCommandArgs("abc-123");
			assertEquals(List.of("exec", "--", "opencode", "export", "abc-123", "--sanitize"), args);
		}

		@Test
		@DisplayName("session ID appears at index 4")
		void sessionIdPosition() {
			List<String> args = ExportSessionJob.buildExportCommandArgs("sess-xyz");
			assertAll(() -> assertEquals(6, args.size()), () -> assertEquals("sess-xyz", args.get(4)),
					() -> assertEquals("--sanitize", args.get(5)));
		}

		@Test
		@DisplayName("preserves fixed prefix elements in order")
		void fixedPrefixOrder() {
			List<String> args = ExportSessionJob.buildExportCommandArgs("id");
			assertAll(() -> assertEquals("exec", args.get(0)), () -> assertEquals("--", args.get(1)),
					() -> assertEquals("opencode", args.get(2)), () -> assertEquals("export", args.get(3)));
		}
	}

	@Nested
	class StripNonJsonPreamble {
		@Test
		@DisplayName("returns text unchanged when it starts with '{'")
		void startsWithBrace() {
			String input = "{\"key\": \"value\"}";
			assertEquals(input, ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("returns text unchanged when it starts with '['")
		void startsWithBracket() {
			String input = "[1, 2, 3]";
			assertEquals(input, ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("strips npm banner line before a JSON object")
		void stripsBannerBeforeBrace() {
			String input = "npm warn exec some banner text\n{\"export\": true}";
			assertEquals("{\"export\": true}", ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("strips npm banner line before a JSON array")
		void stripsBannerBeforeBracket() {
			String input = "npm warn exec some banner text\n[1, 2]";
			assertEquals("[1, 2]", ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("picks earliest JSON marker when both '{' and '[' present")
		void bracketBeforeBrace() {
			String input = "preamble [{ inner }]";
			assertEquals("[{ inner }]", ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("picks earliest JSON marker when '{' comes before '['")
		void braceBeforeBracket() {
			String input = "preamble {\"a\": [1]}";
			assertEquals("{\"a\": [1]}", ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("returns text unchanged when no JSON marker is present")
		void noJsonMarker() {
			String input = "plain text with no json";
			assertEquals(input, ExportSessionJob.stripNonJsonPreamble(input));
		}

		@ParameterizedTest
		@ValueSource(strings = { "{}", "[]", "{\"a\":1}", "[null]" })
		@DisplayName("already-valid JSON is returned as-is")
		void alreadyValidJson(String input) {
			assertEquals(input, ExportSessionJob.stripNonJsonPreamble(input));
		}

		@Test
		@DisplayName("multi-line banner is stripped up to the JSON start")
		void multiLineBanner() {
			String input = "line1\nline2\nline3\n{\"ok\": true}";
			assertEquals("{\"ok\": true}", ExportSessionJob.stripNonJsonPreamble(input));
		}
	}

	// ── run() integration tests using test seams + stubs ─────────────────

	@Nested
	class RunMethod {
		@TempDir
		Path tempDir;

		private Activator previousInstance;
		private Activator activator;
		private RecordingConsole recordingConsole;

		@BeforeEach
		void setUp() throws Exception {
			previousInstance = Activator.getInstance();

			activator = new Activator();
			recordingConsole = new RecordingConsole();

			Field instanceField = Activator.class.getDeclaredField("instance");
			instanceField.setAccessible(true);
			instanceField.set(null, activator);

			Field consoleField = Activator.class.getDeclaredField("aiConsole");
			consoleField.setAccessible(true);
			consoleField.set(activator, recordingConsole);

			ExportSessionJob.testNotifyUiSuppressed = true;
		}

		@AfterEach
		void tearDown() throws Exception {
			ExportSessionJob.testCommandFactory = null;
			ExportSessionJob.testNotifyUiSuppressed = false;

			Field instanceField = Activator.class.getDeclaredField("instance");
			instanceField.setAccessible(true);
			instanceField.set(null, previousInstance);
		}

		@Test
		@DisplayName("happy path: command succeeds → JSON written to target file")
		void happyPath_writesJsonToFile() throws Exception {
			String expectedJson = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
			File targetFile = tempDir.resolve("export.json").toFile();
			StubNpmCommand stub = new StubNpmCommand(expectedJson, 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-001", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK(), "status should be OK"),
					() -> assertTrue(targetFile.exists(), "target file should exist"),
					() -> assertEquals(expectedJson,
							Files.readString(targetFile.toPath(), StandardCharsets.UTF_8)));
		}

		@Test
		@DisplayName("happy path: npm preamble is stripped before writing")
		void happyPath_stripsPreamble() throws Exception {
			String rawOutput = "npm warn exec Installed\n{\"session\":\"data\"}";
			File targetFile = tempDir.resolve("export.json").toFile();
			StubNpmCommand stub = new StubNpmCommand(rawOutput, 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-002", targetFile);
			job.run(new NullProgressMonitor());

			assertEquals("{\"session\":\"data\"}",
					Files.readString(targetFile.toPath(), StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("happy path: session ID is forwarded to command args")
		void happyPath_sessionIdInArgs() {
			StubNpmCommand stub = new StubNpmCommand("{}", 0);
			List<List<String>> capturedArgs = new ArrayList<>();

			ExportSessionJob.testCommandFactory = (workDir, args) -> {
				capturedArgs.add(args);
				return stub;
			};

			File targetFile = tempDir.resolve("out.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "my-session-42", targetFile);
			job.run(new NullProgressMonitor());

			assertAll(
					() -> assertEquals(1, capturedArgs.size()),
					() -> assertTrue(capturedArgs.get(0).contains("my-session-42"),
							"args should contain the session ID"));
		}

		@Test
		@DisplayName("happy path: environment includes PWD and XDG vars")
		void happyPath_environmentIsSet() throws Exception {
			StubNpmCommand stub = new StubNpmCommand("{}", 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("out.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/my/project", 8080, "sess-003", targetFile);
			job.run(new NullProgressMonitor());

			assertAll(
					() -> assertEquals("/my/project", stub.environment.get("PWD")),
					() -> assertTrue(stub.environment.containsKey("XDG_CONFIG_HOME"),
							"should set XDG_CONFIG_HOME"));
		}

		@Test
		@DisplayName("happy path: output is tee'd to console stream")
		void happyPath_outputDelegatedToConsole() throws Exception {
			String output = "{\"exported\":true}";
			StubNpmCommand stub = new StubNpmCommand(output, 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("out.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-004", targetFile);
			job.run(new NullProgressMonitor());

			assertTrue(recordingConsole.stream.allWrites.stream()
							.anyMatch(w -> w.contains(output)),
					"captured output should be delegated to the console stream");
		}

		@Test
		@DisplayName("happy path: 'Session exported to' is logged on success")
		void happyPath_logsExportPath() throws Exception {
			StubNpmCommand stub = new StubNpmCommand("{}", 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-005", targetFile);
			job.run(new NullProgressMonitor());

			assertTrue(recordingConsole.stream.allWrites.stream()
							.anyMatch(w -> w.contains("Session exported to:")),
					"should log the export path to console");
		}

		@Test
		@DisplayName("no session ID and no tracked ID → returns OK, no file written")
		void noSessionId_noFileWritten() {
			File targetFile = tempDir.resolve("export.json").toFile();

			// trackedSessionId is null; OpenCodeUtil.findLastSessionId will
			// fail (no server running) and return null
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 99999, null, targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK()),
					() -> assertFalse(targetFile.exists(), "no file should be created"));
		}

		@Test
		@DisplayName("non-zero exit code → returns OK, no file written, error logged")
		void nonZeroExit_noFileWritten() {
			StubNpmCommand stub = new StubNpmCommand("some output", 1);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-err", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK()),
					() -> assertFalse(targetFile.exists(), "no file should be written on non-zero exit"),
					() -> assertTrue(recordingConsole.stream.allWrites.stream()
									.anyMatch(w -> w.contains("Export error:")),
							"should log an error via notifyUi"));
		}

		@Test
		@DisplayName("command throws IOException → returns OK, error logged")
		void ioException_errorLogged() {
			StubNpmCommand stub = new StubNpmCommand(null, 0);
			stub.throwOnRun = new IOException("connection refused");

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-io", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK()),
					() -> assertFalse(targetFile.exists()),
					() -> assertTrue(recordingConsole.stream.allWrites.stream()
									.anyMatch(w -> w.contains("connection refused")),
							"should log the IOException message"));
		}

		@Test
		@DisplayName("command throws InterruptedException → returns OK, error logged")
		void interruptedException_errorLogged() {
			StubNpmCommand stub = new StubNpmCommand(null, 0);
			stub.throwOnRun = new InterruptedException("cancelled");

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-int", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK()),
					() -> assertFalse(targetFile.exists()));
		}

		@Test
		@DisplayName("target file in non-existent directory → write fails gracefully")
		void writeFailure_errorLogged() {
			StubNpmCommand stub = new StubNpmCommand("{\"data\":1}", 0);

			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			// Point to a path inside a read-only / non-existent nested dir
			File targetFile = tempDir.resolve("no-such-dir/deep/export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-wf", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertTrue(status.isOK()),
					() -> assertFalse(targetFile.exists()),
					() -> assertTrue(recordingConsole.stream.allWrites.stream()
									.anyMatch(w -> w.contains("Failed to write export file")),
							"should log the write-failure message"));
		}

		@Test
		@DisplayName("Activator not available → returns ERROR status")
		void activatorNull_returnsError() throws Exception {
			// Clear the activator instance
			Field instanceField = Activator.class.getDeclaredField("instance");
			instanceField.setAccessible(true);
			instanceField.set(null, null);

			StubNpmCommand stub = new StubNpmCommand("{}", 0);
			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-na", targetFile);
			IStatus status = job.run(new NullProgressMonitor());

			assertAll(
					() -> assertEquals(IStatus.ERROR, status.getSeverity()),
					() -> assertFalse(targetFile.exists()));
		}

		@Test
		@DisplayName("large multi-chunk output is fully captured and written")
		void largeOutput_fullyWritten() throws Exception {
			StringBuilder bigJson = new StringBuilder("{\"items\":[");
			for (int i = 0; i < 1000; i++) {
				if (i > 0) bigJson.append(',');
				bigJson.append("{\"id\":").append(i).append('}');
			}
			bigJson.append("]}");
			String expectedJson = bigJson.toString();

			StubNpmCommand stub = new StubNpmCommand(expectedJson, 0);
			ExportSessionJob.testCommandFactory = (workDir, args) -> stub;

			File targetFile = tempDir.resolve("big-export.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					tempDir.toFile(), "/project", 8080, "sess-big", targetFile);
			job.run(new NullProgressMonitor());

			assertEquals(expectedJson,
					Files.readString(targetFile.toPath(), StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("workDir passed to command factory matches opencodeDir")
		void workDir_matchesOpencodeDir() {
			File opencodeDir = tempDir.resolve("opencode-install").toFile();
			opencodeDir.mkdirs();
			List<File> capturedDirs = new ArrayList<>();

			StubNpmCommand stub = new StubNpmCommand("{}", 0);
			ExportSessionJob.testCommandFactory = (workDir, args) -> {
				capturedDirs.add(workDir);
				return stub;
			};

			File targetFile = tempDir.resolve("out.json").toFile();
			ExportSessionJob job = new ExportSessionJob(
					opencodeDir, "/project", 8080, "sess-wd", targetFile);
			job.run(new NullProgressMonitor());

			assertEquals(opencodeDir, capturedDirs.get(0));
		}
	}

	// ── CapturingOutputStream tests via reflection ───────────────────────

	@Nested
	class CapturingOutputStreamTests {

		@Test
		@DisplayName("captures written content into the StringBuilder")
		void capturesContent() throws Exception {
			StringBuilder captured = new StringBuilder();
			StringOutputStream cos = createCapturingOutputStream(captured, null);

			cos.write("hello ");
			cos.write("world");

			assertEquals("hello world", captured.toString());
		}

		@Test
		@DisplayName("delegates written content to secondary stream")
		void delegatesToSecondary() throws Exception {
			StringBuilder captured = new StringBuilder();
			RecordingStringStream delegate = new RecordingStringStream();
			StringOutputStream cos = createCapturingOutputStream(captured, delegate);

			cos.write("data");

			assertAll(
					() -> assertEquals("data", captured.toString()),
					() -> assertEquals(1, delegate.allWrites.size()),
					() -> assertEquals("data", delegate.allWrites.get(0)));
		}

		@Test
		@DisplayName("handles null delegate without error")
		void nullDelegate_noError() throws Exception {
			StringBuilder captured = new StringBuilder();
			StringOutputStream cos = createCapturingOutputStream(captured, null);

			cos.write("safe");
			cos.close();

			assertEquals("safe", captured.toString());
		}

		@Test
		@DisplayName("close propagates to delegate")
		void closePropagates() throws Exception {
			StringBuilder captured = new StringBuilder();
			RecordingStringStream delegate = new RecordingStringStream();
			StringOutputStream cos = createCapturingOutputStream(captured, delegate);

			cos.close();

			assertEquals(1, delegate.closeCount);
		}

		@Test
		@DisplayName("close with null delegate does not throw")
		void closeNullDelegate_noThrow() throws Exception {
			StringBuilder captured = new StringBuilder();
			StringOutputStream cos = createCapturingOutputStream(captured, null);

			cos.close(); // should not throw
		}

		@Test
		@DisplayName("multiple writes accumulate in the StringBuilder")
		void multipleWrites_accumulate() throws Exception {
			StringBuilder captured = new StringBuilder();
			StringOutputStream cos = createCapturingOutputStream(captured, null);

			cos.write("line1\n");
			cos.write("line2\n");
			cos.write("line3\n");

			assertEquals("line1\nline2\nline3\n", captured.toString());
		}

		/**
		 * Creates a CapturingOutputStream (private inner class) via reflection.
		 */
		private StringOutputStream createCapturingOutputStream(
				StringBuilder captured, StringOutputStream delegate) throws Exception {
			Class<?>[] innerClasses = ExportSessionJob.class.getDeclaredClasses();
			Class<?> cosClass = null;
			for (Class<?> c : innerClasses) {
				if (c.getSimpleName().equals("CapturingOutputStream")) {
					cosClass = c;
					break;
				}
			}
			if (cosClass == null) {
				throw new AssertionError("CapturingOutputStream inner class not found");
			}
			var ctor = cosClass.getDeclaredConstructor(StringBuilder.class, StringOutputStream.class);
			ctor.setAccessible(true);
			return (StringOutputStream) ctor.newInstance(captured, delegate);
		}
	}

	// ── test doubles ─────────────────────────────────────────────────────

	/**
	 * Stub {@link IRunNPMCommand} that writes canned output to the configured
	 * output stream and returns a preset exit code.
	 */
	private static class StubNpmCommand implements IRunNPMCommand {
		private final String output;
		private final int exitCode;
		private StringOutputStream outputStream;
		Map<String, String> environment;
		Exception throwOnRun;

		StubNpmCommand(String output, int exitCode) {
			this.output = output;
			this.exitCode = exitCode;
		}

		@Override
		public void runCommand(IProgressMonitor monitor) throws IOException, InterruptedException {
			if (throwOnRun instanceof IOException ioe) throw ioe;
			if (throwOnRun instanceof InterruptedException ie) throw ie;
			if (output != null && outputStream != null) {
				outputStream.write(output);
			}
		}

		@Override
		public int getExitCode() {
			return exitCode;
		}

		@Override
		public void setOutputStream(StringOutputStream stream) {
			this.outputStream = stream;
		}

		@Override
		public void setExtraEnvironment(Map<String, String> env) {
			this.environment = env;
		}

		@Override public void setUser(boolean b) { }
		@Override public void schedule() { }
		@Override public void join() { }
		@Override public Process getProcess() { return null; }
		@Override public boolean cancel() { return false; }
	}

	private static class RecordingConsole implements IConsole {
		final RecordingStringStream stream = new RecordingStringStream();

		@Override
		public StringOutputStream outputStream() {
			return stream;
		}
	}

	private static class RecordingStringStream implements StringOutputStream {
		final List<String> allWrites = new ArrayList<>();
		int closeCount;

		@Override
		public void write(CharSequence chars) throws IOException {
			allWrites.add(chars.toString());
		}

		@Override
		public void close() throws IOException {
			closeCount++;
		}
	}
}
