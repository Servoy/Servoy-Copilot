package com.servoy.eclipse.developer.mcp.actions;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.IHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryService;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;

@DisplayName("RunCypressFormTestHandler")
class RunCypressFormTestHandlerTest {
	private RunCypressFormTestHandler handler;

	@BeforeEach
	void setUp() {
		handler = new RunCypressFormTestHandler();
	}

	@Nested
	@DisplayName("class structure")
	class ClassStructure {
		@Test
		@DisplayName("extends AbstractHandler")
		void extendsAbstractHandler() {
			assertTrue(AbstractHandler.class.isAssignableFrom(RunCypressFormTestHandler.class));
		}

		@Test
		@DisplayName("implements IHandler")
		void implementsIHandler() {
			assertTrue(IHandler.class.isAssignableFrom(RunCypressFormTestHandler.class));
		}

		@Test
		@DisplayName("can be instantiated")
		void canBeInstantiated() {
			assertNotNull(handler);
		}

		@Test
		@DisplayName("has discoveryService field of correct type")
		void hasDiscoveryServiceField() throws NoSuchFieldException {
			Field field = RunCypressFormTestHandler.class.getDeclaredField("discoveryService");
			assertAll(() -> assertEquals(CypressTestDiscoveryService.class, field.getType()),
					() -> assertTrue(Modifier.isPrivate(field.getModifiers())),
					() -> assertTrue(Modifier.isFinal(field.getModifiers())));
		}

		@Test
		@DisplayName("has execute method accepting ExecutionEvent")
		void hasExecuteMethod() throws NoSuchMethodException {
			Method execute = RunCypressFormTestHandler.class.getMethod("execute", ExecutionEvent.class);
			assertNotNull(execute);
			assertEquals(Object.class, execute.getReturnType());
		}

		@Test
		@DisplayName("has getFormNameFromSelection private method")
		void hasGetFormNameFromSelection() {
			Method[] methods = RunCypressFormTestHandler.class.getDeclaredMethods();
			boolean found = false;
			for (Method m : methods) {
				if ("getFormNameFromSelection".equals(m.getName())) {
					found = true;
					assertEquals(String.class, m.getReturnType());
					break;
				}
			}
			assertTrue(found, "Should have getFormNameFromSelection method");
		}

		@Test
		@DisplayName("has getFormNameFromActiveEditor private method")
		void hasGetFormNameFromActiveEditor() {
			Method[] methods = RunCypressFormTestHandler.class.getDeclaredMethods();
			boolean found = false;
			for (Method m : methods) {
				if ("getFormNameFromActiveEditor".equals(m.getName())) {
					found = true;
					assertEquals(String.class, m.getReturnType());
					break;
				}
			}
			assertTrue(found, "Should have getFormNameFromActiveEditor method");
		}
	}

	@Nested
	@DisplayName("handler behavior")
	class HandlerBehavior {
		@Test
		@DisplayName("handler is enabled by default")
		void isEnabledByDefault() {
			assertTrue(handler.isEnabled());
		}

		@Test
		@DisplayName("discoveryService is initialized on construction")
		void discoveryServiceInitialized() throws Exception {
			Field field = RunCypressFormTestHandler.class.getDeclaredField("discoveryService");
			field.setAccessible(true);
			assertNotNull(field.get(handler));
		}
	}

	@Nested
	@DisplayName("getFormNameFromSelection logic")
	class GetFormNameFromSelectionLogic {
		@Test
		@DisplayName("returns null when event has no application context")
		void returnsNullForNoContext() throws Exception {
			ExecutionEvent event = new ExecutionEvent(null, Collections.emptyMap(), null, null);
			Method method = RunCypressFormTestHandler.class.getDeclaredMethod("getFormNameFromSelection",
					ExecutionEvent.class);
			method.setAccessible(true);

			Object result = method.invoke(handler, event);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("getFormNameFromActiveEditor logic")
	class GetFormNameFromActiveEditorLogic {
		@Test
		@DisplayName("returns null when event has no application context")
		void returnsNullForNoContext() throws Exception {
			ExecutionEvent event = new ExecutionEvent(null, Collections.emptyMap(), null, null);
			Method method = RunCypressFormTestHandler.class.getDeclaredMethod("getFormNameFromActiveEditor",
					ExecutionEvent.class);
			method.setAccessible(true);

			Object result = method.invoke(handler, event);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("discoveryService integration")
	class DiscoveryServiceIntegration {
		private Path tempDir;

		@BeforeEach
		void setUpTempDir() throws Exception {
			tempDir = Files.createTempDirectory("cypress-handler-test");
			injectMockDiscoveryService(tempDir);
		}

		@AfterEach
		void tearDown() throws Exception {
			if (tempDir != null && Files.exists(tempDir)) {
				Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						Files.delete(file);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
						Files.delete(dir);
						return FileVisitResult.CONTINUE;
					}
				});
			}
		}

		private void injectMockDiscoveryService(Path formsDir) throws Exception {
			CypressTestDiscoveryService mockService = new CypressTestDiscoveryService();
			FormSpecGenerator mockGenerator = new FormSpecGenerator() {
				@Override
				public Path getFormsDir() {
					return formsDir;
				}
			};
			Field genField = CypressTestDiscoveryService.class.getDeclaredField("specGenerator");
			genField.setAccessible(true);
			genField.set(mockService, mockGenerator);

			Field serviceField = RunCypressFormTestHandler.class.getDeclaredField("discoveryService");
			serviceField.setAccessible(true);
			serviceField.set(handler, mockService);
		}

		@Test
		@DisplayName("discoveryService can detect form with test file")
		void discoveryServiceDetectsTest() throws Exception {
			Files.createFile(tempDir.resolve("loginForm.spec.cy.js"));

			Field field = RunCypressFormTestHandler.class.getDeclaredField("discoveryService");
			field.setAccessible(true);
			CypressTestDiscoveryService service = (CypressTestDiscoveryService) field.get(handler);

			assertTrue(service.hasTest("loginForm"));
		}

		@Test
		@DisplayName("execute returns null without workbench context")
		void executeReturnsNullWithoutContext() {
			ExecutionEvent event = new ExecutionEvent(null, Collections.emptyMap(), null, null);

			Object result = assertDoesNotThrow(() -> handler.execute(event));

			assertNull(result);
		}
	}
}
