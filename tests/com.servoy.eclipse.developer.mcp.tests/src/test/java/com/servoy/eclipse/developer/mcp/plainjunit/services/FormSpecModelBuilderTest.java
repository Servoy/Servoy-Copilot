package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.servoy.eclipse.cypress.services.FormSpecModel;
import com.servoy.eclipse.cypress.services.FormSpecModel.Effect;
import com.servoy.eclipse.cypress.services.FormSpecModel.EffectKind;
import com.servoy.eclipse.cypress.services.FormSpecModel.Element;
import com.servoy.eclipse.cypress.services.FormSpecModel.Gesture;
import com.servoy.eclipse.cypress.services.FormSpecModel.Handler;
import com.servoy.eclipse.cypress.services.FormSpecModel.Kind;
import com.servoy.eclipse.developer.mcp.services.FormSpecModelBuilder;
import com.servoy.eclipse.developer.mcp.services.HandlerBinding;
import com.servoy.eclipse.developer.mcp.services.InteractiveElement;

class FormSpecModelBuilderTest {
	private static final String BUTTON = "bootstrapcomponents-button";
	private static final String TEXTBOX = "bootstrapcomponents-textbox";
	private static final String HIDE_LABEL = "function hide() { elements.lbl.visible = false; }";

	private static InteractiveElement element(String name, String type, String kind, String dataProvider,
			HandlerBinding... handlers) {
		return new InteractiveElement(name, type, kind, dataProvider, null, false, false, List.of(handlers));
	}

	private static InteractiveElement button(String name, HandlerBinding... handlers) {
		return element(name, BUTTON, InteractiveElement.KIND_CONTROL, null, handlers);
	}

	private static InteractiveElement label(String name) {
		return element(name, "bootstrapcomponents-label", InteractiveElement.KIND_STATIC, null);
	}

	private static HandlerBinding action(String declaration) {
		return HandlerBinding.resolved("onActionMethodID", "hide", "forms.f", declaration);
	}

	private static FormSpecModel build(InteractiveElement... elements) {
		return FormSpecModelBuilder.fromElements("f", List.of(elements));
	}

	private static List<String> names(FormSpecModel model) {
		return model.elements().stream().map(Element::name).toList();
	}

	private static Handler onlyHandler(FormSpecModel model, String elementName) {
		Element found = model.elements().stream().filter(e -> e.name().equals(elementName)).findFirst().orElseThrow();
		assertEquals(1, found.handlers().size());
		return found.handlers().get(0);
	}

	@Nested
	class ElementSelection {
		@Test
		@DisplayName("unnamed, blank-named and null elements are dropped")
		void unnamedAreDropped() {
			List<InteractiveElement> source = new ArrayList<>();
			source.add(button(null));
			source.add(button(""));
			source.add(button("   "));
			source.add(null);
			source.add(button("ok"));

			assertEquals(List.of("ok"), names(FormSpecModelBuilder.fromElements("f", source)));
		}

		@Test
		@DisplayName("specMissing elements are dropped")
		void specMissingAreDropped() {
			InteractiveElement missing = new InteractiveElement("gone", BUTTON, InteractiveElement.KIND_CONTROL, null,
					null, false, true, List.of());

			assertEquals(List.of("ok"), names(build(missing, button("ok"))));
		}

		@Test
		@DisplayName("a duplicate name keeps the first occurrence")
		void duplicatesKeepFirst() {
			InteractiveElement first = element("dup", "first-type", InteractiveElement.KIND_CONTROL, null);
			InteractiveElement second = element("dup", "second-type", InteractiveElement.KIND_INPUT, null);

			FormSpecModel model = build(first, second);

			assertAll(() -> assertEquals(1, model.elements().size()),
					() -> assertEquals("first-type", model.elements().get(0).type()),
					() -> assertEquals(Kind.CONTROL, model.elements().get(0).kind()));
		}

		@Test
		@DisplayName("elements keep the order of their first occurrence")
		void orderIsKept() {
			assertEquals(List.of("b", "a", "c"), names(build(button("b"), button("a"), button("b"), button("c"))));
		}

		@Test
		@DisplayName("a null element list builds an empty model that keeps the form name")
		void nullList() {
			FormSpecModel model = FormSpecModelBuilder.fromElements("orders", null);

			assertAll(() -> assertEquals("orders", model.formName()), () -> assertEquals(List.of(), model.elements()));
		}

		@Test
		@DisplayName("the form name is taken as given")
		void formNameIsKept() {
			assertEquals("f", build(button("a")).formName());
		}
	}

	@Nested
	class ElementKinds {
		@ParameterizedTest
		@CsvSource({ "control,CONTROL", "input,INPUT", "container,CONTAINER", "static,STATIC", "unknown,STATIC" })
		@DisplayName("the element kind is mapped, an unknown kind becomes STATIC")
		void kindIsMapped(String kind, Kind expected) {
			assertEquals(expected, build(element("a", BUTTON, kind, null)).elements().get(0).kind());
		}

