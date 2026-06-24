/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

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

		assertTrue("no package depends on aggrid", WpmService.findDependents("aggrid", all).isEmpty());
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

		assertTrue("not-installed packages must be ignored", WpmService.findDependents("target", all).isEmpty());
	}

	@Test
	public void testFindDependents_ignoresSelf()
	{
		JSONArray all = new JSONArray();
		// a (malformed) package listing itself as a dependency must not count as its own dependent
		all.put(installedPkg("target", "1.0.0", "target#1.0.0"));

		assertTrue("a package is never its own dependent", WpmService.findDependents("target", all).isEmpty());
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

		assertTrue("partial name must not match", WpmService.findDependents("svyutils", all).isEmpty());
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
}
