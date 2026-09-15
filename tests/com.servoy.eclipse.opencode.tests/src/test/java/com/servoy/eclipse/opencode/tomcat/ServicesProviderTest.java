/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode.tomcat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.apache.tomcat.starter.ServletInstance;

/**
 * Unit tests for {@link ServicesProvider}, the {@code IServicesProvider} that
 * hands the {@link OpencodeChatServlet} to the embedded Tomcat.
 * <p>
 * Only the root-context registration contract is exercised — no OSGi runtime is
 * required because the servlet constructor merely captures the supplier method
 * references without invoking them.
 * </p>
 *
 * @author generated
 * @since 2026.06
 */
class ServicesProviderTest
{
	@Test
	@DisplayName("root context returns exactly one servlet instance mapped to /servoy_ai/*")
	void rootContextRegistersChatServlet()
	{
		Set<ServletInstance> instances = new ServicesProvider().getServletInstances("");
		assertNotNull(instances, "root context must return a non-null set");
		assertEquals(1, instances.size(), "exactly one servlet instance expected");

		ServletInstance only = instances.iterator().next();
		assertAll(() -> assertEquals("/servoy_ai/*", only.getUrlPattern()),
			() -> assertNotNull(only.getServletInstance(), "servlet instance must be constructed"),
			() -> assertTrue(only.getServletInstance() instanceof OpencodeChatServlet,
				"the registered servlet must be an OpencodeChatServlet, was: "
					+ only.getServletInstance().getClass().getName()));
	}

	@Test
	@DisplayName("the servlet URL pattern is derived from the BASE_PATH constant")
	void urlPatternUsesBasePath()
	{
		ServletInstance only = new ServicesProvider().getServletInstances("").iterator().next();
		assertEquals(OpencodeChatServlet.BASE_PATH + "/*", only.getUrlPattern());
	}

	@ParameterizedTest
	@ValueSource(strings = { "someContext", "/", "servoy_ai", "otherwebapp" })
	@DisplayName("non-root contexts register nothing (guarded by empty-string check)")
	void nonRootContextsRegisterNothing(String context)
	{
		assertNull(new ServicesProvider().getServletInstances(context),
			"only the root context \"\" should register the chat servlet, not: " + context);
	}
}