		@Test
		@DisplayName("static and container elements are present in the model with their kind")
		void staticAndContainerArePresent() {
			FormSpecModel model = build(label("lbl"),
					element("box", "servoycore-formcontainer", InteractiveElement.KIND_CONTAINER, null));

			assertEquals(List.of(Kind.STATIC, Kind.CONTAINER), model.elements().stream().map(Element::kind).toList());
		}

		@Test
		@DisplayName("hasDataProvider is true only when a dataprovider is bound")
		void hasDataProvider() {
			FormSpecModel model = build(element("a", TEXTBOX, InteractiveElement.KIND_INPUT, "name"),
					element("b", TEXTBOX, InteractiveElement.KIND_INPUT, null));

			assertEquals(List.of(true, false), model.elements().stream().map(Element::hasDataProvider).toList());
		}
	}

	@Nested
	class Handlers {
		@Test
		@DisplayName("onActionMethodID on a button is a CLICK and carries key, method, owner and the recognised effects")
		void onAction_isClick() {
			Handler handler = onlyHandler(build(button("btn", action(HIDE_LABEL)), label("lbl")), "btn");

			assertAll(() -> assertEquals("onActionMethodID", handler.key()),
					() -> assertEquals("hide", handler.method()), () -> assertEquals("forms.f", handler.owner()),
					() -> assertEquals(Gesture.CLICK, handler.gesture()), () -> assertFalse(handler.unresolved()),
					() -> assertFalse(handler.branching()),
					() -> assertEquals(List.of(new Effect(EffectKind.VISIBLE, "lbl", "false")), handler.effects()));
		}

		@Test
		@DisplayName("onDataChangeMethodID on a text input with a dataprovider is a CHANGE")
		void onDataChange_isChange() {
			HandlerBinding binding = HandlerBinding.resolved("onDataChangeMethodID", "changed", "forms.f",
					"function changed() {}");

			assertEquals(Gesture.CHANGE,
					onlyHandler(build(element("txt", TEXTBOX, InteractiveElement.KIND_INPUT, "name", binding)), "txt")
							.gesture());
		}

		@Test
		@DisplayName("onDataChangeMethodID on a text input without a dataprovider is NONE")
		void onDataChange_withoutDataProvider_isNone() {
			HandlerBinding binding = HandlerBinding.resolved("onDataChangeMethodID", "changed", "forms.f",
					"function changed() {}");

			assertEquals(Gesture.NONE,
					onlyHandler(build(element("txt", TEXTBOX, InteractiveElement.KIND_INPUT, null, binding)), "txt")
							.gesture());
		}

		@Test
		@DisplayName("onAction on a text input is NONE and the recogniser is not run")
		void onAction_onTextInput_isNone() {
			Handler handler = onlyHandler(
					build(element("txt", TEXTBOX, InteractiveElement.KIND_INPUT, "name", action(HIDE_LABEL)),
							label("lbl")),
					"txt");

			assertAll(() -> assertEquals(Gesture.NONE, handler.gesture()),
					() -> assertEquals(List.of(), handler.effects()), () -> assertFalse(handler.branching()));
		}

		@Test
		@DisplayName("onCellClick is NONE and the recogniser is not run for it")
		void onCellClick_isNoneAndNotAnalysed() {
			HandlerBinding binding = HandlerBinding.resolved("onCellClick", "cell", "forms.f",
					"function cell() { elements.lbl.visible = false; if (x) { y(); } }");

			Handler handler = onlyHandler(
					build(element("grid", "aggrid-groupingtable", InteractiveElement.KIND_CONTROL, null, binding),
							label("lbl")),
					"grid");

			assertAll(() -> assertEquals("onCellClick", handler.key()),
					() -> assertEquals(Gesture.NONE, handler.gesture()),
					() -> assertEquals(List.of(), handler.effects()), () -> assertFalse(handler.branching()),
					() -> assertFalse(handler.unresolved()));
		}

		@Test
		@DisplayName("an unresolved binding is marked unresolved, keeps its gesture and has no effects")
		void unresolvedBinding() {
			Handler handler = onlyHandler(
					build(button("btn", HandlerBinding.unresolved("onActionMethodID", "some-uuid"))), "btn");

			assertAll(() -> assertTrue(handler.unresolved()), () -> assertEquals(Gesture.CLICK, handler.gesture()),
					() -> assertNull(handler.method()), () -> assertNull(handler.owner()),
					() -> assertEquals(List.of(), handler.effects()), () -> assertFalse(handler.branching()));
		}

