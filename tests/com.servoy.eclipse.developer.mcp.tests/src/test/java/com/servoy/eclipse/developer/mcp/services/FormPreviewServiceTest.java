package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("FormPreviewService")
class FormPreviewServiceTest {
	private FormPreviewService service;

	@BeforeEach
	void setUp() {
		service = new FormPreviewService();
	}

	@Nested
	@DisplayName("screenshotForm - input validation")
	class ScreenshotFormInputValidation {
		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "\t" })
		@DisplayName("returns error for null, empty, or blank form name")
		void returnsErrorForInvalidFormName(String formName) {
			String result = service.screenshotForm(formName, 5);
			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error:"), "Should start with 'Error:': " + result),
					() -> assertTrue(result.contains("null or empty"), "Should mention null or empty: " + result));
		}

	}

	@Nested
	@DisplayName("checkFormMarkers - method structure")
	class CheckFormMarkersStructure {
		@Test
		@DisplayName("checkFormMarkers method exists and is private")
		void methodExistsAndIsPrivate() throws NoSuchMethodException {
			Method m = FormPreviewService.class.getDeclaredMethod("checkFormMarkers",
					com.servoy.eclipse.model.nature.ServoyProject.class, String.class);
			assertNotNull(m);
			assertTrue(java.lang.reflect.Modifier.isPrivate(m.getModifiers()));
		}

		@Test
		@DisplayName("checkFormMarkers returns String (null means no errors)")
		void methodReturnsString() throws NoSuchMethodException {
			Method m = FormPreviewService.class.getDeclaredMethod("checkFormMarkers",
					com.servoy.eclipse.model.nature.ServoyProject.class, String.class);
			assertTrue(String.class.equals(m.getReturnType()));
		}
	}

	@Nested
	@DisplayName("collectErrorMarkers - method structure")
	class CollectErrorMarkersStructure {
		@Test
		@DisplayName("collectErrorMarkers method exists with correct signature")
		void methodExistsWithCorrectSignature() throws NoSuchMethodException {
			Method m = FormPreviewService.class.getDeclaredMethod("collectErrorMarkers",
					org.eclipse.core.resources.IResource.class, int.class, List.class);
			assertNotNull(m);
			assertTrue(java.lang.reflect.Modifier.isPrivate(m.getModifiers()));
		}
	}

	@Nested
	@DisplayName("error message format")
	class ErrorMessageFormat {
		@Test
		@DisplayName("collectErrorMarkers formats error with line number correctly")
		void collectErrorMarkersFormatsWithLineNumber() throws Exception {
			Method m = FormPreviewService.class.getDeclaredMethod("collectErrorMarkers",
					org.eclipse.core.resources.IResource.class, int.class, List.class);
			m.setAccessible(true);

			org.eclipse.core.resources.IMarker marker = (org.eclipse.core.resources.IMarker) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IMarker.class }, (proxy, method, args) -> {
								if (method.getName().equals("getAttribute") && args.length == 2
										&& args[0] instanceof String) {
									String attr = (String) args[0];
									if (org.eclipse.core.resources.IMarker.SEVERITY.equals(attr))
										return org.eclipse.core.resources.IMarker.SEVERITY_ERROR;
									if (org.eclipse.core.resources.IMarker.LINE_NUMBER.equals(attr))
										return 42;
									if (org.eclipse.core.resources.IMarker.MESSAGE.equals(attr))
										return "Test error message";
								}
								return null;
							});

			org.eclipse.core.resources.IResource resource = (org.eclipse.core.resources.IResource) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IResource.class }, (proxy, method, args) -> {
								if (method.getName().equals("findMarkers")) {
									return new org.eclipse.core.resources.IMarker[] { marker };
								}
								return null;
							});

			List<String> errors = new ArrayList<>();
			m.invoke(service, resource, org.eclipse.core.resources.IResource.DEPTH_INFINITE, errors);

			assertTrue(errors.size() == 1, "Should collect exactly one error");
			assertTrue(errors.get(0).equals("- [ERROR] Test error message (line 42)"),
					"Format should be '- [ERROR] <message> (line <n>)' but was: " + errors.get(0));
		}

		@Test
		@DisplayName("collectErrorMarkers formats error without line number correctly")
		void collectErrorMarkersFormatsWithoutLineNumber() throws Exception {
			Method m = FormPreviewService.class.getDeclaredMethod("collectErrorMarkers",
					org.eclipse.core.resources.IResource.class, int.class, List.class);
			m.setAccessible(true);

			org.eclipse.core.resources.IMarker marker = (org.eclipse.core.resources.IMarker) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IMarker.class }, (proxy, method, args) -> {
								if (method.getName().equals("getAttribute") && args.length == 2
										&& args[0] instanceof String) {
									String attr = (String) args[0];
									if (org.eclipse.core.resources.IMarker.SEVERITY.equals(attr))
										return org.eclipse.core.resources.IMarker.SEVERITY_ERROR;
									if (org.eclipse.core.resources.IMarker.LINE_NUMBER.equals(attr))
										return -1;
									if (org.eclipse.core.resources.IMarker.MESSAGE.equals(attr))
										return "Another error";
								}
								return null;
							});

			org.eclipse.core.resources.IResource resource = (org.eclipse.core.resources.IResource) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IResource.class }, (proxy, method, args) -> {
								if (method.getName().equals("findMarkers")) {
									return new org.eclipse.core.resources.IMarker[] { marker };
								}
								return null;
							});

			List<String> errors = new ArrayList<>();
			m.invoke(service, resource, org.eclipse.core.resources.IResource.DEPTH_INFINITE, errors);

			assertTrue(errors.size() == 1, "Should collect exactly one error");
			assertTrue(errors.get(0).equals("- [ERROR] Another error"),
					"Format should be '- [ERROR] <message>' but was: " + errors.get(0));
		}

		@Test
		@DisplayName("collectErrorMarkers skips non-error markers")
		void collectErrorMarkersSkipsWarnings() throws Exception {
			Method m = FormPreviewService.class.getDeclaredMethod("collectErrorMarkers",
					org.eclipse.core.resources.IResource.class, int.class, List.class);
			m.setAccessible(true);

			org.eclipse.core.resources.IMarker warningMarker = (org.eclipse.core.resources.IMarker) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IMarker.class }, (proxy, method, args) -> {
								if (method.getName().equals("getAttribute") && args.length == 2
										&& args[0] instanceof String) {
									String attr = (String) args[0];
									if (org.eclipse.core.resources.IMarker.SEVERITY.equals(attr))
										return org.eclipse.core.resources.IMarker.SEVERITY_WARNING;
									if (org.eclipse.core.resources.IMarker.MESSAGE.equals(attr))
										return "Warning message";
								}
								return null;
							});

			org.eclipse.core.resources.IResource resource = (org.eclipse.core.resources.IResource) java.lang.reflect.Proxy
					.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { org.eclipse.core.resources.IResource.class }, (proxy, method, args) -> {
								if (method.getName().equals("findMarkers")) {
									return new org.eclipse.core.resources.IMarker[] { warningMarker };
								}
								return null;
							});

			List<String> errors = new ArrayList<>();
			m.invoke(service, resource, org.eclipse.core.resources.IResource.DEPTH_INFINITE, errors);

			assertTrue(errors.isEmpty(), "Should not collect warning markers");
		}
	}
}
