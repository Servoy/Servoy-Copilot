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
import com.servoy.eclipse.designer.webpackage.endpoint.RemoveWebPackageHandler;
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

	/**
	 * Uninstalls (removes) an installed web package from the solution it lives in. Resolves the
	 * installed package first (to find its owning solution/module and the on-disk resource name),
	 * then deletes the package archive via {@link RemoveWebPackageHandler}.
	 * <p>
	 * If other installed packages directly depend on this one, the removal is refused (unless
	 * {@code force} is true) and the dependent package names are reported, so a dependency is never
	 * silently broken.
	 *
	 * @param packageName the exact installed package name (as returned by getInstalledPackages).
	 * @param solutionName optional solution/module to remove it from; if omitted, the solution the
	 *            package is currently installed in (its {@code activeSolution}) is used.
	 * @param force when true, removes the package even if other installed packages depend on it.
	 */
	public String uninstallPackage(String packageName, String solutionName, boolean force) throws Exception
	{
		JSONArray allPackages = GetAllInstalledPackages.getAllInstalledPackages(false, true);
		JSONObject targetPkg = null;

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.getJSONObject(i);
			if (packageName.equals(pkg.optString("name", "")) && !pkg.optString("installed", "").isEmpty())
			{
				targetPkg = pkg;
				break;
			}
		}

		if (targetPkg == null)
		{
			return "Package '" + packageName + "' is not installed.";
		}

		if (solutionName != null && !solutionName.isEmpty())
		{
			targetPkg.put("activeSolution", solutionName);
		}

		if (targetPkg.optString("activeSolution", "").isEmpty())
		{
			return "Cannot determine which solution '" + packageName + "' is installed in; pass solutionName explicitly.";
		}

		if (!force)
		{
			List<String> dependents = findDependents(packageName, allPackages);
			if (!dependents.isEmpty())
			{
				return "Cannot uninstall '" + packageName + "': package(s) " + String.join(", ", dependents) +
					" depend on it. Pass force=true to remove it anyway (this may break those packages).";
			}
		}

		final JSONObject pkgToRemove = targetPkg;
		final String removedFrom = targetPkg.optString("activeSolution", "");
		final String[] error = { null };

		Display.getDefault().syncExec(() -> {
			try
			{
				JSONObject msg = new JSONObject();
				msg.put("package", pkgToRemove);
				new RemoveWebPackageHandler().executeMethod(msg);
			}
			catch (Exception e)
			{
				ServoyLog.logError(e);
				error[0] = e.getMessage();
			}
		});

		if (error[0] != null)
		{
			return "Uninstall failed: " + error[0];
		}

		return "Successfully uninstalled '" + packageName + "' from solution '" + removedFrom + "'.";
	}

	/**
	 * Finds the installed packages that directly depend on {@code packageName}. Pure logic: scans the
	 * supplied package list, and for each installed package reads the {@code dependency} field of its
	 * installed release (format {@code "name#version,name2#version2"}) and collects those that list
	 * {@code packageName} as a dependency.
	 *
	 * @param packageName the package whose dependents are sought.
	 * @param allPackages the full SPM package list (as produced by GetAllInstalledPackages).
	 * @return the names of installed packages that directly depend on {@code packageName}.
	 */
	static List<String> findDependents(String packageName, JSONArray allPackages)
	{
		List<String> dependents = new ArrayList<>();
		if (allPackages == null) return dependents;

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.optJSONObject(i);
			if (pkg == null) continue;

			String otherName = pkg.optString("name", "");
			String installed = pkg.optString("installed", "");
			if (otherName.isEmpty() || installed.isEmpty() || otherName.equals(packageName)) continue;

			String dependency = dependencyForVersion(pkg, installed);
			if (dependency == null || dependency.isEmpty()) continue;

			for (String dep : dependency.split(","))
			{
				String depName = dep.split("#")[0].trim();
				if (packageName.equals(depName))
				{
					dependents.add(otherName);
					break;
				}
			}
		}
		return dependents;
	}

	/**
	 * Returns the {@code dependency} string of the release matching {@code version} for the given
	 * package, or the latest release's dependency as a fallback when no exact match is found.
	 */
	static String dependencyForVersion(JSONObject pkg, String version)
	{
		JSONArray releases = pkg.optJSONArray("releases");
		if (releases == null || releases.length() == 0) return null;

		for (int i = 0; i < releases.length(); i++)
		{
			JSONObject release = releases.optJSONObject(i);
			if (release != null && version.equals(release.optString("version", "")))
			{
				return release.optString("dependency", null);
			}
		}
		// fallback: latest release (index 0, sorted descending by the WPM layer)
		return releases.getJSONObject(0).optString("dependency", null);
	}

	/**
	 * Returns all packages from the configured SPM repositories that are NOT installed in the active
	 * solution (or that have a newer version available than the one installed).
	 *
	 * @param packageType optional filter by package type (Web-Component, Web-Service, Web-Layout, Solution, Solution-Main).
	 * @param onlyUpdates when true, returns only installed packages that have a newer version available.
	 */
	public List<JSONObject> getAvailableWebPackages(String packageType, boolean onlyUpdates) throws Exception
	{
		JSONArray allPackages = GetAllInstalledPackages.getAllInstalledPackages(false, true);
		List<JSONObject> result = new ArrayList<>();

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.getJSONObject(i);

			if (packageType != null && !packageType.isEmpty())
			{
				if (!packageType.equalsIgnoreCase(pkg.optString("packageType", ""))) continue;
			}

			String installed = pkg.optString("installed", "");
			String latest = latestVersion(pkg);

			if (onlyUpdates)
			{
				// only installed packages that have a newer release than the installed one
				if (installed.isEmpty() || GetAllInstalledPackages.UNKNOWN_VERSION.equals(installed)) continue;
				if (latest != null && isNewerVersion(latest, installed)) result.add(pkg);
			}
			else
			{
				// not installed at all, OR installed but an update exists
				if (installed.isEmpty())
				{
					result.add(pkg);
				}
				else if (latest != null && !GetAllInstalledPackages.UNKNOWN_VERSION.equals(installed) && isNewerVersion(latest, installed))
				{
					result.add(pkg);
				}
			}
		}
		return result;
	}

	/**
	 * Returns all packages currently installed in the active solution and its modules, annotated with the
	 * module they live in (the {@code activeSolution} field) and the installed version.
	 *
	 * @param packageType optional filter by package type.
	 */
	public List<JSONObject> getInstalledPackages(String packageType) throws Exception
	{
		JSONArray allPackages = GetAllInstalledPackages.getAllInstalledPackages(false, true);
		List<JSONObject> result = new ArrayList<>();

		for (int i = 0; i < allPackages.length(); i++)
		{
			JSONObject pkg = allPackages.getJSONObject(i);
			String installed = pkg.optString("installed", "");
			if (installed.isEmpty()) continue;

			if (packageType != null && !packageType.isEmpty())
			{
				if (!packageType.equalsIgnoreCase(pkg.optString("packageType", ""))) continue;
			}
			result.add(pkg);
		}
		return result;
	}

	static String latestVersion(JSONObject pkg)
	{
		JSONArray releases = pkg.optJSONArray("releases");
		if (releases != null && releases.length() > 0)
		{
			// releases are sorted descending by the WPM layer; index 0 is the latest compatible release
			return releases.getJSONObject(0).optString("version", null);
		}
		return null;
	}

	/**
	 * Compares two dotted version strings (e.g. "2.1.0"). Returns true if {@code candidate} is strictly
	 * newer than {@code reference}. Non-numeric segments are compared lexically as a fallback.
	 */
	static boolean isNewerVersion(String candidate, String reference)
	{
		if (candidate == null) return false;
		if (reference == null) return true;
		String[] a = candidate.split("[._-]");
		String[] b = reference.split("[._-]");
		int len = Math.max(a.length, b.length);
		for (int i = 0; i < len; i++)
		{
			String sa = i < a.length ? a[i] : "0";
			String sb = i < b.length ? b[i] : "0";
			try
			{
				int ia = Integer.parseInt(sa);
				int ib = Integer.parseInt(sb);
				if (ia != ib) return ia > ib;
			}
			catch (NumberFormatException nfe)
			{
				int cmp = sa.compareTo(sb);
				if (cmp != 0) return cmp > 0;
			}
		}
		return false;
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
