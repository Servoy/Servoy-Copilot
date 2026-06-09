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
package com.servoy.eclipse.developer.mcp.servers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;

@McpServer(name = "servoy-media")
public class ServoyMediaServer {
	private static final Pattern VALID_MEDIA_NAME = Pattern.compile("^[_a-zA-Z0-9\\-\\./]+$");

	@Tool(name = "media_rename", description = "Renames a media file or folder in a Servoy solution, preserving the UUID. "
			+ "For single file rename, provide the full media path name. "
			+ "For folder rename (batch), append a trailing '/' to both mediaName and newName.", type = "object")
	public String mediaRename(
			@ToolParam(name = "solutionName", description = "Name of the Servoy solution.", required = true) String solutionName,
			@ToolParam(name = "mediaName", description = "Current media path name (e.g. 'css/old.css') or folder path ending with '/' (e.g. 'images/icons/').", required = true) String mediaName,
			@ToolParam(name = "newName", description = "New media path name (e.g. 'css/new.css') or new folder prefix ending with '/' (e.g. 'images/new-icons/').", required = true) String newName) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject project = model.getServoyProject(solutionName);
			if (project == null) {
				List<String> available = new ArrayList<>();
				for (ServoyProject p : model.getServoyProjects()) {
					available.add(p.getProject().getName());
				}
				return "Error: Solution '" + solutionName + "' not found. Available solutions: " + available;
			}

			Solution solution = project.getEditingSolution();
			if (solution == null) {
				return "Error: Cannot get editing solution for '" + solutionName + "'.";
			}

			boolean isFolderRename = mediaName.endsWith("/");

			if (isFolderRename) {
				return renameFolderMedia(mediaName, newName, solution, project);
			} else {
				return renameSingleMedia(mediaName, newName, solution, project);
			}
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	private String renameSingleMedia(String mediaName, String newName, Solution solution, ServoyProject project) {
		String validationError = validateMediaName(newName);
		if (validationError != null)
			return validationError;

		Media media = solution.getMedia(mediaName);
		if (media == null) {
			List<String> available = collectMediaNames(solution);
			return "Error: Media '" + mediaName + "' not found in solution '" + project.getProject().getName()
					+ "'. Available media: " + available;
		}

		Media existing = solution.getMedia(newName);
		if (existing != null) {
			return "Error: Media '" + newName + "' already exists at the target name.";
		}

		try {
			media.setName(newName);
			project.saveEditingSolutionNodes(new IPersist[] { media }, true);
			return "{ \"oldName\": \"" + escapeJson(mediaName) + "\", \"newName\": \"" + escapeJson(newName)
					+ "\", \"renamed\": true }";
		} catch (Exception e) {
			return "Error renaming media: " + e.getMessage();
		}
	}

	private String renameFolderMedia(String oldFolder, String newFolder, Solution solution, ServoyProject project) {
		if (!newFolder.endsWith("/")) {
			return "Error: New folder name must end with '/'.";
		}

		String validationError = validateMediaName(newFolder.substring(0, newFolder.length() - 1));
		if (validationError != null)
			return validationError;

		List<Media> toRename = new ArrayList<>();
		Iterator<Media> mediaIter = solution.getMedias(false);
		while (mediaIter.hasNext()) {
			Media m = mediaIter.next();
			if (m.getName().startsWith(oldFolder)) {
				toRename.add(m);
			}
		}

		if (toRename.isEmpty()) {
			return "Error: No media found with folder prefix '" + oldFolder + "' in solution '"
					+ project.getProject().getName() + "'.";
		}

		for (Media m : toRename) {
			String computedNewName = newFolder + m.getName().substring(oldFolder.length());
			String nameValidation = validateMediaName(computedNewName);
			if (nameValidation != null)
				return nameValidation;

			Media existing = solution.getMedia(computedNewName);
			if (existing != null && existing != m) {
				return "Error: Media '" + computedNewName + "' already exists at the target name.";
			}
		}

		try {
			IPersist[] nodes = new IPersist[toRename.size()];
			for (int i = 0; i < toRename.size(); i++) {
				Media m = toRename.get(i);
				String computedNewName = newFolder + m.getName().substring(oldFolder.length());
				m.setName(computedNewName);
				nodes[i] = m;
			}
			project.saveEditingSolutionNodes(nodes, true);
			return "{ \"oldFolder\": \"" + escapeJson(oldFolder) + "\", \"newFolder\": \"" + escapeJson(newFolder)
					+ "\", \"renamedCount\": " + toRename.size() + ", \"renamed\": true }";
		} catch (Exception e) {
			return "Error renaming folder media: " + e.getMessage();
		}
	}

	private String validateMediaName(String name) {
		if (name == null || name.isBlank()) {
			return "Error: Target name cannot be empty.";
		}
		if (name.contains(" ")) {
			String suggested = name.replace(' ', '_');
			return "Error: Target name '" + name + "' is invalid: spaces not allowed. Suggested: '" + suggested + "'";
		}
		if (!VALID_MEDIA_NAME.matcher(name).matches()) {
			return "Error: Target name '" + name + "' is invalid: only [_a-zA-Z0-9\\-\\./] characters are allowed.";
		}
		String[] segments = name.split("/");
		for (String seg : segments) {
			if (seg.startsWith(".") || seg.endsWith(".")) {
				return "Error: Target name '" + name + "' is invalid: path segments cannot start or end with a dot.";
			}
		}
		return null;
	}

	private List<String> collectMediaNames(Solution solution) {
		List<String> names = new ArrayList<>();
		Iterator<Media> iter = solution.getMedias(true);
		while (iter.hasNext()) {
			names.add(iter.next().getName());
		}
		return names;
	}

	private String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
