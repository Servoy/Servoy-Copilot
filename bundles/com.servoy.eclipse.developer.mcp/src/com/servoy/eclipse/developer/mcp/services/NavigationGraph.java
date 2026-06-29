package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class NavigationGraph {
	private final Map<String, List<NavigationEdge>> adjacency = new HashMap<>();
	private final Map<String, List<NavigationEdge>> reverseAdjacency = new HashMap<>();

	public void addEdge(NavigationEdge edge) {
		adjacency.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
		reverseAdjacency.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge);
	}

	public List<NavigationEdge> getEdgesFrom(String formName) {
		return adjacency.getOrDefault(formName, Collections.emptyList());
	}

	public List<NavigationEdge> getEdgesTo(String formName) {
		return reverseAdjacency.getOrDefault(formName, Collections.emptyList());
	}

	public List<NavigationEdge> getAllEdges() {
		List<NavigationEdge> all = new ArrayList<>();
		for (List<NavigationEdge> edges : adjacency.values()) {
			all.addAll(edges);
		}
		return all;
	}

	public Set<String> getAllFormNames() {
		Set<String> names = new HashSet<>();
		names.addAll(adjacency.keySet());
		names.addAll(reverseAdjacency.keySet());
		return names;
	}

	public List<NavigationEdge> findPath(String fromForm, String toForm) {
		if (fromForm == null || toForm == null)
			return Collections.emptyList();
		if (fromForm.equals(toForm))
			return Collections.emptyList();

		Queue<String> queue = new LinkedList<>();
		Map<String, NavigationEdge> cameFrom = new HashMap<>();
		Set<String> visited = new HashSet<>();

		queue.add(fromForm);
		visited.add(fromForm);

		while (!queue.isEmpty()) {
			String current = queue.poll();
			List<NavigationEdge> edges = getEdgesFrom(current);
			for (NavigationEdge edge : edges) {
				String next = edge.getTo();
				if (visited.contains(next))
					continue;
				visited.add(next);
				cameFrom.put(next, edge);
				if (next.equals(toForm)) {
					return reconstructPath(cameFrom, fromForm, toForm);
				}
				queue.add(next);
			}
		}
		return Collections.emptyList();
	}

	private List<NavigationEdge> reconstructPath(Map<String, NavigationEdge> cameFrom, String fromForm, String toForm) {
		List<NavigationEdge> path = new ArrayList<>();
		String current = toForm;
		while (!current.equals(fromForm)) {
			NavigationEdge edge = cameFrom.get(current);
			if (edge == null)
				break;
			path.add(0, edge);
			current = edge.getFrom();
		}
		return path;
	}

	public List<NavigationEdge> getSubgraphEdges(String fromForm, String targetForm) {
		List<NavigationEdge> path = findPath(fromForm, targetForm);
		if (path.isEmpty())
			return Collections.emptyList();

		Set<String> relevantForms = new HashSet<>();
		for (NavigationEdge edge : path) {
			relevantForms.add(edge.getFrom());
			relevantForms.add(edge.getTo());
		}

		List<NavigationEdge> subgraph = new ArrayList<>();
		for (String form : relevantForms) {
			subgraph.addAll(getEdgesFrom(form));
		}
		return subgraph;
	}
}
