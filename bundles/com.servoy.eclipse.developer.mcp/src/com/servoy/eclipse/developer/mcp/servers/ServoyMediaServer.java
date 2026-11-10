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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;


import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.SolutionSerializer;
import com.servoy.eclipse.model.util.ResourcesUtils;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptNameValidator;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.util.Pair;

@McpServer(name = "servoy-media")
public class ServoyMediaServer {
	private static final Pattern VALID_MEDIA_NAME = Pattern.compile("^[_a-zA-Z0-9\\-\\./]+$");

	private static final String DEFAULT_TOKENS_FILE = "tokens.css";
	private static final String CSS_MIME_TYPE = "text/css";
	private static final long MAX_TOKENS_FILE_SIZE = 1024L * 1024L;

	private static final String IMPORT_ADDED = "added";
	private static final String IMPORT_UPGRADED = "upgraded";
	private static final String IMPORT_ALREADY_PRESENT = "alreadyPresent";
	private static final String IMPORT_SKIPPED = "skipped";

	private record ImportEdit(String text, String outcome) {
	}

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

	@Tool(name = "media_install_tokens", description = "Installs a CSS custom-properties design-tokens file into the ACTIVE Servoy solution's media folder, "
			+ "registered properly (UUID + medias.obj) like solution-created media, and idempotently ensures a single "
			+ "'@import (inline)' of that file in the solution's <solution>.less stylesheet, so the tokens are compiled into the solution CSS. "
			+ "Operates on the active solution (no solutionName). Running it again with the same inputs leaves exactly one tokens media "
			+ "and one @import; running again with changed content updates the media bytes in place. A plain (non-inline) @import of "
			+ "the same file is upgraded to the inline form. If the tokens file or the stylesheet lives in a module, that module is "
			+ "updated and named in the result. Prefer sourcePath (the tool copies the whole file byte for byte, so no token is lost); "
			+ "pass exactly one of sourcePath or tokensContent. Custom properties only - does not project tokens into compile-time LESS variables.", type = "object")
	public String installDesignTokens(
			@ToolParam(name = "tokensContent", description = "The complete CSS custom-properties file content (e.g. a ':root{ --token: value; ... }' block), written verbatim as UTF-8. Use only when there is no file to point sourcePath at; never pass a subset of the tokens.", required = false) String tokensContent,
			@ToolParam(name = "mediaFileName", description = "Media path/name for the tokens file (default 'tokens.css'). Same naming rules as media_rename.", required = false) String mediaFileName,
			@ToolParam(name = "sourcePath", description = "Path of an existing .css tokens file to copy byte for byte: absolute, or relative to the workspace root. Must be inside the workspace, an open project or ~/.servoy/opencode. Preferred over tokensContent.", required = false) String sourcePath) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			if (activeProject == null) {
				return "Error: No active Servoy solution. Activate a solution first.";
			}

			String fileName = (mediaFileName == null || mediaFileName.isBlank()) ? DEFAULT_TOKENS_FILE : mediaFileName;
			String validationError = validateMediaName(fileName);
			if (validationError != null)
				return validationError;
			if (!fileName.toLowerCase(Locale.ROOT).endsWith(".css"))
				return "Error: mediaFileName must end in .css: " + fileName;

			byte[] contentBytes;
			try {
				IPath workspaceLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
				Path baseDir = workspaceLocation == null ? null : workspaceLocation.toFile().toPath();
				contentBytes = resolveTokensBytes(tokensContent, sourcePath, baseDir, allowedSourceRoots(baseDir));
			} catch (IllegalArgumentException e) {
				return "Error: " + e.getMessage();
			}

			FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
			Solution editingSolution = activeProject.getEditingSolution();
			if (fs == null || editingSolution == null) {
				return "Error: Cannot resolve the active solution's editing state.";
			}

