/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.WpmService;
import com.servoy.eclipse.developer.mcp.services.WpmService.ScoredPackage;

/**
 * Pure-logic unit tests for {@link WpmService} version helpers. These do not require the
 * Eclipse/Servoy runtime - they exercise the static {@code isNewerVersion} / {@code latestVersion}
 * helpers that back getAvailableWebPackages' update detection.
 */
public class WpmServiceTest
{
	@Test
	public void testIsNewerVersion_higherMajor()
	{
		assertTrue(WpmService.isNewerVersion("2.0.0", "1.9.9"));
	}

	@Test
	public void testIsNewerVersion_higherMinor()
	{
		assertTrue(WpmService.isNewerVersion("1.3.0", "1.2.5"));
	}

	@Test
	public void testIsNewerVersion_higherPatch()
	{
		assertTrue(WpmService.isNewerVersion("1.2.3", "1.2.2"));
	}

	@Test
	public void testIsNewerVersion_equalIsNotNewer()
	{
		assertFalse(WpmService.isNewerVersion("1.2.3", "1.2.3"));
	}

	@Test
	public void testIsNewerVersion_olderIsNotNewer()
	{
		assertFalse(WpmService.isNewerVersion("1.2.0", "1.2.3"));
	}

	@Test
	public void testIsNewerVersion_differentSegmentCounts()
	{
		assertTrue(WpmService.isNewerVersion("1.2.1", "1.2"));
		assertFalse(WpmService.isNewerVersion("1.2", "1.2.1"));
		assertFalse(WpmService.isNewerVersion("1.2.0", "1.2"));
	}

	@Test
	public void testIsNewerVersion_nullCandidate()
	{
		assertFalse(WpmService.isNewerVersion(null, "1.0.0"));
	}

	@Test
	public void testIsNewerVersion_nullReference()
	{
		assertTrue(WpmService.isNewerVersion("1.0.0", null));
	}

	@Test
	public void testLatestVersion_picksFirstRelease()
	{
		JSONObject pkg = new JSONObject();
		JSONArray releases = new JSONArray();
		releases.put(new JSONObject().put("version", "3.1.0"));
		releases.put(new JSONObject().put("version", "3.0.0"));
		pkg.put("releases", releases);

		assertEquals("3.1.0", WpmService.latestVersion(pkg));
	}

	@Test
	public void testLatestVersion_noReleases()
	{
		assertNull(WpmService.latestVersion(new JSONObject()));
	}

	// -------------------------------------------------------------------------
	// findDependents / dependencyForVersion (uninstall dependency-safety logic)
	// -------------------------------------------------------------------------

	/** Builds an installed package with a single release carrying the given dependency string. */
	private static JSONObject installedPkg(String name, String installedVersion, String dependency)
	{
		JSONObject pkg = new JSONObject();
		pkg.put("name", name);
		pkg.put("installed", installedVersion);
		JSONArray releases = new JSONArray();
		JSONObject release = new JSONObject().put("version", installedVersion);
		if (dependency != null) release.put("dependency", dependency);
		releases.put(release);
		pkg.put("releases", releases);
		return pkg;
	}

	@Test
	public void testFindDependents_noDependents()
	{
		JSONArray all = new JSONArray();
		all.put(installedPkg("aggrid", "1.0.0", null));
		all.put(installedPkg("bootstrapcomponents", "2.0.0", "servoyextra#1.0.0"));

		assertTrue(WpmService.findDependents("aggrid", all).isEmpty(), "no package depends on aggrid");
	}

	@Test
	public void testFindDependents_singleDependent()
	{
		JSONArray all = new JSONArray();
		all.put(installedPkg("svyutils", "1.0.0", null));
		all.put(installedPkg("svynavigation", "2.0.0", "svyutils#1.0.0"));

		List<String> dependents = WpmService.findDependents("svyutils", all);
		assertEquals(1, dependents.size());
		assertEquals("svynavigation", dependents.get(0));
	}

	@Test
	public void testFindDependents_multipleDependents()
	{
		JSONArray all = new JSONArray();
		all.put(installedPkg("svyutils", "1.0.0", null));
		all.put(installedPkg("svynavigation", "2.0.0", "svyutils#1.0.0"));
		all.put(installedPkg("svysecurity", "3.0.0", "svyutils#1.0.0,aggrid#1.2.0"));

		List<String> dependents = WpmService.findDependents("svyutils", all);
		assertEquals(2, dependents.size());
		assertTrue(dependents.contains("svynavigation"));
		assertTrue(dependents.contains("svysecurity"));
	}

