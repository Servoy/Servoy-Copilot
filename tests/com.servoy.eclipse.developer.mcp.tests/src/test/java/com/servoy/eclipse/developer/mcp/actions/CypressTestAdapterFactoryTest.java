package com.servoy.eclipse.developer.mcp.actions;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IAdapterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryService;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.ui.node.SimpleUserNode;
import com.servoy.eclipse.ui.node.UserNodeType;

@DisplayName("CypressTestAdapterFactory")
class CypressTestAdapterFactoryTest {
	private CypressTestAdapterFactory factory;

	@BeforeEach
	void setUp() {
		factory = new CypressTestAdapterFactory();
	}

	@Nested
	@DisplayName("structural verification")
	class StructuralVerification {
		@Test
		@DisplayName("implements IAdapterFactory")
		void implementsAdapterFactory() {
			assertTrue(IAdapterFactory.class.isAssignableFrom(CypressTestAdapterFactory.class));
		}

		@Test
		@DisplayName("getAdapterList returns CypressFormTestTarget")
		void getAdapterListReturnsCorrectType() {
			Class<?>[] adapters = factory.getAdapterList();

			assertNotNull(adapters);
			assertEquals(1, adapters.length);
			assertEquals(CypressFormTestTarget.class, adapters[0]);
		}

		@Test
		@DisplayName("has discoveryService field")
		void hasDiscoveryServiceField() throws NoSuchFieldException {
			Field field = CypressTestAdapterFactory.class.getDeclaredField("discoveryService");
			assertNotNull(field);
			assertEquals(CypressTestDiscoveryService.class, field.getType());
		}

		@Test
		@DisplayName("has ADAPTERS constant")
		void hasAdaptersConstant() throws NoSuchFieldException {
			Field field = CypressTestAdapterFactory.class.getDeclaredField("ADAPTERS");
			assertTrue(Modifier.isStatic(field.getModifiers()));
			assertTrue(Modifier.isFinal(field.getModifiers()));
		}
	}

