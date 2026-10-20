/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

/**
 * One bound handler (event) of an {@link InteractiveElement}. Immutable, no Servoy/Eclipse types.
 *
 * @param handler the persisted key as is, e.g. {@code onActionMethodID} or {@code onCellClick}
 * @param method the resolved method name, {@code null} when the id could not be resolved
 * @param owner {@code forms.<form>} or {@code scopes.<scope>}, {@code null} when unresolved
 * @param unresolved true when the raw id could not be turned into a method
 * @param methodId the raw persisted value, only set when unresolved
 */
public record HandlerBinding(String handler, String method, String owner, boolean unresolved, String methodId)
{
	public static HandlerBinding resolved(String handler, String method, String owner)
	{
		return new HandlerBinding(handler, method, owner, false, null);
	}

	public static HandlerBinding unresolved(String handler, String methodId)
	{
		return new HandlerBinding(handler, null, null, true, methodId);
	}
}
