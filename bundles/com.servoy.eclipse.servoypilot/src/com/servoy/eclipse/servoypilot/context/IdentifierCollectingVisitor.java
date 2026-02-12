package com.servoy.eclipse.servoypilot.context;

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
 * Ported from AI Bridge's IdentifierCollectingVisitor.
 * 
 * Extends DLTK's TypeInferencerVisitor to leverage type inference during AST traversal.
 */
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

	/**
	 * Creates an IdentifierCollectingVisitor for a specific selection range.
	 * 
	 * @param context the type inference context
	 * @param offset the start offset of the selection
	 * @param length the length of the selection
	 */
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
		
		// Only process nodes within the selection range
		if (reference != null && node.sourceStart() >= offset && node.sourceEnd() <= (offset + length))
		{
			if (node instanceof Identifier id)
			{
				// Store the identifier and its reference
				identifiers.put(id, Pair.create(reference, id.getName()));
			}
			else if (node instanceof PropertyExpression pe && identifiers.containsKey(pe.getObject()))
			{
				// This is a property/call on an identifier we've seen
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
		// We want to capture ngdesktop as a type too
		if (name instanceof Identifier id && 
			name.getParent().getParent() instanceof PropertyExpression &&
			name.sourceStart() >= offset && 
			name.sourceEnd() <= (offset + length))
		{
			identifiers.put(id.getParent(), Pair.create(ref, id.getParent().toString()));
		}
		
		return ref;
	}

	/**
	 * Clears all collected data. Useful for reusing the visitor.
	 */
	public void clear()
	{
		identifiers.clear();
		propertiesOrCalls.clear();
	}

	/**
	 * Gets the number of identifiers collected.
	 * 
	 * @return count of identifiers
	 */
	public int getIdentifierCount()
	{
		return identifiers.size();
	}
}
