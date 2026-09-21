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
package com.servoy.eclipse.developer.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.servoy.eclipse.developer.mcp.ResourceNotFoundException;

/**
 * Provides generic file-editing operations for MCP tools.
 * <p>
 * Ported from AssistAI's {@code CodeEditingService}. Differences:
 * <ul>
 *   <li>No JDT dependencies - no Java refactoring, no code formatter, no organize imports.</li>
 *   <li>No {@code AiIgnoreService} - access control is at the MCP Bearer token layer.</li>
 *   <li>No {@code UISynchronize} / editor refresh - the MCP server runs headless in Servoy Developer.</li>
 * </ul>
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class CodeEditingService
{
	// --- Public API ---

	public String createFile(String projectName, String filePath, String content)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");

		if (projectName.isEmpty()) throw new IllegalArgumentException("Project name cannot be empty.");
		if (filePath.isEmpty()) throw new IllegalArgumentException("File path cannot be empty.");
		if (content == null) content = "";

		try
		{
			IProject project = resolveProject(projectName);
			String normalizedPath = normalizePath(filePath);
			IFile file = project.getFile(normalizedPath);

			if (file.exists())
				throw new RuntimeException("Error: File '" + normalizedPath + "' already exists in project '" + projectName + "'.");

			IContainer parent = file.getParent();
			if (parent instanceof IFolder && !parent.exists())
				createFolderHierarchy((IFolder)parent);

			ByteArrayInputStream source = new ByteArrayInputStream(
				content.getBytes(Charset.forName(project.getDefaultCharset())));
			file.create(source, true, null);
			file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

			return "Success: File '" + normalizedPath + "' created in project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String insertIntoFile(String projectName, String filePath, String content, int atLine)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		if (content == null) content = "";

		try
		{
			IFile file = resolveFile(projectName, filePath);
			String originalContent = readFileContent(file);
			String eol = detectDominantLineEnding(originalContent);
			boolean trailingNewline = endsWithNewline(originalContent);
			List<String> lines = readFileLines(file);

			int effectiveAtLine = atLine - 1;
			if (effectiveAtLine < 0 || effectiveAtLine > lines.size())
				throw new RuntimeException("Error: Invalid line number " + atLine + ". File has " + lines.size() + " lines.");

			List<String> insertedLines = new ArrayList<>(java.util.Arrays.asList(content.split("\r?\n", -1)));
			if (!insertedLines.isEmpty() && insertedLines.get(insertedLines.size() - 1).isEmpty())
				insertedLines.remove(insertedLines.size() - 1);

			List<String> resultLines = new ArrayList<>();
			for (int i = 0; i < effectiveAtLine; i++)
				resultLines.add(lines.get(i));
			resultLines.addAll(insertedLines);
			for (int i = effectiveAtLine; i < lines.size(); i++)
				resultLines.add(lines.get(i));

			writeFile(file, joinLines(resultLines, eol, trailingNewline));
			return "Success: Content inserted into '" + filePath + "' at line " + atLine + " in project '" + projectName + "'.";
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String replaceStringInFile(String projectName, String filePath, String oldString, String newString,
		Integer startLine, Integer endLine)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		Objects.requireNonNull(oldString, "oldString is required");
		if (newString == null) newString = "";

		try
		{
			IFile file = resolveFile(projectName, filePath);
			String originalContent = readFileContent(file);
			String eol = detectDominantLineEnding(originalContent);
			boolean trailingNewline = endsWithNewline(originalContent);
			List<String> lines = readFileLines(file);
			int totalLines = lines.size();

			int effectiveStart = (startLine != null) ? Math.max(0, startLine - 1) : 0;
			int effectiveEnd = (endLine != null) ? Math.min(totalLines - 1, endLine - 1) : totalLines - 1;

			if (effectiveStart >= totalLines)
				throw new RuntimeException("Error: Start line " + startLine + " is beyond the end of the file.");
			effectiveEnd = Math.min(effectiveEnd, totalLines - 1);
			if (effectiveStart > effectiveEnd)
				throw new RuntimeException("Error: Start line cannot be greater than end line.");

			StringBuilder rangeContent = new StringBuilder();
			for (int i = effectiveStart; i <= effectiveEnd; i++)
			{
				rangeContent.append(lines.get(i));
				if (i < effectiveEnd) rangeContent.append("\n");
			}

			String rangeText = rangeContent.toString();
			if (!rangeText.contains(oldString))
			{
				String rangeInfo = (startLine != null || endLine != null)
					? " within range (lines " + (startLine != null ? startLine : 1) + " to " + (endLine != null ? endLine : totalLines) + ")"
					: "";
				throw new RuntimeException("Error: The specified string was not found in the file" + rangeInfo + ".");
			}

			String replacedRange = rangeText.replace(oldString, newString);

			List<String> resultLines = new ArrayList<>();
			for (int i = 0; i < effectiveStart; i++)
				resultLines.add(lines.get(i));
			for (String rangeLine : replacedRange.split("\r?\n", -1))
				resultLines.add(rangeLine);
			for (int i = effectiveEnd + 1; i < totalLines; i++)
				resultLines.add(lines.get(i));

			String modifiedContent = joinLines(resultLines, eol, trailingNewline);
			String diff = generateSimpleDiff(originalContent, modifiedContent, filePath);
			writeFile(file, modifiedContent);

			return "Success: String replaced in file '" + filePath + "' in project '" + projectName + "'.\n"
				+ "Changes:\n```diff\n" + diff + "\n```";
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String undoEdit(String projectName, String filePath)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");

		try
		{
			IFile file = resolveFile(projectName, filePath);
			org.eclipse.core.resources.IFileState[] history = file.getHistory(null);
			if (history == null || history.length == 0)
				throw new RuntimeException("Error: No edit history found for file '" + filePath + "'.");

			org.eclipse.core.resources.IFileState previousState = history[0];
			String previousContent = new String(
				readInputStream(previousState.getContents()),
				Charset.forName(file.getCharset()));

			try (ByteArrayInputStream source = new ByteArrayInputStream(
				previousContent.getBytes(Charset.forName(file.getCharset()))))
			{
				file.setContents(source, IResource.FORCE, null);
			}
			file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

			return "Success: Undid last edit in file '" + filePath + "' in project '" + projectName + "'.";
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String createDirectories(String projectName, String directoryPath)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(directoryPath, "directoryPath is required");

		try
		{
			IProject project = resolveProject(projectName);
			String normalizedPath = normalizePath(directoryPath);
			if (normalizedPath.isEmpty())
				throw new RuntimeException("Error: Invalid directory path.");

			IFolder folder = project.getFolder(normalizedPath);
			if (folder.exists())
				return "Directory '" + normalizedPath + "' already exists in project '" + projectName + "'.";

			createFolderHierarchy(folder);
			folder.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);
			return "Success: Directory structure '" + normalizedPath + "' created in project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String renameFile(String projectName, String filePath, String newFileName)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		Objects.requireNonNull(newFileName, "newFileName is required");

		try
		{
			IFile file = resolveFile(projectName, filePath);
			IContainer parent = file.getParent();
			IPath newPath = parent.getFullPath().append(newFileName);

			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			IFile newFile = root.getFile(newPath);
			if (newFile.exists())
				throw new RuntimeException("Error: A file named '" + newFileName + "' already exists in the same directory.");

			file.move(newPath, IResource.FORCE, null);
			parent.refreshLocal(IResource.DEPTH_ONE, null);

			return "Success: File '" + filePath + "' renamed to '" + newFileName + "' in project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String moveResource(String projectName, String sourcePath, String targetPath)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(sourcePath, "sourcePath is required");
		Objects.requireNonNull(targetPath, "targetPath is required");

		try
		{
			IProject project = resolveProject(projectName);
			String normalizedSource = normalizePath(sourcePath);
			String normalizedTarget = normalizePath(targetPath);

			IResource sourceResource = project.findMember(normalizedSource);
			if (sourceResource == null || !sourceResource.exists())
				throw new ResourceNotFoundException("Error: Resource '" + sourcePath + "' does not exist in project '" + projectName + "'.");

			IFolder targetFolder = project.getFolder(normalizedTarget);
			if (!targetFolder.exists())
				createFolderHierarchy(targetFolder);

			String resourceName = sourceResource.getName();
			IPath destinationPath = targetFolder.getFullPath().append(resourceName);

			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			IResource existing = root.findMember(destinationPath);
			if (existing != null && existing.exists())
				throw new RuntimeException("Error: A resource named '" + resourceName + "' already exists at the destination.");

			sourceResource.move(destinationPath, IResource.FORCE, new NullProgressMonitor());
			sourceResource.getParent().refreshLocal(IResource.DEPTH_ONE, null);
			targetFolder.refreshLocal(IResource.DEPTH_ONE, null);

			return "Success: Resource '" + sourcePath + "' moved to '" + normalizedTarget + "/" + resourceName + "' in project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException("Error during move: " + e.getMessage(), e);
		}
	}

	public String deleteFile(String projectName, String filePath)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");

		try
		{
			IFile file = resolveFile(projectName, filePath);
			file.delete(true, null);
			file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
			return "Success: File '" + filePath + "' deleted from project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Reads and returns the full text content of the specified file.
	 *
	 * @param projectName the Eclipse project name
	 * @param filePath    path relative to the project root
	 * @return file content as a string, or {@code null} if the file cannot be read
	 */
	public String readFileContent(String projectName, String filePath)
	{
		try
		{
			IFile file = resolveFile(projectName, filePath);
			return readFileContent(file);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public String replaceFileContent(String projectName, String filePath, String content)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		Objects.requireNonNull(content, "content is required");


		try
		{
			IFile file = resolveFile(projectName, filePath);
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			ByteArrayInputStream source = new ByteArrayInputStream(bytes);
			file.setContents(source, IResource.FORCE, null);
			file.refreshLocal(IResource.DEPTH_ZERO, null);
			return "Success: Content of file '" + filePath + "' replaced in project '" + projectName + "'.";
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
		catch (RuntimeException e)
		{
			// Fallback: if project doesn't exist or file not in project, try workspace-root-level write
			java.nio.file.Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
			java.nio.file.Path fallbackFile = workspaceRoot.resolve(projectName).resolve(filePath);
			if (java.nio.file.Files.exists(fallbackFile.getParent()))
			{
				try
				{
					java.nio.file.Files.writeString(fallbackFile, content, StandardCharsets.UTF_8);
					return "Success: Content of file '" + filePath + "' replaced in workspace directory '" + projectName + "'.";
				}
				catch (java.io.IOException ioe)
				{
					throw new RuntimeException("Error writing to workspace file: " + ioe.getMessage(), ioe);
				}
			}
			throw e;
		}
	}

	public String deleteLinesInFile(String projectName, String filePath, int startLine, int endLine)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		if (startLine < 1) throw new IllegalArgumentException("Start line must be at least 1.");
		if (endLine < startLine) throw new IllegalArgumentException("End line must be >= start line.");


		try
		{
			IFile file = resolveFile(projectName, filePath);
			String fileContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
			String eol = detectDominantLineEnding(fileContent);
			boolean trailingNewline = endsWithNewline(fileContent);
			String[] lines = fileContent.split("\r?\n", -1);

			if (startLine > lines.length)
				throw new IllegalArgumentException("Start line " + startLine + " is beyond the file length (" + lines.length + " lines).");
			if (endLine > lines.length)
				throw new IllegalArgumentException("End line " + endLine + " is beyond the file length (" + lines.length + " lines).");

			List<String> keptLines = new ArrayList<>();
			for (int i = 0; i < lines.length; i++)
			{
				int lineNum = i + 1;
				if (lineNum < startLine || lineNum > endLine)
					keptLines.add(lines[i]);
			}

			byte[] bytes = joinLines(keptLines, eol, trailingNewline).getBytes(StandardCharsets.UTF_8);
			try (ByteArrayInputStream source = new ByteArrayInputStream(bytes))
			{
				file.setContents(source, IResource.FORCE, null);
			}
			file.refreshLocal(IResource.DEPTH_ZERO, null);

			int deletedCount = endLine - startLine + 1;
			return "Success: Deleted " + deletedCount + " line(s) (lines " + startLine + " to " + endLine
				+ ") from file '" + filePath + "' in project '" + projectName + "'.";
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public String applyPatch(String projectName, String filePath, String patch)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		Objects.requireNonNull(patch, "patch is required");
		if (patch.isBlank()) throw new IllegalArgumentException("Patch content cannot be empty.");


		try
		{
			IFile file = resolveFile(projectName, filePath);
			String originalContent = readFileContent(file);
			String eol = detectDominantLineEnding(originalContent);
			boolean trailingNewline = endsWithNewline(originalContent);
			List<String> originalLines = readFileLines(file);
			List<String> driftNotes = new ArrayList<>();
			List<String> patchedLines = applyUnifiedDiff(originalLines, patch, driftNotes);

			String patchedContentString = joinLines(patchedLines, eol, trailingNewline);
			String diff = generateSimpleDiff(originalContent, patchedContentString, filePath);

			try (ByteArrayInputStream source = new ByteArrayInputStream(
				patchedContentString.getBytes(Charset.forName(file.getCharset()))))
			{
				file.setContents(source, IResource.FORCE, null);
			}

			String driftSuffix = driftNotes.isEmpty() ? "" : "\n" + String.join("\n", driftNotes);
			return "Success: Patch applied to file '" + filePath + "' in project '" + projectName + "'." + driftSuffix + "\n"
				+ "Changes:\n```diff\n" + diff + "\n```";
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	// --- Private helpers ---

	private IProject resolveProject(String projectName) throws CoreException
	{
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IProject project = root.getProject(projectName);
		if (!project.exists())
			throw new ResourceNotFoundException("Error: Project '" + projectName + "' does not exist.");
		if (!project.isOpen())
			throw new ResourceNotFoundException("Error: Project '" + projectName + "' is closed.");
		return project;
	}

	private IFile resolveFile(String projectName, String filePath) throws CoreException
	{
		IProject project = resolveProject(projectName);
		IPath path = IPath.fromPath(Path.of(filePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new ResourceNotFoundException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
		return file;
	}

	private static String normalizePath(String path)
	{
		String normalized = path;
		while (normalized.startsWith("/") || normalized.startsWith("\\"))
			normalized = normalized.substring(1);
		return normalized;
	}

	private static void createFolderHierarchy(IFolder folder) throws CoreException
	{
		if (!folder.exists())
		{
			IContainer parent = folder.getParent();
			if (parent instanceof IFolder && !parent.exists())
				createFolderHierarchy((IFolder)parent);
			folder.create(true, true, null);
		}
	}

	private static List<String> readFileLines(IFile file) throws CoreException, IOException
	{
		List<String> lines = new ArrayList<>();
		try (InputStream is = file.getContents();
			java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(is, Charset.forName(file.getCharset()))))
		{
			String line;
			while ((line = reader.readLine()) != null)
				lines.add(line);
		}
		return lines;
	}

	private static String readFileContent(IFile file) throws CoreException, IOException
	{
		try (InputStream is = file.getContents())
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int len;
			while ((len = is.read(buf)) != -1)
				out.write(buf, 0, len);
			return out.toString(file.getCharset());
		}
	}

	private static byte[] readInputStream(InputStream is) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while ((len = is.read(buf)) != -1)
			out.write(buf, 0, len);
		return out.toByteArray();
	}

	private static void writeFile(IFile file, String content) throws CoreException
	{
		try (ByteArrayInputStream source = new ByteArrayInputStream(
			content.getBytes(Charset.forName(file.getCharset()))))
		{
			file.setContents(source, IResource.FORCE, null);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Generates a simple unified diff between two strings.
	 */
	private static String generateSimpleDiff(String original, String modified, String filePath)
	{
		try
		{
			Path origFile = Files.createTempFile("orig-", ".tmp");
			Path modFile = Files.createTempFile("mod-", ".tmp");
			try
			{
				Files.writeString(origFile, original);
				Files.writeString(modFile, modified);

				ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
				org.eclipse.jgit.diff.DiffFormatter formatter = new org.eclipse.jgit.diff.DiffFormatter(diffOutput);
				formatter.setContext(3);
				formatter.setDiffComparator(org.eclipse.jgit.diff.RawTextComparator.DEFAULT);

				org.eclipse.jgit.diff.RawText rawOrig = new org.eclipse.jgit.diff.RawText(origFile.toFile());
				org.eclipse.jgit.diff.RawText rawMod = new org.eclipse.jgit.diff.RawText(modFile.toFile());

				diffOutput.write(("--- /" + filePath + "\n").getBytes());
				diffOutput.write(("+++ /" + filePath + "\n").getBytes());

				org.eclipse.jgit.diff.EditList edits = new org.eclipse.jgit.diff.HistogramDiff()
					.diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT, rawOrig, rawMod);
				formatter.format(edits, rawOrig, rawMod);
				formatter.close();

				return diffOutput.toString();
			}
			finally
			{
				Files.deleteIfExists(origFile);
				Files.deleteIfExists(modFile);
			}
		}
		catch (Exception e)
		{
			return "(diff unavailable: " + e.getMessage() + ")";
		}
	}

	// --- Line-ending helpers ---

	/**
	 * Detects the dominant line ending of the given content, returning {@code "\r\n"},
	 * {@code "\r"}, or {@code "\n"}. On a CRLF-vs-LF tie, LF is preferred. A content with no
	 * terminator (empty or a single unterminated line) defaults to the platform-neutral {@code "\n"}.
	 */
	static String detectDominantLineEnding(String content)
	{
		if (content == null || content.isEmpty()) return "\n";

		int crlf = 0, cr = 0, lf = 0;
		for (int i = 0; i < content.length(); i++)
		{
			char c = content.charAt(i);
			if (c == '\r')
			{
				if (i + 1 < content.length() && content.charAt(i + 1) == '\n')
				{
					crlf++;
					i++;
				}
				else
				{
					cr++;
				}
			}
			else if (c == '\n')
			{
				lf++;
			}
		}

		if (crlf == 0 && cr == 0 && lf == 0) return "\n";
		// On any tie involving LF, prefer LF (resolved open question 3).
		if (lf >= crlf && lf >= cr) return "\n";
		if (crlf >= cr) return "\r\n";
		return "\r";
	}

	/**
	 * Returns whether the content ends with a line terminator, so a file that had no trailing
	 * newline is not given one (and one that did keeps it).
	 */
	static boolean endsWithNewline(String content)
	{
		return content != null && !content.isEmpty() && (content.endsWith("\n") || content.endsWith("\r"));
	}

	/**
	 * Joins the given lines with the supplied line ending, appending a trailing terminator only
	 * when {@code trailingNewline} is {@code true}.
	 */
	static String joinLines(List<String> lines, String eol, boolean trailingNewline)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++)
		{
			sb.append(lines.get(i));
			if (i < lines.size() - 1) sb.append(eol);
		}
		if (trailingNewline && !lines.isEmpty()) sb.append(eol);
		return sb.toString();
	}

	// --- Patch application (ported from AssistAI CodeEditingService, hardened for SVY-21472) ---

	/** Truncation cap for the evidence-rich failure message (resolved open question 1). */
	private static final int EVIDENCE_CAP = 10;

	/** Ordered whitespace-tolerance tiers, strictest first. */
	enum MatchTier
	{
		EXACT("exact"),
		TRAILING_WS("trailing whitespace"),
		STRIP_BOTH("leading/trailing whitespace");

		final String label;

		MatchTier(String label)
		{
			this.label = label;
		}
	}

	static class DiffHunk
	{
		int originalStart;
		int originalCount;
		boolean headerParsed;
		String headerLine;
		List<String> hunkLines = new ArrayList<>();
	}

	static List<DiffHunk> parseHunks(String patch)
	{
		List<DiffHunk> hunks = new ArrayList<>();
		// Split on \r?\n so a CRLF-delimited patch does not leave a trailing \r on every
		// line: a stray \r would fail the @@ header regex (flagging every hunk malformed)
		// and pollute the context/added lines that get spliced into the file.
		String[] lines = patch.split("\r?\n", -1);
		// split(..., -1) keeps a trailing empty token produced by the patch's own final
		// newline. That terminator artifact must NOT become a phantom blank context line:
		// a spurious trailing " " context line inflates the expected block, desyncs it from
		// the file, and defeats otherwise-matching hunks (and the tier-4 drop-outer-context
		// fuzz, which would then drop the phantom instead of the real outermost context).
		// Only the single trailing empty element is dropped, so genuine blank lines BETWEEN
		// content (a real " "/empty context line mid-hunk) are preserved.
		int lineCount = lines.length;
		if (lineCount > 0 && lines[lineCount - 1].isEmpty()) lineCount--;
		DiffHunk currentHunk = null;

		for (int li = 0; li < lineCount; li++)
		{
			String line = lines[li];
			if (line.startsWith("---") || line.startsWith("+++")) continue;

			if (line.startsWith("@@"))
			{
				currentHunk = new DiffHunk();
				currentHunk.headerLine = line;
				hunks.add(currentHunk);
				var matcher = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*").matcher(line);
				if (matcher.matches())
				{
					currentHunk.headerParsed = true;
					currentHunk.originalStart = Integer.parseInt(matcher.group(1));
					currentHunk.originalCount = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
				}
				else
				{
					// Malformed header (e.g. "@@ @@"): flag it so applyHunk can reject it
					// explicitly instead of letting the Java default 0 become a phantom "line 0".
					currentHunk.headerParsed = false;
					currentHunk.originalStart = -1;
				}
				continue;
			}

			if (currentHunk != null)
			{
				if (line.startsWith(" ") || line.startsWith("-") || line.startsWith("+"))
					currentHunk.hunkLines.add(line);
				else if (line.isEmpty())
					currentHunk.hunkLines.add(" ");
			}
		}
		return hunks;
	}

	static List<String> applyUnifiedDiff(List<String> originalLines, String patch)
	{
		return applyUnifiedDiff(originalLines, patch, null);
	}

	static List<String> applyUnifiedDiff(List<String> originalLines, String patch, List<String> driftNotesOut)
	{
		List<String> result = new ArrayList<>(originalLines);
		List<DiffHunk> hunks = parseHunks(patch);
		Collections.reverse(hunks);
		for (DiffHunk hunk : hunks)
			result = applyHunk(result, hunk, driftNotesOut);
		return result;
	}

	static List<String> applyHunk(List<String> lines, DiffHunk hunk)
	{
		return applyHunk(lines, hunk, null);
	}

	static List<String> applyHunk(List<String> lines, DiffHunk hunk, List<String> driftNotesOut)
	{
		if (!hunk.headerParsed)
			throw new RuntimeException("Error: Malformed hunk header: `" + hunk.headerLine
				+ "`. Expected the form `@@ -<start>[,<count>] +<start>[,<count>] @@`.");

		// The full (undropped) expected block: context + removed lines.
		List<String> expectedLines = new ArrayList<>();
		for (String hunkLine : hunk.hunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("-"))
				expectedLines.add(hunkLine.substring(1));

		int hint = hunk.originalStart - 1;

		// Pure insertion (no context/removed lines): keep the original clamp behaviour.
		if (expectedLines.isEmpty())
		{
			int insertPos = Math.max(0, Math.min(hint, lines.size()));
			List<String> added = new ArrayList<>();
			for (String hunkLine : hunk.hunkLines)
				if (hunkLine.startsWith("+")) added.add(hunkLine.substring(1));
			List<String> result = new ArrayList<>();
			result.addAll(lines.subList(0, insertPos));
			result.addAll(added);
			result.addAll(lines.subList(insertPos, lines.size()));
			return result;
		}

		Located located = locate(lines, hunk, expectedLines, hint);
		if (located == null)
			throw new RuntimeException(buildEvidenceFailure(lines, expectedLines, hint, hunk));

		return spliceHunk(lines, hunk, located, driftNotesOut);
	}

	/** Result of locating a hunk: the matched file position, the tier that matched, and how many
	 *  outer context lines were dropped to achieve the match (GNU-patch-style fuzz). */
	private static class Located
	{
		final int pos;
		final MatchTier tier;
		final int droppedLeading;
		final int droppedTrailing;

		Located(int pos, MatchTier tier, int droppedLeading, int droppedTrailing)
		{
			this.pos = pos;
			this.tier = tier;
			this.droppedLeading = droppedLeading;
			this.droppedTrailing = droppedTrailing;
		}
	}

	/**
	 * Locates a hunk's expected block, degrading gracefully: for each tier (strictest first) the
	 * hint window is tried, then the whole file. A stricter tier is exhausted everywhere before a
	 * looser one is attempted, so a strict match is always preferred. When a loose tier matches more
	 * than one location and the header did not disambiguate, an ambiguity error is thrown rather than
	 * guessing. Only after tiers 1-3 fail everywhere is the outermost context line dropped (tier 4)
	 * and tiers 1-3 retried. Returns {@code null} when nothing matched.
	 */
	private static Located locate(List<String> lines, DiffHunk hunk, List<String> expectedLines, int hint)
	{
		for (MatchTier tier : MatchTier.values())
		{
			Located found = tryLocateAtTier(lines, expectedLines, hint, tier, 0, 0);
			if (found != null) return found;
		}
		// Tier 4: GNU-patch-style fuzz - drop the OUTERMOST CONTEXT line(s) and retry tiers 1-3.
		//
		// Lockstep invariant (do not break): the expected block, the reduced hunk lines, the hint
		// adjustment, and droppedLeading/droppedTrailing must all be reduced from the SAME source.
		// expectedLines mixes context (' ') AND removed ('-') lines; reduceHunkLines only ever drops
		// context lines. A blind subList(1, size-1) here would drop whatever the outermost expected
		// line happens to be (possibly a '-' removal) while reduceHunkLines drops 0 of that end - the
		// two desync and the splice deletes the wrong file lines. So we ONLY drop an end when its
		// outermost EXPECTED line is a context line, and we derive the reduced expected block from the
		// reduced hunk lines produced by reduceHunkLines - the single source of truth the splice reuses.
		boolean dropLeading = firstExpectedIsContext(hunk.hunkLines);
		boolean dropTrailing = lastExpectedIsContext(hunk.hunkLines);
		if (dropLeading || dropTrailing)
		{
			int droppedLeading = dropLeading ? 1 : 0;
			int droppedTrailing = dropTrailing ? 1 : 0;
			List<String> reducedHunkLines = reduceHunkLines(hunk.hunkLines, droppedLeading, droppedTrailing);
			List<String> reducedExpected = expectedBlock(reducedHunkLines);
			if (!reducedExpected.isEmpty() && reducedExpected.size() < expectedLines.size())
			{
				for (MatchTier tier : MatchTier.values())
				{
					Located found = tryLocateAtTier(lines, reducedExpected, hint + droppedLeading, tier,
						droppedLeading, droppedTrailing);
					if (found != null) return found;
				}
			}
		}
		return null;
	}

	/** The context+removed block (' ' and '-' prefixed lines, prefix stripped) of the given hunk lines. */
	private static List<String> expectedBlock(List<String> hunkLines)
	{
		List<String> block = new ArrayList<>();
		for (String hunkLine : hunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("-"))
				block.add(hunkLine.substring(1));
		return block;
	}

	/** Whether the FIRST expected (context-or-removed) line of the hunk is a context (' ') line -
	 *  i.e. the leading outer context is droppable. Dropping a removed/added line is never valid fuzz. */
	private static boolean firstExpectedIsContext(List<String> hunkLines)
	{
		for (String hunkLine : hunkLines)
		{
			if (hunkLine.startsWith(" ")) return true;
			if (hunkLine.startsWith("-")) return false;
		}
		return false;
	}

	/** Whether the LAST expected (context-or-removed) line of the hunk is a context (' ') line -
	 *  i.e. the trailing outer context is droppable. Dropping a removed/added line is never valid fuzz. */
	private static boolean lastExpectedIsContext(List<String> hunkLines)
	{
		for (int i = hunkLines.size() - 1; i >= 0; i--)
		{
			String hunkLine = hunkLines.get(i);
			if (hunkLine.startsWith(" ")) return true;
			if (hunkLine.startsWith("-")) return false;
		}
		return false;
	}

	/**
	 * Tries to locate {@code expectedLines} at the given tier: exact hint, then the ±1..50 window,
	 * then a full-file scan. A unique full-file match is used; multiple matches throw the ambiguity
	 * error; zero matches return {@code null}. {@code droppedLeading}/{@code droppedTrailing} record
	 * outer-context fuzz so the splice can compensate.
	 */
	private static Located tryLocateAtTier(List<String> lines, List<String> expectedLines, int hint,
		MatchTier tier, int droppedLeading, int droppedTrailing)
	{
		if (expectedLines.isEmpty()) return null;

		if (matchesAtTier(lines, expectedLines, hint, tier))
			return new Located(hint, tier, droppedLeading, droppedTrailing);
		int maxSearch = 50;
		for (int offset = 1; offset <= maxSearch; offset++)
		{
			if (matchesAtTier(lines, expectedLines, hint + offset, tier))
				return new Located(hint + offset, tier, droppedLeading, droppedTrailing);
			if (matchesAtTier(lines, expectedLines, hint - offset, tier))
				return new Located(hint - offset, tier, droppedLeading, droppedTrailing);
		}

		// Full-file scan: collect every position that matches at this tier.
		List<Integer> matches = new ArrayList<>();
		int last = lines.size() - expectedLines.size();
		for (int pos = 0; pos <= last; pos++)
			if (matchesAtTier(lines, expectedLines, pos, tier)) matches.add(pos);

		if (matches.size() == 1) return new Located(matches.get(0), tier, droppedLeading, droppedTrailing);
		if (matches.size() > 1)
		{
			StringBuilder candidates = new StringBuilder();
			for (int i = 0; i < matches.size(); i++)
			{
				if (i > 0) candidates.append(", ");
				candidates.append(matches.get(i) + 1);
			}
			throw new RuntimeException("Error: The hunk context matches " + matches.size()
				+ " locations (lines " + candidates + "); the @@ header did not disambiguate. "
				+ "Re-emit the patch with a correct @@ line number or a longer unique context.");
		}
		return null;
	}

	/**
	 * Splices a located hunk into the file. For exact matches the hunk's own context/added lines are
	 * used verbatim. For fuzzy matches (tier 2/3) the file's original context/removed lines are
	 * reused and added lines are re-indented to the surrounding file indentation, so the patch's
	 * whitespace is never pasted over the file's real indentation.
	 */
	private static List<String> spliceHunk(List<String> lines, DiffHunk hunk, Located located, List<String> driftNotesOut)
	{
		// The reduced hunk lines (dropping the outermost context line(s) when tier-4 fuzz was used).
		List<String> effectiveHunkLines = reduceHunkLines(hunk.hunkLines, located.droppedLeading, located.droppedTrailing);

		int expectedSpan = 0;
		for (String hunkLine : effectiveHunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("-")) expectedSpan++;

		List<String> replacement = new ArrayList<>();
		if (located.tier == MatchTier.EXACT)
		{
			for (String hunkLine : effectiveHunkLines)
				if (hunkLine.startsWith(" ") || hunkLine.startsWith("+"))
					replacement.add(hunkLine.substring(1));
		}
		else
		{
			// Content-match write-back: retained context/removed lines come from the FILE; added
			// lines are re-indented to match the surrounding file indentation.
			int filePtr = located.pos;
			String lastExpectedIndent = null;
			String lastFileIndent = null;
			for (String hunkLine : effectiveHunkLines)
			{
				char kind = hunkLine.charAt(0);
				String text = hunkLine.substring(1);
				if (kind == ' ')
				{
					String fileLine = lines.get(filePtr);
					replacement.add(fileLine);
					lastExpectedIndent = leadingWhitespace(text);
					lastFileIndent = leadingWhitespace(fileLine);
					filePtr++;
				}
				else if (kind == '-')
				{
					String fileLine = lines.get(filePtr);
					lastExpectedIndent = leadingWhitespace(text);
					lastFileIndent = leadingWhitespace(fileLine);
					filePtr++;
				}
				else if (kind == '+')
				{
					replacement.add(reindentAddedLine(text, lastExpectedIndent, lastFileIndent));
				}
			}
		}

		List<String> result = new ArrayList<>();
		result.addAll(lines.subList(0, located.pos));
		result.addAll(replacement);
		result.addAll(lines.subList(located.pos + expectedSpan, lines.size()));

		// A drop-outer-context match records the base tier of the REDUCED block, which can be EXACT,
		// so also treat any dropped outer context as drift so a tier-4 success still reports fuzz.
		boolean droppedContext = located.droppedLeading > 0 || located.droppedTrailing > 0;
		if (driftNotesOut != null && (located.tier != MatchTier.EXACT || droppedContext))
		{
			int actualExpectedCount = 0;
			for (String hunkLine : hunk.hunkLines)
				if (hunkLine.startsWith(" ") || hunkLine.startsWith("-")) actualExpectedCount++;
			StringBuilder note = new StringBuilder();
			note.append("Applied with fuzzy matching (tier: ").append(located.tier.label).append(") \u2014 ")
				.append("the file had drifted from the patch context; re-read the affected region (around line ")
				.append(located.pos + 1).append(") to verify.");
			if (hunk.originalCount != actualExpectedCount)
				note.append(" (Header count ").append(hunk.originalCount)
					.append(" disagreed with the actual context+removed line count ").append(actualExpectedCount)
					.append(".)");
			driftNotesOut.add(note.toString());
		}

		return result;
	}

	/**
	 * Drops the outermost <em>expected context</em> line(s) from the hunk line list: for the leading
	 * side it removes the first context (' '-prefixed) line only when that is the first
	 * context-or-removed line (skipping any leading '+' added lines, which are kept); for the trailing
	 * side likewise. A removed ('-') outermost line is never dropped. This mirrors the
	 * {@link #firstExpectedIsContext}/{@link #lastExpectedIsContext} guards in {@link #locate} so the
	 * reduced hunk lines and the reduced expected block stay in lockstep with the splice offsets.
	 */
	private static List<String> reduceHunkLines(List<String> hunkLines, int droppedLeading, int droppedTrailing)
	{
		if (droppedLeading == 0 && droppedTrailing == 0) return hunkLines;
		List<String> result = new ArrayList<>(hunkLines);
		for (int dropped = 0; dropped < droppedLeading; dropped++)
		{
			int idx = firstExpectedContextIndex(result);
			if (idx < 0) break;
			result.remove(idx);
		}
		for (int dropped = 0; dropped < droppedTrailing; dropped++)
		{
			int idx = lastExpectedContextIndex(result);
			if (idx < 0) break;
			result.remove(idx);
		}
		return result;
	}

	/** Index of the first context (' ') line, but only if it precedes any removed ('-') line; else -1. */
	private static int firstExpectedContextIndex(List<String> hunkLines)
	{
		for (int i = 0; i < hunkLines.size(); i++)
		{
			String hunkLine = hunkLines.get(i);
			if (hunkLine.startsWith(" ")) return i;
			if (hunkLine.startsWith("-")) return -1;
		}
		return -1;
	}

	/** Index of the last context (' ') line, but only if it follows any removed ('-') line; else -1. */
	private static int lastExpectedContextIndex(List<String> hunkLines)
	{
		for (int i = hunkLines.size() - 1; i >= 0; i--)
		{
			String hunkLine = hunkLines.get(i);
			if (hunkLine.startsWith(" ")) return i;
			if (hunkLine.startsWith("-")) return -1;
		}
		return -1;
	}

	private static String leadingWhitespace(String line)
	{
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
		return line.substring(0, i);
	}

	/**
	 * Re-indents an added line so its indentation matches the surrounding file context rather than
	 * the patch's. When the added line's own indent starts with the indentation the hunk expected at
	 * the splice point, that prefix is swapped for the file's actual indentation. When there is no
	 * adjacent context to relate to, the added line is left unchanged.
	 */
	private static String reindentAddedLine(String added, String expectedIndent, String fileIndent)
	{
		if (expectedIndent == null || fileIndent == null) return added;
		if (added.startsWith(expectedIndent))
			return fileIndent + added.substring(expectedIndent.length());
		return added;
	}

	/** Builds the evidence-rich failure message: searched range, expected block, and the file's
	 *  actual lines at the hint, each bounded, plus an originalCount discrepancy note when relevant. */
	private static String buildEvidenceFailure(List<String> lines, List<String> expectedLines, int hint, DiffHunk hunk)
	{
		int windowLow = Math.max(0, hint - 50);
		int windowHigh = Math.min(lines.size(), hint + 50);

		StringBuilder sb = new StringBuilder();
		sb.append("Error: Could not find matching context for hunk near line ").append(hint + 1).append(". ");
		sb.append("Searched the hint \u00b1 50 lines (lines ").append(windowLow + 1).append(" to ").append(windowHigh)
			.append(") and then the whole file, including whitespace-tolerant matching; none matched.\n");

		sb.append("Expected block (context + removed lines");
		if (expectedLines.size() > EVIDENCE_CAP) sb.append(", first ").append(EVIDENCE_CAP).append(" of ").append(expectedLines.size());
		sb.append("):\n");
		for (int i = 0; i < Math.min(EVIDENCE_CAP, expectedLines.size()); i++)
			sb.append("  ").append(expectedLines.get(i)).append("\n");

		int actualStart = Math.max(0, Math.min(hint, Math.max(0, lines.size() - 1)));
		int actualEnd = Math.min(lines.size(), actualStart + EVIDENCE_CAP);
		sb.append("Actual lines at the hint (from line ").append(actualStart + 1).append("):\n");
		if (actualStart >= lines.size())
		{
			sb.append("  (hint is beyond the end of the file, which has ").append(lines.size()).append(" lines)\n");
		}
		else
		{
			for (int i = actualStart; i < actualEnd; i++)
				sb.append("  ").append(lines.get(i)).append("\n");
		}

		int actualExpectedCount = 0;
		for (String hunkLine : hunk.hunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("-")) actualExpectedCount++;
		if (hunk.headerParsed && hunk.originalCount != actualExpectedCount)
			sb.append("Note: the @@ header count (").append(hunk.originalCount)
				.append(") disagrees with the actual context+removed line count (").append(actualExpectedCount)
				.append("), so the hunk may be truncated.\n");

		sb.append("The file may have been modified since the diff was generated.");
		return sb.toString();
	}

	static int findMatchPosition(List<String> lines, List<String> expectedLines, int hintPosition)
	{
		if (expectedLines.isEmpty()) return Math.min(hintPosition, lines.size());
		if (matchesAt(lines, expectedLines, hintPosition)) return hintPosition;
		int maxSearch = 50;
		for (int offset = 1; offset <= maxSearch; offset++)
		{
			if (matchesAt(lines, expectedLines, hintPosition + offset)) return hintPosition + offset;
			if (matchesAt(lines, expectedLines, hintPosition - offset)) return hintPosition - offset;
		}
		return -1;
	}

	static boolean matchesAt(List<String> lines, List<String> expectedLines, int position)
	{
		return matchesAtTier(lines, expectedLines, position, MatchTier.EXACT);
	}

	/**
	 * Whitespace-tolerant line-block comparison. {@code originalCount} is intentionally NOT validated
	 * here: measured over real patches it disagrees with the actual block size in 83% of cases, so
	 * gating on it would reject far more good patches than it protects against. It is used only as a
	 * soft hint in failure diagnostics (see {@link #buildEvidenceFailure}).
	 */
	private static boolean matchesAtTier(List<String> lines, List<String> expectedLines, int position, MatchTier tier)
	{
		if (position < 0 || position + expectedLines.size() > lines.size()) return false;
		for (int i = 0; i < expectedLines.size(); i++)
		{
			String actual = lines.get(position + i);
			String expected = expectedLines.get(i);
			boolean eq;
			switch (tier)
			{
				case TRAILING_WS:
					eq = actual.stripTrailing().equals(expected.stripTrailing());
					break;
				case STRIP_BOTH:
					eq = actual.strip().equals(expected.strip());
					break;
				case EXACT:
				default:
					eq = actual.equals(expected);
					break;
			}
			if (!eq) return false;
		}
		return true;
	}
}
