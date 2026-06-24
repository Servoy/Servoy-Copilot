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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyWpmServer;
import com.servoy.eclipse.developer.mcp.services.ComponentSpecService;

/**
 * Integration tests for the Servoy Package Manager tooling ({@link ServoyWpmServer} /
 * {@link ComponentSpecService}) that require a running Servoy workspace so the
 * web-component/web-service spec providers are populated.
 * <p>
 * The set of installed component packages depends on the launch configuration's selected
 * bundles (aggrid, svychartjs, fontawesome, ...), so these tests are deliberately
 * <b>package-agnostic</b>: they discover whatever component actually exists in the running spec
 * providers and assert spec/docs behaviour against that one. If <b>no</b> component spec is loaded
 * the tests <b>fail</b> - that means the runtime is not set up correctly (the launch must include
 * component bundles), which is a real problem, not something to skip over.
 * <p>
 * Must be run as a JUnit Plug-in Test (PDE), e.g. via the WpmIntegrationTests_mac launch config.
 */
public class ServoyWpmServerIntegrationTest
{
	private ServoyWpmServer server;
	private ComponentSpecService componentSpecService;

	@Before
	public void setUp()
	{
		componentSpecService = new ComponentSpecService();
		server = new ServoyWpmServer();
	}

	/**
	 * Returns the name of an arbitrary component that is loaded in this runtime, failing the test
	 * if none is present. A populated component spec provider is a precondition for these
	 * integration tests - an empty provider means the launch is missing its component bundles.
	 */
	private String requireLoadedComponentName()
	{
		JSONArray comps = componentSpecService.listObjects(null, false);
		assertNotNull("component spec provider returned null - runtime not initialised", comps);
		assertTrue("no component specs are loaded - the launch must include component bundles "
			+ "(aggrid, svychartjs, ...) for these integration tests to be meaningful", comps.length() > 0);
		String name = comps.getJSONObject(0).optString("name", null);
		assertNotNull("first loaded component has no name", name);
		return name;
	}

	// -----------------------------------------------------------------------
	// getComponents
	// -----------------------------------------------------------------------

	@Test
	public void testGetComponents_doesNotThrow()
	{
		// regardless of which components are loaded, the tool must return a String and never throw
		String result = server.getComponents(null, null);
		assertNotNull("getComponents must return a result", result);
	}

	@Test
	public void testGetComponents_listsLoadedComponents()
	{
		String component = requireLoadedComponentName();

		String result = server.getComponents(null, null);
		assertNotNull(result);
		assertTrue("getComponents output must mention a loaded component (" + component + "): " + result,
			result.contains(component));
	}

	@Test
	public void testListObjects_componentHasExpectedShape()
	{
		requireLoadedComponentName();

		JSONArray comps = componentSpecService.listObjects(null, false);
		assertNotNull(comps);
		assertTrue("at least one component must be exposed", comps.length() > 0);

		JSONObject first = comps.getJSONObject(0);
		assertTrue("each component carries a name", first.has("name") && !first.optString("name", "").isEmpty());
		assertTrue("each component reports its package", !first.optString("packageName", "").isEmpty());
		assertTrue("each component carries property/handler/api counts",
			first.has("propertyCount") && first.has("handlerCount") && first.has("apiCount"));
	}

	// -----------------------------------------------------------------------
	// getComponentSpec
	// -----------------------------------------------------------------------

	@Test
	public void testGetComponentSpec_loadedComponent()
	{
		String component = requireLoadedComponentName();

		String result = server.getComponentSpec(component, null);
		assertNotNull(result);
		assertTrue("spec must mention the component name: " + result, result.contains(component));
		assertTrue("spec must include a properties section: " + result, result.contains("\"properties\""));
		assertTrue("spec must include a handlers section: " + result, result.contains("\"handlers\""));
		assertTrue("spec must include an apis section: " + result, result.contains("\"apis\""));
	}

	@Test
	public void testGetComponentSpec_unknownComponent_returnsNotFound()
	{
		String result = server.getComponentSpec("nonExistentComponent_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue("unknown component must return a not-found message: " + result,
			result.contains("No component spec found"));
	}

	@Test
	public void testGetObjectSpec_unknownComponent_returnsNull()
	{
		assertNull("unknown component spec must be null",
			componentSpecService.getObjectSpec("nonExistentComponent_XYZ_ABC", false));
	}

	// -----------------------------------------------------------------------
	// getComponentDocs
	// -----------------------------------------------------------------------

	@Test
	public void testGetComponentDocs_loadedComponent()
	{
		String component = requireLoadedComponentName();

		String result = server.getComponentDocs(component, null);
		assertNotNull(result);
		assertTrue("docs must mention the component name: " + result, result.contains(component));
		assertTrue("docs must include a memberDocs section: " + result, result.contains("memberDocs"));
	}

	@Test
	public void testGetComponentDocs_unknownComponent_returnsNotFound()
	{
		String result = server.getComponentDocs("nonExistentComponent_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue("unknown component docs must return a not-found message: " + result,
			result.contains("No component found"));
	}

	// -----------------------------------------------------------------------
	// getInstalledPackages / getAvailableWebPackages (no-exception contract)
	// -----------------------------------------------------------------------

	@Test
	public void testGetInstalledPackages_doesNotThrow()
	{
		String result = server.getInstalledPackages(null);
		assertNotNull("getInstalledPackages must return a result", result);
	}

	// -----------------------------------------------------------------------
	// uninstallPackage - safe negative path (never removes anything real)
	// -----------------------------------------------------------------------

	@Test
	public void testUninstallPackage_notInstalled_returnsMessage() throws Exception
	{
		String result = server.uninstallPackage("nonExistentPackage_XYZ_ABC", null, null);
		assertNotNull(result);
		assertTrue("uninstalling a non-installed package must report it cleanly: " + result,
			result.contains("is not installed"));
	}

	@Test
	public void testUninstallPackage_servicesFlagDefaults_noThrow() throws Exception
	{
		// force=false on an unknown package must still be a clean message, never a throw
		String result = server.uninstallPackage("anotherMissingPackage_123", null, Boolean.FALSE);
		assertNotNull(result);
		assertFalse("must not claim success for a missing package: " + result, result.contains("Successfully uninstalled"));
	}
}
