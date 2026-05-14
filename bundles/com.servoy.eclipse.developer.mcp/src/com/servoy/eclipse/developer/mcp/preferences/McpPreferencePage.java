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
package com.servoy.eclipse.developer.mcp.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.servoy.eclipse.developer.mcp.Activator;
import com.servoy.eclipse.developer.mcp.McpServerRegistry;

/**
 * Eclipse preference page for the Servoy Developer MCP Server.
 * Displays the auth token and the list of active endpoints.
 */
public class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
	private StringFieldEditor tokenEditor;

	public McpPreferencePage()
	{
		super("Servoy Developer MCP Server");
		setDescription("Configuration for the Servoy Developer MCP Server.\n" +
			"The MCP server is deployed on Servoy's embedded Tomcat instance.");
	}

	@Override
	public void init(IWorkbench workbench)
	{
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
	}

	@Override
	protected Control createContents(Composite parent)
	{
		Composite container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(2, false));
		container.setLayoutData(new GridData(GridData.FILL_BOTH));

		// Auth token field
		tokenEditor = new StringFieldEditor(
			McpPreferenceConstants.MCP_AUTH_TOKEN,
			"Auth Token:",
			container);
		tokenEditor.setPage(this);
		tokenEditor.setPreferenceStore(getPreferenceStore());
		tokenEditor.load();

		// Endpoints info
		new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL)
			.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

		Label endpointsLabel = new Label(container, SWT.NONE);
		endpointsLabel.setText("Active MCP Endpoints (on Servoy's Tomcat port):");
		endpointsLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

		Label endpointsList = new Label(container, SWT.NONE);
		String endpoints = McpServerRegistry.getInstance().getServletInstances().stream()
			.map(si -> si.getUrlPattern().replace("/*", ""))
			.sorted()
			.collect(java.util.stream.Collectors.joining("\n"));
		endpointsList.setText(endpoints.isEmpty() ? "(No endpoints registered)" : endpoints);
		endpointsList.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

		return container;
	}

	@Override
	protected void performApply()
	{
		tokenEditor.store();
	}

	@Override
	public boolean performOk()
	{
		tokenEditor.store();
		return true;
	}
}
