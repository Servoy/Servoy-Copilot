package com.servoy.eclipse.servoypilot.context.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable data class representing complete code context for a selection.
 * Contains selection info and all extracted identifier contexts.
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

	/**
	 * Creates a successful CodeContext with extracted identifiers.
	 * 
	 * @param selectionInfo the selection metadata
	 * @param identifiers the list of identifier contexts
	 * @return new CodeContext instance
	 */
	public static CodeContext success(SelectionInfo selectionInfo, List<IdentifierContext> identifiers)
	{
		return new CodeContext(selectionInfo, identifiers != null ? identifiers : Collections.emptyList(), false, null);
	}

	/**
	 * Creates a CodeContext representing an error condition.
	 * 
	 * @param selectionInfo the selection metadata (may be null)
	 * @param errorMessage the error message
	 * @return new CodeContext instance with error
	 */
	public static CodeContext error(SelectionInfo selectionInfo, String errorMessage)
	{
		return new CodeContext(selectionInfo, Collections.emptyList(), true, errorMessage);
	}

	/**
	 * Creates an empty CodeContext (no identifiers found).
	 * 
	 * @param selectionInfo the selection metadata
	 * @return new CodeContext instance with no identifiers
	 */
	public static CodeContext empty(SelectionInfo selectionInfo)
	{
		return new CodeContext(selectionInfo, Collections.emptyList(), false, null);
	}

	public SelectionInfo getSelectionInfo()
	{
		return selectionInfo;
	}

	public List<IdentifierContext> getIdentifiers()
	{
		return identifiers;
	}

	public boolean hasError()
	{
		return hasError;
	}

	public String getErrorMessage()
	{
		return errorMessage;
	}

	public boolean isEmpty()
	{
		return identifiers.isEmpty() && !hasError;
	}

	public int getIdentifierCount()
	{
		return identifiers.size();
	}

	/**
	 * Formats the context as XML for LLM consumption.
	 * Matches AI Bridge format: <type>...</type> and <description>...</description> tags.
	 * 
	 * @return XML formatted string
	 */
	public String getFormattedXML()
	{
		if (hasError)
		{
			return "<error>" + errorMessage + "</error>";
		}

		if (isEmpty())
		{
			return "<!-- No context information available -->";
		}

		StringBuilder sb = new StringBuilder();
		for (IdentifierContext identifier : identifiers)
		{
			sb.append(identifier.toFormattedXML());
		}

		return sb.toString().trim();
	}

	/**
	 * Formats the context as plain text for LLM consumption.
	 * 
	 * @return plain text formatted string
	 */
	public String getFormattedPlainText()
	{
		if (hasError)
		{
			return "Error: " + errorMessage;
		}

		if (isEmpty())
		{
			return "No context information available.";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Code Context:\n");
		sb.append("=============\n\n");

		for (IdentifierContext identifier : identifiers)
		{
			sb.append(identifier.toFormattedString());
			sb.append("\n\n");
		}

		return sb.toString();
	}

	@Override
	public String toString()
	{
		if (hasError)
		{
			return "CodeContext{error='" + errorMessage + "'}";
		}
		return "CodeContext{identifiers=" + identifiers.size() + ", selection=" + selectionInfo + "}";
	}
}