	@Test
	public void testFindDependents_parsesNameHashVersion()
	{
		JSONArray all = new JSONArray();
		// dependency lists name#version - only the name part must match
		all.put(installedPkg("consumer", "1.0.0", "target#9.9.9"));

		List<String> dependents = WpmService.findDependents("target", all);
		assertEquals(1, dependents.size());
		assertEquals("consumer", dependents.get(0));
	}

	@Test
	public void testFindDependents_ignoresNotInstalled()
	{
		JSONArray all = new JSONArray();
		// a package that lists the dependency but is NOT installed (empty 'installed') must be ignored
		JSONObject notInstalled = installedPkg("consumer", "1.0.0", "target#1.0.0");
		notInstalled.put("installed", "");
		all.put(notInstalled);

		assertTrue(WpmService.findDependents("target", all).isEmpty(), "not-installed packages must be ignored");
	}

	@Test
	public void testFindDependents_ignoresSelf()
	{
		JSONArray all = new JSONArray();
		// a (malformed) package listing itself as a dependency must not count as its own dependent
		all.put(installedPkg("target", "1.0.0", "target#1.0.0"));

		assertTrue(WpmService.findDependents("target", all).isEmpty(), "a package is never its own dependent");
	}

	@Test
	public void testFindDependents_nullArray()
	{
		assertTrue(WpmService.findDependents("anything", null).isEmpty());
	}

	@Test
	public void testFindDependents_partialNameNoFalseMatch()
	{
		JSONArray all = new JSONArray();
		// "svyutils" must not match a dependency on "svyutils-extra"
		all.put(installedPkg("consumer", "1.0.0", "svyutils-extra#1.0.0"));

		assertTrue(WpmService.findDependents("svyutils", all).isEmpty(), "partial name must not match");
	}

	@Test
	public void testDependencyForVersion_exactMatch()
	{
		JSONObject pkg = new JSONObject();
		JSONArray releases = new JSONArray();
		releases.put(new JSONObject().put("version", "2.0.0").put("dependency", "a#2.0.0"));
		releases.put(new JSONObject().put("version", "1.0.0").put("dependency", "a#1.0.0"));
		pkg.put("releases", releases);

		assertEquals("a#1.0.0", WpmService.dependencyForVersion(pkg, "1.0.0"));
	}

	@Test
	public void testDependencyForVersion_fallbackToLatest()
	{
		JSONObject pkg = new JSONObject();
		JSONArray releases = new JSONArray();
		releases.put(new JSONObject().put("version", "2.0.0").put("dependency", "a#2.0.0"));
		pkg.put("releases", releases);

		// installed version not present in releases -> fall back to latest (index 0)
		assertEquals("a#2.0.0", WpmService.dependencyForVersion(pkg, "9.9.9"));
	}

	@Test
	public void testDependencyForVersion_noReleases()
	{
		assertNull(WpmService.dependencyForVersion(new JSONObject(), "1.0.0"));
	}

	// -------------------------------------------------------------------------
	// Seam-driven branch tests (searchPackages / install / uninstall / list)
	//
	// WpmService reaches the WPM backend through the protected fetchAllPackages()
	// seam. These tests subclass the service to return a crafted JSONArray, so the
	// service's OWN filtering/scoring/validation/"not found" branches run with no
	// live WPM backend, workbench or network. We only drive the branches that
	// return BEFORE the Display.getDefault().syncExec(...) install/remove step -
	// the actual install/remove (and the real scoring threshold the WPM layer
	// feeds) stay for the integration tests. We never assert WPM behaviour, only
	// what this service does with the data it is handed.
	// -------------------------------------------------------------------------

	/** A {@link WpmService} whose {@code fetchAllPackages()} seam returns the supplied list. */
	private static WpmService serviceReturning(JSONArray packages)
	{
		return new WpmService()
		{
			@Override
			protected JSONArray fetchAllPackages()
			{
				return packages;
			}
		};
	}

	/** A package with name/displayName/description/packageType, no releases, not installed. */
	private static JSONObject repoPkg(String name, String displayName, String description, String packageType)
	{
		JSONObject pkg = new JSONObject();
		pkg.put("name", name);
		if (displayName != null) pkg.put("displayName", displayName);
		if (description != null) pkg.put("description", description);
		if (packageType != null) pkg.put("packageType", packageType);
		return pkg;
	}

