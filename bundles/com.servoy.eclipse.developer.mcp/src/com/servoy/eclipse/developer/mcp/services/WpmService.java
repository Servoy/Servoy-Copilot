/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;
import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.designer.webpackage.endpoint.GetAllInstalledPackages;
import com.servoy.eclipse.designer.webpackage.endpoint.InstallWebPackageHandler;
import com.servoy.eclipse.model.util.ServoyLog;

@Creatable
public class WpmService
{
	private static final int MAX_RESULTS = 15;

	public List<ScoredPackage> searchPackages(String query, String packageType) throws Exception
	{
		JSONArray allPackages = GetAllInstalledPackages.getAllInstalledPackages(false, true);
		List<ScoredPackage> scored = new ArrayList<>();
		String[] keywords = query.toLowerCase(Locale.ROOT).split("\\s+");

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.getJSONObject(i);

			if (packageType != null && !packageType.isEmpty())
			{
				String type = pkg.optString("packageType", "");
				if (!type.equalsIgnoreCase(packageType)) continue;
			}

			int score = computeScore(pkg, keywords, query.toLowerCase(Locale.ROOT));
			if (score > 0)
			{
				scored.add(new ScoredPackage(pkg, score));
			}
		}

		scored.sort((a, b) -> Integer.compare(b.score, a.score));
		return scored.subList(0, Math.min(scored.size(), MAX_RESULTS));
	}

	private int computeScore(JSONObject pkg, String[] keywords, String fullQuery)
	{
		String name = pkg.optString("name", "").toLowerCase(Locale.ROOT);
		String displayName = pkg.optString("displayName", "").toLowerCase(Locale.ROOT);
		String description = pkg.optString("description", "").toLowerCase(Locale.ROOT);

		int score = 0;

		if (name.equals(fullQuery) || displayName.equals(fullQuery))
		{
			score += 100;
		}
		else if (name.contains(fullQuery) || displayName.contains(fullQuery))
		{
			score += 60;
		}

		int keywordHits = 0;
		for (String kw : keywords)
		{
			if (name.contains(kw))
			{
				score += 20;
				keywordHits++;
			}
			else if (displayName.contains(kw))
			{
				score += 15;
				keywordHits++;
			}
			else if (description.contains(kw))
			{
				score += 5;
				keywordHits++;
			}
		}

		if (keywordHits == keywords.length && keywords.length > 1)
		{
			score += 10;
		}

		if (pkg.optBoolean("top", false))
		{
			score += 3;
		}

		return score;
	}

	public String installPackage(String packageName, String version, String solutionName) throws Exception
	{
		JSONArray allPackages = GetAllInstalledPackages.getAllInstalledPackages(false, true);
		JSONObject targetPkg = null;

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.getJSONObject(i);
			if (packageName.equals(pkg.optString("name", "")))
			{
				targetPkg = pkg;
				break;
			}
		}

		if (targetPkg == null)
		{
			return "Package '" + packageName + "' not found in SPM repositories.";
		}

		if (solutionName != null && !solutionName.isEmpty())
		{
			targetPkg.put("activeSolution", solutionName);
		}

		String selectedVersion = version;
		if (selectedVersion == null || selectedVersion.isEmpty())
		{
			JSONArray releases = targetPkg.optJSONArray("releases");
			if (releases != null && releases.length() > 0)
			{
				selectedVersion = releases.getJSONObject(0).optString("version", null);
			}
		}

		final JSONObject pkgToInstall = targetPkg;
		final String versionToInstall = selectedVersion;
		final String[] error = { null };

		Display.getDefault().syncExec(() -> {
			try
			{
				InstallWebPackageHandler.importPackage(pkgToInstall, versionToInstall);
			}
			catch (IOException e)
			{
				ServoyLog.logError(e);
				error[0] = e.getMessage();
			}
		});

		if (error[0] != null)
		{
			return "Installation failed: " + error[0];
		}

		String installedVer = versionToInstall != null ? versionToInstall : "latest";
		return "Successfully installed '" + packageName + "' version " + installedVer +
			(solutionName != null && !solutionName.isEmpty() ? " into solution '" + solutionName + "'" : " into the active solution") + ".";
	}

	public static class ScoredPackage
	{
		public final JSONObject pkg;
		public final int score;

		public ScoredPackage(JSONObject pkg, int score)
		{
			this.pkg = pkg;
			this.score = score;
		}
	}
}
