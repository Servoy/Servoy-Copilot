/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.dltk.ast.ASTNode;
import org.eclipse.dltk.internal.javascript.ti.ITypeInferenceContext;
import org.eclipse.dltk.internal.javascript.ti.TypeInferencerVisitor;
import org.eclipse.dltk.javascript.ast.Expression;
import org.eclipse.dltk.javascript.ast.Identifier;
import org.eclipse.dltk.javascript.ast.JSNode;
import org.eclipse.dltk.javascript.ast.PropertyExpression;
import org.eclipse.dltk.javascript.typeinference.IValueReference;

import com.servoy.j2db.util.Pair;

/**
 * AST visitor that collects identifiers and their types within a specific code range.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.context.IdentifierCollectingVisitor}.
 * Extends DLTK's {@code TypeInferencerVisitor} to leverage type inference during AST traversal.
 * </p>
 * <p>
 * <b>Note:</b> Uses {@code org.eclipse.dltk.internal.javascript.ti.*} which are {@code x-internal}
 * packages. Works in Eclipse development mode; for Tycho production builds, target platform must
 * grant access.
 * </p>
 */
@SuppressWarnings("restriction")
public class IdentifierCollectingVisitor extends TypeInferencerVisitor
{
	/**
	 * Map of identifiers found in the selection range.
	 * Key: JSNode (identifier node)
	 * Value: Pair of IValueReference (for type resolution) and String (identifier name)
	 */
	public final Map<JSNode, Pair<IValueReference, String>> identifiers = new HashMap<>();

	/**
	 * Map of properties/calls on identifiers.
	 * Key: JSNode (the base identifier)
	 * Value: List of IValueReference (properties or method calls on that identifier)
	 */
	public final Map<JSNode, List<IValueReference>> propertiesOrCalls = new HashMap<>();

	private final int offset;
	private final int length;

	public IdentifierCollectingVisitor(ITypeInferenceContext context, int offset, int length)
	{
		super(context);
		this.offset = offset;
		this.length = length;
	}

	@Override
	public IValueReference visit(ASTNode node)
	{
		final IValueReference reference = super.visit(node);

		if (reference != null && node.sourceStart() >= offset && node.sourceEnd() <= (offset + length))
		{
			if (node instanceof Identifier id)
			{
				identifiers.put(id, Pair.create(reference, id.getName()));
			}
			else if (node instanceof PropertyExpression pe && identifiers.containsKey(pe.getObject()))
			{
				List<IValueReference> list = propertiesOrCalls.get(pe.getObject());
				if (list == null)
				{
					list = new ArrayList<>();
					propertiesOrCalls.put(pe.getObject(), list);
				}
				list.add(reference);
			}
		}
		return reference;
	}

	@Override
	protected IValueReference extractNamedChild(IValueReference parent, Expression name)
	{
		IValueReference ref = super.extractNamedChild(parent, name);

		// Handle nested properties like plugins.ngdesktop.openFile
		if (name instanceof Identifier id &&
			name.getParent().getParent() instanceof PropertyExpression &&
			name.sourceStart() >= offset &&
			name.sourceEnd() <= (offset + length))
		{
			identifiers.put(id.getParent(), Pair.create(ref, id.getParent().toString()));
		}
		return ref;
	}

	public void clear()
	{
		identifiers.clear();
		propertiesOrCalls.clear();
	}

	public int getIdentifierCount()
	{
		return identifiers.size();
	}
}
