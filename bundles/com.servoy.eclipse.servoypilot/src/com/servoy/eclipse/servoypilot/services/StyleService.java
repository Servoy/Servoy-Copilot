package com.servoy.eclipse.servoypilot.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Service for managing CSS/LESS styles in Servoy solutions.
 * Migrated from knowledgebase.mcp StyleService.
 * 
 * Handles CRUD operations for CSS classes in LESS files.
 */
public class StyleService
{
	/**
	 * Adds or updates a CSS class in a LESS file.
	 */
	public static String addOrUpdateStyle(String projectPath, String solutionName, String lessFileName,
		String className, String cssContent)
	{
		try
		{
			// Determine target LESS file
			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
				? lessFileName
				: solutionName + ".less";

			if (!targetFile.endsWith(".less"))
			{
				targetFile += ".less";
			}

			Path lessPath = Paths.get(projectPath, "medias", targetFile);

			// Create file if doesn't exist
			if (!Files.exists(lessPath))
			{
				Files.createDirectories(lessPath.getParent());
				Files.write(lessPath, "/* Styles */\n\n".getBytes());
			}

			// Read existing content
			String content = new String(Files.readAllBytes(lessPath));

			// Check if content already has the class wrapper
			String trimmed = cssContent.trim();
			boolean hasClassWrapper = trimmed.matches("^\\." + Pattern.quote(className) + "\\s*\\{.*");

			String newClassDef;
			if (hasClassWrapper)
			{
				// Model already provided the complete class definition - use as-is
				newClassDef = trimmed;
				if (!newClassDef.endsWith("\n"))
				{
					newClassDef += "\n";
				}
			}
			else
			{
				// Model provided only inner content - add class wrapper
				String formattedCss = formatLessContent(cssContent);
				newClassDef = "." + className + " {\n" + formattedCss + "\n}\n";
			}

			// Create backup
			Path backupPath = Paths.get(lessPath.toString() + ".backup");
			Files.copy(lessPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			// Check if class already exists
			Pattern pattern = Pattern.compile("\\." + Pattern.quote(className) + "\\s*\\{[^}]*\\}", Pattern.DOTALL);
			Matcher matcher = pattern.matcher(content);

			if (matcher.find())
			{
				// Replace existing
				content = matcher.replaceFirst(Matcher.quoteReplacement(newClassDef));
			}
			else
			{
				// Append new
				content += "\n" + newClassDef;
			}

			// Write updated content
			Files.write(lessPath, content.getBytes());

			// If this is a separate file, ensure it's imported
			if (lessFileName != null && !lessFileName.trim().isEmpty() &&
				!targetFile.equals(solutionName + ".less"))
			{
				String importError = ensureImportInMainLess(projectPath, solutionName, targetFile);
				if (importError != null)
				{
					return importError;
				}
			}

			ServoyLog.logInfo("[StyleService] Successfully added/updated style: " + className);
			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[StyleService] Error adding/updating style: " + e.getMessage(), e);
			return "Error adding/updating style: " + e.getMessage();
		}
	}

	/**
	 * Gets the CSS content of a class from a LESS file.
	 */
	public static String getStyle(String projectPath, String solutionName, String lessFileName, String className)
	{
		try
		{
			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
				? lessFileName
				: solutionName + ".less";

			if (!targetFile.endsWith(".less"))
			{
				targetFile += ".less";
			}

			Path lessPath = Paths.get(projectPath, "medias", targetFile);

			if (!Files.exists(lessPath))
			{
				return "LESS file not found: " + targetFile;
			}

			String content = new String(Files.readAllBytes(lessPath));

			// Find class definition
			Pattern pattern = Pattern.compile("\\." + Pattern.quote(className) + "\\s*\\{([^}]*)\\}", Pattern.DOTALL);
			Matcher matcher = pattern.matcher(content);

			if (matcher.find())
			{
				String cssContent = matcher.group(1).trim();
				return cssContent;
			}

			return "Class '" + className + "' not found in " + targetFile;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[StyleService] Error getting style: " + e.getMessage(), e);
			return "Error getting style: " + e.getMessage();
		}
	}

	/**
	 * Lists all CSS class names in a LESS file.
	 */
	public static String listStyles(String projectPath, String solutionName, String lessFileName)
	{
		try
		{
			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
				? lessFileName
				: solutionName + ".less";

			if (!targetFile.endsWith(".less"))
			{
				targetFile += ".less";
			}

			Path lessPath = Paths.get(projectPath, "medias", targetFile);

			if (!Files.exists(lessPath))
			{
				return "LESS file not found: " + targetFile;
			}

			String content = new String(Files.readAllBytes(lessPath));

			// Find all class definitions
			Pattern pattern = Pattern.compile("\\.([a-zA-Z0-9_-]+)\\s*\\{", Pattern.MULTILINE);
			Matcher matcher = pattern.matcher(content);

			List<String> classes = new ArrayList<>();
			while (matcher.find())
			{
				classes.add(matcher.group(1));
			}

			if (classes.isEmpty())
			{
				return "No CSS classes found in " + targetFile;
			}

			return String.join(", ", classes);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[StyleService] Error listing styles: " + e.getMessage(), e);
			return "Error listing styles: " + e.getMessage();
		}
	}

	/**
	 * Deletes a CSS class from a LESS file.
	 */
	public static String deleteStyle(String projectPath, String solutionName, String lessFileName, String className)
	{
		try
		{
			String targetFile = (lessFileName != null && !lessFileName.trim().isEmpty())
				? lessFileName
				: solutionName + ".less";

			if (!targetFile.endsWith(".less"))
			{
				targetFile += ".less";
			}

			Path lessPath = Paths.get(projectPath, "medias", targetFile);

			if (!Files.exists(lessPath))
			{
				return "LESS file not found: " + targetFile;
			}

			String content = new String(Files.readAllBytes(lessPath));

			// Create backup
			Path backupPath = Paths.get(lessPath.toString() + ".backup");
			Files.copy(lessPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			// Find and remove class definition
			Pattern pattern = Pattern.compile("\\." + Pattern.quote(className) + "\\s*\\{[^}]*\\}\\s*", Pattern.DOTALL);
			Matcher matcher = pattern.matcher(content);

			if (!matcher.find())
			{
				return "Class '" + className + "' not found in " + targetFile;
			}

			content = matcher.replaceFirst("");

			// Write updated content
			Files.write(lessPath, content.getBytes());

			ServoyLog.logInfo("[StyleService] Successfully deleted style: " + className);
			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[StyleService] Error deleting style: " + e.getMessage(), e);
			return "Error deleting style: " + e.getMessage();
		}
	}

	/**
	 * Ensures a LESS file is imported in the main solution LESS file.
	 */
	private static String ensureImportInMainLess(String projectPath, String solutionName, String lessFileName)
	{
		try
		{
			Path mainLessPath = Paths.get(projectPath, "medias", solutionName + ".less");

			// Create main file if doesn't exist
			if (!Files.exists(mainLessPath))
			{
				Files.createDirectories(mainLessPath.getParent());
				Files.write(mainLessPath, ("/* " + solutionName + " styles */\n\n").getBytes());
			}

			String content = new String(Files.readAllBytes(mainLessPath));
			String importStatement = "@import '" + lessFileName + "';";

			// Check if already imported
			if (content.contains(importStatement) || content.contains("@import \"" + lessFileName + "\";"))
			{
				return null; // Already imported
			}

			// Create backup
			Path backupPath = Paths.get(mainLessPath.toString() + ".backup");
			Files.copy(mainLessPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			// Add import at appropriate location
			String[] lines = content.split("\n");
			StringBuilder newContent = new StringBuilder();
			boolean importAdded = false;

			for (int i = 0; i < lines.length; i++)
			{
				String line = lines[i].trim();

				if (!importAdded && !line.startsWith("//") && !line.startsWith("/*") &&
					!line.isEmpty() && !line.startsWith("@import"))
				{
					newContent.append(importStatement).append("\n");
					importAdded = true;
				}

				newContent.append(lines[i]).append("\n");
			}

			// If we reached end without adding, add at end
			if (!importAdded)
			{
				newContent.append(importStatement).append("\n");
			}

			Files.write(mainLessPath, newContent.toString().getBytes());

			ServoyLog.logInfo("[StyleService] Added import for " + lessFileName + " to " + solutionName + ".less");
			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[StyleService] Error ensuring import: " + e.getMessage(), e);
			return "Error ensuring import: " + e.getMessage();
		}
	}

	/**
	 * Formats LESS content by ensuring proper indentation.
	 */
	private static String formatLessContent(String cssContent)
	{
		String[] lines = cssContent.split("\n");
		StringBuilder formatted = new StringBuilder();

		for (String line : lines)
		{
			String trimmedLine = line.trim();
			if (!trimmedLine.isEmpty())
			{
				formatted.append("  ").append(trimmedLine).append("\n");
			}
		}

		return formatted.toString().trim();
	}
}
