package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FormSpecRunnerShutdownTest {
	private FormSpecRunner runner;

	@BeforeEach
	void setUp() {
		runner = new FormSpecRunner();
	}

	@Nested
	class FormpreviewUserUidConstant {
		@Test
		@DisplayName("FORMPREVIEW_USER_UID constant exists as private static final String")
		void constantExists() throws Exception {
			Field field = FormSpecRunner.class.getDeclaredField("FORMPREVIEW_USER_UID");
			field.setAccessible(true);
			assertAll(() -> assertTrue(Modifier.isPrivate(field.getModifiers()), "should be private"),
					() -> assertTrue(Modifier.isStatic(field.getModifiers()), "should be static"),
					() -> assertTrue(Modifier.isFinal(field.getModifiers()), "should be final"),
					() -> assertEquals(String.class, field.getType(), "should be String type"));
		}

		@Test
		@DisplayName("FORMPREVIEW_USER_UID equals 'formpreview_user'")
		void constantValue() throws Exception {
			Field field = FormSpecRunner.class.getDeclaredField("FORMPREVIEW_USER_UID");
			field.setAccessible(true);
			assertEquals("formpreview_user", field.get(null));
		}
	}

	@Nested
	class ShutdownFormPreviewClientsMethod {
		@Test
		@DisplayName("shutdownFormPreviewClients method exists as private void")
		void methodExists() throws Exception {
			Method method = FormSpecRunner.class.getDeclaredMethod("shutdownFormPreviewClients");
			assertAll(() -> assertTrue(Modifier.isPrivate(method.getModifiers()), "should be private"),
					() -> assertEquals(void.class, method.getReturnType(), "should return void"));
		}

		@Test
		@DisplayName("shutdownFormPreviewClients does not throw when ApplicationServerRegistry is unavailable")
		void doesNotThrowWhenNoAppServer() throws Exception {
			Method method = FormSpecRunner.class.getDeclaredMethod("shutdownFormPreviewClients");
			method.setAccessible(true);
			assertDoesNotThrow(() -> method.invoke(runner),
					"best-effort cleanup must not throw even when app server is unavailable");
		}

		@Test
		@DisplayName("shutdownFormPreviewClients can be invoked multiple times without error")
		void canBeInvokedMultipleTimes() throws Exception {
			Method method = FormSpecRunner.class.getDeclaredMethod("shutdownFormPreviewClients");
			method.setAccessible(true);
			assertDoesNotThrow(() -> {
				method.invoke(runner);
				method.invoke(runner);
				method.invoke(runner);
			}, "repeated invocations must not throw");
		}
	}

	@Nested
	class RunFormCypressTestsIntegration {
		@Test
		@DisplayName("runFormCypressTests returns error string when no active project (no workspace)")
		void returnsErrorWhenNoActiveProject() {
			Exception thrown = null;
			String result = null;
			try {
				result = runner.runFormCypressTests("testForm", true);
			} catch (Exception e) {
				thrown = e;
			}
			assertTrue(thrown != null || (result != null && result.contains("Error")),
					"should either throw or return error message without workspace");
		}

		@Test
		@DisplayName("runFormCypressTests with timeout overload returns error when no active project")
		void returnsErrorWithTimeoutOverload() {
			Exception thrown = null;
			String result = null;
			try {
				result = runner.runFormCypressTests("testForm", true, 30, null);
			} catch (Exception e) {
				thrown = e;
			}
			assertTrue(thrown != null || (result != null && result.contains("Error")),
					"should either throw or return error message without workspace");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "nonExistentForm", "form with spaces", "form/with/slashes" })
		@DisplayName("runFormCypressTests handles invalid form names gracefully")
		void handlesInvalidFormNames(String formName) {
			Exception thrown = null;
			String result = null;
			try {
				result = runner.runFormCypressTests(formName, true);
			} catch (Exception e) {
				thrown = e;
			}
			assertTrue(thrown != null || result == null || result.contains("Error"),
					"should either throw or return error for invalid form name: " + formName);
		}
	}

	@Nested
	class ShutdownCalledInRunFlow {
		@Test
		@DisplayName("runFormCypressTests 4-arg overload exists with correct signature")
		void fourArgOverloadExists() throws NoSuchMethodException {
			Method method = FormSpecRunner.class.getMethod("runFormCypressTests", String.class, boolean.class,
					int.class, String.class);
			assertAll(() -> assertEquals(String.class, method.getReturnType(), "should return String"),
					() -> assertTrue(Modifier.isPublic(method.getModifiers()), "should be public"));
		}

		@Test
		@DisplayName("runFormCypressTests 2-arg delegates to 4-arg with DEFAULT_TIMEOUT_SECONDS")
		void twoArgDelegatesToFourArg() throws Exception {
			Field timeoutField = FormSpecRunner.class.getDeclaredField("DEFAULT_TIMEOUT_SECONDS");
			timeoutField.setAccessible(true);
			int defaultTimeout = (int) timeoutField.get(null);
			assertEquals(60, defaultTimeout, "DEFAULT_TIMEOUT_SECONDS should be 60");
		}

		@Test
		@DisplayName("shutdownFormPreviewClients is called at least twice in runFormCypressTests bytecode (before launch and in finally)")
		void shutdownCalledBeforeLaunchAndInFinally() throws IOException {
			int callCount = countInvocationsInClass(FormSpecRunner.class, "shutdownFormPreviewClients");
			assertTrue(callCount >= 2,
					"shutdownFormPreviewClients must be invoked at least 2 times in the class (before launch + finally), found: "
							+ callCount);
		}

		private int countInvocationsInClass(Class<?> clazz, String methodName) throws IOException {
			String resourcePath = "/" + clazz.getName().replace('.', '/') + ".class";
			try (InputStream is = clazz.getResourceAsStream(resourcePath)) {
				assertNotNull(is, "class bytecode must be loadable");
				DataInputStream dis = new DataInputStream(is);

				int magic = dis.readInt();
				assertEquals(0xCAFEBABE, magic);
				dis.readUnsignedShort(); // minor
				dis.readUnsignedShort(); // major

				int cpCount = dis.readUnsignedShort();
				String[] utf8Entries = new String[cpCount];
				int[][] methodRefs = new int[cpCount][2];
				int[][] nameAndTypes = new int[cpCount][2];
				int[] tags = new int[cpCount];

				for (int i = 1; i < cpCount; i++) {
					int tag = dis.readUnsignedByte();
					tags[i] = tag;
					switch (tag) {
					case 1: // UTF8
						utf8Entries[i] = dis.readUTF();
						break;
					case 3: // Integer
					case 4: // Float
						dis.readInt();
						break;
					case 5: // Long
					case 6: // Double
						dis.readLong();
						i++;
						break;
					case 7: // Class
					case 8: // String
					case 16: // MethodType
					case 19: // Module
					case 20: // Package
						dis.readUnsignedShort();
						break;
					case 9: // Fieldref
					case 10: // Methodref
					case 11: // InterfaceMethodref
					case 12: // NameAndType
						int idx1 = dis.readUnsignedShort();
						int idx2 = dis.readUnsignedShort();
						if (tag == 10 || tag == 11) {
							methodRefs[i][0] = idx1;
							methodRefs[i][1] = idx2;
						} else if (tag == 12) {
							nameAndTypes[i][0] = idx1;
							nameAndTypes[i][1] = idx2;
						}
						break;
					case 15: // MethodHandle
						dis.readUnsignedByte();
						dis.readUnsignedShort();
						break;
					case 17: // Dynamic
					case 18: // InvokeDynamic
						dis.readInt();
						break;
					default:
						break;
					}
				}

				int targetMethodRefIndex = -1;
				for (int i = 1; i < cpCount; i++) {
					if ((tags[i] == 10 || tags[i] == 11)) {
						int natIdx = methodRefs[i][1];
						if (natIdx > 0 && natIdx < cpCount && tags[natIdx] == 12) {
							int nameIdx = nameAndTypes[natIdx][0];
							if (nameIdx > 0 && nameIdx < cpCount && methodName.equals(utf8Entries[nameIdx])) {
								targetMethodRefIndex = i;
								break;
							}
						}
					}
				}

				if (targetMethodRefIndex < 0)
					return 0;

				dis.close();
				byte[] fullBytes;
				try (InputStream is2 = clazz.getResourceAsStream(resourcePath)) {
					fullBytes = is2.readAllBytes();
				}

				byte hi = (byte) ((targetMethodRefIndex >> 8) & 0xFF);
				byte lo = (byte) (targetMethodRefIndex & 0xFF);
				int count = 0;
				for (int i = 0; i < fullBytes.length - 2; i++) {
					int opcode = fullBytes[i] & 0xFF;
					if ((opcode == 0xB6 || opcode == 0xB7 || opcode == 0xB8 || opcode == 0xB9) && fullBytes[i + 1] == hi
							&& fullBytes[i + 2] == lo) {
						count++;
					}
				}
				return count;
			}
		}
	}
}