	/** A repo package carrying a single release of the given version (sorted-descending index 0). */
	private static JSONObject repoPkgWithRelease(String name, String packageType, String latestVersion)
	{
		JSONObject pkg = repoPkg(name, null, null, packageType);
		JSONArray releases = new JSONArray();
		releases.put(new JSONObject().put("version", latestVersion));
		pkg.put("releases", releases);
		return pkg;
	}

	@Nested
	@DisplayName("searchPackages scoring and filtering")
	class SearchPackages
	{
		@Test
		@DisplayName("matches on name and ranks an exact name above a mere substring hit")
		void ranksExactAboveSubstring() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkg("grid", "Grid", "a data grid", "Web-Component"));
			all.put(repoPkg("aggrid", "AG Grid", "advanced grid", "Web-Component"));

			List<ScoredPackage> result = serviceReturning(all).searchPackages("grid", null);

			assertEquals(2, result.size(), "both packages contain the query");
			assertEquals("grid", result.get(0).pkg.optString("name"), "exact name match ranks first");
			assertTrue(result.get(0).score > result.get(1).score, "exact match scores higher than substring match");
		}

		@Test
		@DisplayName("excludes packages that score zero (no keyword hit anywhere)")
		void excludesZeroScore() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkg("calendar", "Calendar", "date picker", "Web-Component"));

			assertTrue(serviceReturning(all).searchPackages("grid", null).isEmpty(),
				"a package with no match for the query must be dropped");
		}

		@Test
		@DisplayName("packageType filter keeps only the requested type (case-insensitively)")
		void packageTypeFilter() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkg("grid", "Grid", "a grid", "Web-Component"));
			all.put(repoPkg("gridservice", "Grid Service", "a grid service", "Web-Service"));

			List<ScoredPackage> result = serviceReturning(all).searchPackages("grid", "web-service");

			assertEquals(1, result.size(), "only the Web-Service package passes the filter");
			assertEquals("gridservice", result.get(0).pkg.optString("name"));
		}

		@Test
		@DisplayName("caps the number of results at MAX_RESULTS (15)")
		void capsResults() throws Exception
		{
			JSONArray all = new JSONArray();
			for (int i = 0; i < 30; i++)
			{
				all.put(repoPkg("grid" + i, "Grid " + i, "grid number " + i, "Web-Component"));
			}

			assertEquals(15, serviceReturning(all).searchPackages("grid", null).size(),
				"results are capped at MAX_RESULTS");
		}

		@Test
		@DisplayName("empty package list yields no results")
		void emptyList() throws Exception
		{
			assertTrue(serviceReturning(new JSONArray()).searchPackages("grid", null).isEmpty());
		}
	}

	@Nested
	@DisplayName("installPackage validation and not-found branches")
	class InstallPackage
	{
		@Test
		@DisplayName("reports 'not found' when the package name is not in the repositories")
		void notFound() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkg("aggrid", "AG Grid", "grid", "Web-Component"));

			String result = serviceReturning(all).installPackage("doesnotexist", null, null);
			assertEquals("Package 'doesnotexist' not found in SPM repositories.", result);
		}

		@Test
		@DisplayName("reports 'not found' against an empty repository list")
		void notFoundEmpty() throws Exception
		{
			String result = serviceReturning(new JSONArray()).installPackage("anything", "1.0.0", "mySolution");
			assertEquals("Package 'anything' not found in SPM repositories.", result);
		}
	}

	@Nested
	@DisplayName("uninstallPackage validation and guard branches")
	class UninstallPackage
	{
		/** An installed package with an owning solution and optional dependency string. */
		private JSONObject installedInSolution(String name, String version, String solution, String dependency)
		{
			JSONObject pkg = new JSONObject();
			pkg.put("name", name);
			pkg.put("installed", version);
			if (solution != null) pkg.put("activeSolution", solution);
			JSONArray releases = new JSONArray();
			JSONObject release = new JSONObject().put("version", version);
			if (dependency != null) release.put("dependency", dependency);
			releases.put(release);
			pkg.put("releases", releases);
			return pkg;
		}

		@Test
		@DisplayName("reports 'not installed' when the package has no installed version")
		void notInstalled()
			throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkg("aggrid", "AG Grid", "grid", "Web-Component")); // present but not installed

			String result = serviceReturning(all).uninstallPackage("aggrid", null, false);
			assertEquals("Package 'aggrid' is not installed.", result);
		}

		@Test
		@DisplayName("reports 'not installed' when the package is absent entirely")
		void absent() throws Exception
		{
			String result = serviceReturning(new JSONArray()).uninstallPackage("ghost", null, false);
			assertEquals("Package 'ghost' is not installed.", result);
		}

		@Test
		@DisplayName("asks for an explicit solution when the owning solution cannot be determined")
		void unknownSolution() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(installedInSolution("aggrid", "1.0.0", null, null)); // installed, but no activeSolution

			String result = serviceReturning(all).uninstallPackage("aggrid", null, false);
			assertEquals("Cannot determine which solution 'aggrid' is installed in; pass solutionName explicitly.", result);
		}

		@Test
		@DisplayName("refuses removal when another installed package depends on it and force is false")
		void blockedByDependents() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(installedInSolution("svyutils", "1.0.0", "mySolution", null));
			all.put(installedInSolution("svynavigation", "2.0.0", "mySolution", "svyutils#1.0.0"));

			String result = serviceReturning(all).uninstallPackage("svyutils", null, false);
			assertTrue(result.startsWith("Cannot uninstall 'svyutils':"), "removal is refused: " + result);
			assertTrue(result.contains("svynavigation"), "the dependent package is named: " + result);
			assertTrue(result.contains("force=true"), "the message points at force=true: " + result);
		}
	}

	@Nested
	@DisplayName("getAvailableWebPackages / getInstalledPackages filtering")
	class ListingFilters
	{
		@Test
		@DisplayName("getAvailableWebPackages returns not-installed packages when onlyUpdates is false")
		void availableReturnsNotInstalled() throws Exception
		{
			JSONArray all = new JSONArray();
			all.put(repoPkgWithRelease("newpkg", "Web-Component", "1.0.0")); // not installed
			JSONObject installed = repoPkgWithRelease("oldpkg", "Web-Component", "1.0.0");
			installed.put("installed", "1.0.0"); // installed, no newer release
			all.put(installed);

			List<JSONObject> result = serviceReturning(all).getAvailableWebPackages(null, false);

			assertEquals(1, result.size(), "only the not-installed package is available to install");
			assertEquals("newpkg", result.get(0).optString("name"));
		}

		@Test
		@DisplayName("getAvailableWebPackages with onlyUpdates returns installed packages that have a newer release")
		void onlyUpdates() throws Exception
		{
			JSONArray all = new JSONArray();
			JSONObject updatable = repoPkgWithRelease("updatable", "Web-Component", "2.0.0");
			updatable.put("installed", "1.0.0"); // newer release available
			all.put(updatable);
			JSONObject current = repoPkgWithRelease("current", "Web-Component", "1.0.0");
			current.put("installed", "1.0.0"); // already at latest
			all.put(current);
			all.put(repoPkgWithRelease("notinstalled", "Web-Component", "3.0.0")); // not installed, excluded by onlyUpdates

			List<JSONObject> result = serviceReturning(all).getAvailableWebPackages(null, true);

			assertEquals(1, result.size(), "only the package with a newer release is reported");
			assertEquals("updatable", result.get(0).optString("name"));
		}

		@Test
		@DisplayName("getInstalledPackages returns only installed packages, filtered by type")
		void installedFilteredByType() throws Exception
		{
			JSONArray all = new JSONArray();
			JSONObject comp = repoPkgWithRelease("comp", "Web-Component", "1.0.0");
			comp.put("installed", "1.0.0");
			all.put(comp);
			JSONObject svc = repoPkgWithRelease("svc", "Web-Service", "1.0.0");
			svc.put("installed", "1.0.0");
			all.put(svc);
			all.put(repoPkgWithRelease("notinstalled", "Web-Component", "1.0.0")); // excluded: not installed

			List<JSONObject> result = serviceReturning(all).getInstalledPackages("Web-Component");

			assertEquals(1, result.size(), "only the installed Web-Component is returned");
			assertEquals("comp", result.get(0).optString("name"));
		}

		@Test
		@DisplayName("getInstalledPackages without a type filter returns every installed package")
		void installedNoFilter() throws Exception
		{
			JSONArray all = new JSONArray();
			JSONObject comp = repoPkgWithRelease("comp", "Web-Component", "1.0.0");
			comp.put("installed", "1.0.0");
			all.put(comp);
			all.put(repoPkgWithRelease("notinstalled", "Web-Component", "1.0.0"));

			List<JSONObject> result = serviceReturning(all).getInstalledPackages(null);
			assertEquals(1, result.size());
			assertEquals("comp", result.get(0).optString("name"));
		}
	}
}
