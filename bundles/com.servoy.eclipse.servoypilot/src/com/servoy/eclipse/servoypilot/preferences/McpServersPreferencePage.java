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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.preferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.mcp.client.McpServerConnectionService;
import com.servoy.eclipse.servoypilot.mcp.client.McpServerConnectionService.McpServerResult;
import com.servoy.eclipse.servoypilot.preferences.McpConfiguration.McpServerConfig;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Preference page: Preferences -> Servoy -> Servoy AI Pilot -> MCP Servers In Use
 *
 * Layout:
 *   - Short description label
 *   - SashForm (vertical, resizable)
 *       - Top pane: Server Configurations JSON text area + Registry URL row + Browse button
 *       - Bottom pane: Tools tree-table (CheckboxTreeViewer with per-agent columns)
 */
public class McpServersPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SAMPLE_REMOTE_SERVER_NAME = "your.url.example/sample-remote-mcp";
	private static final String SAMPLE_STDIO_SERVER_NAME = "your.url.example/sample-stdio-mcp";
	private static final int JSON_PREF_HEIGHT = 140;
	private static final int JSON_MIN_HEIGHT = 100;
	private static final int TOOLS_MIN_HEIGHT = 220;

	// --- Tree model ---

	/** Root node in the tree: represents one MCP server. */
	private static class ServerNode
	{
		final String name;
		/** null while loading; non-null after probe completes */
		List<Object> children; // List of ToolNode or ErrorNode

		ServerNode(String name)
		{
			this.name = name;
			this.children = null; // null = still loading
		}
	}

	/** Leaf node for a successfully discovered tool. */
	private static class ToolNode
	{
		final ServerNode server;
		final ToolSpecification spec;

		ToolNode(ServerNode server, ToolSpecification spec)
		{
			this.server = server;
			this.spec = spec;
		}
	}

	/** Leaf node shown when a server fails to connect. */
	private static class ErrorNode
	{
		final ServerNode server;
		final String message;

		ErrorNode(ServerNode server, String message)
		{
			this.server = server;
			this.message = message;
		}
	}

	/** Leaf node shown while a server is being probed. */
	private static class LoadingNode
	{
		final ServerNode server;

		LoadingNode(ServerNode server)
		{
			this.server = server;
		}
	}

	// --- State ---

	/** In-memory checkbox state for this editing session. Cleared/rebuilt on reconnect. */
	private final Map<String, Boolean> serverEnabled = new HashMap<>();
	private final Map<String, Boolean> toolEnabled = new HashMap<>();
	/** key: serverName + "." + toolName + "." + AssistantType.name() */
	private final Map<String, Boolean> toolAgentEnabled = new HashMap<>();

	private McpConfiguration mcpConfig;
	private List<ServerNode> serverNodes = new ArrayList<>();

	private Text jsonText;
	private Text registryUrlText;
	private CheckboxTreeViewer treeViewer;

	// Debounce timer for JSON text changes
	private Runnable pendingRefresh;

	@Override
	public void init(IWorkbench workbench)
	{
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
	}

	@Override
	protected Control createContents(Composite parent)
	{
		mcpConfig = new McpConfiguration();

		Composite root = new Composite(parent, SWT.NONE);
		root.setLayout(new GridLayout(1, false));
		root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		// Description
		Label desc = new Label(root, SWT.WRAP);
		desc.setText(
			"MCP (Model Context Protocol) servers extend Servoy Pilot with external tools and data sources. " +
				"Configure the servers below and choose which tools are available to each assistant.");
		GridData descData = new GridData(SWT.FILL, SWT.TOP, true, false);
		descData.widthHint = 200;
		desc.setLayoutData(descData);

		// Vertical sash: JSON config on top, tree-table below
		SashForm sash = new SashForm(root, SWT.VERTICAL);
		sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createTopPane(sash);
		createBottomPane(sash);

		//		sash.setWeights(new int[] { 35, 65 });

		// Load initial state from prefs
		loadFromPreferences();

		// Trigger initial server probe
		triggerReconnect();

		return root;
	}

	// -------------------------------------------------------------------------
	// Top pane: JSON + Registry URL
	// -------------------------------------------------------------------------

	private void createTopPane(Composite parent)
	{
		Composite top = new Composite(parent, SWT.NONE);
		top.setLayout(new GridLayout(1, false));

		// Server Configurations group
		Group configGroup = new Group(top, SWT.NONE);
		configGroup.setText("Server Configurations");
		configGroup.setLayout(new GridLayout(1, false));
		configGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		Label jsonHint = new Label(configGroup, SWT.WRAP);
		var txt = "Enter MCP server definitions in JSON format (use the MCP registry below, edit or use the add sample buttons).";
		jsonHint.setText(txt);
		jsonHint.setToolTipText(txt);
		GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
		jsonHint.setLayoutData(hintData);

		Composite jsonRow = new Composite(configGroup, SWT.NONE);
		jsonRow.setLayout(new GridLayout(2, false));
		jsonRow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		jsonText = new Text(jsonRow, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData jsonTextData = new GridData(SWT.FILL, SWT.FILL, true, true);
		jsonTextData.heightHint = JSON_PREF_HEIGHT;
		jsonTextData.minimumHeight = JSON_MIN_HEIGHT;
		jsonText.setLayoutData(jsonTextData);
		jsonText.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
		jsonText.addModifyListener(new ModifyListener()
		{
			@Override
			public void modifyText(ModifyEvent e)
			{
				scheduleRefreshAfterEdit();
			}
		});

		Composite sampleButtons = new Composite(jsonRow, SWT.NONE);
		GridLayout sampleButtonsLayout = new GridLayout(1, false);
		sampleButtonsLayout.marginWidth = 0;
		sampleButtonsLayout.marginHeight = 0;
		sampleButtons.setLayout(sampleButtonsLayout);
		sampleButtons.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, false, false));

		Button addSampleRemoteButton = new Button(sampleButtons, SWT.PUSH);
		addSampleRemoteButton.setText("Add sample Remote MCP");
		addSampleRemoteButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		addSampleRemoteButton.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				addSampleRemoteMcp();
			}
		});

		Button addSampleStdioButton = new Button(sampleButtons, SWT.PUSH);
		addSampleStdioButton.setText("Add sample STDIO MCP");
		addSampleStdioButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		addSampleStdioButton.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				addSampleStdioMcp();
			}
		});

		// Registry URL row
		Composite registryRow = new Composite(configGroup, SWT.NONE);
		registryRow.setLayout(new GridLayout(3, false));
		registryRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

		Label urlLabel = new Label(registryRow, SWT.NONE);
		urlLabel.setText("MCP Registry URL:");

		registryUrlText = new Text(registryRow, SWT.SINGLE | SWT.BORDER);
		registryUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		Button browseButton = new Button(registryRow, SWT.PUSH);
		browseButton.setText("Browse MCP Registry...");
		browseButton.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				openRegistryBrowser();
			}
		});
	}

	// -------------------------------------------------------------------------
	// Bottom pane: Tree-table
	// -------------------------------------------------------------------------

	private void createBottomPane(Composite parent)
	{
		Group toolsGroup = new Group(parent, SWT.NONE);
		toolsGroup.setText("Tools (select which tool is available to which assistant)");
		toolsGroup.setLayout(new GridLayout(1, false));
		GridData toolsGroupData = new GridData(SWT.FILL, SWT.FILL, true, true);
		toolsGroupData.minimumHeight = TOOLS_MIN_HEIGHT;
		toolsGroupData.minimumWidth = 200;
		toolsGroup.setLayoutData(toolsGroupData);

		treeViewer = new CheckboxTreeViewer(toolsGroup,
			SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
		Tree tree = treeViewer.getTree();
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);
		GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true);
		//		treeData.minimumHeight = TOOLS_MIN_HEIGHT;
		tree.setLayoutData(treeData);

		// Column 0: Server / Tool name+description (with checkbox)
		TreeViewerColumn nameCol = new TreeViewerColumn(treeViewer, SWT.NONE);
		nameCol.getColumn().setText("Server / Tool");
		nameCol.getColumn().setWidth(280);

		// One column per AssistantType
		for (AssistantType at : AssistantType.values())
		{
			TreeViewerColumn agentCol = new TreeViewerColumn(treeViewer, SWT.CENTER);
			agentCol.getColumn().setText(at.getDisplayName().replace(" Assistant", "")); // as they are column names, and many of them, make them a bit shorter
			agentCol.getColumn().setWidth(120);
		}

		treeViewer.setContentProvider(new McpTreeContentProvider());
		treeViewer.setLabelProvider(new McpTreeLabelProvider());
		treeViewer.setCheckStateProvider(new McpCheckStateProvider());

		treeViewer.setInput(serverNodes);

		treeViewer.addCheckStateListener(new ICheckStateListener()
		{
			@Override
			public void checkStateChanged(CheckStateChangedEvent event)
			{
				Object element = event.getElement();
				boolean checked = event.getChecked();

				if (element instanceof ServerNode serverNode)
				{
					serverEnabled.put(serverNode.name, checked);
					// Refresh children to update disabled appearance
					treeViewer.refresh(serverNode);
				}
				else if (element instanceof ToolNode toolNode)
				{
					toolEnabled.put(toolKey(toolNode.server.name, toolNode.spec.name()), checked);
					// Refresh to update per-agent cell state
					treeViewer.refresh(toolNode);
				}
				// Per-agent cells are custom-painted — handled in column click listener below
			}
		});

		// Per-agent column checkbox clicks (columns 1..N)
		tree.addListener(SWT.MouseDown, event -> {
			// Find which column was clicked
			org.eclipse.swt.widgets.TreeItem item = tree.getItem(new org.eclipse.swt.graphics.Point(event.x, event.y));
			if (item == null)
			{
				return;
			}
			Object data = item.getData();
			if (!(data instanceof ToolNode toolNode))
			{
				return;
			}

			for (int col = 1; col <= AssistantType.values().length; col++)
			{
				org.eclipse.swt.graphics.Rectangle bounds = item.getBounds(col);
				if (bounds.contains(event.x, event.y))
				{
					AssistantType at = AssistantType.values()[col - 1];
					String key = agentKey(toolNode.server.name, toolNode.spec.name(), at);
					boolean current = toolAgentEnabled.getOrDefault(key, true);
					toolAgentEnabled.put(key, !current);
					treeViewer.refresh(toolNode);
					break;
				}
			}
		});
	}

	// -------------------------------------------------------------------------
	// Content provider
	// -------------------------------------------------------------------------

	private class McpTreeContentProvider implements ITreeContentProvider
	{
		@Override
		public Object[] getElements(Object inputElement)
		{
			return serverNodes.toArray();
		}

		@Override
		public Object[] getChildren(Object parentElement)
		{
			if (parentElement instanceof ServerNode serverNode)
			{
				if (serverNode.children == null)
				{
					return new Object[] { new LoadingNode(serverNode) };
				}
				return serverNode.children.toArray();
			}
			return new Object[0];
		}

		@Override
		public Object getParent(Object element)
		{
			if (element instanceof ToolNode toolNode)
			{
				return toolNode.server;
			}
			if (element instanceof ErrorNode errorNode)
			{
				return errorNode.server;
			}
			if (element instanceof LoadingNode loadingNode)
			{
				return loadingNode.server;
			}
			return null;
		}

		@Override
		public boolean hasChildren(Object element)
		{
			return element instanceof ServerNode;
		}

		@Override
		public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
		{
		}

		@Override
		public void dispose()
		{
		}
	}

	// -------------------------------------------------------------------------
	// Label provider
	// -------------------------------------------------------------------------

	private class McpTreeLabelProvider implements ITableLabelProvider
	{
		@Override
		public String getColumnText(Object element, int columnIndex)
		{
			if (columnIndex == 0)
			{
				if (element instanceof ServerNode serverNode)
				{
					return serverNode.name;
				}
				if (element instanceof ToolNode toolNode)
				{
					return toolNode.spec.name() +
						(toolNode.spec.description() != null ? " — " + toolNode.spec.description() : "");
				}
				if (element instanceof ErrorNode errorNode)
				{
					return "\u26A0 " + errorNode.message;
				}
				if (element instanceof LoadingNode)
				{
					return "Connecting...";
				}
			}
			else if (element instanceof ToolNode toolNode)
			{
				// Per-agent column: show a unicode checkbox depending on state
				AssistantType at = AssistantType.values()[columnIndex - 1];
				boolean toolLevelEnabled = toolEnabled.getOrDefault(
					toolKey(toolNode.server.name, toolNode.spec.name()), true);
				boolean serverLevelEnabled = serverEnabled.getOrDefault(toolNode.server.name, true);

				if (!serverLevelEnabled || !toolLevelEnabled)
				{
					return "\u2612"; // disabled/grayed checkbox (ballot x)
				}
				boolean agentEnabled = toolAgentEnabled.getOrDefault(
					agentKey(toolNode.server.name, toolNode.spec.name(), at), true);
				return agentEnabled ? "\u2611" : "\u2610"; // checked / unchecked
			}
			return "";
		}

		@Override
		public Image getColumnImage(Object element, int columnIndex)
		{
			return null;
		}

		@Override
		public void addListener(ILabelProviderListener listener)
		{
		}

		@Override
		public void removeListener(ILabelProviderListener listener)
		{
		}

		@Override
		public boolean isLabelProperty(Object element, String property)
		{
			return false;
		}

		@Override
		public void dispose()
		{
		}
	}

	// -------------------------------------------------------------------------
	// Check state provider (for column 0 checkboxes)
	// -------------------------------------------------------------------------

	private class McpCheckStateProvider implements ICheckStateProvider
	{
		@Override
		public boolean isChecked(Object element)
		{
			if (element instanceof ServerNode serverNode)
			{
				return serverEnabled.getOrDefault(serverNode.name, true);
			}
			if (element instanceof ToolNode toolNode)
			{
				boolean serverIsEnabled = serverEnabled.getOrDefault(toolNode.server.name, true);
				if (!serverIsEnabled)
				{
					return false;
				}
				return toolEnabled.getOrDefault(toolKey(toolNode.server.name, toolNode.spec.name()), true);
			}
			return false;
		}

		@Override
		public boolean isGrayed(Object element)
		{
			if (element instanceof ServerNode serverNode)
			{
				// Grayed if checked but at least one child tool is unchecked
				if (!serverEnabled.getOrDefault(serverNode.name, true))
				{
					return false;
				}
				if (serverNode.children == null)
				{
					return false;
				}
				for (Object child : serverNode.children)
				{
					if (child instanceof ToolNode toolNode)
					{
						if (!toolEnabled.getOrDefault(toolKey(serverNode.name, toolNode.spec.name()), true))
						{
							return true;
						}
					}
				}
			}
			return false;
		}
	}

	// -------------------------------------------------------------------------
	// Load / save
	// -------------------------------------------------------------------------

	private void loadFromPreferences()
	{
		jsonText.setText(mcpConfig.getServersJson());
		registryUrlText.setText(mcpConfig.getRegistryUrl());

		// Load server/tool enabled states into in-memory maps
		for (McpServerConfig cfg : mcpConfig.getConfiguredServers())
		{
			serverEnabled.put(cfg.name, mcpConfig.isServerEnabled(cfg.name));
		}
		// Tool-level states are loaded as results come in (see applyResultToNode)
	}

	@Override
	public boolean performOk()
	{
		String json = jsonText.getText().trim();
		String registryUrl = registryUrlText.getText().trim();

		// Determine previous server names (before saving)
		List<String> previousNames = McpConfiguration.parseServerNames(mcpConfig.getServersJson());

		// Determine which servers errored (we skip deleting their prefs)
		List<String> erroredNames = new ArrayList<>();
		for (ServerNode node : serverNodes)
		{
			if (node.children != null)
			{
				for (Object child : node.children)
				{
					if (child instanceof ErrorNode)
					{
						erroredNames.add(node.name);
						break;
					}
				}
			}
		}

		// Save JSON + registry URL + clean up stale server prefs
		mcpConfig.saveServersJson(json, registryUrl, previousNames, erroredNames);

		// Save in-memory checkbox states to preference store
		for (Map.Entry<String, Boolean> entry : serverEnabled.entrySet())
		{
			mcpConfig.setServerEnabled(entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, Boolean> entry : toolEnabled.entrySet())
		{
			String[] parts = parseToolKey(entry.getKey());
			if (parts != null)
			{
				mcpConfig.setToolEnabled(parts[0], parts[1], entry.getValue());
			}
		}
		for (Map.Entry<String, Boolean> entry : toolAgentEnabled.entrySet())
		{
			String[] parts = parseToolAgentKey(entry.getKey());
			if (parts != null)
			{
				try
				{
					AssistantType at = AssistantType.valueOf(parts[2]);
					mcpConfig.setToolEnabledForAgent(parts[0], parts[1], at, entry.getValue());
				}
				catch (IllegalArgumentException e)
				{
					ServoyLog.logWarning("McpServersPreferencePage: unknown AssistantType: " + parts[2], e);
				}
			}
		}

		// Reset the AI model so next use picks up the new MCP tool configuration
		Activator.getDefault().getMcpServerConnectionService().removeStaleServers(
			McpConfiguration.parseServerNames(json));
		Activator.getDefault().clearServoyAiModel();


		return true;
	}

	/** Builds in-memory key for server+tool state maps. */
	private static String toolKey(String serverName, String toolName)
	{
		return serverName + McpPreferenceConstants.KEY_SEPARATOR + toolName;
	}

	/** Builds in-memory key for server+tool+agent state maps. */
	private static String agentKey(String serverName, String toolName, AssistantType at)
	{
		return serverName + McpPreferenceConstants.KEY_SEPARATOR + toolName + McpPreferenceConstants.KEY_SEPARATOR + at.name();
	}

	/** Parses in-memory key for server+tool state maps. Returns null for malformed keys. */
	private static String[] parseToolKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		int sepIdx = key.indexOf(McpPreferenceConstants.KEY_SEPARATOR);
		if (sepIdx <= 0 || sepIdx >= key.length() - McpPreferenceConstants.KEY_SEPARATOR.length())
		{
			return null;
		}
		return new String[] { key.substring(0, sepIdx), key.substring(sepIdx + McpPreferenceConstants.KEY_SEPARATOR.length()) };
	}

	/** Parses in-memory key for server+tool+agent state maps. Returns null for malformed keys. */
	private static String[] parseToolAgentKey(String key)
	{
		if (key == null)
		{
			return null;
		}

		int firstSepIdx = key.indexOf(McpPreferenceConstants.KEY_SEPARATOR);
		if (firstSepIdx <= 0)
		{
			return null;
		}

		int secondSepIdx = key.indexOf(McpPreferenceConstants.KEY_SEPARATOR, firstSepIdx + McpPreferenceConstants.KEY_SEPARATOR.length());
		if (secondSepIdx <= firstSepIdx + McpPreferenceConstants.KEY_SEPARATOR.length() ||
			secondSepIdx >= key.length() - McpPreferenceConstants.KEY_SEPARATOR.length())
		{
			return null;
		}

		String serverName = key.substring(0, firstSepIdx);
		String toolName = key.substring(firstSepIdx + McpPreferenceConstants.KEY_SEPARATOR.length(), secondSepIdx);
		String assistantTypeName = key.substring(secondSepIdx + McpPreferenceConstants.KEY_SEPARATOR.length());
		return new String[] { serverName, toolName, assistantTypeName };
	}

	@Override
	protected void performDefaults()
	{
		jsonText.setText(McpPreferenceConstants.DEFAULT_SERVERS_JSON);
		registryUrlText.setText(McpPreferenceConstants.DEFAULT_REGISTRY_URL);
		serverNodes.clear();
		serverEnabled.clear();
		toolEnabled.clear();
		toolAgentEnabled.clear();
		treeViewer.refresh();
	}

	@Override
	protected void performApply()
	{
		performOk();
	}

	// -------------------------------------------------------------------------
	// Server probing / tree refresh
	// -------------------------------------------------------------------------

	/**
	 * Schedules a re-probe after the user pauses typing in the JSON text area (500 ms debounce).
	 */
	private void scheduleRefreshAfterEdit()
	{
		Display display = Display.getCurrent();
		if (pendingRefresh != null)
		{
			display.timerExec(-1, pendingRefresh);
		}
		pendingRefresh = this::triggerReconnect;
		display.timerExec(500, pendingRefresh);
	}

	/**
	 * Rebuilds the server node list from the current JSON text, shows loading nodes,
	 * then starts a background job to probe each server.
	 */
	private void triggerReconnect()
	{
		pendingRefresh = null;
		String json = jsonText != null ? jsonText.getText() : mcpConfig.getServersJson();

		List<McpServerConfig> servers;

		// Parse the current (possibly unsaved) JSON from the text area
		McpConfiguration tempConfig = new McpConfiguration()
		{
			@Override
			public String getServersJson()
			{
				return json;
			}
		};
		servers = tempConfig.getConfiguredServers(); // this will return an empty list if the json is invalid

		// Rebuild server nodes with loading placeholders
		serverNodes.clear();
		for (McpServerConfig cfg : servers)
		{
			ServerNode node = new ServerNode(cfg.name);
			// Default enabled state: use in-memory map if present, else load from prefs
			if (!serverEnabled.containsKey(cfg.name))
			{
				serverEnabled.put(cfg.name, mcpConfig.isServerEnabled(cfg.name));
			}
			serverNodes.add(node);
		}

		if (treeViewer != null && !treeViewer.getTree().isDisposed())
		{
			treeViewer.setInput(serverNodes);
			treeViewer.expandAll();
		}

		if (servers.isEmpty())
		{
			return;
		}

		final List<McpServerConfig> serversToProbe = new ArrayList<>(servers);

		Job probeJob = new Job("Connecting to MCP servers...")
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				McpServerConnectionService service = Activator.getDefault().getMcpServerConnectionService();
				List<McpServerResult> results = service.reconnectAll(serversToProbe);

				Display.getDefault().asyncExec(() -> {
					if (treeViewer == null || treeViewer.getTree().isDisposed())
					{
						return;
					}
					for (McpServerResult result : results)
					{
						ServerNode node = findServerNode(result.serverName);
						if (node != null)
						{
							applyResultToNode(node, result);
						}
					}
					treeViewer.refresh();
					treeViewer.expandAll();
				});
				return Status.OK_STATUS;
			}
		};
		probeJob.setUser(false);
		probeJob.setPriority(Job.SHORT);
		probeJob.schedule();
	}

	private void applyResultToNode(ServerNode node, McpServerResult result)
	{
		if (result.success)
		{
			List<Object> children = new ArrayList<>();
			for (ToolSpecification spec : result.tools)
			{
				ToolNode toolNode = new ToolNode(node, spec);
				// Load tool-level prefs into in-memory map if not already there
				String tk = toolKey(node.name, spec.name());
				if (!toolEnabled.containsKey(tk))
				{
					toolEnabled.put(tk, mcpConfig.isToolEnabled(node.name, spec.name()));
				}
				// Load per-agent prefs
				for (AssistantType at : AssistantType.values())
				{
					String ak = agentKey(node.name, spec.name(), at);
					if (!toolAgentEnabled.containsKey(ak))
					{
						toolAgentEnabled.put(ak, mcpConfig.isToolEnabledForAgent(node.name, spec.name(), at));
					}
				}
				children.add(toolNode);
			}
			node.children = children;
		}
		else
		{
			node.children = List.of(new ErrorNode(node, result.errorMessage));
		}
	}

	private ServerNode findServerNode(String name)
	{
		for (ServerNode n : serverNodes)
		{
			if (n.name.equals(name))
			{
				return n;
			}
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Registry browser
	// -------------------------------------------------------------------------

	private void openRegistryBrowser()
	{
		String registryUrl = registryUrlText.getText().trim();
		if (registryUrl.isBlank())
		{
			registryUrl = McpPreferenceConstants.DEFAULT_REGISTRY_URL;
		}

		McpRegistryBrowserDialog dialog = new McpRegistryBrowserDialog(
			getShell(), registryUrl, jsonText.getText());
		if (dialog.open() == org.eclipse.jface.window.Window.OK)
		{
			String newJson = dialog.getResultJson();
			if (newJson != null && !newJson.equals(jsonText.getText()))
			{
				jsonText.setText(newJson);
				// triggerReconnect() will be called via the ModifyListener
			}
		}
	}


	private void addSampleRemoteMcp()
	{
		ObjectNode remote = MAPPER.createObjectNode();
		remote.put("type", "streamable-http");
		remote.put("url", "https://example.com/mcp");
		ObjectNode headers = remote.putObject("headers");
		headers.put("Authorization", "Bearer <your-token-here>");
		addSampleServer(SAMPLE_REMOTE_SERVER_NAME, remote);
	}

	private void addSampleStdioMcp()
	{
		ObjectNode stdio = MAPPER.createObjectNode();
		stdio.put("type", "stdio");
		stdio.put("command", "npx");
		stdio.putArray("args")
			.add("-y")
			.add("@example/sample-stdio-mcp");
		addSampleServer(SAMPLE_STDIO_SERVER_NAME, stdio);
	}

	private void addSampleServer(String baseServerName, ObjectNode serverConfig)
	{
		String existingJson = jsonText.getText();
		String serverName = nextAvailableServerName(baseServerName, McpConfiguration.parseServerNames(existingJson));
		String mergedJson = McpConfiguration.mergeServerIntoJson(existingJson, serverName, serverConfig);
		if (!existingJson.equals(mergedJson))
		{
			jsonText.setText(mergedJson);
		}
	}

	private static String nextAvailableServerName(String baseServerName, List<String> existingServerNames)
	{
		Set<String> existing = new HashSet<>(existingServerNames);
		if (!existing.contains(baseServerName))
		{
			return baseServerName;
		}
		int index = 2;
		while (existing.contains(baseServerName + "-" + index))
		{
			index++;
		}
		return baseServerName + "-" + index;
	}
	// -------------------------------------------------------------------------
	// Key helpers
	// -------------------------------------------------------------------------

}
