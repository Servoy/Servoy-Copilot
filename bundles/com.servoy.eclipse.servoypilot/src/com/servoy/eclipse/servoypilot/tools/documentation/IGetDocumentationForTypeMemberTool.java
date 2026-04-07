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

import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Type;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.model.util.ServoyLog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetDocumentationForTypeMemberTool
{
	@Tool("Returns full documentation for one specific method or property of a Servoy API type — description, all parameters, return type, and overloads. " +
		"Works without any file or editor context.")
	default String getDocumentationForTypeMember(
		@P("Servoy API type name (e.g., 'application', 'databaseManager', 'JSFoundSet')") String typeName,
		@P("Member name to look up — case-insensitive (e.g., 'getFoundSet', 'loadAllRecords', 'showInfoDialog')") String memberName)
	{
		if (typeName == null || typeName.trim().isEmpty())
		{
			return "Error: typeName parameter is required";
		}

		if (memberName == null || memberName.trim().isEmpty())
		{
			return "Error: memberName parameter is required";
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
				return "Error: Type '" + typeName + "' not found";
			}

			List<Member> matchingMembers = new ArrayList<>();
			for (Member member : type.getMembers())
			{
				if (member.getName().equalsIgnoreCase(memberName))
				{
					matchingMembers.add(member);
				}
			}

			if (matchingMembers.isEmpty())
			{
				return "Error: Member '" + memberName + "' not found in type '" + type.getName() + "'";
			}

			StringBuilder response = new StringBuilder();
			response.append("=== DOCUMENTATION FOR: ").append(type.getName()).append(".").append(memberName).append(" ===\n\n");

			if (matchingMembers.size() > 1)
			{
				response.append("[Note: ").append(matchingMembers.size()).append(" overloads found]\n\n");
			}

			int overloadNum = 1;
			for (Member member : matchingMembers)
			{
				if (matchingMembers.size() > 1)
				{
					response.append("--- OVERLOAD ").append(overloadNum).append(" of ").append(matchingMembers.size()).append(" ---\n");
				}
				response.append(helper.formatMemberDocumentation(member, type.getName()));
				response.append("\n");
				overloadNum++;
			}

			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting documentation for member: " + typeName + "." + memberName, e);
			return "Error: " + e.getMessage();
		}
	}
}
