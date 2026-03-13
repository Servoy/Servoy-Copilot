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
package com.servoy.eclipse.servoypilot.tools.utility;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Web fetching tools for AI assistant.
 * Provides access to external documentation via HTTP/HTTPS.
 * Restricted to docs.servoy.com for security.
 */
public class WebFetchTools
{
	private static final ILog logger = ILog.of(WebFetchTools.class);
	private static final int TIMEOUT_MS = 10000; // 10 seconds
	private static final int MAX_CONTENT_LENGTH = 500000; // 500KB limit
	private static final String ALLOWED_DOMAIN = "https://docs.servoy.com/";

	/**
	 * Fetches content from a webpage URL.
	 * Restricted to docs.servoy.com for security purposes.
	 * 
	 * @param url The URL to fetch (must be https://docs.servoy.com/...)
	 * @return The webpage content as plain text, or error message
	 */
	@Tool("Fetches content from a webpage URL. Use ONLY when context hints and getKnowledge are insufficient. " +
		"Restricted to https://docs.servoy.com/ URLs for Servoy API documentation verification.")
	public String fetch_webpage(
		@P(value = "URL to fetch (must be https://docs.servoy.com/...)", required = true) String urlString)
	{
		try
		{
			// Validate URL format
			if (urlString == null || urlString.trim().isEmpty())
			{
				return createErrorResponse("URL parameter is required");
			}

			urlString = urlString.trim();

			// Security check: Only allow docs.servoy.com
			if (!urlString.startsWith(ALLOWED_DOMAIN))
			{
				return createErrorResponse("Security restriction: Only " + ALLOWED_DOMAIN + " URLs are allowed. Provided: " + urlString);
			}

			logger.info("Fetching URL: " + urlString);

			URL url = new URL(urlString);
			HttpURLConnection connection = (HttpURLConnection)url.openConnection();

			try
			{
				// Configure connection
				connection.setRequestMethod("GET");
				connection.setConnectTimeout(TIMEOUT_MS);
				connection.setReadTimeout(TIMEOUT_MS);
				connection.setRequestProperty("User-Agent", "ServoyPilot/1.0 (Eclipse Plugin)");
				connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain");

				// Check response code
				int responseCode = connection.getResponseCode();
				if (responseCode != HttpURLConnection.HTTP_OK)
				{
					return createErrorResponse("HTTP " + responseCode + ": " + connection.getResponseMessage());
				}

				// Check content length
				long contentLength = connection.getContentLengthLong();
				if (contentLength > MAX_CONTENT_LENGTH)
				{
					return createErrorResponse("Content too large: " + contentLength + " bytes (max " + MAX_CONTENT_LENGTH + ")");
				}

				// Read content
				StringBuilder content = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)))
				{
					String line;
					int totalChars = 0;
					while ((line = reader.readLine()) != null)
					{
						totalChars += line.length();
						if (totalChars > MAX_CONTENT_LENGTH)
						{
							return createErrorResponse("Content exceeded maximum size during reading");
						}
						content.append(line).append("\n");
					}
				}

				String rawContent = content.toString();

				// Basic HTML stripping (simple approach - extracts text between tags)
				String cleanedContent = stripHtmlTags(rawContent);

				logger.info("Successfully fetched " + cleanedContent.length() + " characters from " + urlString);

				return buildJsonResponse(true, null,
					"url", urlString,
					"status", String.valueOf(responseCode),
					"contentLength", String.valueOf(cleanedContent.length()),
					"content", cleanedContent);
			}
			finally
			{
				connection.disconnect();
			}
		}
		catch (java.net.MalformedURLException e)
		{
			logger.error("Invalid URL: " + urlString, e);
			return createErrorResponse("Invalid URL format: " + e.getMessage());
		}
		catch (java.net.SocketTimeoutException e)
		{
			logger.error("Timeout fetching URL: " + urlString, e);
			return createErrorResponse("Request timeout after " + TIMEOUT_MS + "ms");
		}
		catch (java.io.IOException e)
		{
			logger.error("IO error fetching URL: " + urlString, e);
			return createErrorResponse("Network error: " + e.getMessage());
		}
		catch (Exception e)
		{
			logger.error("Error fetching URL: " + urlString, e);
			return createErrorResponse("Unexpected error: " + e.getMessage());
		}
	}

	/**
	 * Basic HTML tag stripping.
	 * Removes HTML tags, scripts, styles, and excessive whitespace.
	 * 
	 * @param html Raw HTML content
	 * @return Plain text content
	 */
	private String stripHtmlTags(String html)
	{
		if (html == null || html.isEmpty())
		{
			return "";
		}

		// Remove script and style tags with their content
		String text = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
		text = text.replaceAll("(?i)<style[^>]*>.*?</style>", "");

		// Remove HTML comments
		text = text.replaceAll("<!--.*?-->", "");

		// Remove HTML tags
		text = text.replaceAll("<[^>]+>", " ");

		// Decode common HTML entities
		text = text.replace("&nbsp;", " ");
		text = text.replace("&lt;", "<");
		text = text.replace("&gt;", ">");
		text = text.replace("&amp;", "&");
		text = text.replace("&quot;", "\"");
		text = text.replace("&#39;", "'");

		// Normalize whitespace
		text = text.replaceAll("\\s+", " ");
		text = text.replaceAll("(?m)^\\s+|\\s+$", ""); // Trim each line

		return text.trim();
	}

	/**
	 * Build a JSON response manually (avoids Jackson dependency issues).
	 */
	private String buildJsonResponse(boolean success, String error, String... keyValues)
	{
		StringBuilder json = new StringBuilder();
		json.append("{\n");
		json.append("  \"success\": ").append(success).append(",\n");

		if (error != null)
		{
			json.append("  \"error\": \"").append(escapeJson(error)).append("\",\n");
		}

		for (int i = 0; i < keyValues.length; i += 2)
		{
			String key = keyValues[i];
			String value = keyValues[i + 1];
			json.append("  \"").append(key).append("\": \"").append(escapeJson(value)).append("\"");
			if (i < keyValues.length - 2)
			{
				json.append(",");
			}
			json.append("\n");
		}

		json.append("}");
		return json.toString();
	}

	/**
	 * Create error response in JSON format.
	 */
	private String createErrorResponse(String error)
	{
		return buildJsonResponse(false, error);
	}

	/**
	 * Escape special characters for JSON string values.
	 */
	private String escapeJson(String str)
	{
		if (str == null)
		{
			return "";
		}
		return str.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}
}
