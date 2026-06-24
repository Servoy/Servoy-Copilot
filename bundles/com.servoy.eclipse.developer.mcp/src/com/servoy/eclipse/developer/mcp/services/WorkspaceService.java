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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

import com.servoy.eclipse.developer.mcp.cache.ServoyResourceCache;

/**
 * Provides workspace file reading and search operations for MCP tools.
 * <p>
 * Ported from AssistAI's {@code ResourceService}. Differences:
 * <ul>
 *   <li>No JDT dependency.</li>
 *   <li>No {@code AiIgnoreService}.</li>
 *   <li>{@code readProjectResource} populates {@link ServoyResourceCache} as a side-effect.</li>
 * </ul>
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class WorkspaceService
{
	// --- findFiles ---

	public List<String> findFiles(String[] fileNamePatterns, int maxResults)
	{
		int limit = maxResults <= 0 ? 200 : maxResults;
		Pattern fileNamePattern = globPatternsToRegex(fileNamePatterns);

		IResource[] roots = getOpenProjectsAsRoots();
		if (roots.length == 0) return List.of();

		TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
		TextSearchEngine engine = TextSearchEngine.createDefault();

		List<String> matches = new ArrayList<>();

		TextSearchRequestor requestor = new TextSearchRequestor()
		{
			@Override
			public boolean acceptFile(IFile file) throws CoreException
			{
				return matches.size() < limit && file != null && file.isAccessible();
			}

			@Override
			public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
			{
				IFile file = matchAccess.getFile();
				if (file != null)
				{
					String path = file.getFullPath().toString();
					if (!matches.contains(path)) matches.add(path);
				}
				return matches.size() < limit;
			}
		};

		try
		{
			engine.search(scope, requestor, Pattern.compile("."), null);
			return matches;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error finding files: " + e.getMessage(), e);
		}
	}

	// --- readProjectResource ---

	/** Maximum lines returned when no endLine is specified. Prevents accidental full-file dumps. */
	public static final int MAX_LINES_DEFAULT = 500;

	public String readProjectResource(String projectName, String resourcePath,
		boolean showLineNumbers, int startLine, int endLine)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.");

		try
		{
			List<String> lines = readFileLines(file);
			int totalLines = lines.size();
			int effectiveStart = (startLine > 0) ? Math.min(startLine, totalLines) : 1;
			int effectiveEnd = (endLine > 0) ? Math.min(endLine, totalLines) : Math.min(effectiveStart + MAX_LINES_DEFAULT - 1, totalLines);

			StringBuilder response = new StringBuilder();
			response.append("# Content of ").append(resourcePath).append(" in project ").append(projectName);
			response.append(" (lines ").append(effectiveStart).append("-").append(effectiveEnd)
				.append(" of ").append(totalLines).append(")");
			if (effectiveEnd < totalLines && endLine <= 0)
				response.append(" [truncated at ").append(MAX_LINES_DEFAULT).append(" lines - use startLine/endLine for more]");
			response.append("\n\n```\n");

			int width = String.valueOf(totalLines).length();
			for (int i = effectiveStart - 1; i < effectiveEnd; i++)
			{
				if (showLineNumbers)
					response.append(String.format("%" + width + "d\t%s\n", i + 1, lines.get(i)));
				else
					response.append(lines.get(i)).append("\n");
			}
			response.append("\n```\n");

			String content = response.toString();

			// Populate the resource cache
			String uri = "workspace:///" + projectName + "/" + resourcePath;
			ServoyResourceCache.getInstance().put(uri, file.getName(), "WORKSPACE_FILE", content);

			return content;
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	// --- fileSearch ---

	// --- getFileInfo ---

	public record FileInfo(String fullPath, String projectName, String fileName, long sizeBytes, int lineCount, boolean exists) {}

	public FileInfo getFileInfo(String projectName, String resourcePath)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists() || !project.isOpen())
			return new FileInfo("/" + projectName + "/" + resourcePath, projectName,
				Path.of(resourcePath).getFileName().toString(), -1, -1, false);

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			return new FileInfo(file.getFullPath().toString(), projectName,
				file.getName(), -1, -1, false);

		try
		{
			long sizeBytes = file.getLocation().toFile().length();
			List<String> lines = readFileLines(file);
			return new FileInfo(file.getFullPath().toString(), projectName,
				file.getName(), sizeBytes, lines.size(), true);
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	// --- readFileRanges ---

	public record RangeResult(int startLine, int endLine, String content) {}

	/**
	 * Reads multiple non-contiguous line ranges from a file in a single call.
	 * Ranges are specified as a comma-separated string, e.g. {@code "10-20,50-60,100-110"}.
	 */
	public List<RangeResult> readFileRanges(String projectName, String resourcePath, String ranges)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.");

		if (ranges == null || ranges.isBlank())
			throw new IllegalArgumentException("ranges must not be null/blank. Use format: '10-20,50-60'");

		try
		{
			List<String> lines = readFileLines(file);
			int totalLines = lines.size();
			List<RangeResult> results = new ArrayList<>();

			for (String rangePart : ranges.split(","))
			{
				rangePart = rangePart.trim();
				String[] bounds = rangePart.split("-");
				if (bounds.length != 2)
					throw new IllegalArgumentException("Invalid range format: '" + rangePart + "'. Expected 'start-end'.");

				int start = Math.max(1, Integer.parseInt(bounds[0].trim()));
				int end = Math.min(totalLines, Integer.parseInt(bounds[1].trim()));
				if (start > end)
					throw new IllegalArgumentException("Invalid range: start " + start + " > end " + end);

				StringBuilder content = new StringBuilder();
				for (int i = start - 1; i < end; i++)
					content.append(String.format("%d\t%s\n", i + 1, lines.get(i)));

				results.add(new RangeResult(start, end, content.toString()));
			}
			return results;
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	public record SearchResult(String filePath, int lineNumber, String lineContent) {}

	public List<SearchResult> fileSearch(String containingText, String... fileNamePatterns)
	{
		if (containingText == null || containingText.isBlank())
			throw new IllegalArgumentException("containingText must not be null/blank");
		return search(Pattern.compile(Pattern.quote(containingText)), fileNamePatterns);
	}

	public List<SearchResult> fileSearchRegExp(String pattern, String... fileNamePatterns)
	{
		if (pattern == null || pattern.isBlank())
			throw new IllegalArgumentException("pattern must not be null/blank");
		return search(Pattern.compile(pattern), fileNamePatterns);
	}

	// --- searchAndReplace ---

	public record SearchAndReplaceResult(String filePath, int matchesFound, int replacementsMade) {}

	public List<SearchAndReplaceResult> searchAndReplace(String containingText, String replacementText,
		String... fileNamePatterns)
	{
		if (containingText == null || containingText.isBlank())
			throw new IllegalArgumentException("containingText must not be null/blank");
		if (replacementText == null)
			throw new IllegalArgumentException("replacementText must not be null");

		Pattern matchPattern = Pattern.compile(Pattern.quote(containingText));
		IResource[] roots = getOpenProjectsAsRoots();
		if (roots.length == 0) return List.of();

		Pattern fileNamePattern = globPatternsToRegex(fileNamePatterns);
		TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
		TextSearchEngine engine = TextSearchEngine.createDefault();

		List<IFile> matchedFiles = new ArrayList<>();

		TextSearchRequestor requestor = new TextSearchRequestor()
		{
			@Override
			public boolean acceptFile(IFile file) throws CoreException
			{
				return file != null && file.isAccessible();
			}

			@Override
			public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
			{
				IFile file = matchAccess.getFile();
				if (file != null && !matchedFiles.contains(file))
					matchedFiles.add(file);
				return true;
			}
		};

		try
		{
			engine.search(scope, requestor, matchPattern, null);

			if (matchedFiles.isEmpty()) return List.of();

			List<SearchAndReplaceResult> results = new ArrayList<>();
			for (IFile file : matchedFiles)
			{
				int replacements = replaceInFile(file, containingText, replacementText);
				results.add(new SearchAndReplaceResult(file.getFullPath().toString(),
					replacements, replacements));
			}
			return results;
		}
		catch (RuntimeException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error searchAndReplace: " + e.getMessage(), e);
		}
	}

	// --- Private helpers ---

	// --- readFileContext ---

	/**
	 * Reads lines around a center line (smart windowing).
	 *
	 * @param projectName  Eclipse project name
	 * @param resourcePath path relative to project root
	 * @param centerLine   1-based line to center the window on
	 * @param windowSize   number of lines before and after centerLine (default 30)
	 */
	public record FileContextResult(String fullPath, int totalLines, int centerLine, int windowSize, int startLine, int endLine, String content) {}

	public FileContextResult readFileContext(String projectName, String resourcePath, int centerLine, int windowSize)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.");

		if (windowSize <= 0) windowSize = 30;

		try
		{
			List<String> lines = readFileLines(file);
			int totalLines = lines.size();

			if (centerLine < 1 || centerLine > totalLines)
				throw new RuntimeException("Error: Center line " + centerLine + " is out of bounds (file has " + totalLines + " lines).");

			int startLine = Math.max(1, centerLine - windowSize);
			int endLine = Math.min(totalLines, centerLine + windowSize);

			StringBuilder content = new StringBuilder();
			for (int i = startLine - 1; i < endLine; i++)
				content.append(String.format("%d\t%s\n", i + 1, lines.get(i)));

			return new FileContextResult(file.getFullPath().toString(), totalLines,
				centerLine, windowSize, startLine, endLine, content.toString());
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	// --- getFileOutline ---

	public record OutlineEntry(int lineNumber, String functionName) {}

	/**
	 * Extracts function/method names with their starting line numbers using regex.
	 * Handles: {@code function foo}, {@code var foo = function}, {@code async function foo}.
	 */
	public List<OutlineEntry> getFileOutline(String projectName, String resourcePath)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.");

		try
		{
			List<String> lines = readFileLines(file);
			List<OutlineEntry> entries = new ArrayList<>();
			Pattern functionPattern = Pattern.compile(
				"^\\s*(?:(?:async\\s+)?function\\s+(\\w+)|(?:var|let|const)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?function|(\\w+)\\s*:\\s*(?:async\\s+)?function)");

			for (int i = 0; i < lines.size(); i++)
			{
				java.util.regex.Matcher m = functionPattern.matcher(lines.get(i));
				if (m.find())
				{
					String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
					if (name != null) entries.add(new OutlineEntry(i + 1, name));
				}
			}
			return entries;
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	// --- readFunction ---

	public record FunctionResult(String fullPath, String functionName, int startLine, int endLine, String content) {}

	/**
	 * Reads a complete function body by name using brace matching.
	 */
	public FunctionResult readFunction(String projectName, String resourcePath, String functionName)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project == null || !project.exists())
			throw new RuntimeException("Error: Project '" + projectName + "' not found.");
		if (!project.isOpen())
			throw new RuntimeException("Error: Project '" + projectName + "' is closed.");

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFile file = project.getFile(path);
		if (!file.exists())
			throw new RuntimeException("Error: File '" + resourcePath + "' does not exist in project '" + projectName + "'.");

		if (functionName == null || functionName.isBlank())
			throw new IllegalArgumentException("functionName must not be null/blank");

		try
		{
			List<String> lines = readFileLines(file);
			Pattern functionPattern = Pattern.compile(
				"^\\s*(?:(?:async\\s+)?function\\s+" + Pattern.quote(functionName) +
				"|(?:var|let|const)\\s+" + Pattern.quote(functionName) + "\\s*=\\s*(?:async\\s+)?function" +
				"|" + Pattern.quote(functionName) + "\\s*:\\s*(?:async\\s+)?function)");

			int startLine = -1;
			for (int i = 0; i < lines.size(); i++)
			{
				if (functionPattern.matcher(lines.get(i)).find())
				{
					startLine = i;
					break;
				}
			}

			if (startLine == -1)
				throw new RuntimeException("Error: Function '" + functionName + "' not found in file '" + resourcePath + "'.");

			// Brace matching to find end of function
			int braceCount = 0;
			int endLine = startLine;
			boolean inFunction = false;
			for (int i = startLine; i < lines.size(); i++)
			{
				for (char c : lines.get(i).toCharArray())
				{
					if (c == '{') { braceCount++; inFunction = true; }
					else if (c == '}') { braceCount--; }
				}
				if (inFunction && braceCount == 0) { endLine = i; break; }
			}

			StringBuilder content = new StringBuilder();
			for (int i = startLine; i <= endLine; i++)
				content.append(String.format("%d\t%s\n", i + 1, lines.get(i)));

			return new FunctionResult(file.getFullPath().toString(), functionName,
				startLine + 1, endLine + 1, content.toString());
		}
		catch (CoreException | IOException e)
		{
			throw new RuntimeException(e);
		}
	}


	private List<SearchResult> search(Pattern pattern, String... fileNamePatterns)
	{
		IResource[] roots = getOpenProjectsAsRoots();
		if (roots.length == 0) return List.of();

		Pattern fileNamePattern = globPatternsToRegex(fileNamePatterns);
		TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
		TextSearchEngine engine = TextSearchEngine.createDefault();

		List<SearchResult> results = new ArrayList<>();

		TextSearchRequestor requestor = new TextSearchRequestor()
		{
			@Override
			public boolean acceptFile(IFile file) throws CoreException
			{
				return file != null && file.isAccessible();
			}

			@Override
			public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
			{
				IFile file = matchAccess.getFile();
				int offset = matchAccess.getMatchOffset();
				LineInfo info = getLineInfo(file, offset);
				results.add(new SearchResult(file.getFullPath().toString(), info.lineNumber(), info.lineContent()));
				return true;
			}
		};

		try
		{
			engine.search(scope, requestor, pattern, null);
			return results;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error searching files: " + e.getMessage(), e);
		}
	}

	private record LineInfo(int lineNumber, String lineContent) {}

	private static LineInfo getLineInfo(IFile file, int offset)
	{
		if (file == null) return new LineInfo(-1, "");
		try
		{
			List<String> lines = readFileLines(file);
			int charCount = 0;
			for (int i = 0; i < lines.size(); i++)
			{
				String line = lines.get(i);
				int nextCharCount = charCount + line.length() + 1;
				if (offset < nextCharCount) return new LineInfo(i + 1, line);
				charCount = nextCharCount;
			}
			return new LineInfo(-1, "");
		}
		catch (CoreException | IOException e)
		{
			return new LineInfo(-1, "");
		}
	}

	private static int replaceInFile(IFile file, String containingText, String replacementText)
		throws CoreException, IOException
	{
		if (file == null || !file.exists()) return 0;

		Charset charset = Charset.forName(file.getCharset());
		String original = readFileContent(file);
		int count = countOccurrences(original, containingText);
		if (count == 0) return 0;

		String modified = original.replace(containingText, replacementText);
		byte[] bytes = modified.getBytes(charset);
		try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes))
		{
			file.setContents(in, IResource.FORCE, null);
		}
		file.refreshLocal(IResource.DEPTH_ZERO, null);
		return count;
	}

	private static int countOccurrences(String text, String needle)
	{
		if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return 0;
		int count = 0;
		int idx = 0;
		while ((idx = text.indexOf(needle, idx)) >= 0)
		{
			count++;
			idx += needle.length();
		}
		return count;
	}

	static List<String> readFileLines(IFile file) throws CoreException, IOException
	{
		List<String> lines = new ArrayList<>();
		try (InputStream is = file.getContents();
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(is, Charset.forName(file.getCharset()))))
		{
			String line;
			while ((line = reader.readLine()) != null)
				lines.add(line);
		}
		return lines;
	}

	static String readFileContent(IFile file) throws CoreException, IOException
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

	private static IResource[] getOpenProjectsAsRoots()
	{
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		List<IResource> roots = new ArrayList<>();
		for (IProject project : projects)
			if (project != null && project.exists() && project.isOpen())
				roots.add(project);
		return roots.toArray(IResource[]::new);
	}

	static Pattern globPatternsToRegex(String... globs)
	{
		if (globs == null || globs.length == 0)
			return Pattern.compile(".*");

		StringBuilder regex = new StringBuilder("^(?:");
		for (int i = 0; i < globs.length; i++)
		{
			if (i > 0) regex.append("|");
			regex.append(globToRegex(globs[i]));
		}
		regex.append(")$");
		return Pattern.compile(regex.toString());
	}

	private static String globToRegex(String glob)
	{
		String g = (glob == null || glob.trim().isEmpty()) ? "*" : glob.trim();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < g.length(); i++)
		{
			char c = g.charAt(i);
			switch (c)
			{
				case '*': out.append(".*"); break;
				case '?': out.append('.'); break;
				case '.': case '^': case '$': case '+': case '{': case '}':
				case '[': case ']': case '(': case ')': case '|': case '\\':
					out.append('\\').append(c); break;
				default: out.append(c);
			}
		}
		return out.toString();
	}
}