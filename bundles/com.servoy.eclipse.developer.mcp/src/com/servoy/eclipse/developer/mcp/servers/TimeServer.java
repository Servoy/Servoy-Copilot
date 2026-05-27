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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.servers;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * MCP server exposing time-related tools.
 */
@McpServer(name = "time")
public class TimeServer
{
	@Tool(name = "currentTime", description = "Returns the current date and time in the following format: yyyy-MM-dd HH:mm:ss", type = "object")
	public String getCurrentTime()
	{
		ZonedDateTime now = ZonedDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
		return now.format(formatter);
	}

	@Tool(name = "convertTimeZone", description = "Converts time from one time zone to another. Returns a converted time in the yyyy-MM-dd HH:mm:ss z format.", type = "object")
	public String convertTimeZone(
		@ToolParam(name = "time", description = "Date/time in the format yyyy-MM-dd HH:mm:ss", required = true) String timeString,
		@ToolParam(name = "sourceZone", description = "Source time zone id such as, such as Europe/Paris or CST. Default: system time zone") String sourceZone,
		@ToolParam(name = "targetZone", description = "Target time zone id, such as Europe/Paris or CST. Default: UTC") String targetZone)
	{
		try
		{
			String resolvedSource = Optional.ofNullable(sourceZone).orElse(ZoneId.systemDefault().getId());
			String resolvedTarget = Optional.ofNullable(targetZone).orElse("UTC");

			ZoneId sourceZoneId = ZoneId.of(resolvedSource);
			ZoneId targetZoneId = ZoneId.of(resolvedTarget);

			if (resolvedSource.equals(resolvedTarget))
			{
				return timeString;
			}

			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
			var time = formatter.parse(timeString + " " + sourceZoneId.getId());
			var zoneTime = ZonedDateTime.from(time);
			ZonedDateTime convertedTime = zoneTime.withZoneSameInstant(targetZoneId);
			return convertedTime.format(formatter);
		}
		catch (Exception e)
		{
			return "Error converting time zone: " + e.getMessage();
		}
	}
}
