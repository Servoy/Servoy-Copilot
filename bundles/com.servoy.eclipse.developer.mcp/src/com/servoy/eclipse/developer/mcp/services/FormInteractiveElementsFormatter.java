/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure serializer for the {@code getFormInteractiveElements} payload. No Servoy/Eclipse types, so it can be
 * tested without a workspace. The kind of each element is decided by the collector, this class only serializes,
 * truncates and adds notes.
 */
public final class FormInteractiveElementsFormatter
{
	public static final int DEFAULT_MAX_ELEMENTS = 100;
	public static final int MAX_ELEMENTS_LIMIT = 500;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The header fields of the form the elements belong to. */
	public record FormHeader(String name, String title, String module, String dataSource, String layout)
	{
	}

	private FormInteractiveElementsFormatter()
	{
	}

	/** {@code <=0} means the default (100), the hard maximum is 500. */
	public static int clampMaxElements(int requested)
	{
		if (requested <= 0) return DEFAULT_MAX_ELEMENTS;
		return Math.min(requested, MAX_ELEMENTS_LIMIT);
	}

	/** {@code onActionMethodID} gives {@code onAction}; keys without the {@code MethodID} suffix are returned as is. */
	public static String eventName(String handlerKey)
	{
		if (handlerKey == null) return null;
		String suffix = "MethodID";
		if (handlerKey.length() > suffix.length() && handlerKey.endsWith(suffix))
		{
			return handlerKey.substring(0, handlerKey.length() - suffix.length());
		}
		return handlerKey;
	}

	/** {@code <form>.<element>}, or {@code null} for an unnamed element. */
	public static String dataCy(String form, String element)
	{
		if (element == null || element.isBlank()) return null;
		return form + "." + element;
	}

	/** {@code [data-cy="<form>.<element>"]} with quotes and backslashes escaped, or {@code null} for an unnamed element. */
	public static String selector(String form, String element)
	{
		String dataCy = dataCy(form, element);
		if (dataCy == null) return null;
		return "[data-cy=\"" + dataCy.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
	}

	/** The full payload: form header plus the elements part. Compact JSON. */
	public static String toJson(FormHeader header, List<InteractiveElement> elements, boolean includeStatic, int maxElements)
	{
		Objects.requireNonNull(header, "header");
		ObjectNode root = MAPPER.createObjectNode();
		root.put("form", header.name());
		putOrNull(root, "title", header.title());
		putOrNull(root, "module", header.module());
		putOrNull(root, "dataSource", header.dataSource());
		putOrNull(root, "layout", header.layout());
		root.setAll(toElementsNode(header.name(), elements, includeStatic, maxElements));
		return root.toString();
	}

	/**
	 * The payload without the form header: {@code totalElements}, {@code returned}, {@code truncated},
	 * {@code elements} and {@code notes}. Used on its own by {@code locateForm}.
	 */
	public static ObjectNode toElementsNode(String formName, List<InteractiveElement> elements, boolean includeStatic, int maxElements)
	{
		List<InteractiveElement> filtered = new ArrayList<>();
		if (elements != null)
		{
			for (InteractiveElement element : elements)
			{
				if (element == null) continue;
				if (includeStatic || !InteractiveElement.KIND_STATIC.equals(element.kind())) filtered.add(element);
			}
		}
		int limit = clampMaxElements(maxElements);
		int total = filtered.size();
		int returned = Math.min(total, limit);

		ObjectNode node = MAPPER.createObjectNode();
		node.put("totalElements", total);
		node.put("returned", returned);
		node.put("truncated", returned < total);
		ArrayNode array = node.putArray("elements");
		for (int i = 0; i < returned; i++)
		{
			array.add(elementNode(formName, filtered.get(i)));
		}
		ArrayNode notes = node.putArray("notes");
		for (String note : buildNotes(formName, filtered, returned, limit))
		{
			notes.add(note);
		}
		return node;
	}

	/** One element as JSON: name, type, kind, dataprovider, data-cy selector, container, inherited flag and its handlers. */
	private static ObjectNode elementNode(String formName, InteractiveElement element)
	{
		ObjectNode node = MAPPER.createObjectNode();
		putOrNull(node, "name", element.isUnnamed() ? null : element.name());
		putOrNull(node, "type", element.type());
		node.put("kind", element.kind());
		if (element.dataProvider() != null) node.put("dataProvider", element.dataProvider());
		putOrNull(node, "dataCy", dataCy(formName, element.name()));
		putOrNull(node, "selector", selector(formName, element.name()));
		if (element.container() != null) node.put("container", element.container());
		node.put("inherited", element.inherited());
		if (element.specMissing()) node.put("specMissing", true);
		ArrayNode handlers = node.putArray("handlers");
		for (HandlerBinding binding : element.handlers())
		{
			ObjectNode handler = handlers.addObject();
			handler.put("handler", binding.handler());
			handler.put("event", eventName(binding.handler()));
			putOrNull(handler, "method", binding.method());
			if (binding.owner() != null) handler.put("owner", binding.owner());
			if (binding.unresolved())
			{
				handler.put("unresolved", true);
				putOrNull(handler, "methodId", binding.methodId());
			}
		}
		return node;
	}

	/**
	 * The {@code notes} of the result: what the caller cannot rely on. Reports an empty form, unnamed elements
	 * (no data-cy selector), elements whose component spec is not installed, unresolved handlers and truncation.
	 */
	private static List<String> buildNotes(String formName, List<InteractiveElement> filtered, int returned, int limit)
	{
		List<String> notes = new ArrayList<>();
		if (filtered.isEmpty())
		{
			notes.add("No interactive elements found on form '" + formName + "'.");
			return notes;
		}

		int unnamed = 0;
		Set<String> unnamedTypes = new LinkedHashSet<>();
		int specMissing = 0;
		Set<String> specMissingTypes = new LinkedHashSet<>();
		int unresolved = 0;
		for (InteractiveElement element : filtered)
		{
			if (element.isUnnamed())
			{
				unnamed++;
				unnamedTypes.add(String.valueOf(element.type()));
			}
			if (element.specMissing())
			{
				specMissing++;
				specMissingTypes.add(String.valueOf(element.type()));
			}
			for (HandlerBinding binding : element.handlers())
			{
				if (binding.unresolved()) unresolved++;
			}
		}
		if (unnamed > 0)
		{
			notes.add(unnamed + (unnamed == 1 ? " element has" : " elements have") + " no name and cannot be targeted by data-cy: " +
				String.join(", ", unnamedTypes));
		}
		if (specMissing > 0)
		{
			notes.add(specMissing + (specMissing == 1 ? " element uses" : " elements use") +
				" a component whose spec is not installed (handlers cannot be listed): " +
				String.join(", ", specMissingTypes));
		}
		if (unresolved > 0)
		{
			notes.add(unresolved + (unresolved == 1 ? " handler" : " handlers") + " could not be resolved to a method name (methodId holds the raw value)");
		}
		if (returned < filtered.size())
		{
			notes.add("Result truncated to " + limit + " elements; " + (filtered.size() - returned) + " more not returned. Raise maxElements (max " +
				MAX_ELEMENTS_LIMIT + ").");
		}
		return notes;
	}

	/** Writes the string, or an explicit JSON {@code null} so the field is always present. */
	private static void putOrNull(ObjectNode node, String field, String value)
	{
		if (value == null) node.putNull(field);
		else node.put(field, value);
	}
}
