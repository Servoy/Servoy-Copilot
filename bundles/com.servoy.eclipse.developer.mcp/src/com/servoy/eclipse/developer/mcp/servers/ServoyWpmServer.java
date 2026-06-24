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
import com.servoy.eclipse.developer.mcp.services.ComponentSpecService;
import com.servoy.eclipse.developer.mcp.services.WpmService;
import com.servoy.eclipse.developer.mcp.services.WpmService.ScoredPackage;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "servoy-wpm")
public class ServoyWpmServer
{
	private final WpmService wpmService;
	private final ComponentSpecService componentSpecService;

	public ServoyWpmServer()
	{
		this.wpmService = new WpmService();
		this.componentSpecService = new ComponentSpecService();
	}

	@Inject
	public ServoyWpmServer(WpmService wpmService, ComponentSpecService componentSpecService)
	{
		this.wpmService = wpmService;
		this.componentSpecService = componentSpecService;
	}

	@Tool(name = "searchPackages", description = "Search Servoy Package Manager (SPM) for available components, services, or layouts by keyword. Returns matching packages with name, type, description, version, and install status.")
	public String searchPackages(
		@ToolParam(name = "query", description = "Search keywords - package name, description, or functionality (e.g. 'calendar', 'data grid', 'excel export')") String query,
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

	@Tool(name = "uninstallPackage", description = "Uninstall (remove) an installed web package from the solution it lives in. By default the removal is refused if other installed packages depend on it (the dependents are listed); pass force=true to remove it anyway. Use getInstalledPackages first to find the exact package name and the solution/module it is installed in.")
	public String uninstallPackage(
		@ToolParam(name = "packageName", description = "Exact installed package name to remove (as returned by getInstalledPackages)") String packageName,
		@ToolParam(name = "solutionName", description = "Solution/module to remove it from. If omitted, removes from the solution it is currently installed in.", required = false) String solutionName,
		@ToolParam(name = "force", description = "Remove the package even if other installed packages depend on it (may break those packages). Default false.", required = false, type = "boolean") Boolean force)
	{
		try
		{
			return wpmService.uninstallPackage(packageName, solutionName, force != null && force.booleanValue());
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to uninstall package '" + packageName + "': " + e.getMessage(), e);
		}
	}

	@Tool(name = "getAvailableWebPackages", description = "List packages from the Servoy Package Manager (SPM) repositories that can be installed into the active solution - i.e. not yet installed, or installed but with a newer version available. Use 'onlyUpdates' to restrict to packages that have a pending update. Returns name, displayName, packageType, description and latest available version.")
	public String getAvailableWebPackages(
		@ToolParam(name = "packageType", description = "Filter by package type: Web-Component, Web-Service, Web-Layout, Solution, Solution-Main. Leave empty for all types.", required = false) String packageType,
		@ToolParam(name = "onlyUpdates", description = "When true, returns only installed packages that have a newer version available. Default false.", required = false, type = "boolean") Boolean onlyUpdates)
	{
		try
		{
			List<JSONObject> results = wpmService.getAvailableWebPackages(packageType, onlyUpdates != null && onlyUpdates.booleanValue());
			if (results.isEmpty())
			{
				return "No available packages found" +
					(packageType != null && !packageType.isEmpty() ? " with type '" + packageType + "'" : "") + ".";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Found ").append(results.size()).append(" available package(s):\n\n");
			for (JSONObject pkg : results)
			{
				appendPackageSummary(sb, pkg, true);
			}
			return sb.toString().trim();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list available web packages: " + e.getMessage(), e);
		}
	}

	@Tool(name = "getInstalledPackages", description = "List all packages (components, services, layouts, modules) currently installed in the active solution and its modules. Each entry shows the installed version and which solution/module it lives in (the 'installedIn' field) - effectively the dependency tree of installed packages.")
	public String getInstalledPackages(
		@ToolParam(name = "packageType", description = "Filter by package type: Web-Component, Web-Service, Web-Layout, Solution, Solution-Main. Leave empty for all types.", required = false) String packageType)
	{
		try
		{
			List<JSONObject> results = wpmService.getInstalledPackages(packageType);
			if (results.isEmpty())
			{
				return "No installed packages found" +
					(packageType != null && !packageType.isEmpty() ? " with type '" + packageType + "'" : "") + ".";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Found ").append(results.size()).append(" installed package(s):\n\n");
			for (JSONObject pkg : results)
			{
				appendPackageSummary(sb, pkg, false);
				String installedIn = pkg.optString("activeSolution", "");
				if (!installedIn.isEmpty())
				{
					sb.append("  Installed in: ").append(installedIn).append("\n");
				}
				sb.append("\n");
			}
			return sb.toString().trim();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list installed packages: " + e.getMessage(), e);
		}
	}

	@Tool(name = "getComponents", description = "List the web components (or web services) defined inside the installed packages, with the model property/handler/api counts for each. This is the live equivalent of reading the .spec files. Use getComponentSpec for the full property/handler detail of a single one.")
	public String getComponents(
		@ToolParam(name = "packageName", description = "Restrict to a single package (e.g. 'bootstrapcomponents'). Leave empty for all packages.", required = false) String packageName,
		@ToolParam(name = "services", description = "When true, lists web services instead of web components. Default false.", required = false, type = "boolean") Boolean services)
	{
		try
		{
			boolean svc = services != null && services.booleanValue();
			JSONArray objects = componentSpecService.listObjects(packageName, svc);
			if (objects.length() == 0)
			{
				return "No " + (svc ? "services" : "components") + " found" +
					(packageName != null && !packageName.isEmpty() ? " in package '" + packageName + "'" : "") +
					". (Are the specs loaded? A solution must be active.)";
			}
			return objects.toString(2);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list components: " + e.getMessage(), e);
		}
	}

	@Tool(name = "getComponentSpec", description = "Return the full specification of a single web component or service: model properties (type, default, scope, allowed values, doc), handlers (events) and api functions. This is the structured .spec content needed to author a component instance in a .frm file.")
	public String getComponentSpec(
		@ToolParam(name = "objectName", description = "The spec name, e.g. 'bootstrapcomponents-button' (or service name when 'services' is true).") String objectName,
		@ToolParam(name = "services", description = "When true, looks up a web service instead of a component. Default false.", required = false, type = "boolean") Boolean services)
	{
		try
		{
			boolean svc = services != null && services.booleanValue();
			JSONObject spec = componentSpecService.getObjectSpec(objectName, svc);
			if (spec == null)
			{
				return "No " + (svc ? "service" : "component") + " spec found for '" + objectName + "'.";
			}
			return spec.toString(2);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get component spec for '" + objectName + "': " + e.getMessage(), e);
		}
	}

	@Tool(name = "getComponentDocs", description = "Return the documentation for a web component or service: the dedicated documentation file (_doc.js) referenced by the spec when present, plus the structured inline doc text for each property, handler and api function.")
	public String getComponentDocs(
		@ToolParam(name = "objectName", description = "The spec name, e.g. 'bootstrapcomponents-button' (or service name when 'services' is true).") String objectName,
		@ToolParam(name = "services", description = "When true, looks up a web service instead of a component. Default false.", required = false, type = "boolean") Boolean services)
	{
		try
		{
			boolean svc = services != null && services.booleanValue();
			JSONObject docs = componentSpecService.getObjectDocs(objectName, svc);
			if (docs == null)
			{
				return "No " + (svc ? "service" : "component") + " found for '" + objectName + "'.";
			}
			return docs.toString(2);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get component docs for '" + objectName + "': " + e.getMessage(), e);
		}
	}

	private static void appendPackageSummary(StringBuilder sb, JSONObject pkg, boolean showLatest)
	{
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

		String installed = pkg.optString("installed", "");
		if (!installed.isEmpty())
		{
			sb.append("  Installed version: ").append(installed).append("\n");
		}

		if (showLatest)
		{
			JSONArray releases = pkg.optJSONArray("releases");
			if (releases != null && releases.length() > 0)
			{
				sb.append("  Latest version: ").append(releases.getJSONObject(0).optString("version", "?")).append("\n");
			}
			sb.append("\n");
		}
	}
}
