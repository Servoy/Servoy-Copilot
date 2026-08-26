package com.servoy.eclipse.opencode;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.ngclient.ui.IConsole;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

class ActivatorConsoleTest {
	private Activator activator;
	private RecordingConsole recordingConsole;
	private Activator previousInstance;

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
	}

	@AfterEach
	void tearDown() throws Exception {
		Field instanceField = Activator.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		instanceField.set(null, previousInstance);
	}

	@Nested
	class LogToConsole {
		@Test
		@DisplayName("prepends [Servoy AI] prefix to message")
		void prependsPrefix() {
			activator.logToConsole("server started");
			assertEquals("[Servoy AI] server started\n", recordingConsole.stream.lastWritten);
		}

		@Test
		@DisplayName("appends newline to message")
		void appendsNewline() {
			activator.logToConsole("done");
			assertAll(() -> assertNotNull(recordingConsole.stream.lastWritten), () -> assertEquals('\n',
					recordingConsole.stream.lastWritten.charAt(recordingConsole.stream.lastWritten.length() - 1)));
		}

		@ParameterizedTest
		@ValueSource(strings = { "npm install failed: timeout", "opencode installed successfully.",
				"Unexpected exit (code 1) - scheduling retry 1/3 in 5 s." })
		@DisplayName("formats various lifecycle messages correctly")
		void formatsVariousMessages(String message) {
			activator.logToConsole(message);
			assertEquals("[Servoy AI] " + message + "\n", recordingConsole.stream.lastWritten);
		}

		@Test
		@DisplayName("calls close() on the output stream after writing")
		void closesStream() {
			activator.logToConsole("test");
			assertEquals(1, recordingConsole.stream.closeCount);
		}

		@Test
		@DisplayName("handles IOException from write gracefully")
		void handlesWriteIOException() throws Exception {
			recordingConsole.stream.throwOnWrite = true;
			assertDoesNotThrow(() -> activator.logToConsole("should not throw"));
		}

		@Test
		@DisplayName("handles null console gracefully")
		void handlesNullConsole() throws Exception {
			Field consoleField = Activator.class.getDeclaredField("aiConsole");
			consoleField.setAccessible(true);
			consoleField.set(activator, null);

			assertDoesNotThrow(() -> activator.logToConsole("should fail silently"));
		}
	}

	@Nested
	class GetConsole {
		@Test
		@DisplayName("returns same instance on repeated calls (singleton)")
		void returnsSameInstance() {
			IConsole first = activator.getConsole();
			IConsole second = activator.getConsole();
			assertSame(first, second);
		}

		@Test
		@DisplayName("returns the pre-injected console")
		void returnsInjectedConsole() {
			IConsole console = activator.getConsole();
			assertSame(recordingConsole, console);
		}
	}

	@Nested
	class OutputStreamContract {
		@Test
		@DisplayName("outputStream() returns non-null stream from console")
		void outputStreamNotNull() {
			StringOutputStream out = activator.getConsole().outputStream();
			assertNotNull(out);
		}

		@Test
		@DisplayName("multiple logToConsole calls each write to the stream")
		void multipleWrites() {
			activator.logToConsole("first");
			activator.logToConsole("second");
			assertAll(() -> assertEquals(2, recordingConsole.stream.allWrites.size()),
					() -> assertEquals("[Servoy AI] first\n", recordingConsole.stream.allWrites.get(0)),
					() -> assertEquals("[Servoy AI] second\n", recordingConsole.stream.allWrites.get(1)));
		}
	}

	private static class RecordingConsole implements IConsole {
		final RecordingOutputStream stream = new RecordingOutputStream();

		@Override
		public StringOutputStream outputStream() {
			return stream;
		}
	}

	private static class RecordingOutputStream implements StringOutputStream {
		String lastWritten;
		final List<String> allWrites = new ArrayList<>();
		int closeCount;
		boolean throwOnWrite;

		@Override
		public void write(CharSequence chars) throws IOException {
			if (throwOnWrite) {
				throw new IOException("simulated write failure");
			}
			lastWritten = chars.toString();
			allWrites.add(lastWritten);
		}

		@Override
		public void close() throws IOException {
			closeCount++;
		}
	}
}
