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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.integrationpde.utils.DialogGuardBaseCopy;
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
public class ServoyWpmServerIntegrationTest extends DialogGuardBaseCopy
{
	private ServoyWpmServer server;
	private ComponentSpecService componentSpecService;

	@BeforeEach
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
		assertNotNull(comps, "component spec provider returned null - runtime not initialised");
		assertTrue(comps.length() > 0, "no component specs are loaded - the launch must include component bundles "
			+ "(aggrid, svychartjs, ...) for these integration tests to be meaningful");
		String name = comps.getJSONObject(0).optString("name", null);
		assertNotNull(name, "first loaded component has no name");
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
		assertNotNull(result, "getComponents must return a result");
	}

	@Test
	public void testGetComponents_listsLoadedComponents()
	{
		String component = requireLoadedComponentName();

		String result = server.getComponents(null, null);
		assertNotNull(result);
		assertTrue(result.contains(component),
			"getComponents output must mention a loaded component (" + component + "): " + result);
	}

	@Test
	public void testListObjects_componentHasExpectedShape()
	{
		requireLoadedComponentName();

		JSONArray comps = componentSpecService.listObjects(null, false);
		assertNotNull(comps);
		assertTrue(comps.length() > 0, "at least one component must be exposed");

		JSONObject first = comps.getJSONObject(0);
		assertTrue(first.has("name") && !first.optString("name", "").isEmpty(), "each component carries a name");
		assertTrue(!first.optString("packageName", "").isEmpty(), "each component reports its package");
		assertTrue(first.has("propertyCount") && first.has("handlerCount") && first.has("apiCount"),
			"each component carries property/handler/api counts");
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
		assertTrue(result.contains(component), "spec must mention the component name: " + result);
		assertTrue(result.contains("\"properties\""), "spec must include a properties section: " + result);
		assertTrue(result.contains("\"handlers\""), "spec must include a handlers section: " + result);
		assertTrue(result.contains("\"apis\""), "spec must include an apis section: " + result);
	}

	@Test
	public void testGetComponentSpec_unknownComponent_returnsNotFound()
	{
		String result = server.getComponentSpec("nonExistentComponent_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue(result.contains("No component spec found"),
			"unknown component must return a not-found message: " + result);
	}

	@Test
	public void testGetObjectSpec_unknownComponent_returnsNull()
	{
		assertNull(componentSpecService.getObjectSpec("nonExistentComponent_XYZ_ABC", false),
			"unknown component spec must be null");
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
		assertTrue(result.contains(component), "docs must mention the component name: " + result);
		assertTrue(result.contains("memberDocs"), "docs must include a memberDocs section: " + result);
	}

	@Test
	public void testGetComponentDocs_unknownComponent_returnsNotFound()
	{
		String result = server.getComponentDocs("nonExistentComponent_XYZ_ABC", null);
		assertNotNull(result);
		assertTrue(result.contains("No component found"),
			"unknown component docs must return a not-found message: " + result);
	}

	// -----------------------------------------------------------------------
	// getInstalledPackages / getAvailableWebPackages (no-exception contract)
	// -----------------------------------------------------------------------

	@Test
	public void testGetInstalledPackages_doesNotThrow()
	{
		String result = server.getInstalledPackages(null);
		assertNotNull(result, "getInstalledPackages must return a result");
	}

	// -----------------------------------------------------------------------
	// uninstallPackage - safe negative path (never removes anything real)
	// -----------------------------------------------------------------------

	@Test
	public void testUninstallPackage_notInstalled_returnsMessage() throws Exception
	{
		String result = server.uninstallPackage("nonExistentPackage_XYZ_ABC", null, null);
		assertNotNull(result);
		assertTrue(result.contains("is not installed"),
			"uninstalling a non-installed package must report it cleanly: " + result);
	}

	@Test
	public void testUninstallPackage_servicesFlagDefaults_noThrow() throws Exception
	{
		// force=false on an unknown package must still be a clean message, never a throw
		String result = server.uninstallPackage("anotherMissingPackage_123", null, Boolean.FALSE);
		assertNotNull(result);
		assertFalse(result.contains("Successfully uninstalled"), "must not claim success for a missing package: " + result);
	}
}
