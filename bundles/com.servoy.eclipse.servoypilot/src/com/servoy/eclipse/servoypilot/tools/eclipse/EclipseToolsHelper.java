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
package com.servoy.eclipse.servoypilot.tools.eclipse;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Singleton helper providing shared logic for Eclipse tool interfaces.
 * Handles JSON arrays, quoted strings, and comma-separated glob patterns.
 */
public class EclipseToolsHelper
{
	private static final EclipseToolsHelper INSTANCE = new EclipseToolsHelper();

	private EclipseToolsHelper()
	{
	}

	public static EclipseToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String[] normalizeFileNamePatterns(String fileNamePatterns)
	{
		if (fileNamePatterns == null || fileNamePatterns.isBlank())
		{
			return new String[0];
		}

		fileNamePatterns = fileNamePatterns.trim();

		if (fileNamePatterns.startsWith("[") && fileNamePatterns.endsWith("]"))
		{
			try
			{
				ObjectMapper mapper = new ObjectMapper();
				return mapper.readValue(fileNamePatterns, String[].class);
			}
			catch (Exception e)
			{
				// fall through to comma-separated handling
			}
		}

		if (fileNamePatterns.startsWith("\"") && fileNamePatterns.endsWith("\""))
		{
			fileNamePatterns = fileNamePatterns.substring(1, fileNamePatterns.length() - 1);
		}

		String[] split = fileNamePatterns.split(",");
		List<String> result = new ArrayList<>();
		for (String s : split)
		{
			String clean = s.trim();
			if (!clean.isEmpty())
			{
				result.add(clean);
			}
		}
		return result.toArray(new String[0]);
	}
}
