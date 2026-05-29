/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.List;

import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.e4.core.di.annotations.Creatable;

/**
 * Provides Servoy API documentation lookup utilities.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.tools.documentation.DocumentationToolsHelper}.
 * Includes signature formatting, full member documentation formatting, and class-name to
 * scriptingName mapping for Servoy global types.
 * </p>
 */
@Creatable
public class ServoyDocumentationService
{
	/**
	 * Maps Java class names to {@code @ServoyDocumented} scriptingName values.
	 * DLTK returns class names (e.g. {@code "JSApplication"}) but TypeCreator registers
	 * by scriptingName (e.g. {@code "application"}).
	 *
	 * @param className the Java class name
	 * @return the scriptingName, or {@code null} if no mapping exists
	 */
	public String mapClassNameToScriptingName(String className)
	{
		if (className == null) return null;
		return switch (className)
		{
			case "JSApplication" -> "application";
			case "JSDatabaseManager" -> "databaseManager";
			case "JSSecurity" -> "security";
			case "JSI18N" -> "i18n";
			case "JSUtils" -> "utils";
			case "JSForm" -> "controller";
			case "JSEventsManager" -> "eventsManager";
			case "JSSolutionModel" -> "solutionModel";
			default -> null;
		};
	}

	/**
	 * Formats a member signature without full documentation (lightweight).
	 * Examples: {@code "getFoundSet(query): JSFoundSet"}, {@code "loadAllRecords(): Boolean"}.
	 */
	public String formatMemberSignature(Member member)
	{
		if (member == null) return "";

		StringBuilder sb = new StringBuilder();
		sb.append(member.getName());

		if (member instanceof Method method)
		{
			sb.append("(");
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				for (int i = 0; i < params.size(); i++)
				{
					Parameter param = params.get(i);
					sb.append(param.getName());
					if (param.getType() != null)
						sb.append(":").append(param.getType().getName());
					if (i < params.size() - 1) sb.append(", ");
				}
			}
			sb.append(")");
			if (method.getType() != null)
				sb.append(": ").append(method.getType().getName());
		}
		else if (member instanceof Property property)
		{
			if (property.getType() != null)
				sb.append(": ").append(property.getType().getName());
		}

		return sb.toString();
	}

	/**
	 * Formats full documentation for a member: signature, description, parameters, return type,
	 * deprecation status.
	 */
	public String formatMemberDocumentation(Member member, String typeName)
	{
		if (member == null) return "";

		StringBuilder sb = new StringBuilder();
		sb.append("SIGNATURE: ").append(typeName).append(".").append(formatMemberSignature(member)).append("\n\n");

		String description = member.getDescription();
		if (description != null && !description.trim().isEmpty())
			sb.append("DESCRIPTION:\n").append(description).append("\n\n");

		if (member instanceof Method method)
		{
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				sb.append("PARAMETERS:\n");
				for (Parameter param : params)
				{
					sb.append("  - ").append(param.getName());
					if (param.getType() != null)
						sb.append(" (").append(param.getType().getName()).append(")");
					sb.append("\n");
				}
				sb.append("\n");
			}
			if (method.getType() != null)
				sb.append("RETURNS: ").append(method.getType().getName()).append("\n");
		}

		if (member.isDeprecated())
		{
			sb.append("\n[DEPRECATED]");
			if (description == null || !description.toLowerCase().contains("deprecated"))
				sb.append(" This member is deprecated.");
			sb.append("\n");
		}

		return sb.toString();
	}
}
