/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.servers;

import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.WpmService;
import com.servoy.eclipse.developer.mcp.services.WpmService.ScoredPackage;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "servoy-wpm")
public class ServoyWpmServer
{
	private final WpmService wpmService;

	public ServoyWpmServer()
	{
		this.wpmService = new WpmService();
	}

	@Inject
	public ServoyWpmServer(WpmService wpmService)
	{
		this.wpmService = wpmService;
	}

	@Tool(name = "searchPackages", description = "Search Servoy Package Manager (SPM) for available components, services, or layouts by keyword. Returns matching packages with name, type, description, version, and install status.")
	public String searchPackages(
		@ToolParam(name = "query", description = "Search keywords â package name, description, or functionality (e.g. 'calendar', 'data grid', 'excel export')") String query,
		@ToolParam(name = "packageType", description = "Filter by package type: Web-Component, Web-Service, Web-Layout, Solution, Solution-Main. Leave empty for all types.", required = false) String packageType)
	{
		try
		{
			List<ScoredPackage> results = wpmService.searchPackages(query, packageType);
			if (results.isEmpty())
			{
				return "No packages found matching '" + query + "'" +
					(packageType != null && !packageType.isEmpty() ? " with type '" + packageType + "'" : "") + ".";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Found ").append(results.size()).append(" package(s) matching '").append(query).append("':\n\n");

			for (ScoredPackage sp : results)
			{
				JSONObject pkg = sp.pkg;
				sb.append("**").append(pkg.optString("name", "")).append("**");
				String displayName = pkg.optString("displayName", "");
				if (!displayName.isEmpty() && !displayName.equals(pkg.optString("name", "")))
				{
					sb.append(" (").append(displayName).append(")");
				}
				sb.append("\n");
				sb.append("  Type: ").append(pkg.optString("packageType", "unknown")).append("\n");

				String description = pkg.optString("description", "");
				if (!description.isEmpty())
				{
					sb.append("  Description: ").append(description).append("\n");
				}

				JSONArray releases = pkg.optJSONArray("releases");
				if (releases != null && releases.length() > 0)
				{
					sb.append("  Latest version: ").append(releases.getJSONObject(0).optString("version", "?")).append("\n");
				}

				String installed = pkg.optString("installed", "");
				if (!installed.isEmpty())
				{
					sb.append("  Installed: ").append(installed).append("\n");
				}

				sb.append("\n");
			}

			return sb.toString().trim();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to search SPM packages: " + e.getMessage(), e);
		}
	}

	@Tool(name = "installPackage", description = "Install a package from Servoy Package Manager (SPM) into a solution. Resolves and installs dependencies automatically. Use searchPackages first to find the exact package name.")
	public String installPackage(
		@ToolParam(name = "packageName", description = "Exact package name to install (as returned by searchPackages)") String packageName,
		@ToolParam(name = "version", description = "Specific version to install. If omitted, installs the latest compatible version.", required = false) String version,
		@ToolParam(name = "solutionName", description = "Target solution name. If omitted, installs into the active solution.", required = false) String solutionName)
	{
		try
		{
			return wpmService.installPackage(packageName, version, solutionName);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to install package '" + packageName + "': " + e.getMessage(), e);
		}
	}
}
