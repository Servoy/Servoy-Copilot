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

/**
 * Optional richer return value for an {@code @Tool} method that needs to return more than
 * plain text - in particular a text part plus an image.
 * <p>
 * The overwhelming majority of tools simply return a {@link String}, which the framework wraps
 * in a single text content part. When a tool instead returns an {@code McpToolResult},
 * {@code McpServerFactory} emits a text content part for {@link #text()} and, when
 * {@link #imageData()} is present, an additional image content part (base64-encoded, with
 * {@link #imageMimeType()}). This keeps the common String path untouched and fully
 * backward-compatible while allowing a tool such as {@code getFormLayout} to attach a
 * rendered-form screenshot to its response.
 * </p>
 *
 * @param text          the textual result (never {@code null}; use an empty string for none)
 * @param imageData     base64-encoded image bytes to attach, or {@code null} for no image
 * @param imageMimeType the image MIME type (e.g. {@code "image/png"}); ignored when
 *                      {@code imageData} is {@code null}
 */
public record McpToolResult(String text, String imageData, String imageMimeType)
{
	/** A text-only result. */
	public static McpToolResult text(String text)
	{
		return new McpToolResult(text != null ? text : "", null, null);
	}

	/** A text result with an attached base64-encoded image. */
	public static McpToolResult withImage(String text, String imageData, String imageMimeType)
	{
		return new McpToolResult(text != null ? text : "", imageData, imageMimeType);
	}

	/** @return {@code true} when an image is attached. */
	public boolean hasImage()
	{
		return imageData != null && !imageData.isEmpty();
	}
}
