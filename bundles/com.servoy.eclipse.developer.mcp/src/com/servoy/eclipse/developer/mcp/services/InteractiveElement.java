/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.List;

/**
 * One interactive (or, optionally, static named) element of a form. Immutable, no Servoy/Eclipse types.
 *
 * @param name the element name, {@code null}/blank when the element is unnamed
 * @param type spec name for web components, persist type name for legacy persists
 * @param kind one of {@link #KIND_CONTROL}, {@link #KIND_INPUT}, {@link #KIND_CONTAINER}, {@link #KIND_STATIC}
 * @param dataProvider the bound dataprovider, {@code null} when there is none
 * @param container name of the enclosing layout container / portal, {@code null} when there is none
 * @param inherited true when the element is defined in a super form
 * @param specMissing true when the web component spec is not installed (handlers cannot be listed)
 * @param handlers the bound handlers, never {@code null}
 */
public record InteractiveElement(String name, String type, String kind, String dataProvider, String container, boolean inherited,
	boolean specMissing, List<HandlerBinding> handlers)
{
	public static final String KIND_CONTROL = "control";
	public static final String KIND_INPUT = "input";
	public static final String KIND_CONTAINER = "container";
	public static final String KIND_STATIC = "static";

	public InteractiveElement
	{
		handlers = handlers == null ? List.of() : List.copyOf(handlers);
	}

	public boolean isUnnamed()
	{
		return name == null || name.isBlank();
	}
}
