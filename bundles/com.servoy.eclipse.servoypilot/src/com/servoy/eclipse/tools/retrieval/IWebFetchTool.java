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
package com.servoy.eclipse.tools.retrieval;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IWebFetchTool
{
	ILog LOGGER = ILog.of(IWebFetchTool.class);
	int TIMEOUT_MS = 10000;
	int MAX_CONTENT_LENGTH = 500000;
	String ALLOWED_DOMAIN = "https://docs.servoy.com/";

	@Tool("Fetches content from a webpage URL. Use ONLY when context hints and getKnowledge are insufficient. " +
		"Restricted to https://docs.servoy.com/ URLs for Servoy API documentation verification.")
	default String fetch_webpage(
		@P(value = "URL to fetch (must be https://docs.servoy.com/...)", required = true) String urlString)
	{
		try
		{
			if (urlString == null || urlString.trim().isEmpty())
			{
				return buildWebFetchError("URL parameter is required");
			}

			urlString = urlString.trim();

			if (!urlString.startsWith(ALLOWED_DOMAIN))
			{
				return buildWebFetchError("Security restriction: Only " + ALLOWED_DOMAIN + " URLs are allowed. Provided: " + urlString);
			}

			LOGGER.info("Fetching URL: " + urlString);

			URL url = new URL(urlString);
			HttpURLConnection connection = (HttpURLConnection)url.openConnection();

			try
			{
				connection.setRequestMethod("GET");
				connection.setConnectTimeout(TIMEOUT_MS);
				connection.setReadTimeout(TIMEOUT_MS);
				connection.setRequestProperty("User-Agent", "ServoyPilot/1.0 (Eclipse Plugin)");
				connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain");

				int responseCode = connection.getResponseCode();
				if (responseCode != HttpURLConnection.HTTP_OK)
				{
					return buildWebFetchError("HTTP " + responseCode + ": " + connection.getResponseMessage());
				}

				long contentLength = connection.getContentLengthLong();
				if (contentLength > MAX_CONTENT_LENGTH)
				{
					return buildWebFetchError("Content too large: " + contentLength + " bytes (max " + MAX_CONTENT_LENGTH + ")");
				}

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
							return buildWebFetchError("Content exceeded maximum size during reading");
						}
						content.append(line).append("\n");
					}
				}

				String cleanedContent = stripHtmlTags(content.toString());
				LOGGER.info("Successfully fetched " + cleanedContent.length() + " characters from " + urlString);

				return buildWebFetchResponse(true, null,
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
			return buildWebFetchError("Invalid URL format: " + e.getMessage());
		}
		catch (java.net.SocketTimeoutException e)
		{
			return buildWebFetchError("Request timeout after " + TIMEOUT_MS + "ms");
		}
		catch (java.io.IOException e)
		{
			return buildWebFetchError("Network error: " + e.getMessage());
		}
		catch (Exception e)
		{
			return buildWebFetchError("Unexpected error: " + e.getMessage());
		}
	}

	private static String stripHtmlTags(String html)
	{
		if (html == null || html.isEmpty())
		{
			return "";
		}

		String text = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
		text = text.replaceAll("(?i)<style[^>]*>.*?</style>", "");
		text = text.replaceAll("<!--.*?-->", "");
		text = text.replaceAll("<[^>]+>", " ");
		text = text.replace("&nbsp;", " ");
		text = text.replace("&lt;", "<");
		text = text.replace("&gt;", ">");
		text = text.replace("&amp;", "&");
		text = text.replace("&quot;", "\"");
		text = text.replace("&#39;", "'");
		text = text.replaceAll("\\s+", " ");
		text = text.replaceAll("(?m)^\\s+|\\s+$", "");
		return text.trim();
	}

	private static String escapeJson(String str)
	{
		if (str == null) return "";
		return str.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}

	private static String buildWebFetchResponse(boolean success, String error, String... keyValues)
	{
		StringBuilder json = new StringBuilder("{\n");
		json.append("  \"success\": ").append(success).append(",\n");

		if (error != null)
		{
			json.append("  \"error\": \"").append(escapeJson(error)).append("\",\n");
		}

		for (int i = 0; i < keyValues.length; i += 2)
		{
			json.append("  \"").append(keyValues[i]).append("\": \"").append(escapeJson(keyValues[i + 1])).append("\"");
			if (i < keyValues.length - 2) json.append(",");
			json.append("\n");
		}

		json.append("}");
		return json.toString();
	}

	private static String buildWebFetchError(String error)
	{
		return buildWebFetchResponse(false, error);
	}
}
