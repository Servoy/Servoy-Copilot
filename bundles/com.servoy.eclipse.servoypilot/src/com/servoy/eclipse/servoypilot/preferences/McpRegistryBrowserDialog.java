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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.model.util.ServoyLog;

import dev.langchain4j.mcp.registryclient.DefaultMcpRegistryClient;
import dev.langchain4j.mcp.registryclient.McpRegistryClient;
import dev.langchain4j.mcp.registryclient.model.McpGetServerResponse;
import dev.langchain4j.mcp.registryclient.model.McpHeader;
import dev.langchain4j.mcp.registryclient.model.McpMetadata;
import dev.langchain4j.mcp.registryclient.model.McpPackage;
import dev.langchain4j.mcp.registryclient.model.McpRemote;
import dev.langchain4j.mcp.registryclient.model.McpServer;
import dev.langchain4j.mcp.registryclient.model.McpServerList;
import dev.langchain4j.mcp.registryclient.model.McpServerListRequest;
import dev.langchain4j.mcp.registryclient.model.McpServerListRequest.Builder;

/**
 * Dialog that browses an MCP registry and allows the user to add/remove
 * servers to/from the configured JSON.
 *
 * Each row represents one server name. If a server has multiple connection
 * variants (stdio packages and/or remotes), they are shown in one combo.
 */
public class McpRegistryBrowserDialog extends Dialog
{

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static class TransportVariant
	{
		String comboLabel;
		ObjectNode configNode;

		TransportVariant(String comboLabel, ObjectNode configNode)
		{
			this.comboLabel = comboLabel;
			this.configNode = configNode;
		}
	}

	private static class ServerEntry
	{
		String serverName;
		String description;
		String serverVersion;
		boolean inUse;
		boolean detailsLoaded;
		List<TransportVariant> variants = new ArrayList<>();

		ServerEntryUpdatableUI ui;

		ServerEntry(String serverName, String description, String serverVersion, boolean inUse, boolean detailsLoaded)
		{
			this.serverName = serverName;
			this.description = description;
			this.serverVersion = serverVersion;
			this.inUse = inUse;
			this.detailsLoaded = detailsLoaded;
		}

		private void updateDetailsFrom(ServerEntry detailedEntry)
		{
			this.description = detailedEntry.description;
			this.serverVersion = detailedEntry.serverVersion;
			this.detailsLoaded = detailedEntry.detailsLoaded;
			this.variants = detailedEntry.variants;
		}
	}

	private static record ServerEntryUpdatableUI(Label descLabel, StyledText nameLabel, Combo variantCombo, Button useOrRemoveBtn)
	{
	}

	private final String registryUrl;
	private String resultJson;

	private Text searchText;
	private ScrolledComposite scrolledComposite;
	private Composite listComposite;
	private Label statusLabel;
	private Button refreshButton;

	private List<ServerEntry> allEntries = new ArrayList<>();
	private boolean someServersDidNotListEitherRemotesOrPackagesAsTransport = false;
	private String filterText = "";

	private McpRegistryClient registryClient;
	private String nextCursor;
	private volatile boolean loadingMore;
	private volatile List<String> lastRefreshServersAlreadyInUse;
	private Set<ServerIdentifier> serversAlreadyLoadingDetails = new HashSet<>();

