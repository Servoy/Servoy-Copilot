package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsFormatter;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsFormatter.FormHeader;
import com.servoy.eclipse.developer.mcp.services.HandlerBinding;
import com.servoy.eclipse.developer.mcp.services.InteractiveElement;

class FormInteractiveElementsFormatterTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final FormHeader HEADER = new FormHeader("orders_list", "Orders", "orders_module", "db:/example_data/orders", "responsive");

	private static InteractiveElement button(String name)
	{
		return new InteractiveElement(name, "bootstrapcomponents-button", InteractiveElement.KIND_CONTROL, null, null, false, false,
			List.of(HandlerBinding.resolved("onActionMethodID", "newOrder", "forms.orders_list")));
	}

	private static InteractiveElement staticElement(String name)
	{
		return new InteractiveElement(name, "bootstrapcomponents-label", InteractiveElement.KIND_STATIC, null, null, false, false, List.of());
	}

	private static JsonNode parse(String json) throws Exception
	{
		return MAPPER.readTree(json);
	}

	private static List<String> names(JsonNode root)
	{
		List<String> names = new ArrayList<>();
		root.get("elements").forEach(e -> names.add(e.get("name").asText()));
		return names;
	}

	private static List<String> notes(JsonNode root)
	{
		List<String> notes = new ArrayList<>();
		root.get("notes").forEach(n -> notes.add(n.asText()));
		return notes;
	}

	// ---------------------------------------------------------------- eventName

	@ParameterizedTest
	@CsvSource({ "onActionMethodID,onAction", "onDataChangeMethodID,onDataChange", "onCellClick,onCellClick", "onRightClickMethodID,onRightClick" })
	@DisplayName("eventName strips the MethodID suffix and leaves other keys alone")
	void eventName_mapsHandlerKey(String key, String expected)
	{
		assertEquals(expected, FormInteractiveElementsFormatter.eventName(key));
	}

	@Test
	@DisplayName("eventName of null is null and the bare suffix is not stripped to an empty event")
	void eventName_nullAndBareSuffix()
	{
		assertAll(() -> assertNull(FormInteractiveElementsFormatter.eventName(null)),
			() -> assertEquals("MethodID", FormInteractiveElementsFormatter.eventName("MethodID")));
	}

	// ----------------------------------------------------------------- selector

	@Test
	@DisplayName("selector builds the data-cy attribute selector")
	void selector_basic()
	{
		assertEquals("[data-cy=\"orders_list.btn_new\"]", FormInteractiveElementsFormatter.selector("orders_list", "btn_new"));
	}

	@Test
	@DisplayName("selector escapes a double quote in the element name")
	void selector_escapesQuote()
	{
		assertEquals("[data-cy=\"f.a\\\"b\"]", FormInteractiveElementsFormatter.selector("f", "a\"b"));
	}

	@Test
	@DisplayName("selector escapes a backslash in the element name")
	void selector_escapesBackslash()
	{
		assertEquals("[data-cy=\"f.a\\\\b\"]", FormInteractiveElementsFormatter.selector("f", "a\\b"));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   " })
	@DisplayName("selector and dataCy of a null or blank element name are null")
	void selector_unnamedIsNull(String element)
	{
		assertAll(() -> assertNull(FormInteractiveElementsFormatter.selector("orders_list", element)),
			() -> assertNull(FormInteractiveElementsFormatter.dataCy("orders_list", element)));
	}

	@Test
	@DisplayName("dataCy is form dot element")
	void dataCy_basic()
	{
		assertEquals("orders_list.btn_new", FormInteractiveElementsFormatter.dataCy("orders_list", "btn_new"));
	}

	// ---------------------------------------------------------------- JSON shape

	@Test
	@DisplayName("toJson has the documented header, counts and one full element")
	void toJson_shape() throws Exception
	{
		InteractiveElement element = new InteractiveElement("btn_new", "bootstrapcomponents-button", InteractiveElement.KIND_CONTROL, null,
			"panel_toolbar", false, false, List.of(HandlerBinding.resolved("onActionMethodID", "newOrder", "forms.orders_list")));

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(element), false, 100));

		JsonNode first = root.get("elements").get(0);
		JsonNode handler = first.get("handlers").get(0);
		assertAll(() -> assertEquals("orders_list", root.get("form").asText()), () -> assertEquals("Orders", root.get("title").asText()),
			() -> assertEquals("orders_module", root.get("module").asText()),
			() -> assertEquals("db:/example_data/orders", root.get("dataSource").asText()),
			() -> assertEquals("responsive", root.get("layout").asText()), () -> assertEquals(1, root.get("totalElements").asInt()),
			() -> assertEquals(1, root.get("returned").asInt()), () -> assertFalse(root.get("truncated").asBoolean()),
			() -> assertEquals(1, root.get("elements").size()), () -> assertEquals("btn_new", first.get("name").asText()),
			() -> assertEquals("bootstrapcomponents-button", first.get("type").asText()), () -> assertEquals("control", first.get("kind").asText()),
			() -> assertEquals("orders_list.btn_new", first.get("dataCy").asText()),
			() -> assertEquals("[data-cy=\"orders_list.btn_new\"]", first.get("selector").asText()),
			() -> assertEquals("panel_toolbar", first.get("container").asText()), () -> assertFalse(first.get("inherited").asBoolean()),
			() -> assertFalse(first.has("dataProvider")), () -> assertFalse(first.has("specMissing")),
			() -> assertEquals(1, first.get("handlers").size()), () -> assertEquals("onActionMethodID", handler.get("handler").asText()),
			() -> assertEquals("onAction", handler.get("event").asText()), () -> assertEquals("newOrder", handler.get("method").asText()),
			() -> assertEquals("forms.orders_list", handler.get("owner").asText()), () -> assertFalse(handler.has("unresolved")),
			() -> assertFalse(handler.has("methodId")), () -> assertEquals(0, root.get("notes").size()));
	}

	@Test
	@DisplayName("toJson serializes dataProvider and inherited for an input element")
	void toJson_inputElement() throws Exception
	{
		InteractiveElement input = new InteractiveElement("fld_customer", "bootstrapcomponents-textbox", InteractiveElement.KIND_INPUT, "customerid",
			null, true, false, List.of(HandlerBinding.resolved("onDataChangeMethodID", "onCustomerChange", "scopes.orders")));

		JsonNode first = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(input), false, 100)).get("elements").get(0);

		assertAll(() -> assertEquals("input", first.get("kind").asText()), () -> assertEquals("customerid", first.get("dataProvider").asText()),
			() -> assertTrue(first.get("inherited").asBoolean()), () -> assertFalse(first.has("container")),
			() -> assertEquals("onDataChange", first.get("handlers").get(0).get("event").asText()),
			() -> assertEquals("onCustomerChange", first.get("handlers").get(0).get("method").asText()),
			() -> assertEquals("scopes.orders", first.get("handlers").get(0).get("owner").asText()));
	}

	@Test
	@DisplayName("toJson writes null header fields as JSON null")
	void toJson_nullHeaderFields() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(new FormHeader("f", null, null, null, "css"), List.of(button("b")), false, 100));

		assertAll(() -> assertEquals("f", root.get("form").asText()), () -> assertTrue(root.get("title").isNull()),
			() -> assertTrue(root.get("module").isNull()), () -> assertTrue(root.get("dataSource").isNull()),
			() -> assertEquals("css", root.get("layout").asText()));
	}

	@Test
	@DisplayName("toElementsNode has the elements part only, without the form header")
	void toElementsNode_hasNoHeader() throws Exception
	{
		JsonNode node = FormInteractiveElementsFormatter.toElementsNode("orders_list", List.of(button("b1")), false, 100);

		assertAll(() -> assertFalse(node.has("form")), () -> assertFalse(node.has("title")), () -> assertEquals(1, node.get("totalElements").asInt()),
			() -> assertEquals(1, node.get("returned").asInt()), () -> assertFalse(node.get("truncated").asBoolean()),
			() -> assertEquals("b1", node.get("elements").get(0).get("name").asText()), () -> assertEquals(0, node.get("notes").size()));
	}

	@Test
	@DisplayName("toJson output is compact (no newline) and parses")
	void toJson_isCompact() throws Exception
	{
		String json = FormInteractiveElementsFormatter.toJson(HEADER, List.of(button("b1"), button("b2")), false, 100);

		assertAll(() -> assertFalse(json.contains("\n")), () -> assertFalse(json.contains("\r")), () -> assertEquals(2, parse(json).get("elements").size()));
	}

	// ---------------------------------------------------------------- truncation

	@Test
	@DisplayName("maxElements=2 over 5 elements returns the first two in input order and flags truncation")
	void toJson_truncation() throws Exception
	{
		List<InteractiveElement> elements = List.of(button("e1"), button("e2"), button("e3"), button("e4"), button("e5"));

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, elements, false, 2));

		assertAll(() -> assertEquals(2, root.get("returned").asInt()), () -> assertEquals(5, root.get("totalElements").asInt()),
			() -> assertTrue(root.get("truncated").asBoolean()), () -> assertEquals(List.of("e1", "e2"), names(root)),
			() -> assertEquals(List.of("Result truncated to 2 elements; 3 more not returned. Raise maxElements (max 500)."), notes(root)));
	}

	@Test
	@DisplayName("maxElements equal to the element count is not truncated")
	void toJson_exactFitIsNotTruncated() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(button("e1"), button("e2")), false, 2));

		assertAll(() -> assertEquals(2, root.get("returned").asInt()), () -> assertEquals(2, root.get("totalElements").asInt()),
			() -> assertFalse(root.get("truncated").asBoolean()), () -> assertEquals(0, root.get("notes").size()));
	}

	@ParameterizedTest
	@CsvSource({ "0,100", "-5,100", "1,1", "7,7", "100,100", "500,500", "501,500", "100000,500" })
	@DisplayName("clampMaxElements: <=0 is 100, above 500 is 500, otherwise as requested")
	void clampMaxElements(int requested, int expected)
	{
		assertEquals(expected, FormInteractiveElementsFormatter.clampMaxElements(requested));
	}

	// ------------------------------------------------------------- includeStatic

	@Test
	@DisplayName("includeStatic=false drops static elements from the list and the count")
	void toJson_includeStaticFalse() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(button("c1"), staticElement("s1"), staticElement("s2")), false, 100));

		assertAll(() -> assertEquals(1, root.get("totalElements").asInt()), () -> assertEquals(1, root.get("returned").asInt()),
			() -> assertEquals(List.of("c1"), names(root)));
	}

	@Test
	@DisplayName("includeStatic=true keeps static elements and counts them")
	void toJson_includeStaticTrue() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(button("c1"), staticElement("s1"), staticElement("s2")), true, 100));

		assertAll(() -> assertEquals(3, root.get("totalElements").asInt()), () -> assertEquals(3, root.get("returned").asInt()),
			() -> assertEquals(List.of("c1", "s1", "s2"), names(root)), () -> assertEquals("static", root.get("elements").get(1).get("kind").asText()));
	}

	// -------------------------------------------------------------------- notes

	@Test
	@DisplayName("an unnamed element has null name, dataCy and selector and is counted in the notes")
	void toJson_unnamedElement() throws Exception
	{
		InteractiveElement unnamed = new InteractiveElement(null, "bootstrapcomponents-button", InteractiveElement.KIND_CONTROL, null, null, false, false,
			List.of(HandlerBinding.resolved("onActionMethodID", "doIt", "forms.orders_list")));

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(unnamed), false, 100));
		JsonNode first = root.get("elements").get(0);

		assertAll(() -> assertTrue(first.get("name").isNull()), () -> assertTrue(first.get("dataCy").isNull()),
			() -> assertTrue(first.get("selector").isNull()),
			() -> assertEquals(List.of("1 element has no name and cannot be targeted by data-cy: bootstrapcomponents-button"), notes(root)));
	}

	@Test
	@DisplayName("several unnamed elements are counted once with their distinct types")
	void toJson_unnamedElementsCounted() throws Exception
	{
		InteractiveElement a = new InteractiveElement("  ", "typeA", InteractiveElement.KIND_CONTROL, null, null, false, false, List.of());
		InteractiveElement b = new InteractiveElement(null, "typeB", InteractiveElement.KIND_INPUT, "dp", null, false, false, List.of());
		InteractiveElement c = new InteractiveElement(null, "typeA", InteractiveElement.KIND_CONTROL, null, null, false, false, List.of());

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(a, b, c), false, 100));

		assertEquals(List.of("3 elements have no name and cannot be targeted by data-cy: typeA, typeB"), notes(root));
	}

	@Test
	@DisplayName("an unresolved handler has null method, unresolved=true, the raw methodId and is counted in the notes")
	void toJson_unresolvedHandler() throws Exception
	{
		InteractiveElement element = new InteractiveElement("btn", "bootstrapcomponents-button", InteractiveElement.KIND_CONTROL, null, null, false, false,
			List.of(HandlerBinding.unresolved("onActionMethodID", "0a1b2c3d-raw-id")));

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(element), false, 100));
		JsonNode handler = root.get("elements").get(0).get("handlers").get(0);

		assertAll(() -> assertEquals("onActionMethodID", handler.get("handler").asText()), () -> assertEquals("onAction", handler.get("event").asText()),
			() -> assertTrue(handler.get("method").isNull()), () -> assertFalse(handler.has("owner")),
			() -> assertTrue(handler.get("unresolved").asBoolean()), () -> assertEquals("0a1b2c3d-raw-id", handler.get("methodId").asText()),
			() -> assertEquals(List.of("1 handler could not be resolved to a method name (methodId holds the raw value)"), notes(root)));
	}

	@Test
	@DisplayName("several unresolved handlers are summed in one note")
	void toJson_unresolvedHandlersSummed() throws Exception
	{
		InteractiveElement a = new InteractiveElement("a", "t", InteractiveElement.KIND_CONTROL, null, null, false, false,
			List.of(HandlerBinding.unresolved("onActionMethodID", "id1"), HandlerBinding.unresolved("onRightClickMethodID", "id2")));
		InteractiveElement b = new InteractiveElement("b", "t", InteractiveElement.KIND_CONTROL, null, null, false, false,
			List.of(HandlerBinding.unresolved("onActionMethodID", "id3")));

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(a, b), false, 100));

		assertEquals(List.of("3 handlers could not be resolved to a method name (methodId holds the raw value)"), notes(root));
	}

	@Test
	@DisplayName("a specMissing element is serialized with specMissing=true, no handlers and a note")
	void toJson_specMissingElement() throws Exception
	{
		InteractiveElement element = new InteractiveElement("fake", "nonexistent-fake", InteractiveElement.KIND_CONTROL, null, null, false, true, List.of());

		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(element), false, 100));
		JsonNode first = root.get("elements").get(0);

		assertAll(() -> assertEquals("nonexistent-fake", first.get("type").asText()), () -> assertTrue(first.get("specMissing").asBoolean()),
			() -> assertEquals(0, first.get("handlers").size()),
			() -> assertEquals(List.of("1 element uses a component whose spec is not installed (handlers cannot be listed): nonexistent-fake"),
				notes(root)));
	}

	@Test
	@DisplayName("an empty element list gives valid JSON with elements [] and the no-interactive-elements note")
	void toJson_emptyList() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(), false, 100));

		assertAll(() -> assertTrue(root.get("elements").isArray()), () -> assertEquals(0, root.get("elements").size()),
			() -> assertEquals(0, root.get("totalElements").asInt()), () -> assertEquals(0, root.get("returned").asInt()),
			() -> assertFalse(root.get("truncated").asBoolean()),
			() -> assertEquals(List.of("No interactive elements found on form 'orders_list'."), notes(root)));
	}

	@Test
	@DisplayName("a null element list behaves like an empty one")
	void toJson_nullList() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, null, false, 100));

		assertAll(() -> assertEquals(0, root.get("elements").size()),
			() -> assertEquals(List.of("No interactive elements found on form 'orders_list'."), notes(root)));
	}

	@Test
	@DisplayName("a form with only static elements and includeStatic=false reports no interactive elements")
	void toJson_onlyStaticHidden() throws Exception
	{
		JsonNode root = parse(FormInteractiveElementsFormatter.toJson(HEADER, List.of(staticElement("s1")), false, 100));

		assertAll(() -> assertEquals(0, root.get("totalElements").asInt()),
			() -> assertEquals(List.of("No interactive elements found on form 'orders_list'."), notes(root)));
	}
}
