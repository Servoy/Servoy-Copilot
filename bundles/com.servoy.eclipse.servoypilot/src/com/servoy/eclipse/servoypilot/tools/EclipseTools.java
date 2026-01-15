package com.servoy.eclipse.servoypilot.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class EclipseTools
{
	private ResourceService resourceService = new ResourceService();

	private SearchService searchService = new SearchService();

	@Tool("Searches for a plain substring in workspace files using Eclipse's text search engine.")
	public String fileSearch(
		@P(value = "Text that must be contained in a line (plain substring, not regex)", required = true) String containingText,
		@P(value = "Optional file name patterns. Accepts a string like  \"*.frm,*.rel,*.val\". If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
		return searchService.fileSearch(containingText, patterns).toString();
	}

	@Tool("Searches workspace files using a Java regular expression via Eclipse's text search engine.")
	public String fileSearchRegExp(
		@P(value = "Java regular expression", required = true) String pattern,
		@P(value = "Optional file name patterns. Accepts a string like  \"*.frm,*.rel,*.val\". If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
		return searchService.fileSearchRegExp(pattern, patterns).toString();
	}

	@Tool("Finds workspace files matching the given glob patterns.")
	public String findFiles(
		@P(value = "Glob patterns. Accepts a string like  \"*.frm,*.rel,*.val\". If omitted, defaults to '*'", required = false) String fileNamePatterns,
		@P(value = "Maximum number of results to return (default: 200)", required = false) String maxResults)
	{
		String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
		int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(0);
		return resourceService.findFiles(patterns, limit).toString();
	}

	@Tool("Search and replace across multiple files in the workspace using Eclipse's text search engine.")
	public String searchAndReplace(
		@P(value = "Plain text to find (not regex)", required = true) String containingText,
		@P(value = "Replacement text (can be empty)", required = true) String replacementText,
		@P(value = "Optional file name patterns. Accepts a string like  \"*.frm,*.rel,*.val\". If omitted, all files are searched.", required = false) String fileNamePatterns)
	{
		String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
		return searchService.searchAndReplace(containingText, replacementText, patterns).toString();
	}


	private static String[] normalizeFileNamePatterns(String fileNamePatterns)
	{
		if (fileNamePatterns == null || fileNamePatterns.isBlank())
		{
			return new String[0]; // or return null if you prefer
		}

		fileNamePatterns = fileNamePatterns.trim();

		// 1. If it looks like a JSON array, try to parse it
		if (fileNamePatterns.startsWith("[") && fileNamePatterns.endsWith("]"))
		{
			try
			{
				ObjectMapper mapper = new ObjectMapper();
				return mapper.readValue(fileNamePatterns, String[].class);
			}
			catch (Exception e)
			{
				// If parsing fails, fall back to treating it as a standard string
				// (This handles cases where the AI generated something weird like "[weird-text]")
			}
		}

		// 2. Fallback: Treat as a simple comma-separated string (e.g., "*.java, *.xml" or just "*.frm")
		// Remove quotes if the AI accidentally wrapped a single string in extra quotes
		if (fileNamePatterns.startsWith("\"") && fileNamePatterns.endsWith("\""))
		{
			fileNamePatterns = fileNamePatterns.substring(1, fileNamePatterns.length() - 1);
		}

		String[] split = fileNamePatterns.split(",");
		List<String> result = new ArrayList<>();
		for (String s : split)
		{
			String clean = s.trim();
			if (!clean.isEmpty())
			{
				result.add(clean);
			}
		}
		return result.toArray(new String[0]);
	}
}