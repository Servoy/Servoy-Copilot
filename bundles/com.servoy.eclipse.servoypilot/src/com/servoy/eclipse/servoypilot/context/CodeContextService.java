package com.servoy.eclipse.servoypilot.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.internal.javascript.ti.TypeInferencer2;
import org.eclipse.dltk.javascript.ast.Script;
import org.eclipse.dltk.javascript.parser.JavaScriptParserUtil;
import org.eclipse.dltk.javascript.typeinfo.IRClassType;
import org.eclipse.dltk.javascript.typeinfo.IRType;
import org.eclipse.dltk.javascript.typeinfo.JSTypeSet;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.IdentifierContext;
import com.servoy.eclipse.servoypilot.context.dto.IdentifierContext.IdentifierKind;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.j2db.util.Pair;

/**
 * Service for extracting code context from JavaScript selections.
 * 
 * Orchestrates:
 * - AST parsing using DLTK
 * - Type inference
 * - Documentation extraction from multiple sources
 * - Context formatting for LLM consumption
 */
public class CodeContextService
{
	private static CodeContextService instance;

	public static synchronized CodeContextService getInstance()
	{
		if (instance == null)
		{
			instance = new CodeContextService();
		}
		return instance;
	}

	private CodeContextService()
	{
		// Singleton
	}

	/**
	 * Extracts code context for a selection.
	 * 
	 * @param selectionInfo the selection to analyze
	 * @return CodeContext with extracted information, or error context if parsing fails
	 */
	public CodeContext getCodeContext(SelectionInfo selectionInfo)
	{
		if (selectionInfo != null)
		{
			if (selectionInfo.hasSelection())
			{
				try
				{
					ISourceModule module = selectionInfo.getSourceModule();
					
					// Parse the JavaScript file
					final Script script = JavaScriptParserUtil.parse(module, null);
					if (script != null)
					{
						// Run type inference with our custom visitor
						TypeInferencer2 inferencer = new TypeInferencer2();
						final IdentifierCollectingVisitor collector = new IdentifierCollectingVisitor(
							inferencer, 
							selectionInfo.getOffset(), 
							selectionInfo.getLength()
						);
						
						inferencer.setVisitor(collector);
						inferencer.setModelElement(module);
						inferencer.doInferencing(script);

						// Extract context for each identifier (with deduplication)
						Map<String, IdentifierContext> uniqueIdentifiers = new HashMap<>();
						
						collector.identifiers.forEach((node, pair) -> {
							IdentifierContext identifierContext = extractIdentifierContext(node, pair, collector);
							if (identifierContext != null)
							{
								// Use name+type as unique key to avoid duplicates
								String key = identifierContext.getName() + ":" + identifierContext.getTypeName();
								uniqueIdentifiers.putIfAbsent(key, identifierContext);
							}
						});

						// Convert to list
						List<IdentifierContext> identifierContexts = new ArrayList<>(uniqueIdentifiers.values());

						return CodeContext.success(selectionInfo, identifierContexts);
					}
					return CodeContext.error(selectionInfo, "Failed to parse JavaScript file");
				}
				catch (Exception e)
				{
					ServoyLog.logError("Error extracting code context", e);
					return CodeContext.error(selectionInfo, "Error analyzing code: " + e.getMessage());
				}
			}
			return CodeContext.empty(selectionInfo);
		}
		return CodeContext.error(null, "No selection provided");
	}

	/**
	 * Extracts context for a single identifier.
	 * TODO: Implement full extraction logic in Phase 3
	 * 
	 * @param node the AST node
	 * @param pair the identifier reference and name
	 * @param collector the visitor with collected data
	 * @return IdentifierContext or null if no context could be extracted
	 */
	private IdentifierContext extractIdentifierContext(
		org.eclipse.dltk.javascript.ast.JSNode node,
		Pair<org.eclipse.dltk.javascript.typeinference.IValueReference, String> pair,
		IdentifierCollectingVisitor collector)
	{
		// Get type information
		JSTypeSet types = pair.getLeft().getTypes();
		JSTypeSet declaredTypes = pair.getLeft().getDeclaredTypes();
		
		String typeName = null;
		if (types.size() > 0)
		{
			IRType irType = types.iterator().next();
			if (irType instanceof IRClassType clsType)
			{
				irType = clsType.toItemType();
			}
			typeName = irType.getName();
		}
		
		if (declaredTypes.size() > 0)
		{
			IRType irType = declaredTypes.iterator().next();
			if (irType instanceof IRClassType clsType)
			{
				irType = clsType.toItemType();
			}
			typeName = irType.getName();
		}

		if (typeName != null)
		{
			// For now, create basic context with type only
			// TODO Phase 3: Extract documentation from ScriptObjectRegistry, WebComponentSpecProvider, etc.
			IdentifierKind kind = determineIdentifierKind(typeName);
			
			return IdentifierContext.create(
				pair.getRight(), // identifier name
				typeName,
				"", // TODO: Extract documentation in Phase 3
				kind
			);
		}
		return null; // No type information available
	}

	/**
	 * Determines the kind of identifier based on type name.
	 * 
	 * @param typeName the resolved type name
	 * @return IdentifierKind classification
	 */
	private IdentifierKind determineIdentifierKind(String typeName)
	{
		if (typeName.startsWith("RuntimeWebComponent<"))
		{
			return IdentifierKind.WEB_COMPONENT;
		}
		if (typeName.startsWith("WebService<"))
		{
			return IdentifierKind.WEB_SERVICE;
		}
		if ("Function".equals(typeName))
		{
			return IdentifierKind.SOLUTION_FUNCTION;
		}
		
		// Check if it's a Servoy API type
		// TODO Phase 3: Use ScriptObjectRegistry to verify
		return IdentifierKind.SERVOY_API;
	}
}
