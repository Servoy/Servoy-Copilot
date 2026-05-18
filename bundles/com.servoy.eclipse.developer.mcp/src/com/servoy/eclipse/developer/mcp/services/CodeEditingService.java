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

import com.servoy.eclipse.developer.mcp.guard.ServoyFileGuard;

/**
 * Provides generic file-editing operations for MCP tools.
 * <p>
 * Ported from AssistAI's {@code CodeEditingService}. Differences:
 * <ul>
 *   <li>No JDT dependencies â no Java refactoring, no code formatter, no organize imports.</li>
 *   <li>No {@code AiIgnoreService} â access control is at the MCP Bearer token layer.</li>
 *   <li>No {@code UISynchronize} / editor refresh â the MCP server runs headless in Servoy Developer.</li>
 *   <li>Destructive methods call {@link ServoyFileGuard#assertEditable(String)} before writing.</li>
 * </ul>
 * </p>
 */
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

		ServoyFileGuard.assertEditable(filePath);

		try
		{
			IFile file = resolveFile(projectName, filePath);
			List<String> lines = readFileLines(file);

			int effectiveAtLine = atLine - 1;
			if (effectiveAtLine < 0 || effectiveAtLine > lines.size())
				throw new RuntimeException("Error: Invalid line number " + atLine + ". File has " + lines.size() + " lines.");

			StringBuilder modified = new StringBuilder();
			for (int i = 0; i < effectiveAtLine; i++)
				modified.append(lines.get(i)).append("\n");
			modified.append(content);
			if (!content.endsWith("\n")) modified.append("\n");
			for (int i = effectiveAtLine; i < lines.size(); i++)
			{
				modified.append(lines.get(i));
				if (i < lines.size() - 1) modified.append("\n");
			}

			writeFile(file, modified.toString());
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

		ServoyFileGuard.assertEditable(filePath);

		try
		{
			IFile file = resolveFile(projectName, filePath);
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

			StringBuilder modified = new StringBuilder();
			for (int i = 0; i < effectiveStart; i++)
				modified.append(lines.get(i)).append("\n");
			modified.append(replacedRange);
			if (effectiveEnd < totalLines - 1) modified.append("\n");
			for (int i = effectiveEnd + 1; i < totalLines; i++)
				modified.append(lines.get(i)).append("\n");
			if (!modified.toString().endsWith("\n")) modified.append("\n");

			String diff = generateSimpleDiff(readFileContent(file), modified.toString(), filePath);
			writeFile(file, modified.toString());

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
		// undoEdit is exempt from ServoyFileGuard â it is recovery, not authoring

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
				throw new RuntimeException("Error: Resource '" + sourcePath + "' does not exist in project '" + projectName + "'.");

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

	public String replaceFileContent(String projectName, String filePath, String content)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		Objects.requireNonNull(content, "content is required");

		ServoyFileGuard.assertEditable(filePath);

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
	}

	public String deleteLinesInFile(String projectName, String filePath, int startLine, int endLine)
	{
		Objects.requireNonNull(projectName, "projectName is required");
		Objects.requireNonNull(filePath, "filePath is required");
		if (startLine < 1) throw new IllegalArgumentException("Start line must be at least 1.");
		if (endLine < startLine) throw new IllegalArgumentException("End line must be >= start line.");

		ServoyFileGuard.assertEditable(filePath);

		try
		{
			IFile file = resolveFile(projectName, filePath);
			String fileContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
			String[] lines = fileContent.split("\r?\n", -1);

			if (startLine > lines.length)
				throw new IllegalArgumentException("Start line " + startLine + " is beyond the file length (" + lines.length + " lines).");
			if (endLine > lines.length)
				throw new IllegalArgumentException("End line " + endLine + " is beyond the file length (" + lines.length + " lines).");

			StringBuilder newContent = new StringBuilder();
			for (int i = 0; i < lines.length; i++)
			{
				int lineNum = i + 1;
				if (lineNum < startLine || lineNum > endLine)
				{
					newContent.append(lines[i]);
					if (i < lines.length - 1) newContent.append("\n");
				}
			}

			byte[] bytes = newContent.toString().getBytes(StandardCharsets.UTF_8);
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

		ServoyFileGuard.assertEditable(filePath);

		try
		{
			IFile file = resolveFile(projectName, filePath);
			List<String> originalLines = readFileLines(file);
			List<String> patchedLines = applyUnifiedDiff(originalLines, patch);

			StringBuilder patchedContent = new StringBuilder();
			for (int i = 0; i < patchedLines.size(); i++)
			{
				patchedContent.append(patchedLines.get(i));
				if (i < patchedLines.size() - 1) patchedContent.append("\n");
			}
			if (!patchedContent.toString().endsWith("\n")) patchedContent.append("\n");

			String patchedContentString = patchedContent.toString();
			String diff = generateSimpleDiff(readFileContent(file), patchedContentString, filePath);

			try (ByteArrayInputStream source = new ByteArrayInputStream(
				patchedContentString.getBytes(Charset.forName(file.getCharset()))))
			{
				file.setContents(source, IResource.FORCE, null);
			}

			return "Success: Patch applied to file '" + filePath + "' in project '" + projectName + "'.\n"
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
			throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
		return project;
	}

	private IFile resolveFile(String projectName, String filePath) throws CoreException
	{
		IProject project = resolveProject(projectName);
		IPath path = IPath.fromPath(Path.of(filePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
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

	// --- Patch application (ported from AssistAI CodeEditingService) ---

	private static class DiffHunk
	{
		int originalStart;
		int originalCount;
		List<String> hunkLines = new ArrayList<>();
	}

	private static List<DiffHunk> parseHunks(String patch)
	{
		List<DiffHunk> hunks = new ArrayList<>();
		String[] lines = patch.split("\n");
		DiffHunk currentHunk = null;

		for (String line : lines)
		{
			if (line.startsWith("---") || line.startsWith("+++")) continue;

			if (line.startsWith("@@"))
			{
				currentHunk = new DiffHunk();
				hunks.add(currentHunk);
				var matcher = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*").matcher(line);
				if (matcher.matches())
				{
					currentHunk.originalStart = Integer.parseInt(matcher.group(1));
					currentHunk.originalCount = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
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

	private static List<String> applyUnifiedDiff(List<String> originalLines, String patch)
	{
		List<String> result = new ArrayList<>(originalLines);
		List<DiffHunk> hunks = parseHunks(patch);
		Collections.reverse(hunks);
		for (DiffHunk hunk : hunks)
			result = applyHunk(result, hunk);
		return result;
	}

	private static List<String> applyHunk(List<String> lines, DiffHunk hunk)
	{
		List<String> expectedLines = new ArrayList<>();
		for (String hunkLine : hunk.hunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("-"))
				expectedLines.add(hunkLine.substring(1));

		int matchPos = findMatchPosition(lines, expectedLines, hunk.originalStart - 1);
		if (matchPos < 0)
			throw new RuntimeException("Error: Could not find matching context for hunk at line " + hunk.originalStart
				+ ". The file may have been modified since the diff was generated.");

		List<String> replacementLines = new ArrayList<>();
		for (String hunkLine : hunk.hunkLines)
			if (hunkLine.startsWith(" ") || hunkLine.startsWith("+"))
				replacementLines.add(hunkLine.substring(1));

		List<String> result = new ArrayList<>();
		for (int i = 0; i < matchPos; i++)
			result.add(lines.get(i));
		result.addAll(replacementLines);
		for (int i = matchPos + expectedLines.size(); i < lines.size(); i++)
			result.add(lines.get(i));
		return result;
	}

	private static int findMatchPosition(List<String> lines, List<String> expectedLines, int hintPosition)
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

	private static boolean matchesAt(List<String> lines, List<String> expectedLines, int position)
	{
		if (position < 0 || position + expectedLines.size() > lines.size()) return false;
		for (int i = 0; i < expectedLines.size(); i++)
			if (!lines.get(position + i).equals(expectedLines.get(i))) return false;
		return true;
	}
}
