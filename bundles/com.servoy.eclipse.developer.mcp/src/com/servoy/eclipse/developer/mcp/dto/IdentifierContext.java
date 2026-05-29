/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.dto;

/**
 * Immutable data class representing a single identifier's context.
 * Contains type information and documentation for one identifier in the code.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.dto.IdentifierContext}.
 * </p>
 */
public final class IdentifierContext
{
	public enum IdentifierKind
	{
		SERVOY_API,
		WEB_COMPONENT,
		WEB_SERVICE,
		SOLUTION_FUNCTION,
		UNKNOWN
	}

	private final String name;
	private final String typeName;
	private final String documentation;
	private final IdentifierKind kind;

	private IdentifierContext(String name, String typeName, String documentation, IdentifierKind kind)
	{
		this.name = name;
		this.typeName = typeName;
		this.documentation = documentation;
		this.kind = kind;
	}

	public static IdentifierContext create(String name, String typeName, String documentation, IdentifierKind kind)
	{
		return new IdentifierContext(
			name != null ? name : "",
			typeName != null ? typeName : "Unknown",
			documentation != null ? documentation : "",
			kind != null ? kind : IdentifierKind.UNKNOWN);
	}

	public String getName() { return name; }
	public String getTypeName() { return typeName; }
	public String getDocumentation() { return documentation; }
	public IdentifierKind getKind() { return kind; }

	public boolean hasDocumentation()
	{
		return documentation != null && !documentation.trim().isEmpty();
	}

	public String toFormattedString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(name).append(": ").append(typeName);
		if (hasDocumentation()) sb.append("\n").append(documentation);
		return sb.toString();
	}

	public String toFormattedXML()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("<type>").append(name).append(": ").append(typeName).append("</type>\n");
		if (hasDocumentation()) sb.append("<description>").append(documentation).append("</description>\n");
		return sb.toString();
	}

	@Override
	public String toString()
	{
		return "IdentifierContext{name='" + name + "', type='" + typeName + "', kind=" + kind + "}";
	}
}
