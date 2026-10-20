package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsService;

class FormInteractiveElementsServiceGuardTest
{
	@Test
	@DisplayName("getElements rejects a null or blank form name with the exact error before touching the model")
	void getElements_blankFormName_returnsError()
	{
		FormInteractiveElementsService service = new FormInteractiveElementsService();
		assertAll(() -> assertEquals("Error: formName parameter is required", service.getElements(null, false, 100)),
			() -> assertEquals("Error: formName parameter is required", service.getElements("", true, 100)),
			() -> assertEquals("Error: formName parameter is required", service.getElements("   ", false, 1)));
	}
}
