package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

class McpToolParamValidationTest {
	private static final List<Class<?>> SERVER_CLASSES = List.of(ServoyTestingServer.class, ServoyDevServer.class,
			ServoyIdeServer.class, ServoyCoderServer.class, ServoyContextServer.class, ServoyGitServer.class,
			ServoyWpmServer.class, MemoryServer.class, ServoyI18nServer.class, ServoyMediaServer.class,
			TimeServer.class);

	private static final Pattern OPTIONAL_HINT_PATTERN = Pattern
			.compile("(?i)\\b(optional|if omitted|defaults to|default:|when omitted)\\b");

	static Stream<Arguments> toolMethods() {
		List<Arguments> args = new ArrayList<>();
		for (Class<?> serverClass : SERVER_CLASSES) {
			for (Method method : serverClass.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Tool.class)) {
					args.add(Arguments.of(serverClass.getSimpleName(), method));
				}
			}
		}
		return args.stream();
	}

	@ParameterizedTest(name = "{0}.{1}")
	@MethodSource("toolMethods")
	@DisplayName("Validate @ToolParam annotations on all MCP server tool methods")
	void validateToolParams(String serverName, Method method) {
		Tool tool = method.getAnnotation(Tool.class);
		String toolName = tool.name().isEmpty() ? method.getName() : tool.name();
		String prefix = serverName + "." + toolName;

		List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();

		for (Parameter p : method.getParameters()) {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			if (tp == null) {
				continue;
			}

			String paramId = prefix + "." + (tp.name().isEmpty() ? p.getName() : tp.name());

			assertions.add(() -> assertFalse(tp.name().isEmpty(), paramId + ": @ToolParam name must not be empty"));

			String desc = tp.description();
			String descLower = desc.toLowerCase(Locale.ROOT);
			assertions.add(() -> assertFalse(descLower.startsWith("optional:") || descLower.startsWith("optional "),
					paramId + ": description must not start with 'Optional:' or 'Optional '"));

			if (OPTIONAL_HINT_PATTERN.matcher(desc).find()) {
				assertions.add(
						() -> assertFalse(tp.required(), paramId + ": description hints optional but required=true"));
			}
		}

		if (!assertions.isEmpty()) {
			assertAll(prefix, assertions);
		}
	}
}