			String mediaOutcome;
			ServoyProject mediaOwner;
			Media tokens = fs.getMedia(fileName);
			if (tokens != null) {
				if (isStylesheet(tokens, editingSolution)) {
					return "Error: mediaFileName '" + fileName + "' is the solution stylesheet; choose another name.";
				}
				mediaOwner = owningProject(model, tokens, activeProject);
				byte[] oldData = tokens.getMediaData();
				String oldMimeType = tokens.getMimeType();
				tokens.setPermMediaData(contentBytes);
				tokens.setMimeType(CSS_MIME_TYPE);
				try {
					saveMedia(mediaOwner, tokens);
				} catch (RepositoryException | RuntimeException e) {
					tokens.setPermMediaData(oldData);
					tokens.setMimeType(oldMimeType);
					restoreSaved(mediaOwner, tokens, e);
					throw e;
				}
				mediaOutcome = "updated";
			} else {
				mediaOwner = activeProject;
				// Write the file first, so a failed write leaves nothing half-created in the model. The IDE's resource
				// listener also registers a new file under medias/; while the resource-changes counter is held it only
				// queues the change and handles it once the counter is back at zero, when this media already exists, so
				// it finds it by name and the media is registered once.
				model.getResourceChangesHandlerCounter().increment();
				try {
					writeNewMediaFile(activeProject, fileName, contentBytes);
					tokens = editingSolution.getMedia(fileName);
					if (tokens == null) {
						tokens = editingSolution.createNewMedia(new ScriptNameValidator(), fileName);
					}
					tokens.setMimeType(CSS_MIME_TYPE);
					tokens.setPermMediaData(contentBytes);
					try {
						saveMedia(activeProject, tokens);
					} catch (RepositoryException | RuntimeException e) {
						throw new RepositoryException("the media file '" + fileName
								+ "' was written but saving its registration failed; run the tool again to finish it: "
								+ e.getMessage(), e);
					}
				} finally {
					model.getResourceChangesHandlerCounter().decrement();
				}
				mediaOutcome = "created";
			}

			StringBuilder result = new StringBuilder();
			result.append("{ \"solution\": \"").append(escapeJson(activeProject.getProject().getName()))
					.append("\", \"mediaFile\": \"").append(escapeJson(fileName))
					.append("\", \"mediaSolution\": \"").append(escapeJson(mediaOwner.getProject().getName()))
					.append("\", \"media\": \"").append(mediaOutcome).append('"');