	public McpRegistryBrowserDialog(Shell parentShell, String registryUrl, String currentJson)
	{
		super(parentShell);
		this.registryUrl = registryUrl;
		this.resultJson = currentJson;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	protected void configureShell(Shell shell)
	{
		super.configureShell(shell);
		shell.setText("Browse MCP Registry");
		shell.setSize(700, 600);
	}

	@Override
	protected Control createDialogArea(Composite parent)
	{
		Composite area = (Composite)super.createDialogArea(parent);
		area.setLayout(new GridLayout(1, false));

		Label title = new Label(area, SWT.NONE);
		title.setText("MCP Registry");
		org.eclipse.jface.resource.JFaceResources.getFontRegistry();
		title.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
		title.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		Label subtitle = new Label(area, SWT.WRAP);
		subtitle.setText("Select the MCP servers that you want to use from this registry:");
		GridData subtitleData = new GridData(SWT.FILL, SWT.TOP, true, false);
		subtitleData.widthHint = 600;
		subtitle.setLayoutData(subtitleData);

		Composite searchRow = new Composite(area, SWT.NONE);
		searchRow.setLayout(new GridLayout(2, false));
		searchRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		searchText = new Text(searchRow, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
		searchText.setMessage("Search...");
		searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		final Timer searchTimer = new Timer(true);
		searchText.addModifyListener(new ModifyListener()
		{
			private TimerTask pendingTask;

			@Override
			public void modifyText(ModifyEvent e)
			{
				if (pendingTask != null)
				{
					pendingTask.cancel();
				}
				pendingTask = new TimerTask()
				{
					@Override
					public void run()
					{
						Display.getDefault().asyncExec(() -> {
							if (searchText.isDisposed())
							{
								return;
							}

							filterText = searchText.getText().trim();
							loadRegistry();
						});
					}
				};
				searchTimer.schedule(pendingTask, 1200);
			}
		});
		searchText.addDisposeListener(new DisposeListener()
		{
			@Override
			public void widgetDisposed(DisposeEvent e)
			{
				searchTimer.cancel();
			}
		});

		refreshButton = new Button(searchRow, SWT.PUSH);
		refreshButton.setText("\u21BB Refresh");
		refreshButton.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				loadRegistry();
			}
		});

		scrolledComposite = new ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
		scrolledComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		scrolledComposite.setExpandHorizontal(true);
		scrolledComposite.setExpandVertical(true);

		listComposite = new Composite(scrolledComposite, SWT.NONE);
		listComposite.setLayout(new GridLayout(1, false));
		scrolledComposite.setContent(listComposite);
		scrolledComposite.addControlListener(new org.eclipse.swt.events.ControlAdapter()
		{
			@Override
			public void controlResized(org.eclipse.swt.events.ControlEvent e)
			{
				if (listComposite != null && !listComposite.isDisposed())
				{
					relayoutScrolledComposite();
				}
			}
		});

		statusLabel = new Label(area, SWT.NONE);
		statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		statusLabel.setText("Loading registry...");

