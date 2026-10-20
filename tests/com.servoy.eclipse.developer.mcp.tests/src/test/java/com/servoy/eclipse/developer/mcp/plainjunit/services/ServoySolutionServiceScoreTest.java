package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService.FormCandidate;

class ServoySolutionServiceScoreTest
{
	private static int[] score(String name, String title, String ds, String comment, String queryLower) throws Exception
	{
		Method method = ServoySolutionService.class.getDeclaredMethod("score", String.class, String.class, String.class, String.class, String.class);
		method.setAccessible(true);
		return (int[])method.invoke(null, name, title, ds, comment, queryLower);
	}

	private static int compare(FormCandidate a, FormCandidate b) throws Exception
	{
		Method method = ServoySolutionService.class.getDeclaredMethod("compareCandidates", FormCandidate.class, FormCandidate.class);
		method.setAccessible(true);
		return (int)method.invoke(null, a, b);
	}

	@Test
	@DisplayName("exact name match is tier 3, case-insensitive on the form side")
	void score_exactName() throws Exception
	{
		assertArrayEquals(new int[] { 3, 0 }, score("OrderList", "Some title", null, null, "orderlist"));
	}

	@Test
	@DisplayName("exact title match is tier 3")
	void score_exactTitle() throws Exception
	{
		assertArrayEquals(new int[] { 3, 0 }, score("orders_form", "Orders List", null, null, "orders list"));
	}

	@Test
	@DisplayName("query contained in the name is tier 2")
	void score_nameContains() throws Exception
	{
		assertArrayEquals(new int[] { 2, 0 }, score("customer_detail", "Detail", null, null, "customer"));
	}

	@Test
	@DisplayName("query contained in the title is tier 2")
	void score_titleContains() throws Exception
	{
		assertArrayEquals(new int[] { 2, 0 }, score("frm_1", "Query performance popup", null, null, "performance"));
	}

	@Test
	@DisplayName("query contained in the datasource is tier 1")
	void score_datasourceContains() throws Exception
	{
		assertArrayEquals(new int[] { 1, 0 }, score("frm_1", "Title", "db:/example_data/orders", null, "example_data"));
	}

	@Test
	@DisplayName("query contained in the comment is tier 1")
	void score_commentContains() throws Exception
	{
		assertArrayEquals(new int[] { 1, 0 }, score("frm_1", "Title", null, "Shows the invoice overview", "invoice"));
	}

	@Test
	@DisplayName("a name/title contains match wins over a datasource match")
	void score_nameBeatsDatasource() throws Exception
	{
		assertArrayEquals(new int[] { 2, 0 }, score("orders_form", "Title", "db:/example_data/orders", null, "orders"));
	}

	@Test
	@DisplayName("words-only match is tier 0 with the number of matching words")
	void score_wordsOnly() throws Exception
	{
		assertArrayEquals(new int[] { 0, 1 }, score("customer_detail", "Customer Detail Dialog", null, null, "customer popup"));
	}

	@Test
	@DisplayName("three matching words give wordMatches 3 at tier 0, not the exact-match tier")
	void score_threeWordsDoNotCollideWithExact() throws Exception
	{
		int[] threeWords = score("customer_detail", "Customer Detail Dialog", null, null, "detail customer dialog");
		int[] exact = score("customer_detail", "Customer Detail Dialog", null, null, "customer_detail");

		assertArrayEquals(new int[] { 0, 3 }, threeWords);
		assertArrayEquals(new int[] { 3, 0 }, exact);
	}

	@Test
	@DisplayName("query words shorter than 3 characters are ignored")
	void score_shortWordsIgnored() throws Exception
	{
		assertArrayEquals(new int[] { 0, 1 }, score("customer_detail", "Customer Detail", null, null, "go to customer now"));
	}

	@Test
	@DisplayName("no match at all is {0, 0}")
	void score_noMatch() throws Exception
	{
		assertArrayEquals(new int[] { 0, 0 }, score("customer_detail", "Customer Detail", "db:/x/y", "comment", "zebra"));
	}

	@Test
	@DisplayName("an empty or null query is {0, 0}")
	void score_emptyQuery() throws Exception
	{
		assertArrayEquals(new int[] { 0, 0 }, score("customer_detail", "Customer Detail", null, null, ""));
		assertArrayEquals(new int[] { 0, 0 }, score("customer_detail", "Customer Detail", null, null, null));
	}

	@Test
	@DisplayName("null form fields are tolerated")
	void score_nullFields() throws Exception
	{
		assertArrayEquals(new int[] { 0, 0 }, score(null, null, null, null, "customer"));
	}

	@Test
	@DisplayName("compareCandidates orders by tier first, then by word matches, descending")
	void compareCandidates_order() throws Exception
	{
		FormCandidate exact = new FormCandidate(null, "m", 3, 0);
		FormCandidate contains = new FormCandidate(null, "m", 2, 0);
		FormCandidate threeWords = new FormCandidate(null, "m", 0, 3);
		FormCandidate twoWords = new FormCandidate(null, "m", 0, 2);

		assertTrue(compare(exact, contains) < 0, "higher tier sorts first");
		assertTrue(compare(contains, exact) > 0);
		assertTrue(compare(contains, threeWords) < 0, "tier beats word matches");
		assertTrue(compare(threeWords, twoWords) < 0, "more word matches sorts first within a tier");
		assertEquals(0, compare(new FormCandidate(null, "a", 2, 1), new FormCandidate(null, "b", 2, 1)));
	}
}
