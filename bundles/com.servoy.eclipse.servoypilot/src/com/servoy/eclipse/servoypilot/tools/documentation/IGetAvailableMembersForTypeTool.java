/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.documentation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.dltk.javascript.typeinfo.model.Type;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.model.util.ServoyLog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetAvailableMembersForTypeTool
{
	int MEMBERS_THRESHOLD = 50;

	@Tool("Returns lightweight method and property signatures for a Servoy API type. " +
		"Returns signatures like 'getFoundSet(query): JSFoundSet', 'loadAllRecords(): Boolean'. " +
		"Truncates at 50 members — use memberFilter regex to narrow results: 'get.*' for getters, 'show.*|hide.*' for show/hide.")
	default String getAvailableMembersForType(
		@P("Servoy API type name (e.g., 'application', 'databaseManager', 'JSFoundSet', 'controller')") String typeName,
		@P("Optional regex filter for member names. Examples: 'get.*', 'is.*', 'show.*|hide.*'. Default: all members.") String memberFilter)
	{
		if (typeName == null || typeName.trim().isEmpty())
		{
			return "Error: typeName parameter is required";
		}

		try
		{
			TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
			if (typeCreator == null)
			{
				return "Error: TypeCreator not available";
			}

			DocumentationToolsHelper helper = DocumentationToolsHelper.getInstance();

			Type type = typeCreator.findType(null, typeName);
			if (type == null)
			{
				String scriptingName = helper.mapClassNameToScriptingName(typeName);
				if (scriptingName != null && !scriptingName.equals(typeName))
				{
					type = typeCreator.findType(null, scriptingName);
				}
			}

			if (type == null)
			{
				return "Error: Type '" + typeName + "' not found. Try using scriptingName like 'application' instead of 'JSApplication'.";
			}

			String filter = (memberFilter != null && !memberFilter.trim().isEmpty()) ? memberFilter.trim() : "*";
			Pattern pattern = filter.equals("*") ? null : Pattern.compile(filter, Pattern.CASE_INSENSITIVE);

			List<Member> methods = new ArrayList<>();
			List<Member> properties = new ArrayList<>();

			for (Member member : type.getMembers())
			{
				if (pattern != null && !pattern.matcher(member.getName()).matches())
				{
					continue;
				}

				if (member instanceof Method)
				{
					methods.add(member);
				}
				else if (member instanceof Property)
				{
					properties.add(member);
				}
			}

			int totalFiltered = methods.size() + properties.size();
			boolean truncated = totalFiltered > MEMBERS_THRESHOLD;

			StringBuilder response = new StringBuilder();
			response.append("=== AVAILABLE MEMBERS FOR TYPE: ").append(type.getName()).append(" ===\n\n");

			if (!filter.equals("*"))
			{
				response.append("Filter: ").append(filter).append("\n");
			}
			response.append("Total found: ").append(totalFiltered).append(" members\n\n");

			if (!methods.isEmpty())
			{
				response.append("METHODS (").append(methods.size()).append("):\n");
				int count = 0;
				for (Member method : methods)
				{
					if (truncated && count >= MEMBERS_THRESHOLD)
					{
						break;
					}
					response.append("  - ").append(helper.formatMemberSignature(method)).append("\n");
					count++;
				}
				response.append("\n");
			}

			if (!properties.isEmpty())
			{
				response.append("PROPERTIES (").append(properties.size()).append("):\n");
				int count = methods.size();
				for (Member property : properties)
				{
					if (truncated && count >= MEMBERS_THRESHOLD)
					{
						break;
					}
					response.append("  - ").append(helper.formatMemberSignature(property)).append("\n");
					count++;
				}
				response.append("\n");
			}

			if (truncated)
			{
				response.append("[WARNING: ").append(totalFiltered).append(" members found, showing first ").append(MEMBERS_THRESHOLD);
				response.append(". Use memberFilter with regex like 'get.*', 'show.*', or 'is.*' to narrow results]\n");
			}

			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting available members for type: " + typeName, e);
			return "Error: " + e.getMessage();
		}
	}
}
