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
package com.servoy.eclipse.developer.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Headless unit tests for the text+image tool-result pipeline: {@link McpToolResult} and
 * {@link McpServerFactory#toContents(Object)}. Verifies that a plain tool return still yields a
 * single text content part (backward-compatible), and that an {@link McpToolResult} with an
 * image yields a text part plus an image part.
 */
class McpToolResultTest
{
	@Test
	@org.junit.jupiter.api.DisplayName("plain String result -> a single TextContent (backward-compatible)")
	void plainString_singleTextContent()
	{
		List<McpSchema.Content> contents = McpServerFactory.toContents("hello world");

		assertEquals(1, contents.size(), "a plain value maps to exactly one content part");
		McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, contents.get(0),
			"the single part is a TextContent");
		assertEquals("hello world", text.text(), "the text is the tool's return value");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("null result -> a single empty TextContent")
	void nullResult_singleEmptyTextContent()
	{
		List<McpSchema.Content> contents = McpServerFactory.toContents(null);

		assertEquals(1, contents.size());
		McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, contents.get(0));
		assertEquals("", text.text(), "a null return maps to empty text, not the literal 'null'");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("text-only McpToolResult -> a single TextContent, no image part")
	void textOnlyToolResult_singleTextContent()
	{
		List<McpSchema.Content> contents = McpServerFactory.toContents(McpToolResult.text("just text"));

		assertEquals(1, contents.size(), "no image -> only the text part");
		McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, contents.get(0));
		assertEquals("just text", text.text());
	}

	@Test
	@org.junit.jupiter.api.DisplayName("McpToolResult with image -> a TextContent followed by an ImageContent")
	void toolResultWithImage_textThenImage()
	{
		String base64 = "aGVsbG8="; // "hello"
		List<McpSchema.Content> contents = McpServerFactory
			.toContents(McpToolResult.withImage("{\"form\":\"orders\"}", base64, "image/png"));

		assertEquals(2, contents.size(), "text + image -> two content parts, in that order");

		McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, contents.get(0),
			"the first part is the text envelope");
		assertEquals("{\"form\":\"orders\"}", text.text());

		McpSchema.ImageContent image = assertInstanceOf(McpSchema.ImageContent.class, contents.get(1),
			"the second part is the image");
		assertEquals(base64, image.data(), "the image carries the base64 PNG data");
		assertEquals("image/png", image.mimeType(), "the image mime type is carried through");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("McpToolResult factories and hasImage() behave as documented")
	void factories()
	{
		McpToolResult textOnly = McpToolResult.text("t");
		assertEquals("t", textOnly.text());
		assertFalse(textOnly.hasImage(), "text() has no image");

		McpToolResult withImage = McpToolResult.withImage("t", "Zm9v", "image/png");
		assertTrue(withImage.hasImage(), "withImage() with non-empty data has an image");
		assertEquals("Zm9v", withImage.imageData());
		assertEquals("image/png", withImage.imageMimeType());

		assertFalse(McpToolResult.withImage("t", "", "image/png").hasImage(),
			"empty image data is treated as no image");
		assertFalse(McpToolResult.withImage("t", null, "image/png").hasImage(),
			"null image data is treated as no image");

		assertEquals("", McpToolResult.text(null).text(), "a null text becomes an empty string");
	}
}