			Media stylesheet = findStylesheetMedia(fs, editingSolution);
			String importOutcome;
			if (stylesheet == null) {
				importOutcome = IMPORT_SKIPPED;
			} else {
				ServoyProject stylesheetOwner = owningProject(model, stylesheet, activeProject);
				try {
					importOutcome = ensureImport(stylesheet, fileName, stylesheetOwner);
				} catch (Exception e) {
					return "Error: tokens media '" + fileName + "' was " + mediaOutcome + " in solution '"
							+ mediaOwner.getProject().getName() + "', but adding the @import to stylesheet '"
							+ stylesheet.getName() + "' failed: " + e.getMessage();
				}
				result.append(", \"stylesheet\": \"").append(escapeJson(stylesheet.getName()))
						.append("\", \"stylesheetSolution\": \"")
						.append(escapeJson(stylesheetOwner.getProject().getName())).append('"');
			}
			result.append(", \"import\": \"").append(importOutcome).append("\" }");
			return result.toString();
		} catch (Exception e) {
			StringBuilder error = new StringBuilder("Error: ").append(e.getMessage());
			for (Throwable cleanup : e.getSuppressed()) {
				error.append(" (cleanup also failed: ").append(cleanup.getMessage()).append(')');
			}
			return error.toString();
		}
	}

	/**
	 * The folders a sourcePath may be read from: the Eclipse workspace, the location of every open project (a project
	 * can live outside the workspace folder) and the opencode scratch folder under ~/.servoy/opencode.
	 */
	private List<Path> allowedSourceRoots(Path workspaceDir) {
		List<Path> roots = new ArrayList<>();
		if (workspaceDir != null)
			roots.add(workspaceDir);
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isOpen() && project.getLocation() != null)
				roots.add(project.getLocation().toFile().toPath());
		}
		roots.add(Paths.get(System.getProperty("user.home"), ".servoy", "opencode"));
		return roots;
	}

	/**
	 * Returns the tokens bytes from exactly one of the two sources. A sourcePath is copied byte for byte so the whole
	 * token set is installed; a relative path resolves against baseDir (the workspace root), and the real path must lie
	 * inside one of allowedRoots, so neither '..' nor a symbolic link reaches a file outside them. Throws
	 * IllegalArgumentException with the user-facing message on invalid input.
	 */
	private byte[] resolveTokensBytes(String tokensContent, String sourcePath, Path baseDir, List<Path> allowedRoots) {
		boolean hasContent = tokensContent != null && !tokensContent.isBlank();
		boolean hasPath = sourcePath != null && !sourcePath.isBlank();
		if (hasContent && hasPath) {
			throw new IllegalArgumentException("pass either sourcePath or tokensContent, not both.");
		}
		if (!hasContent && !hasPath) {
			throw new IllegalArgumentException("sourcePath or tokensContent is required.");
		}
		if (hasContent) {
			return tokensContent.getBytes(StandardCharsets.UTF_8);
		}

		Path path;
		try {
			path = Paths.get(sourcePath.trim());
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("invalid sourcePath '" + sourcePath + "'.");
		}
		if (!path.isAbsolute()) {
			if (baseDir == null) {
				throw new IllegalArgumentException("relative sourcePath '" + sourcePath
						+ "' cannot be resolved; pass an absolute path.");
			}
			path = baseDir.resolve(path);
		}
		path = path.normalize();
		if (path.getFileName() == null || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css")) {
			throw new IllegalArgumentException("sourcePath must point to a .css file: " + path);
		}
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException("sourcePath file not found: " + path);
		}
		try {
			Path real = path.toRealPath();
			if (!isInsideAny(real, allowedRoots)) {
				throw new IllegalArgumentException("sourcePath must be inside the workspace or ~/.servoy/opencode: " + real
						+ ". Copy the file into the workspace, or pass its content as tokensContent.");
			}
			if (!real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css")) {
				throw new IllegalArgumentException("sourcePath must point to a .css file: " + real);
			}
			path = real;
			if (Files.size(path) > MAX_TOKENS_FILE_SIZE) {
				throw new IllegalArgumentException("sourcePath file is larger than 1 MB: " + path);
			}
			byte[] bytes = Files.readAllBytes(path);
			if (new String(bytes, StandardCharsets.UTF_8).isBlank()) {
				throw new IllegalArgumentException("sourcePath file is empty: " + path);
			}
			return bytes;
		} catch (IOException e) {
			throw new IllegalArgumentException("cannot read sourcePath '" + path + "': " + e.getMessage());
		}
	}

	/**
	 * Persists a changed media through its owning project. Protected so tests can simulate a failed save.
	 */
	protected void saveMedia(ServoyProject owner, Media media) throws RepositoryException {
		owner.saveEditingSolutionNodes(new IPersist[] { media }, true);
		// saving the nodes registers the media but does not rewrite the bytes of an existing media file
		Pair<String, String> filePath = SolutionSerializer.getFilePath(media, false);
		IFile file = ResourcesPlugin.getWorkspace().getRoot()
				.getFile(new org.eclipse.core.runtime.Path(filePath.getLeft() + filePath.getRight()));
		byte[] data = media.getMediaData();
		try {
			ResourcesUtils.createOrWriteFile(file, new ByteArrayInputStream(data == null ? new byte[0] : data), true);
		} catch (CoreException e) {
			throw new RepositoryException("cannot write media file '" + media.getName() + "': " + e.getMessage(), e);
		}
	}

	private boolean isInsideAny(Path real, List<Path> roots) {
		for (Path root : roots) {
			Path realRoot;
			try {
				realRoot = root.toRealPath();
			} catch (IOException e) {
				continue;
			}
			if (real.startsWith(realRoot))
				return true;
		}
		return false;
	}

	private boolean isStylesheet(Media media, Solution editingSolution) {
		String styleSheetID = editingSolution.getStyleSheetID();
		return styleSheetID != null && media.getUUID().toString().equalsIgnoreCase(styleSheetID.trim());
	}

	/**
	 * After a failed save of a changed media whose in-memory state was already restored, saves that restored state
	 * once more so disk matches the model again; a failure of this second save is attached to the original error.
	 */
	private void restoreSaved(ServoyProject owner, Media media, Exception original) {
		try {
			saveMedia(owner, media);
		} catch (RepositoryException | RuntimeException e) {
			original.addSuppressed(e);
		}
	}

	/**
	 * Writes a new media file under the owner's medias/ folder before anything is added to the model. Protected so
	 * tests can simulate a failed write.
	 */
	protected void writeNewMediaFile(ServoyProject owner, String mediaName, byte[] content) throws RepositoryException {
		IFile file = owner.getProject().getFile(SolutionSerializer.MEDIAS_DIR + "/" + mediaName);
		try {
			ResourcesUtils.createOrWriteFile(file, new ByteArrayInputStream(content), true);
		} catch (CoreException e) {
			throw new RepositoryException("cannot write media file '" + mediaName + "': " + e.getMessage(), e);
		}
	}

	private ServoyProject owningProject(IDeveloperServoyModel model, IPersist persist, ServoyProject fallback) {
		IRootObject root = persist.getRootObject();
		ServoyProject owner = root == null ? null : model.getServoyProject(root.getName());
		return owner != null ? owner : fallback;
	}

	private Media findStylesheetMedia(FlattenedSolution fs, Solution editingSolution) {
		String styleSheetID = editingSolution.getStyleSheetID();
		if (styleSheetID == null || styleSheetID.isBlank()) {
			return null;
		}
		return fs.getMedia(styleSheetID);
	}

	private String ensureImport(Media stylesheet, String mediaFileName, ServoyProject owner) throws RepositoryException {
		byte[] original = stylesheet.getMediaData();
		String text = original == null ? "" : new String(original, StandardCharsets.UTF_8);

		ImportEdit edit = applyImport(text, stylesheet.getName(), mediaFileName);
		if (IMPORT_ALREADY_PRESENT.equals(edit.outcome())) {
			return edit.outcome();
		}

		stylesheet.setPermMediaData(edit.text().getBytes(StandardCharsets.UTF_8));
		try {
			saveMedia(owner, stylesheet);
		} catch (RepositoryException | RuntimeException e) {
			stylesheet.setPermMediaData(original);
			restoreSaved(owner, stylesheet, e);
			throw e;
		}
		return edit.outcome();
	}

	/**
	 * Computes the stylesheet text with exactly one inlined {@code @import} of the media. Commented-out imports
	 * are ignored; an existing import of the same media matches by the path it resolves to, relative to the
	 * stylesheet's folder (the way Servoy's LESS compiler resolves it). A plain import of a .css file is not
	 * compiled in by LESS, so it is upgraded to the inline form instead of counting as present.
	 */
	private ImportEdit applyImport(String stylesheetText, String stylesheetName, String mediaFileName) {
		String text = stylesheetText == null ? "" : stylesheetText;
		String folder = folderOf(stylesheetName);
		String importLine = "@import (inline) \"" + relativeImportRef(folder, mediaFileName) + "\";";
		if (text.isEmpty()) {
			return new ImportEdit(importLine + "\n", IMPORT_ADDED);
		}

		String delimiter = text.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			lines[i] = stripTrailingCr(lines[i]);
		}

		boolean inBlockComment = false;
		boolean inHeader = true;
		int insertIndex = 0;
		boolean compiledInFound = false;
		List<Integer> plainImportIndexes = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			boolean commented = inBlockComment || trimmed.startsWith("//") || trimmed.startsWith("/*");
			inBlockComment = isInBlockCommentAfter(trimmed, inBlockComment);
			if (commented)
				continue;

			if (trimmed.startsWith("@import")) {
				String ref = importRef(trimmed);
				if (ref != null && mediaFileName.equals(resolveImportRef(folder, ref))) {
					if (isCompiledIn(trimmed, ref))
						compiledInFound = true;
					else
						plainImportIndexes.add(Integer.valueOf(i));
				}
				if (inHeader)
					insertIndex = i + 1;
			} else if (trimmed.startsWith("@charset")) {
				if (inHeader)
					insertIndex = i + 1;
			} else if (!trimmed.isEmpty()) {
				inHeader = false;
			}
		}

		List<String> result = new ArrayList<>(Arrays.asList(lines));
		if (plainImportIndexes.isEmpty()) {
			if (compiledInFound)
				return new ImportEdit(text, IMPORT_ALREADY_PRESENT);
		} else {
			for (int k = plainImportIndexes.size() - 1; k >= 0; k--) {
				int index = plainImportIndexes.get(k).intValue();
				if (k == 0 && !compiledInFound)
					result.set(index, importLine);
				else
					result.remove(index);
			}
			return new ImportEdit(String.join(delimiter, result), IMPORT_UPGRADED);
		}
		result.add(insertIndex, importLine);
		return new ImportEdit(String.join(delimiter, result), IMPORT_ADDED);
	}

	private boolean isInBlockCommentAfter(String line, boolean inBlockComment) {
		boolean state = inBlockComment;
		int index = 0;
		while (index < line.length()) {
			if (state) {
				int end = line.indexOf("*/", index);
				if (end < 0)
					return true;
				state = false;
				index = end + 2;
			} else {
				int start = line.indexOf("/*", index);
				int lineComment = line.indexOf("//", index);
				if (start < 0 || (lineComment >= 0 && lineComment < start))
					return false;
				state = true;
				index = start + 2;
			}
		}
		return state;
	}

	private String importRef(String importLine) {
		for (int i = 0; i < importLine.length(); i++) {
			char c = importLine.charAt(i);
			if (c == '"' || c == '\'') {
				int end = importLine.indexOf(c, i + 1);
				return end < 0 ? null : importLine.substring(i + 1, end).trim();
			}
		}
		return null;
	}

	private boolean isCompiledIn(String importLine, String ref) {
		String rest = importLine.substring("@import".length()).trim();
		if (rest.startsWith("(")) {
			int close = rest.indexOf(')');
			String options = (close < 0 ? rest.substring(1) : rest.substring(1, close)).toLowerCase(Locale.ROOT);
			if (options.contains("reference"))
				return false;
			if (options.contains("inline") || options.contains("less"))
				return true;
			if (options.contains("css"))
				return false;
		}
		String path = stripQuery(ref).toLowerCase(Locale.ROOT);
		return !path.endsWith(".css") && !path.contains("://");
	}

	private String resolveImportRef(String folder, String ref) {
		String path = stripQuery(ref);
		try {
			return new URI(folder + path).normalize().toString();
		} catch (URISyntaxException e) {
			return folder + path;
		}
	}

	private String stripQuery(String ref) {
		int questionMark = ref.lastIndexOf('?');
		return questionMark > 0 ? ref.substring(0, questionMark) : ref;
	}

	private String folderOf(String mediaName) {
		if (mediaName == null)
			return "";
		int index = mediaName.lastIndexOf('/') + 1;
		return index > 0 ? mediaName.substring(0, index) : "";
	}

	private String relativeImportRef(String folder, String mediaFileName) {
		if (folder.isEmpty())
			return mediaFileName;
		String[] from = folder.substring(0, folder.length() - 1).split("/");
		String[] to = mediaFileName.split("/");
		int common = 0;
		while (common < from.length && common < to.length - 1 && from[common].equals(to[common])) {
			common++;
		}
		StringBuilder ref = new StringBuilder();
		for (int i = common; i < from.length; i++) {
			ref.append("../");
		}
		for (int i = common; i < to.length; i++) {
			if (i > common)
				ref.append('/');
			ref.append(to[i]);
		}
		return ref.toString();
	}

	private String stripTrailingCr(String line) {
		return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
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