		@Test
		@DisplayName("a branching method sets branching on the handler")
		void branchingMethod() {
			Handler handler = onlyHandler(
					build(button("btn", action("function hide() { if (a) { elements.lbl.visible = false; } }")),
							label("lbl")),
					"btn");

			assertTrue(handler.branching());
		}

		@Test
		@DisplayName("a resolved binding without a declaration yields the not-analysable off-form note")
		void resolvedWithoutDeclaration() {
			Handler handler = onlyHandler(
					build(button("btn", HandlerBinding.resolved("onActionMethodID", "hide", "forms.f"))), "btn");

			assertAll(() -> assertEquals(Gesture.CLICK, handler.gesture()), () -> assertFalse(handler.branching()),
					() -> assertEquals(
							List.of(new Effect(EffectKind.OFF_FORM, null, "method body could not be analyzed")),
							handler.effects()));
		}
	}

	@Nested
	class EffectTargets {
		@Test
		@DisplayName("an effect on a name that is not in the element list becomes OFF_FORM with the original statement")
		void unknownTarget_becomesOffForm() {
			Handler handler = onlyHandler(
					build(button("btn", action("function hide() { elements.ghost.visible = false; }"))), "btn");

			assertEquals(List.of(new Effect(EffectKind.OFF_FORM, null, "elements.ghost.visible = false")),
					handler.effects());
		}

		@Test
		@DisplayName("a text effect on an unknown name keeps its quoted literal in the OFF_FORM statement")
		void unknownTextTarget_keepsOriginalStatement() {
			Handler handler = onlyHandler(
					build(button("btn", action("function hide() { elements.ghost.text = 'Hi'; }"))), "btn");

			assertEquals(List.of(new Effect(EffectKind.OFF_FORM, null, "elements.ghost.text = 'Hi'")),
					handler.effects());
		}

		@Test
		@DisplayName("an effect on a dropped element name becomes OFF_FORM")
		void droppedElementIsNoTarget() {
			InteractiveElement missing = new InteractiveElement("lbl", "x", InteractiveElement.KIND_CONTROL, null, null,
					false, true, List.of());

			Handler handler = onlyHandler(build(button("btn", action(HIDE_LABEL)), missing), "btn");

			assertEquals(List.of(new Effect(EffectKind.OFF_FORM, null, "elements.lbl.visible = false")),
					handler.effects());
		}

		@Test
		@DisplayName("known targets stay on-form, unknown ones are converted, off-form statements are untouched, in source order")
		void mixedTargets() {
			String declaration = "function hide() { elements.lbl.visible = false; elements.ghost.enabled = true; scopes.x.y(); }";

			Handler handler = onlyHandler(build(button("btn", action(declaration)), label("lbl")), "btn");

			assertEquals(List.of(new Effect(EffectKind.VISIBLE, "lbl", "false"),
					new Effect(EffectKind.OFF_FORM, null, "elements.ghost.enabled = true"),
					new Effect(EffectKind.OFF_FORM, null, "scopes.x.y()")), handler.effects());
		}
	}

	@Nested
	class HandlerBindingFactories {
		@Test
		@DisplayName("resolved without a declaration has declaration null")
		void resolvedWithoutDeclaration() {
			HandlerBinding binding = HandlerBinding.resolved("onActionMethodID", "m", "forms.f");

			assertAll(() -> assertNull(binding.declaration()), () -> assertFalse(binding.unresolved()),
					() -> assertNull(binding.methodId()), () -> assertEquals("m", binding.method()),
					() -> assertEquals("forms.f", binding.owner()));
		}

		@Test
		@DisplayName("resolved with a declaration carries it")
		void resolvedWithDeclaration() {
			assertEquals("function m() {}",
					HandlerBinding.resolved("onActionMethodID", "m", "forms.f", "function m() {}").declaration());
		}

		@Test
		@DisplayName("unresolved keeps the raw id and has no method, owner or declaration")
		void unresolved() {
			HandlerBinding binding = HandlerBinding.unresolved("onActionMethodID", "some-uuid");

			assertAll(() -> assertTrue(binding.unresolved()), () -> assertEquals("some-uuid", binding.methodId()),
					() -> assertNull(binding.method()), () -> assertNull(binding.owner()),
					() -> assertNull(binding.declaration()));
		}

		@Test
		@DisplayName("equality includes the declaration")
		void equality() {
			assertAll(
					() -> assertEquals(HandlerBinding.resolved("k", "m", "o", "d"),
							HandlerBinding.resolved("k", "m", "o", "d")),
					() -> assertNotEquals(HandlerBinding.resolved("k", "m", "o", "d"),
							HandlerBinding.resolved("k", "m", "o", "e")),
					() -> assertEquals(HandlerBinding.resolved("k", "m", "o"),
							HandlerBinding.resolved("k", "m", "o", null)),
					() -> assertEquals(HandlerBinding.unresolved("k", "id"), HandlerBinding.unresolved("k", "id")));
		}
	}
}
