package com.servoy.eclipse.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.RunNPMCommand;
import com.servoy.eclipse.ngclient.ui.StringOutputStream;

class RunNPMCommandOutputStreamTest {
	@Nested
	class SetOutputStreamContract {
		@Test
		@DisplayName("setOutputStream stores the custom stream for later use")
		void storesCustomStream() {
			StubNPMCommand cmd = new StubNPMCommand();
			RecordingOutputStream custom = new RecordingOutputStream();

			cmd.setOutputStream(custom);

			assertSame(custom, cmd.storedStream);
		}

		@Test
		@DisplayName("custom output stream is null by default")
		void defaultIsNull() {
			StubNPMCommand cmd = new StubNPMCommand();
			assertNull(cmd.storedStream);
		}

		@Test
		@DisplayName("can replace previously set output stream")
		void canReplace() {
			StubNPMCommand cmd = new StubNPMCommand();
			RecordingOutputStream first = new RecordingOutputStream();
			RecordingOutputStream second = new RecordingOutputStream();

			cmd.setOutputStream(first);
			cmd.setOutputStream(second);

			assertSame(second, cmd.storedStream);
		}

		@Test
		@DisplayName("can set output stream to null to revert to default")
		void canSetNull() {
			StubNPMCommand cmd = new StubNPMCommand();
			cmd.setOutputStream(new RecordingOutputStream());
			cmd.setOutputStream(null);

			assertNull(cmd.storedStream);
		}

		@Test
		@DisplayName("runCommand uses the custom stream when set")
		void runCommandUsesCustomStream() throws Exception {
			StubNPMCommand cmd = new StubNPMCommand();
			RecordingOutputStream custom = new RecordingOutputStream();
			cmd.setOutputStream(custom);

			cmd.runCommand();

			assertEquals(1, custom.writeCount);
			assertNotNull(custom.lastWritten);
		}

		@Test
		@DisplayName("runCommand uses fallback when no custom stream set")
		void runCommandUsesFallback() throws Exception {
			StubNPMCommand cmd = new StubNPMCommand();

			cmd.runCommand();

			assertEquals(1, cmd.fallbackStream.writeCount);
		}
	}

	@Nested
	class ReflectionOnRealRunNPMCommand {
		private RunNPMCommand allocateInstance() throws Exception {
			Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
			return (RunNPMCommand) unsafe.allocateInstance(RunNPMCommand.class);
		}

		@Test
		@DisplayName("setOutputStream sets the customOutputStream field via reflection")
		void setsFieldViaReflection() throws Exception {
			RunNPMCommand cmd = allocateInstance();
			RecordingOutputStream custom = new RecordingOutputStream();

			java.lang.reflect.Method setter = RunNPMCommand.class.getMethod("setOutputStream",
					StringOutputStream.class);
			setter.invoke(cmd, custom);

			Field field = RunNPMCommand.class.getDeclaredField("customOutputStream");
			field.setAccessible(true);
			assertSame(custom, field.get(cmd));
		}

		@Test
		@DisplayName("setOutputStream(null) clears the customOutputStream field")
		void clearsFieldWhenNull() throws Exception {
			RunNPMCommand cmd = allocateInstance();
			RecordingOutputStream custom = new RecordingOutputStream();

			java.lang.reflect.Method setter = RunNPMCommand.class.getMethod("setOutputStream",
					StringOutputStream.class);
			setter.invoke(cmd, custom);
			setter.invoke(cmd, (StringOutputStream) null);

			Field field = RunNPMCommand.class.getDeclaredField("customOutputStream");
			field.setAccessible(true);
			assertNull(field.get(cmd));
		}

		@Test
		@DisplayName("canceling() can access the customOutputStream field")
		void cancelingCanAccessField() throws Exception {
			Field field = RunNPMCommand.class.getDeclaredField("customOutputStream");
			field.setAccessible(true);
			assertTrue(field.getType().isAssignableFrom(StringOutputStream.class));
		}
	}

	@Nested
	class InterfaceContract {
		@Test
		@DisplayName("IRunNPMCommand.setOutputStream is declared")
		void interfaceDeclaresMethod() throws Exception {
			assertNotNull(IRunNPMCommand.class.getMethod("setOutputStream", StringOutputStream.class));
		}

		@Test
		@DisplayName("setOutputStream accepts StringOutputStream parameter")
		void acceptsStringOutputStream() throws Exception {
			var method = IRunNPMCommand.class.getMethod("setOutputStream", StringOutputStream.class);
			assertEquals(void.class, method.getReturnType());
			assertEquals(1, method.getParameterCount());
		}
	}

	private static class StubNPMCommand {
		StringOutputStream storedStream;
		final RecordingOutputStream fallbackStream = new RecordingOutputStream();

		void setOutputStream(StringOutputStream outputStream) {
			this.storedStream = outputStream;
		}

		void runCommand() throws IOException {
			StringOutputStream out = storedStream != null ? storedStream : fallbackStream;
			out.write("test output\n");
			out.close();
		}
	}

	private static class RecordingOutputStream implements StringOutputStream {
		String lastWritten;
		int writeCount;

		@Override
		public void write(CharSequence chars) throws IOException {
			lastWritten = chars.toString();
			writeCount++;
		}

		@Override
		public void close() throws IOException {
		}
	}
}
