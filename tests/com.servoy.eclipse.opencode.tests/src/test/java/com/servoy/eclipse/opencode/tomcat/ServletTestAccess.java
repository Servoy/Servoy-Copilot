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

/**
 * Test-side bridge to the package-private members of {@link OpencodeChatServlet}.
 * <p>
 * This test bundle is a fragment of the opencode bundle, so a class declared in
 * the same package shares the host's classloader and sees package-private
 * members. That is the second choice of the repo's test-access order (public
 * API first, then package access from a fragment, reflection last); it avoids
 * both reflection and widening production visibility.
 * </p>
 */
public final class ServletTestAccess {
	private ServletTestAccess() {
	}

	/** @see OpencodeChatServlet#isSameOriginLoopbackRequest(String, String, String) */
	public static boolean isSameOriginLoopbackRequest(String host, String secFetchSite, String origin) {
		return OpencodeChatServlet.isSameOriginLoopbackRequest(host, secFetchSite, origin);
	}

	/** @see OpencodeChatServlet#setFileReader(WorkspaceFileReader) */
	public static void setFileReader(OpencodeChatServlet servlet, WorkspaceFileReader reader) {
		servlet.setFileReader(reader);
	}
}
