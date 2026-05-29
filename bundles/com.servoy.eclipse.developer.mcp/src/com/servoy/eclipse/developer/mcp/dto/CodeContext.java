/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable data class representing complete code context for a selection.
 * Contains selection info and all extracted identifier contexts.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.dto.CodeContext}.
 * </p>
 */
public final class CodeContext
{
	private final SelectionInfo selectionInfo;
	private final List<IdentifierContext> identifiers;
	private final boolean hasError;
	private final String errorMessage;

	private CodeContext(SelectionInfo selectionInfo, List<IdentifierContext> identifiers, boolean hasError, String errorMessage)
	{
		this.selectionInfo = selectionInfo;
		this.identifiers = Collections.unmodifiableList(new ArrayList<>(identifiers));
		this.hasError = hasError;
		this.errorMessage = errorMessage;
	}

	public static CodeContext success(SelectionInfo selectionInfo, List<IdentifierContext> identifiers)
	{
		return new CodeContext(selectionInfo, identifiers != null ? identifiers : Collections.emptyList(), false, null);
	}

	public static CodeContext error(SelectionInfo selectionInfo, String errorMessage)
	{
		return new CodeContext(selectionInfo, Collections.emptyList(), true, errorMessage);
	}

	public static CodeContext empty(SelectionInfo selectionInfo)
	{
		return new CodeContext(selectionInfo, Collections.emptyList(), false, null);
	}

	public SelectionInfo getSelectionInfo() { return selectionInfo; }
	public List<IdentifierContext> getIdentifiers() { return identifiers; }
	public boolean hasError() { return hasError; }
	public String getErrorMessage() { return errorMessage; }
	public boolean isEmpty() { return identifiers.isEmpty() && !hasError; }
	public int getIdentifierCount() { return identifiers.size(); }

	public String getFormattedXML()
	{
		if (hasError) return "<error>" + errorMessage + "</error>";
		if (isEmpty()) return "<!-- No context information available -->";

		StringBuilder sb = new StringBuilder();
		for (IdentifierContext id : identifiers) sb.append(id.toFormattedXML());
		return sb.toString().trim();
	}

	public String getFormattedPlainText()
	{
		if (hasError) return "Error: " + errorMessage;
		if (isEmpty()) return "No context information available.";

		StringBuilder sb = new StringBuilder();
		sb.append("Code Context:\n=============\n\n");
		for (IdentifierContext id : identifiers)
			sb.append(id.toFormattedString()).append("\n\n");
		return sb.toString();
	}

	@Override
	public String toString()
	{
		if (hasError) return "CodeContext{error='" + errorMessage + "'}";
		return "CodeContext{identifiers=" + identifiers.size() + ", selection=" + selectionInfo + "}";
	}
}
