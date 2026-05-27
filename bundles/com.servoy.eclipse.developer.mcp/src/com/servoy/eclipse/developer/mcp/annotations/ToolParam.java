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
package com.servoy.eclipse.developer.mcp.annotations;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotates a parameter of a {@link Tool}-annotated method, providing the
 * parameter metadata required to build the MCP JSON Schema.
 */
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface ToolParam
{
	/** Parameter name in the MCP JSON schema. Defaults to the Java parameter name if blank. */
	String name() default "";

	/** Human-readable description of the parameter. */
	String description();

	/** Whether this parameter is required. Default: true. */
	boolean required() default true;

	/** JSON Schema type of this parameter. Default: "string". */
	String type() default "string";
}
