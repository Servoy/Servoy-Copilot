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
import java.util.List;

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

@DisplayName("RunAllCypressFormTestsHandler")
class RunAllCypressFormTestsHandlerTest {
	private RunAllCypressFormTestsHandler handler;

	@BeforeEach
	void setUp() {
		handler = new RunAllCypressFormTestsHandler();
	}

	@Nested
	@DisplayName("class structure")
	class ClassStructure {
		@Test
		@DisplayName("extends AbstractHandler")
		void extendsAbstractHandler() {
			assertTrue(AbstractHandler.class.isAssignableFrom(RunAllCypressFormTestsHandler.class));
		}

		@Test
		@DisplayName("implements IHandler")
		void implementsIHandler() {
			assertTrue(IHandler.class.isAssignableFrom(RunAllCypressFormTestsHandler.class));
		}

		@Test
		@DisplayName("can be instantiated")
		void canBeInstantiated() {
			assertNotNull(handler);
		}

		@Test
		@DisplayName("has execute method accepting ExecutionEvent")
		void hasExecuteMethod() throws NoSuchMethodException {
			Method execute = RunAllCypressFormTestsHandler.class.getMethod("execute", ExecutionEvent.class);
			assertNotNull(execute);
			assertEquals(Object.class, execute.getReturnType());
		}

		@Test
		@DisplayName("execute method is public")
		void executeMethodIsPublic() throws NoSuchMethodException {
			Method execute = RunAllCypressFormTestsHandler.class.getMethod("execute", ExecutionEvent.class);
			assertTrue(Modifier.isPublic(execute.getModifiers()));
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
		@DisplayName("handler is not disposed after creation")
		void handlerNotDisposed() {
			assertTrue(handler.isHandled());
		}

		@Test
		@DisplayName("execute returns null when event has no selection context")
		void executeReturnsNullWithoutContext() {
			ExecutionEvent event = new ExecutionEvent(null, Collections.emptyMap(), null, null);

			Object result = assertDoesNotThrow(() -> handler.execute(event));

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("CypressFormTestTarget integration")
	class TargetIntegration {
		@Test
		@DisplayName("SolutionLevelTestTarget getTestFormNames delegates to discoveryService")
		void solutionLevelTargetDelegatesToDiscovery() throws Exception {
			Path tempDir = Files.createTempDirectory("cypress-all-handler-test");
			try {
				Files.createFile(tempDir.resolve("formA.spec.cy.js"));
				Files.createFile(tempDir.resolve("formB.spec.cy.js"));
				Files.createFile(tempDir.resolve("formC.spec.cy.js"));

				CypressTestAdapterFactory factory = new CypressTestAdapterFactory();
				CypressTestDiscoveryService mockService = createMockService(tempDir);
				Field serviceField = CypressTestAdapterFactory.class.getDeclaredField("discoveryService");
				serviceField.setAccessible(true);
				serviceField.set(factory, mockService);

				Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
				Class<?> solutionClass = null;
				for (Class<?> c : innerClasses) {
					if (c.getSimpleName().equals("SolutionLevelTestTarget")) {
						solutionClass = c;
						break;
					}
				}
				assertNotNull(solutionClass);
				var ctor = solutionClass.getDeclaredConstructors()[0];
				ctor.setAccessible(true);
				CypressFormTestTarget target = (CypressFormTestTarget) ctor.newInstance(factory);

				List<String> testForms = target.getTestFormNames();

				assertAll(() -> assertEquals(3, testForms.size()), () -> assertTrue(testForms.contains("formA")),
						() -> assertTrue(testForms.contains("formB")), () -> assertTrue(testForms.contains("formC")));
			} finally {
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

		@Test
		@DisplayName("SolutionLevelTestTarget returns empty list when no tests exist")
		void solutionLevelTargetReturnsEmptyWhenNoTests() throws Exception {
			Path tempDir = Files.createTempDirectory("cypress-all-handler-empty");
			try {
				CypressTestAdapterFactory factory = new CypressTestAdapterFactory();
				CypressTestDiscoveryService mockService = createMockService(tempDir);
				Field serviceField = CypressTestAdapterFactory.class.getDeclaredField("discoveryService");
				serviceField.setAccessible(true);
				serviceField.set(factory, mockService);

				Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
				Class<?> solutionClass = null;
				for (Class<?> c : innerClasses) {
					if (c.getSimpleName().equals("SolutionLevelTestTarget")) {
						solutionClass = c;
						break;
					}
				}
				assertNotNull(solutionClass);
				var ctor = solutionClass.getDeclaredConstructors()[0];
				ctor.setAccessible(true);
				CypressFormTestTarget target = (CypressFormTestTarget) ctor.newInstance(factory);

				List<String> testForms = target.getTestFormNames();

				assertTrue(testForms.isEmpty());
			} finally {
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

		@Test
		@DisplayName("aggregate result counting pattern works correctly")
		void aggregateResultCountingLogic() {
			List<String> results = List.of("All tests passed for formA", "FAILED: formB had 2 errors",
					"All tests passed for formC", "All tests passed for formD", "FAILED: formE timeout");

			int passed = 0;
			int failed = 0;
			for (String result : results) {
				if (result.contains("All tests passed")) {
					passed++;
				} else {
					failed++;
				}
			}

			int finalPassed = passed;
			int finalFailed = failed;
			assertAll(() -> assertEquals(3, finalPassed), () -> assertEquals(2, finalFailed),
					() -> assertEquals(results.size(), finalPassed + finalFailed));
		}

		private CypressTestDiscoveryService createMockService(Path formsDir) throws Exception {
			CypressTestDiscoveryService service = new CypressTestDiscoveryService();
			FormSpecGenerator mockGenerator = new FormSpecGenerator() {
				@Override
				public Path getFormsDir() {
					return formsDir;
				}
			};
			Field genField = CypressTestDiscoveryService.class.getDeclaredField("specGenerator");
			genField.setAccessible(true);
			genField.set(service, mockGenerator);
			return service;
		}
	}

	@Nested
	@DisplayName("CypressConsoleUtil")
	class ConsoleUtilStructure {
		@Test
		@DisplayName("CypressConsoleUtil has findOrCreateConsole method")
		void hasFindOrCreateConsoleMethod() throws NoSuchMethodException {
			Method m = CypressConsoleUtil.class.getMethod("findOrCreateConsole");
			assertNotNull(m);
			assertTrue(Modifier.isStatic(m.getModifiers()));
		}

		@Test
		@DisplayName("CypressConsoleUtil has showConsole method")
		void hasShowConsoleMethod() throws NoSuchMethodException {
			Method m = CypressConsoleUtil.class.getMethod("showConsole", org.eclipse.ui.console.MessageConsole.class);
			assertNotNull(m);
			assertTrue(Modifier.isStatic(m.getModifiers()));
		}

		@Test
		@DisplayName("CypressConsoleUtil is final utility class")
		void isFinalUtilityClass() {
			assertTrue(Modifier.isFinal(CypressConsoleUtil.class.getModifiers()));
		}
	}
}