		scrolledComposite.getVerticalBar().addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				org.eclipse.swt.widgets.ScrollBar vBar = scrolledComposite.getVerticalBar();
				if (vBar != null && vBar.getMaximum() > 0)
				{
					int pos = vBar.getSelection() + vBar.getThumb();
					if (pos >= vBar.getMaximum() - 20)
					{
						loadMoreFromRegistry();
					}
				}
			}
		});

		loadRegistry();

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent)
	{
		createButton(parent, IDialogConstants.OK_ID, "OK", true);
		createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
	}

	/** Returns the updated JSON text after the user's add/remove actions. */
	public String getResultJson()
	{
		return resultJson;
	}

	private void loadRegistry()
	{
		allEntries.clear();
		someServersDidNotListEitherRemotesOrPackagesAsTransport = false;
		nextCursor = null;
		registryClient = null;
		serversAlreadyLoadingDetails.clear();

		Display.getDefault().asyncExec(() -> {
			updateStatusLabelAndReloadList(true);

			if (statusLabel != null && !statusLabel.isDisposed())
			{
				statusLabel.setText("Loading registry...");
			}
			if (refreshButton != null && !refreshButton.isDisposed())
			{
				refreshButton.setEnabled(false);
			}
		});

		Job job = new Job("Loading MCP Registry entries...")
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				try
				{
					McpRegistryClient client = new DefaultMcpRegistryClient.Builder()
						.baseUrl(registryUrl)
						.build();
					registryClient = client;

					Builder mcpServerListRequest = McpServerListRequest.builder().limit(50L);
					if (!filterText.isBlank())
					{
						mcpServerListRequest.search(filterText);
					}

					McpServerList list = client.listServers(mcpServerListRequest.build());

					McpMetadata meta = list != null ? list.getMetadata() : null;
					nextCursor = meta != null ? meta.getNextCursor() : null;

					List<ServerEntry> loaded = buildEntries(list);
					mergeEntriesByServerName(allEntries, loaded);

					updateStatusLabelAndReloadList(false);
					Display.getDefault().asyncExec(() -> {
						if (scrolledComposite != null && !scrolledComposite.isDisposed())
						{
							scrolledComposite.getVerticalBar().setSelection(0);
						}
					});
				}
				catch (Exception ex)
				{
					ServoyLog.logWarning("McpRegistryBrowserDialog: failed to load registry", ex);
					Display.getDefault().asyncExec(() -> {
						if (statusLabel != null && !statusLabel.isDisposed())
						{
							statusLabel.setText("Error loading registry: " + ex.getMessage());
						}
						if (refreshButton != null && !refreshButton.isDisposed())
						{
							refreshButton.setEnabled(true);
						}
					});
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
	}

	private void updateStatusLabelAndReloadList(boolean loading)
	{
		Display.getDefault().asyncExec(() -> {
			if (statusLabel == null || statusLabel.isDisposed())
			{
				return;
			}
			String moreTxt = nextCursor != null ? " ... scroll down for more." : "";
			String someNotUseful = (someServersDidNotListEitherRemotesOrPackagesAsTransport
				? " (some servers did not contain enough connection info and were ignored)"
				: "");
			String txt = loading ? "loading..." : ("loaded " + allEntries.size() + " servers" + moreTxt + someNotUseful);
			statusLabel.setText(txt);
			statusLabel.setToolTipText(txt);
			if (refreshButton != null && !refreshButton.isDisposed())
			{
				refreshButton.setEnabled(true);
			}
			rebuildList(loading);
		});
	}

	private void loadMoreFromRegistry()
	{
		if (loadingMore || nextCursor == null || registryClient == null)
		{
			return;
		}
		loadingMore = true;

		Display.getDefault().asyncExec(() -> {
			if (statusLabel != null && !statusLabel.isDisposed())
			{
				statusLabel.setText("Loading more...");
			}
			if (refreshButton != null && !refreshButton.isDisposed())
			{
				refreshButton.setEnabled(false);
			}
		});

		final String cursorToLoad = nextCursor;
		Job job = new Job("Loading more MCP Registry entries...")
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				try
				{
					Builder mcpServerListRequest = McpServerListRequest.builder().cursor(cursorToLoad).limit(50L);
					if (!filterText.isBlank())
					{
						mcpServerListRequest.search(filterText);
					}

					McpServerList list = registryClient.listServers(mcpServerListRequest.build());

					McpMetadata meta = list != null ? list.getMetadata() : null;
					nextCursor = meta != null ? meta.getNextCursor() : null;

					List<ServerEntry> loaded = buildEntries(list);
					mergeEntriesByServerName(allEntries, loaded);

					updateStatusLabelAndReloadList(false);
				}
				catch (Exception ex)
				{
					ServoyLog.logWarning("McpRegistryBrowserDialog: failed to load more entries", ex);
					Display.getDefault().asyncExec(() -> {
						if (statusLabel != null && !statusLabel.isDisposed())
						{
							statusLabel.setText("Error loading more: " + ex.getMessage());
						}
						if (refreshButton != null && !refreshButton.isDisposed())
						{
							refreshButton.setEnabled(true);
						}
					});
				}
				finally
				{
					loadingMore = false;
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
	}

	private List<ServerEntry> buildEntries(McpServerList list)
	{
		lastRefreshServersAlreadyInUse = McpConfiguration.parseServerNames(resultJson);
		List<ServerEntry> entries = new ArrayList<>();

		List<McpGetServerResponse> serverListResponses = list != null ? list.getServers() : null;
		if (list == null || serverListResponses == null)
		{
			return entries;
		}

		for (McpGetServerResponse serverGetResponse : serverListResponses)
		{
			List<ServerEntry> candidateEntries = new ArrayList<>();
			addRegistryEntryForServer(serverGetResponse, true, candidateEntries);
			mergeEntriesByServerName(entries, candidateEntries);
		}

		return entries;
	}


	private void mergeEntriesByServerName(List<ServerEntry> targetEntries, List<ServerEntry> additions)
	{
		if (targetEntries == null || additions == null || additions.isEmpty())
		{
			return;
		}

		for (ServerEntry addition : additions)
		{
			if (addition == null || addition.serverName == null || addition.serverName.isBlank())
			{
				continue;
			}

			ServerEntry existing = findEntryByServerName(targetEntries, addition.serverName);
			if (existing == null)
			{
				targetEntries.add(addition);
			}
			else
			{
				if ((existing.description == null || existing.description.isBlank()) && addition.description != null)
				{
					existing.description = addition.description;
				}
				if ((existing.serverVersion == null || existing.serverVersion.isBlank()) && addition.serverVersion != null)
				{
					existing.serverVersion = addition.serverVersion;
				}
				existing.inUse = existing.inUse || addition.inUse;
				existing.detailsLoaded = existing.detailsLoaded && addition.detailsLoaded;

				for (TransportVariant variant : addition.variants)
				{
					if (!containsVariant(existing.variants, variant))
					{
						existing.variants.add(variant);
					}
				}
			}
		}
	}

	private static ServerEntry findEntryByServerName(List<ServerEntry> entries, String serverName)
	{
		for (ServerEntry entry : entries)
		{
			if (entry != null && serverName.equals(entry.serverName))
			{
				return entry;
			}
		}
		return null;
	}

	private static boolean containsVariant(List<TransportVariant> variants, TransportVariant candidate)
	{
		if (candidate == null)
		{
			return false;
		}
		for (TransportVariant existing : variants)
		{
			if (existing != null && Objects.equals(existing.comboLabel, candidate.comboLabel) &&
				Objects.equals(existing.configNode, candidate.configNode))
			{
				return true;
			}
		}
		return false;
	}

	private void addRegistryEntryForServer(McpGetServerResponse serverGetResponse, boolean canReturnPartialEntry, List<ServerEntry> entries)
	{
		boolean foundEitherPackageOrRemoteEntry = false;

		McpServer server = serverGetResponse != null ? serverGetResponse.getServer() : null;
		if (server == null)
		{
			return;
		}

		String serverName = server.getName();
		if (serverName == null || serverName.isBlank())
		{
			return;
		}

		String description = server.getDescription() != null ? server.getDescription() : "";
		String serverVersion = server.getVersion();
		boolean inUse = lastRefreshServersAlreadyInUse.contains(serverName);

		ServerEntry serverEntry = new ServerEntry(serverName, description, serverVersion, inUse, true);

		List<McpPackage> packages = server.getPackages();
		if (packages != null)
		{
			for (McpPackage pkg : packages)
			{
				if (pkg == null)
				{
					continue;
				}
				foundEitherPackageOrRemoteEntry = true;

				String registryType = pkg.getRegistryType();
				String packageVersion = pkg.getVersion();
				String packageName = pkg.getIdentifier() != null ? pkg.getIdentifier() : serverName;
				ObjectNode stdioNode = buildSTDIONode(registryType, packageName, pkg);

				if (stdioNode == null)
				{
					serverEntry.detailsLoaded = false;
					if (canReturnPartialEntry)
					{
						serverEntry.variants.add(new TransportVariant("(loading...) STDIO", null));
					}
				}
				else
				{
					String registryTypeOrCommand = registryType != null ? registryType : pkg.getRuntimeHint();
					if (registryTypeOrCommand == null || registryTypeOrCommand.isBlank())
					{
						registryTypeOrCommand = "command";
					}
					String label = "STDIO (" + registryTypeOrCommand + ")" +
						(packageVersion != null && !packageVersion.isBlank() ? " - package " + packageVersion : "");
					serverEntry.variants.add(new TransportVariant(label, stdioNode));
				}
			}
		}

		List<McpRemote> remotes = server.getRemotes();
		if (remotes != null)
		{
			for (McpRemote remote : remotes)
			{
				if (remote == null)
				{
					continue;
				}
				foundEitherPackageOrRemoteEntry = true;

				String url = remote.getUrl();
				if (url != null && !url.isBlank())
				{
					ObjectNode httpNode = MAPPER.createObjectNode();
					if (remote.getType() != null)
					{
						httpNode.put("type", remote.getType());
					}
					httpNode.put("url", url);
					List<McpHeader> hdrs = remote.getHeaders();
					if (hdrs != null && !hdrs.isEmpty())
					{
						ObjectNode headersNode = httpNode.putObject("headers");
						for (McpHeader hdr : hdrs)
						{
							if (hdr.getName() != null)
							{
								String sampleValue;
								if (hdr.getDefaultValue() != null && !hdr.getDefaultValue().isBlank())
								{
									sampleValue = hdr.getDefaultValue();
								}
								else if (hdr.getValue() != null && !hdr.getValue().isBlank())
								{
									sampleValue = hdr.getValue();
								}
								else
								{
									sampleValue = "<your-" + hdr.getName().toLowerCase() + "-here>";
								}
								headersNode.put(hdr.getName(), sampleValue);
							}
						}
					}
					serverEntry.variants.add(new TransportVariant("Remote (" + url + ")", httpNode));
				}
				else
				{
					serverEntry.detailsLoaded = false;
					if (canReturnPartialEntry)
					{
						serverEntry.variants.add(new TransportVariant("(loading...) REMOTE", null));
					}
				}
			}
		}

		someServersDidNotListEitherRemotesOrPackagesAsTransport = someServersDidNotListEitherRemotesOrPackagesAsTransport || !foundEitherPackageOrRemoteEntry;

		if (!serverEntry.variants.isEmpty())
		{
			entries.add(serverEntry);
		}
	}

	/**
	 * Builds a stdio ObjectNode for a package entry.
	 * Supports npm, pypi/pip, nuget, oci, mcpb and maven registries.
	 * Returns null if the registry is not recognized or required metadata is missing.
	 */
	private ObjectNode buildSTDIONode(String registryName, String packageName, McpPackage pkg)
	{
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", "stdio");

		if ("npm".equalsIgnoreCase(registryName))
		{
			node.put("command", resolveCommand(pkg, "npx"));
			var args = node.putArray("args");
			args.add("-y");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}
		else if ("pypi".equalsIgnoreCase(registryName) || "pip".equalsIgnoreCase(registryName))
		{
			node.put("command", resolveCommand(pkg, "uvx"));
			var args = node.putArray("args");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}
		else if ("nuget".equalsIgnoreCase(registryName))
		{
			node.put("command", resolveCommand(pkg, "dotnet"));
			var args = node.putArray("args");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}
		else if ("oci".equalsIgnoreCase(registryName))
		{
			node.put("command", resolveCommand(pkg, "docker"));
			var args = node.putArray("args");
			args.add("run");
			args.add("--rm");
			args.add("-i");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}
		else if ("mcpb".equalsIgnoreCase(registryName))
		{
			String command = resolveCommand(pkg, null);
			if (command == null || command.isBlank())
			{
				return null;
			}
			node.put("command", command);
			var args = node.putArray("args");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}
		else if ("maven".equalsIgnoreCase(registryName))
		{
			String mainClass = resolveMavenMainClass(pkg);
			if (mainClass == null || mainClass.isBlank())
			{
				return null;
			}

			node.put("command", resolveCommand(pkg, "mvn"));
			var args = node.putArray("args");
			args.add("exec:java");
			args.add("-Dexec.mainClass=" + mainClass);
			appendRuntimeArguments(args, pkg);
		}
		else
		{
			String command = resolveCommand(pkg, null);
			if (command == null || command.isBlank())
			{
				return null;
			}
			node.put("command", command);
			var args = node.putArray("args");
			args.add(packageName);
			appendRuntimeArguments(args, pkg);
		}

		if (pkg.getEnvironmentVariables() != null && !pkg.getEnvironmentVariables().isEmpty())
		{
			ObjectNode envNode = node.putObject("env");
			for (var envVar : pkg.getEnvironmentVariables())
			{
				if (envVar != null && envVar.getName() != null)
				{
					envNode.put(envVar.getName(), envVar.getValue() != null ? envVar.getValue() : "");
				}
			}
		}

		return node;
	}

	private static String resolveCommand(McpPackage pkg, String defaultCommand)
	{
		String runtimeHint = pkg != null ? pkg.getRuntimeHint() : null;
		if (runtimeHint != null && !runtimeHint.isBlank())
		{
			return runtimeHint;
		}
		return defaultCommand;
	}

	private static void appendRuntimeArguments(ArrayNode args, McpPackage pkg)
	{
		if (args == null || pkg == null || pkg.getRuntimeArguments() == null)
		{
			return;
		}

		for (var arg : pkg.getRuntimeArguments())
		{
			if (arg != null && arg.isRequired())
			{
				if (arg.getName() != null)
				{
					args.add(arg.getName());
				}
				args.add(arg.getValue() != null ? arg.getValue()
					: arg.getDefaultValue() != null ? arg.getDefaultValue() : arg.getValueHint() != null ? arg.getValueHint() : "null");
			}
		}
	}

	private static String resolveMavenMainClass(McpPackage pkg)
	{
		if (pkg == null || pkg.getPackageArguments() == null)
		{
			return null;
		}

		for (var arg : pkg.getPackageArguments())
		{
			if (arg == null || arg.getName() == null)
			{
				continue;
			}

			String name = arg.getName();
			if ("mainClass".equalsIgnoreCase(name) || "main-class".equalsIgnoreCase(name) || "exec.mainClass".equalsIgnoreCase(name))
			{
				if (arg.getValue() != null && !arg.getValue().isBlank())
				{
					return arg.getValue();
				}
				if (arg.getDefaultValue() != null && !arg.getDefaultValue().isBlank())
				{
					return arg.getDefaultValue();
				}
			}
		}

		return null;
	}

	private void rebuildList(boolean loading)
	{
		if (listComposite == null || listComposite.isDisposed())
		{
			return;
		}

		for (var child : listComposite.getChildren())
		{
			child.dispose();
		}

		if (allEntries.isEmpty())
		{
			Label noMatch = new Label(listComposite, SWT.NONE);
			noMatch.setText(loading ? "" : (filterText.isBlank()
				? "No usable MCP servers were found in the registry."
				: "No usable servers from the registry match your search string."));
			noMatch.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		}

		for (ServerEntry entry : allEntries)
		{
			createEntryRow(listComposite, entry);
			Label sep = new Label(listComposite, SWT.SEPARATOR | SWT.HORIZONTAL);
			sep.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		}

		listComposite.layout(true, true);
		relayoutScrolledComposite();
	}

	private void relayoutScrolledComposite()
	{
		int availableWidth = scrolledComposite.getClientArea().width;
		if (availableWidth <= 0)
		{
			availableWidth = 580;
		}
		scrolledComposite.setMinSize(listComposite.computeSize(availableWidth, SWT.DEFAULT));
		scrolledComposite.layout(true, true);
	}

	private void createEntryRow(Composite parent, ServerEntry entry)
	{
		Composite rowComposite = new Composite(parent, SWT.NONE);
		rowComposite.setLayout(new GridLayout(2, false));
		rowComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		Composite textAreaComposite = new Composite(rowComposite, SWT.NONE);
		textAreaComposite.setLayout(new GridLayout(1, false));
		textAreaComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		StyledText nameLabel = new StyledText(textAreaComposite, SWT.NONE);
		nameLabel.setBackground(parent.getBackground());
		nameLabel.setEditable(false);
		nameLabel.setCaret(null);
		nameLabel.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
		updateNameLabel(entry, nameLabel);

		Label descLabel = new Label(textAreaComposite, SWT.WRAP);
		descLabel.setText(entry.description != null ? entry.description : "");
		GridData descData = new GridData(SWT.FILL, SWT.TOP, true, false);
		descData.widthHint = 480;
		descLabel.setLayoutData(descData);

		Combo variantsCombo = new Combo(textAreaComposite, SWT.DROP_DOWN | SWT.READ_ONLY);
		GridData comboData = new GridData(SWT.LEFT, SWT.TOP, false, false);
		comboData.widthHint = computeComboPreferredWidth(textAreaComposite, entry);
		variantsCombo.setLayoutData(comboData);
		updateVariantsCombo(entry, variantsCombo);
		hookComboMouseWheelToScrollContainer(variantsCombo);

		Composite buttonArea = new Composite(rowComposite, SWT.NONE);
		buttonArea.setLayout(new GridLayout(1, false));
		buttonArea.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, false, false));

		Button useOrRemoveBtnFinal = new Button(buttonArea, SWT.PUSH);
		entry.ui = new ServerEntryUpdatableUI(descLabel, nameLabel, variantsCombo, useOrRemoveBtnFinal);
		useOrRemoveBtnFinal.addListener(SWT.Selection, event -> {
			if (entry.inUse)
			{
				resultJson = McpConfiguration.removeServerFromJson(resultJson, entry.serverName);
			}
			else
			{
				TransportVariant selectedVariant = getSelectedVariant(entry, variantsCombo);
				if (selectedVariant == null || selectedVariant.configNode == null)
				{
					return;
				}
				resultJson = McpConfiguration.mergeServerIntoJson(resultJson, entry.serverName, selectedVariant.configNode);
			}

			entry.inUse = McpConfiguration.parseServerNames(resultJson).contains(entry.serverName);
			updateNameLabel(entry, entry.ui != null ? entry.ui.nameLabel : null);
			configureButton(entry, useOrRemoveBtnFinal, variantsCombo);
		});

		configureButton(entry, useOrRemoveBtnFinal, variantsCombo);

		variantsCombo.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				configureButton(entry, useOrRemoveBtnFinal, variantsCombo);
			}
		});

		if (!entry.detailsLoaded && entry.serverName != null && registryClient != null && !isServerAlreadyLoadingDetails(entry))
		{
			setServerAlreadyLoadingDetails(entry, true);
			final McpRegistryClient clientRef = registryClient;
			Job detailJob = new Job("Loading details for MCP Server entry '" + entry.serverName + "'...")
			{
				@Override
				protected IStatus run(IProgressMonitor monitor)
				{
					try
					{
						McpGetServerResponse resp = clientRef.getSpecificServerVersion(entry.serverName,
							entry.serverVersion != null ? entry.serverVersion : "latest");

						List<ServerEntry> detailedEntriesForThisServer = new ArrayList<>();
						addRegistryEntryForServer(resp, false, detailedEntriesForThisServer);
						updateExistingEntryWithDetailedEntry(entry, detailedEntriesForThisServer);
					}
					catch (Exception ex)
					{
						ServoyLog.logWarning(entry.description, ex);
						updateExistingEntryWithDetailedEntry(entry, new ArrayList<>());
					}
					finally
					{
						setServerAlreadyLoadingDetails(entry, false);
					}
					return Status.OK_STATUS;
				}

				private void updateExistingEntryWithDetailedEntry(ServerEntry oneEntryToIdentifyServer,
					List<ServerEntry> detailedEntriesForThisServer)
				{
					ServerEntry shownEntry = null;
					for (ServerEntry candidate : allEntries)
					{
						if (oneEntryToIdentifyServer.serverName.equals(candidate.serverName) &&
							Objects.equals(oneEntryToIdentifyServer.serverVersion, candidate.serverVersion))
						{
							shownEntry = candidate;
							break;
						}
					}
					if (shownEntry == null)
					{
						return;
					}

					ServerEntry detailedEntry = detailedEntriesForThisServer.isEmpty() ? null : detailedEntriesForThisServer.get(0);
					if (detailedEntry != null)
					{
						shownEntry.updateDetailsFrom(detailedEntry);
					}
					else
					{
						shownEntry.detailsLoaded = true;
						shownEntry.description = "Could not load enough details from the registry for this MCP Server: " + shownEntry.serverName +
							(shownEntry.serverVersion != null ? " " + shownEntry.serverVersion : "");
					}

					shownEntry.inUse = McpConfiguration.parseServerNames(resultJson).contains(shownEntry.serverName);

					final ServerEntry entryToRefresh = shownEntry;
					Display.getDefault().asyncExec(() -> {
						if (entryToRefresh.ui == null || entryToRefresh.ui.descLabel.isDisposed())
						{
							return;
						}
						entryToRefresh.ui.descLabel.setText(entryToRefresh.description != null ? entryToRefresh.description : "");
						updateNameLabel(entryToRefresh, entryToRefresh.ui.nameLabel);
						updateVariantsCombo(entryToRefresh, entryToRefresh.ui.variantCombo);
						configureButton(entryToRefresh, entryToRefresh.ui.useOrRemoveBtn, entryToRefresh.ui.variantCombo);
						textAreaComposite.layout(true, true);
						listComposite.layout(true, true);
						relayoutScrolledComposite();
					});
				}
			};
			detailJob.setUser(false);
			detailJob.schedule();
		}
	}

	private static record ServerIdentifier(String serverName, String serverVersion)
	{
	}

	private void setServerAlreadyLoadingDetails(ServerEntry entry, boolean loading)
	{
		ServerIdentifier identifier = new ServerIdentifier(entry.serverName, entry.serverVersion);
		if (loading)
		{
			serversAlreadyLoadingDetails.add(identifier);
		}
		else
		{
			serversAlreadyLoadingDetails.remove(identifier);
		}
	}

	private boolean isServerAlreadyLoadingDetails(ServerEntry entry)
	{
		return serversAlreadyLoadingDetails.contains(new ServerIdentifier(entry.serverName, entry.serverVersion));
	}

	private void updateNameLabel(ServerEntry entry, StyledText nameLabel)
	{
		if (nameLabel == null || nameLabel.isDisposed())
		{
			return;
		}
		nameLabel.setText(entry.serverName + (entry.inUse ? " (already in use)" : ""));
	}


	private void updateVariantsCombo(ServerEntry entry, Combo variantsCombo)
	{
		int previousSelection = variantsCombo.getSelectionIndex();
		String[] labels = new String[entry.variants.size()];
		for (int i = 0; i < entry.variants.size(); i++)
		{
			labels[i] = entry.variants.get(i).comboLabel;
		}
		variantsCombo.setItems(labels);

		if (variantsCombo.getLayoutData() instanceof GridData gd)
		{
			gd.widthHint = computeComboPreferredWidth(variantsCombo.getParent(), entry);
			variantsCombo.getParent().layout(true, true);
		}

		if (labels.length == 0)
		{
			return;
		}

		if (previousSelection >= 0 && previousSelection < labels.length)
		{
			variantsCombo.select(previousSelection);
			return;
		}

		int defaultSelection = findDefaultVariantSelectionIndex(entry);
		variantsCombo.select(defaultSelection);
	}

	private int findDefaultVariantSelectionIndex(ServerEntry entry)
	{
		for (int i = entry.variants.size() - 1; i >= 0; i--)
		{
			TransportVariant variant = entry.variants.get(i);
			if (variant != null && variant.configNode != null && variant.comboLabel != null &&
				variant.comboLabel.startsWith("STDIO ("))
			{
				return i;
			}
		}

		for (int i = 0; i < entry.variants.size(); i++)
		{
			TransportVariant variant = entry.variants.get(i);
			if (variant != null && variant.configNode != null)
			{
				return i;
			}
		}

		return 0;
	}

	private int computeComboPreferredWidth(Composite parent, ServerEntry entry)
	{
		int minWidth = 180;
		int maxWidth = 420;
		int widest = minWidth;
		GC gc = new GC(parent);
		try
		{
			gc.setFont(parent.getFont());
			for (TransportVariant variant : entry.variants)
			{
				if (variant != null && variant.comboLabel != null)
				{
					int w = gc.textExtent(variant.comboLabel).x + 44;
					if (w > widest)
					{
						widest = w;
					}
				}
			}
		}
		finally
		{
			gc.dispose();
		}
		if (widest < minWidth)
		{
			return minWidth;
		}
		return Math.min(widest, maxWidth);
	}

	private void hookComboMouseWheelToScrollContainer(Combo combo)
	{
		combo.addListener(SWT.MouseVerticalWheel, e -> {
			if (scrolledComposite == null || scrolledComposite.isDisposed())
			{
				return;
			}
			org.eclipse.swt.widgets.ScrollBar vBar = scrolledComposite.getVerticalBar();
			if (vBar == null)
			{
				return;
			}
			int step = Math.max(8, vBar.getIncrement());
			int direction = e.count > 0 ? -1 : 1;
			int newSelection = vBar.getSelection() + (direction * step);
			int min = vBar.getMinimum();
			int max = Math.max(min, vBar.getMaximum() - vBar.getThumb());
			if (newSelection < min)
			{
				newSelection = min;
			}
			if (newSelection > max)
			{
				newSelection = max;
			}
			vBar.setSelection(newSelection);
			scrolledComposite.setOrigin(scrolledComposite.getOrigin().x, newSelection);
			e.type = SWT.None;
			e.doit = false;
		});
	}

	private TransportVariant getSelectedVariant(ServerEntry entry, Combo variantsCombo)
	{
		int index = variantsCombo.getSelectionIndex();
		if (index < 0 || index >= entry.variants.size())
		{
			return null;
		}
		return entry.variants.get(index);
	}

	private void configureButton(ServerEntry entry, Button useOrRemoveBtn, Combo variantsCombo)
	{
		useOrRemoveBtn.setText(entry.inUse ? "Remove" : "Use");
		useOrRemoveBtn.requestLayout();
		if (useOrRemoveBtn.getParent() != null && !useOrRemoveBtn.getParent().isDisposed())
		{
			useOrRemoveBtn.getParent().layout(true, true);
		}
		if (entry.inUse)
		{
			useOrRemoveBtn.setEnabled(true);
		}
		else
		{
			TransportVariant selectedVariant = getSelectedVariant(entry, variantsCombo);
			useOrRemoveBtn.setEnabled(selectedVariant != null && selectedVariant.configNode != null);
		}
	}
}