package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLocatorService;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphService;
import com.servoy.eclipse.developer.mcp.services.NavigationEdge;
import com.servoy.eclipse.developer.mcp.services.NavigationGraph;

class FormNavigationGraphReachabilityTest
{
	private NavigationGraph graph;

	@BeforeEach
	void setUp()
	{
		graph = new NavigationGraph();
		graph.addEdge(edge("main", "orders"));
		graph.addEdge(edge("orders", "order_detail"));
		graph.addEdge(edge("island_a", "island_b"));
	}

	private static NavigationEdge edge(String from, String to)
	{
		return new NavigationEdge.Builder().from(from).to(to).containerType("tabpanel").confidence("static").build();
	}

	@Test
	@DisplayName("the main form itself is isMainForm")
	void isMainForm()
	{
		assertEquals("isMainForm", FormNavigationGraphService.describeReachability(graph, "main", "main"));
	}

	@Test
	@DisplayName("the main form is isMainForm even when it is not in the graph")
	void isMainForm_notInGraph()
	{
		assertEquals("isMainForm", FormNavigationGraphService.describeReachability(new NavigationGraph(), "main", "main"));
	}

	@Test
	@DisplayName("a form with a BFS path from the main form is reachable (directly and transitively)")
	void reachable()
	{
		assertEquals("reachable", FormNavigationGraphService.describeReachability(graph, "main", "orders"));
		assertEquals("reachable", FormNavigationGraphService.describeReachability(graph, "main", "order_detail"));
	}

	@Test
	@DisplayName("a form in the graph without a path from the main form is unreachable")
	void unreachable()
	{
		assertEquals("unreachable", FormNavigationGraphService.describeReachability(graph, "main", "island_b"));
	}

	@Test
	@DisplayName("a form the graph has never seen is unknownForm")
	void unknownForm()
	{
		assertEquals("unknownForm", FormNavigationGraphService.describeReachability(graph, "main", "no_such_form"));
	}

	@Test
	@DisplayName("a null or blank form is unknownForm")
	void unknownForm_blank()
	{
		assertEquals("unknownForm", FormNavigationGraphService.describeReachability(graph, "main", null));
		assertEquals("unknownForm", FormNavigationGraphService.describeReachability(graph, "main", "   "));
	}

	@Test
	@DisplayName("without a main form reachability cannot be decided: noMainForm")
	void noMainForm()
	{
		assertEquals("noMainForm", FormNavigationGraphService.describeReachability(graph, null, "orders"));
	}

	@Test
	@DisplayName("the form name is trimmed before the lookup")
	void trimmedFormName()
	{
		assertEquals("reachable", FormNavigationGraphService.describeReachability(graph, "main", "  orders  "));
	}

	@Test
	@DisplayName("the subgraph variant keeps the legacy values: no main form falls back to unreachable for a known form")
	void subgraph_noMainFormKnownForm()
	{
		assertEquals("unreachable", FormNavigationGraphService.describeSubgraphReachability(graph, null, "orders"));
	}

	@Test
	@DisplayName("the subgraph variant keeps the legacy values: no main form falls back to unknownForm for an unknown form")
	void subgraph_noMainFormUnknownForm()
	{
		assertEquals("unknownForm", FormNavigationGraphService.describeSubgraphReachability(graph, null, "no_such_form"));
	}

	@Test
	@DisplayName("locateForm: a resolved form that no edge mentions is unreachable, never unknownForm")
	void located_formWithoutEdges_isUnreachable()
	{
		assertEquals("unknownForm", FormNavigationGraphService.describeReachability(graph, "main", "edgeless"),
			"precondition: the graph itself cannot tell an edge-less form from a wrong name");
		assertEquals("unreachable", FormLocatorService.locatedReachability(graph, "main", "edgeless"));
	}

	@Test
	@DisplayName("locateForm: the other reachability values are passed through unchanged")
	void located_otherValuesUnchanged()
	{
		assertEquals("isMainForm", FormLocatorService.locatedReachability(graph, "main", "main"));
		assertEquals("reachable", FormLocatorService.locatedReachability(graph, "main", "order_detail"));
		assertEquals("unreachable", FormLocatorService.locatedReachability(graph, "main", "island_b"));
		assertEquals("noMainForm", FormLocatorService.locatedReachability(graph, null, "orders"));
	}

	@Test
	@DisplayName("the subgraph variant equals describeReachability when a main form exists")
	void subgraph_withMainForm()
	{
		assertEquals("reachable", FormNavigationGraphService.describeSubgraphReachability(graph, "main", "orders"));
		assertEquals("isMainForm", FormNavigationGraphService.describeSubgraphReachability(graph, "main", "main"));
		assertEquals("unreachable", FormNavigationGraphService.describeSubgraphReachability(graph, "main", "island_b"));
	}
}