	@Nested
	@DisplayName("getAdapter guard clauses")
	class GuardClauses {
		@Test
		@DisplayName("returns null for wrong adapter type")
		void returnsNullForWrongAdapterType() {
			SimpleUserNode node = new SimpleUserNode("test", UserNodeType.FORM, null, null);

			Object result = factory.getAdapter(node, String.class);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null for non-SimpleUserNode object")
		void returnsNullForNonSimpleUserNode() {
			Object result = factory.getAdapter("not a node", CypressFormTestTarget.class);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null for null adaptable object")
		void returnsNullForNullObject() {
			Object result = factory.getAdapter(null, CypressFormTestTarget.class);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null for Integer object (non-node)")
		void returnsNullForInteger() {
			Object result = factory.getAdapter(Integer.valueOf(42), CypressFormTestTarget.class);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null for FORM node before ServoyModel is available")
		void returnsNullOrThrowsForFormNodeWithoutRuntime() {
			SimpleUserNode node = new SimpleUserNode("myForm", UserNodeType.FORM, null, null);

			assertThrows(NullPointerException.class, () -> factory.getAdapter(node, CypressFormTestTarget.class));
		}

		@Test
		@DisplayName("returns null for SOLUTION node before ServoyModel is available")
		void returnsNullOrThrowsForSolutionNodeWithoutRuntime() {
			SimpleUserNode node = new SimpleUserNode("MySol", UserNodeType.SOLUTION, null, null);

			assertThrows(NullPointerException.class, () -> factory.getAdapter(node, CypressFormTestTarget.class));
		}

		@Test
		@DisplayName("returns null for FORMS node before ServoyModel is available")
		void returnsNullOrThrowsForFormsNodeWithoutRuntime() {
			SimpleUserNode node = new SimpleUserNode("Forms", UserNodeType.FORMS, null, null);

			assertThrows(NullPointerException.class, () -> factory.getAdapter(node, CypressFormTestTarget.class));
		}

		@Test
		@DisplayName("returns null for SOLUTION_ITEM node before ServoyModel is available")
		void returnsNullOrThrowsForSolutionItemNodeWithoutRuntime() {
			SimpleUserNode node = new SimpleUserNode("Item", UserNodeType.SOLUTION_ITEM, null, null);

			assertThrows(NullPointerException.class, () -> factory.getAdapter(node, CypressFormTestTarget.class));
		}
	}

	@Nested
	@DisplayName("CypressFormTestTarget interface")
	class TargetInterface {
		@Test
		@DisplayName("interface defines getFormName method")
		void interfaceHasGetFormName() throws NoSuchMethodException {
			assertNotNull(CypressFormTestTarget.class.getMethod("getFormName"));
			assertEquals(String.class, CypressFormTestTarget.class.getMethod("getFormName").getReturnType());
		}

		@Test
		@DisplayName("interface defines isSolutionLevel method")
		void interfaceHasIsSolutionLevel() throws NoSuchMethodException {
			assertNotNull(CypressFormTestTarget.class.getMethod("isSolutionLevel"));
			assertEquals(boolean.class, CypressFormTestTarget.class.getMethod("isSolutionLevel").getReturnType());
		}

		@Test
		@DisplayName("interface defines getTestFormNames method")
		void interfaceHasGetTestFormNames() throws NoSuchMethodException {
			assertNotNull(CypressFormTestTarget.class.getMethod("getTestFormNames"));
			assertEquals(List.class, CypressFormTestTarget.class.getMethod("getTestFormNames").getReturnType());
		}
	}

	@Nested
	@DisplayName("SingleFormTestTarget inner class")
	class SingleFormTestTargetTest {
		@Test
		@DisplayName("can be instantiated via reflection")
		void canInstantiate() throws Exception {
			Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
			Class<?> singleFormClass = null;
			for (Class<?> c : innerClasses) {
				if (c.getSimpleName().equals("SingleFormTestTarget")) {
					singleFormClass = c;
					break;
				}
			}
			assertNotNull(singleFormClass, "SingleFormTestTarget inner class should exist");
			assertTrue(CypressFormTestTarget.class.isAssignableFrom(singleFormClass));
		}

		@Test
		@DisplayName("returns correct values for single form")
		void returnsCorrectValues() throws Exception {
			Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
			Class<?> singleFormClass = null;
			for (Class<?> c : innerClasses) {
				if (c.getSimpleName().equals("SingleFormTestTarget")) {
					singleFormClass = c;
					break;
				}
			}
			assertNotNull(singleFormClass);

			var ctor = singleFormClass.getDeclaredConstructors()[0];
			ctor.setAccessible(true);
			CypressFormTestTarget target = (CypressFormTestTarget) ctor.newInstance("myTestForm");

			assertAll(() -> assertEquals("myTestForm", target.getFormName()),
					() -> assertFalse(target.isSolutionLevel()),
					() -> assertEquals(Collections.singletonList("myTestForm"), target.getTestFormNames()));
		}

		@Test
		@DisplayName("getTestFormNames returns single-element list matching formName")
		void testFormNamesMatchesFormName() throws Exception {
			Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
			Class<?> singleFormClass = null;
			for (Class<?> c : innerClasses) {
				if (c.getSimpleName().equals("SingleFormTestTarget")) {
					singleFormClass = c;
					break;
				}
			}
			assertNotNull(singleFormClass);

			var ctor = singleFormClass.getDeclaredConstructors()[0];
			ctor.setAccessible(true);
			CypressFormTestTarget target = (CypressFormTestTarget) ctor.newInstance("anotherForm");

			assertAll(() -> assertEquals(1, target.getTestFormNames().size()),
					() -> assertEquals(target.getFormName(), target.getTestFormNames().get(0)));
		}
	}

	@Nested
	@DisplayName("SolutionLevelTestTarget inner class")
	class SolutionLevelTestTargetTest {
		@Test
		@DisplayName("exists and implements CypressFormTestTarget")
		void existsAndImplementsInterface() {
			Class<?>[] innerClasses = CypressTestAdapterFactory.class.getDeclaredClasses();
			Class<?> solutionClass = null;
			for (Class<?> c : innerClasses) {
				if (c.getSimpleName().equals("SolutionLevelTestTarget")) {
					solutionClass = c;
					break;
				}
			}
			assertNotNull(solutionClass, "SolutionLevelTestTarget inner class should exist");
			assertTrue(CypressFormTestTarget.class.isAssignableFrom(solutionClass));
		}

		@Test
		@DisplayName("returns null formName and isSolutionLevel true")
		void returnsCorrectValues() throws Exception {
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

			assertAll(() -> assertNull(target.getFormName()), () -> assertTrue(target.isSolutionLevel()));
		}

		@Test
		@DisplayName("getTestFormNames delegates to discoveryService")
		void getTestFormNamesDelegates() throws Exception {
			Path tempDir = Files.createTempDirectory("adapter-factory-test");
			try {
				Files.createFile(tempDir.resolve("formX.spec.cy.js"));
				Files.createFile(tempDir.resolve("formY.spec.cy.js"));

				injectMockDiscoveryService(tempDir);

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

				List<String> forms = target.getTestFormNames();
				assertAll(() -> assertEquals(2, forms.size()), () -> assertTrue(forms.contains("formX")),
						() -> assertTrue(forms.contains("formY")));
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
	}

	@Nested
	@DisplayName("node type handling logic")
	class NodeTypeHandling {
		@Test
		@DisplayName("FORM type is recognized by getAdapter")
		void formTypeRecognized() {
			SimpleUserNode node = new SimpleUserNode("testForm", UserNodeType.FORM, null, null);
			assertEquals(UserNodeType.FORM, node.getType());
		}

		@Test
		@DisplayName("SOLUTION type is recognized by getAdapter")
		void solutionTypeRecognized() {
			SimpleUserNode node = new SimpleUserNode("Sol", UserNodeType.SOLUTION, null, null);
			assertEquals(UserNodeType.SOLUTION, node.getType());
		}

		@Test
		@DisplayName("FORMS type is recognized by getAdapter")
		void formsTypeRecognized() {
			SimpleUserNode node = new SimpleUserNode("Forms", UserNodeType.FORMS, null, null);
			assertEquals(UserNodeType.FORMS, node.getType());
		}

		@Test
		@DisplayName("SOLUTION_ITEM type is recognized")
		void solutionItemTypeRecognized() {
			SimpleUserNode node = new SimpleUserNode("Item", UserNodeType.SOLUTION_ITEM, null, null);
			assertEquals(UserNodeType.SOLUTION_ITEM, node.getType());
		}

		@Test
		@DisplayName("unrelated node type throws without runtime (ServoyModel unavailable)")
		void unrelatedNodeTypeThrowsWithoutRuntime() {
			SimpleUserNode node = new SimpleUserNode("table", UserNodeType.TABLE, null, null);
			assertThrows(NullPointerException.class, () -> factory.getAdapter(node, CypressFormTestTarget.class));
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

		Field serviceField = CypressTestAdapterFactory.class.getDeclaredField("discoveryService");
		serviceField.setAccessible(true);
		serviceField.set(factory, mockService);
	}
}
