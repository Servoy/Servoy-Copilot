package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.cypress.services.FormSpecModel;
import com.servoy.eclipse.developer.mcp.services.FormSpecModelBuilder;
import com.servoy.eclipse.developer.mcp.services.HandlerBinding;
import com.servoy.eclipse.developer.mcp.services.InteractiveElement;

/**
 * SVY-21517: the visible flag of an element (visible=false in the form) must reach the spec model, because a hidden element is not rendered.
 */
class FormSpecModelBuilderHiddenElementTest {
	private static final String BUTTON = "bootstrapcomponents-button";

	private static InteractiveElement element(String name, boolean visible, HandlerBinding... handlers) {
		return new InteractiveElement(name, BUTTON, InteractiveElement.KIND_CONTROL, null, null, false, false, visible, List.of(handlers));
	}

	private static FormSpecModel.Element find(FormSpecModel model, String name) {
		return model.elements().stream().filter(e -> name.equals(e.name())).findFirst().orElseThrow();
	}

	@Test
	void elementCreatedWithoutTheVisibleArgumentIsVisible() {
		assertTrue(new InteractiveElement("a", BUTTON, InteractiveElement.KIND_CONTROL, null, null, false, false, List.of()).visible());
	}

	@Test
	void hiddenAndShownElementsKeepTheirFlagInTheModel() {
		FormSpecModel model = FormSpecModelBuilder.fromElements("f", List.of(element("shown", true), element("secret", false)));

		assertAll(() -> assertTrue(find(model, "shown").visible()), () -> assertFalse(find(model, "secret").visible()));
	}

	@Test
	void hiddenElementStaysInTheModelSoItCanBeAnEffectTarget() {
		HandlerBinding show = HandlerBinding.resolved("onActionMethodID", "show", "forms.f", "function show() { elements.secret.visible = true; }");

		FormSpecModel model = FormSpecModelBuilder.fromElements("f", List.of(element("btn", true, show), element("secret", false)));

		FormSpecModel.Handler handler = find(model, "btn").handlers().get(0);
		assertEquals(List.of(new FormSpecModel.Effect(FormSpecModel.EffectKind.VISIBLE, "secret", "true")), handler.effects());
	}
}
