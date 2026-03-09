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
package com.servoy.eclipse.servoypilot.tools.dto;

/**
 * Represents a single documentation item for signature-based JSDoc generation.
 * Used by AI to return documentation items - signature to search for and JSDoc to apply.
 */
public record DocumentationItem(String signature, String jsdoc)
{
	/**
	 * Canonical constructor with validation.
	 */
	public DocumentationItem
	{
		if (signature == null || signature.isBlank())
		{
			throw new IllegalArgumentException("Signature cannot be null or blank");
		}
		if (jsdoc == null || jsdoc.isBlank())
		{
			throw new IllegalArgumentException("JSDoc cannot be null or blank");
		}
		if (!jsdoc.trim().startsWith("/**") || !jsdoc.trim().endsWith("*/"))
		{
			throw new IllegalArgumentException("JSDoc must start with /** and end with */ - got: " +
				jsdoc.trim().substring(0, Math.min(50, jsdoc.trim().length())));
		}
	}
}
