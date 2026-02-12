package com.servoy.eclipse.servoypilot.context.dto;

/**
 * Immutable data class representing a single identifier's context.
 * Contains type information and documentation for one identifier in the code.
 */
public final class IdentifierContext
{
	/**
	 * Kind of identifier for categorization.
	 */
	public enum IdentifierKind
	{
		SERVOY_API,        // Servoy built-in API (plugins.*, application.*, etc.)
		WEB_COMPONENT,     // RuntimeWebComponent<componentName>
		WEB_SERVICE,       // WebService<serviceName>
		SOLUTION_FUNCTION, // User-defined function in solution
		UNKNOWN            // Could not determine type
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

	/**
	 * Creates an IdentifierContext instance.
	 * 
	 * @param name the identifier name
	 * @param typeName the resolved type name
	 * @param documentation the extracted documentation
	 * @param kind the kind of identifier
	 * @return new IdentifierContext instance
	 */
	public static IdentifierContext create(String name, String typeName, String documentation, IdentifierKind kind)
	{
		return new IdentifierContext(
			name != null ? name : "",
			typeName != null ? typeName : "Unknown",
			documentation != null ? documentation : "",
			kind != null ? kind : IdentifierKind.UNKNOWN
		);
	}

	public String getName()
	{
		return name;
	}

	public String getTypeName()
	{
		return typeName;
	}

	public String getDocumentation()
	{
		return documentation;
	}

	public IdentifierKind getKind()
	{
		return kind;
	}

	public boolean hasDocumentation()
	{
		return documentation != null && !documentation.trim().isEmpty();
	}

	/**
	 * Formats this identifier context for LLM consumption (plain text).
	 * 
	 * @return formatted string
	 */
	public String toFormattedString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(name).append(": ").append(typeName);
		if (hasDocumentation())
		{
			sb.append("\n").append(documentation);
		}
		return sb.toString();
	}

	/**
	 * Formats this identifier context as XML for LLM consumption.
	 * 
	 * @return XML formatted string
	 */
	public String toFormattedXML()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("<type>").append(name).append(": ").append(typeName).append("</type>\n");
		if (hasDocumentation())
		{
			sb.append("<description>").append(documentation).append("</description>\n");
		}
		return sb.toString();
	}

	@Override
	public String toString()
	{
		return "IdentifierContext{name='" + name + "', type='" + typeName + "', kind=" + kind + "}";
	}
}
