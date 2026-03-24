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
package com.servoy.eclipse.servoypilot.services;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.codeassist.ISelectionRequestor;
import org.eclipse.dltk.compiler.env.IModuleSource;
import org.eclipse.dltk.compiler.env.ModuleSource;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IMember;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.IModelElementVisitor;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.core.ScriptModelUtil;
import org.eclipse.dltk.internal.javascript.ti.TypeInferencer2;
import org.eclipse.dltk.javascript.ast.JSNode;
import org.eclipse.dltk.javascript.ast.Script;
import org.eclipse.dltk.javascript.internal.core.codeassist.JavaScriptSelectionEngine2;
import org.eclipse.dltk.javascript.parser.JavaScriptParserUtil;
import org.eclipse.dltk.javascript.typeinference.IValueReference;
import org.eclipse.dltk.javascript.typeinference.ReferenceLocation;
import org.eclipse.dltk.javascript.typeinfo.IRClassType;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.dltk.javascript.typeinfo.IRType;
import org.eclipse.dltk.javascript.typeinfo.JSTypeSet;
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.ParameterKind;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.dltk.javascript.typeinfo.model.Type;
import org.eclipse.dltk.javascript.ui.scriptdoc.ScriptdocContentAccess;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.context.IdentifierCollectingVisitor;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.IdentifierContext;
import com.servoy.eclipse.servoypilot.context.dto.IdentifierContext.IdentifierKind;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.j2db.documentation.ClientSupport;
import com.servoy.j2db.documentation.DocumentationUtil;
import com.servoy.j2db.documentation.IFunctionDocumentation;
import com.servoy.j2db.documentation.IObjectDocumentation;
import com.servoy.j2db.documentation.IParameterDocumentation;
import com.servoy.j2db.documentation.XMLScriptObjectAdapter;
import com.servoy.j2db.scripting.ITypedScriptObject;
import com.servoy.j2db.scripting.ScriptObjectRegistry;
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
	 * Gets the code text from selection (or entire file if no selection).
	 * 
	 * @param selectionInfo the selection info
	 * @return the selected text or full file content, empty string if null
	 */
	public String getCodeText(SelectionInfo selectionInfo)
	{
		if (selectionInfo != null)
		{
			return selectionInfo.getSelectedText();
		}
		return "";
	}

	/**
	 * Extracts code context for a selection.
	 * 
	 * @param selectionInfo the selection to analyze
	 * @return CodeContext with extracted information, or error context if parsing fails
	 */
	public CodeContext getCodeContext(SelectionInfo selectionInfo)
	{
		return getCodeContext(selectionInfo, null);
	}

	/**
	 * Extracts code context for a selection with optional filtering.
	 * 
	 * @param selectionInfo the selection to analyze
	 * @param filterIdentifiers optional array of identifier names to extract documentation for (null = extract all)
	 * @return CodeContext with extracted information, or error context if parsing fails
	 */
	public CodeContext getCodeContext(SelectionInfo selectionInfo, String[] filterIdentifiers)
	{
		if (selectionInfo != null)
		{
			if (selectionInfo.hasSelection())
			{
				// Debug header
				System.out.println("\n=== CODE CONTEXT EXTRACTION ===");
				System.out.println("File: " + selectionInfo.getFilePath());
				System.out.println("Selection: offset=" + selectionInfo.getOffset() + ", length=" + selectionInfo.getLength());
				System.out.println("--------------------------------\n");

				try
				{
					ISourceModule module = selectionInfo.getSourceModule();

					if (module == null)
					{
						return CodeContext.success(selectionInfo, null);
					}

					// Parse the JavaScript file
					final Script script = JavaScriptParserUtil.parse(module, null);
					if (script != null)
					{
						// Run type inference with our custom visitor
						TypeInferencer2 inferencer = new TypeInferencer2();
						final IdentifierCollectingVisitor collector = new IdentifierCollectingVisitor(
							inferencer,
							selectionInfo.getOffset(),
							selectionInfo.getLength());

						inferencer.setVisitor(collector);
						inferencer.setModelElement(module);
						inferencer.doInferencing(script);

						// Extract context for each identifier (with deduplication)
						Map<String, IdentifierContext> uniqueIdentifiers = new HashMap<>();

						collector.identifiers.forEach((node, pair) -> {
							// Check if we should extract documentation for this identifier
							String identifierName = pair.getRight();
							boolean shouldExtract = (filterIdentifiers == null) || containsIdentifier(filterIdentifiers, identifierName);

							if (shouldExtract)
							{
								IdentifierContext identifierContext = extractIdentifierContext(node, pair, collector);
								if (identifierContext != null)
								{
									// Use name+type as unique key to avoid duplicates
									String key = identifierContext.getName() + ":" + identifierContext.getTypeName();
									uniqueIdentifiers.putIfAbsent(key, identifierContext);
								}
							}
						});

						// Convert to list
						List<IdentifierContext> identifierContexts = new ArrayList<>(uniqueIdentifiers.values());

						// Print simple summary
						System.out.println("Detected " + identifierContexts.size() + " identifiers");
						System.out.println("================================\n");

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
			IdentifierKind kind = determineIdentifierKind(typeName);
			String documentation = "";

			// Extract documentation for solution functions
			if (kind == IdentifierKind.SOLUTION_FUNCTION)
			{
				documentation = extractSolutionFunctionDocumentation(pair.getLeft());
			}
			// Extract documentation for Servoy API
			else if (kind == IdentifierKind.SERVOY_API)
			{
				documentation = extractServoyApiDocumentation(typeName, node, collector);
			}
			// Extract documentation for web components
			else if (kind == IdentifierKind.WEB_COMPONENT)
			{
				documentation = extractWebComponentDocumentation(typeName, node, collector);
			}
			// Extract documentation for web services
			else if (kind == IdentifierKind.WEB_SERVICE)
			{
				documentation = extractWebServiceDocumentation(typeName, node, collector);
			}

			return IdentifierContext.create(
				pair.getRight(), // identifier name
				typeName,
				documentation,
				kind);
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

		// Check if it's a Servoy API type (uses ScriptObjectRegistry to verify in extractServoyApiDocumentation)
		return IdentifierKind.SERVOY_API;
	}

	/**
	 * Extracts ScriptDoc documentation for solution-defined functions.
	 * Ported from AI Bridge AiBridgeHandler.getContextData() lines 243-262.
	 * 
	 * @param valueReference the identifier reference
	 * @return formatted documentation string, or empty string if not available
	 */
	private String extractSolutionFunctionDocumentation(org.eclipse.dltk.javascript.typeinference.IValueReference valueReference)
	{
		if (valueReference == null)
		{
			return "";
		}

		ReferenceLocation location = valueReference.getLocation();
		if (location != null)
		{
			IModelElement element = locateModelElement(location);
			if (element instanceof IMember member)
			{
				try (Reader reader = ScriptdocContentAccess.getContentReader(member, true))
				{
					if (reader != null)
					{
						String doc = IOUtils.toString(reader);
						if (doc != null && !doc.trim().isEmpty())
						{
							String formattedDoc = formatSolutionFunctionDoc(doc);
							return formattedDoc;
						}
					}
				}
				catch (ModelException | IOException e)
				{
					ServoyLog.logError("Error extracting solution function documentation", e);
				}
			}
		}
		return "";
	}

	/**
	 * Formats solution function ScriptDoc by filtering out internal metadata.
	 * Removes @properties= lines and blank lines.
	 * 
	 * @param doc the raw ScriptDoc content
	 * @return formatted documentation
	 */
	private String formatSolutionFunctionDoc(String doc)
	{
		if (doc == null || doc.trim().isEmpty())
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		String[] lines = doc.split("\n");

		for (String line : lines)
		{
			String trimmedLine = line.trim();
			if (!trimmedLine.startsWith("@properties=") && !trimmedLine.isBlank())
			{
				sb.append(trimmedLine).append("\n");
			}
		}

		return sb.toString().trim();
	}

	/**
	 * Extracts Servoy API documentation for API objects like plugins, application, etc.
	 * Ported from AI Bridge AiBridgeHandler.getContextData() lines 289-307.
	 * 
	 * Enhanced with TypeCreator fallback to handle @ServoyDocumented scriptingName mappings
	 * (e.g., "controller" → JSForm class with full member documentation).
	 * 
	 * @param typeName the API type name (e.g., "Plugins", "Application", "controller")
	 * @param node the identifier node
	 * @param collector the visitor with collected properties/calls
	 * @return formatted documentation string, or empty string if not available
	 */
	private String extractServoyApiDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || typeName.trim().isEmpty())
		{
			return "";
		}

		System.out.println("  [Servoy API Doc] Extracting documentation for type: " + typeName);

		// PRIMARY PATH: Try ScriptObjectRegistry (XML-based documentation)
		ITypedScriptObject scriptObject = ScriptObjectRegistry.getScriptObjectByName(typeName);
		System.out.println("  [Servoy API Doc] ScriptObjectRegistry.getScriptObjectByName(\"" + typeName + "\") returned: " +
			(scriptObject != null ? scriptObject.getClass().getSimpleName() : "null"));

		if (scriptObject != null && scriptObject.getObjectDocumentation() != null)
		{
			System.out.println("  [Servoy API Doc] ✓ Found in ScriptObjectRegistry");
			List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
			System.out.println("  [Servoy API Doc] collector.propertiesOrCalls.get(node) returned: " +
				(callsOrProperties != null ? callsOrProperties.size() + " items" : "null"));
			System.out.println("  [Servoy API Doc] node = " + node + " (class: " + node.getClass().getSimpleName() + ")");
			System.out.println("  [Servoy API Doc] Total keys in collector.propertiesOrCalls map: " + collector.propertiesOrCalls.keySet().size());

			if (callsOrProperties != null && !callsOrProperties.isEmpty())
			{
				IObjectDocumentation docFile = scriptObject.getObjectDocumentation();
				StringBuilder sb = new StringBuilder();
				String identifierName = node.toString();

				System.out.println("  [Servoy API Doc] Processing " + callsOrProperties.size() + " property/method calls:");
				for (IValueReference action : callsOrProperties)
				{
					String propertyName = action.getName();
					System.out.println("    - Extracting doc for: " + propertyName);
					String funcDoc = extractFunctionDocumentation(docFile, propertyName, identifierName);
					if (!funcDoc.isEmpty())
					{
						System.out.println("      ✓ Found documentation (" + funcDoc.length() + " chars)");
						sb.append(funcDoc).append("\n\n");
					}
					else
					{
						System.out.println("      ✗ No documentation found for: " + propertyName);
					}
				}

				String result = sb.toString().trim();
				if (!result.isEmpty())
				{
					System.out.println("  [Servoy API Doc] ✓✓✓ SUCCESS - Extracted documentation via ScriptObjectRegistry");
					return result;
				}
				System.out.println("  [Servoy API Doc] ✗ ScriptObjectRegistry path returned empty result");
			}
			else
			{
				System.out.println("  [Servoy API Doc] ✗ No properties/calls found on this node");
				System.out.println("  [Servoy API Doc] DEBUG: Listing all nodes in propertiesOrCalls map:");
				for (JSNode key : collector.propertiesOrCalls.keySet())
				{
					System.out.println("    - Node: " + key + " (" + key.getClass().getSimpleName() + ") → " +
						collector.propertiesOrCalls.get(key).size() + " calls");
				}
			}
		}
		else
		{
			System.out.println("  [Servoy API Doc] ✗ Not found in ScriptObjectRegistry (trying TypeCreator fallback)");
		}

		// FALLBACK PATH: Try TypeCreator (Type system with @ServoyDocumented mappings)
		// This handles cases like "controller" which is mapped via @ServoyDocumented annotation
		// to JSForm class, providing the same documentation as code completion (Ctrl+Space)
		return extractServoyApiDocumentationFromTypeCreator(typeName, node, collector);
	}

	/**
	 * Fallback extraction using TypeCreator's Type system.
	 * Handles @ServoyDocumented scriptingName mappings like "controller" → JSForm.
	 * Uses the same path as code completion for consistent documentation.
	 * 
	 * @param typeName the type name to resolve via TypeCreator
	 * @param node the identifier node
	 * @param collector the visitor with collected properties/calls
	 * @return formatted documentation string, or empty string if not available
	 */
	private String extractServoyApiDocumentationFromTypeCreator(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null)
		{
			return "";
		}

		System.out.println("  [TypeCreator Fallback] ========== STARTING ==========");
		System.out.println("  [TypeCreator Fallback] Input typeName: " + typeName);
		System.out.println("  [TypeCreator Fallback] Input node: " + node + " (" + node.getClass().getSimpleName() + ")");

		// Get TypeCreator instance
		TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
		if (typeCreator == null)
		{
			System.out.println("  [TypeCreator Fallback] ✗ TypeCreator instance not available");
			return "";
		}
		System.out.println("  [TypeCreator Fallback] ✓ TypeCreator instance obtained");

		// Resolve type via TypeCreator (same as code completion)
		// Context = null for global types
		System.out.println("  [TypeCreator Fallback] Calling typeCreator.findType(null, \"" + typeName + "\")...");
		Type servoyType = typeCreator.findType(null, typeName);

		// If not found by class name, try scriptingName (DLTK returns class name, but TypeCreator uses scriptingName)
		if (servoyType == null)
		{
			System.out.println("  [TypeCreator Fallback] ✗ Direct lookup failed");
			String scriptingName = mapClassNameToScriptingName(typeName);
			System.out.println("  [TypeCreator Fallback] mapClassNameToScriptingName(\"" + typeName + "\") returned: " +
				(scriptingName != null ? "\"" + scriptingName + "\"" : "null"));

			if (scriptingName != null && !scriptingName.equals(typeName))
			{
				System.out.println("  [TypeCreator Fallback] Trying mapped scriptingName: calling typeCreator.findType(null, \"" + scriptingName + "\")...");
				servoyType = typeCreator.findType(null, scriptingName);
				if (servoyType != null)
				{
					System.out.println("  [TypeCreator Fallback] ✓✓ SUCCESS - Found via scriptingName mapping!");
				}
				else
				{
					System.out.println("  [TypeCreator Fallback] ✗ Mapped scriptingName lookup also failed");
				}
			}
		}
		else
		{
			System.out.println("  [TypeCreator Fallback] ✓ Direct lookup succeeded");
		}

		if (servoyType == null)
		{
			System.out.println("  [TypeCreator Fallback] ✗✗ FINAL: Type not found in TypeCreator");
			return "";
		}

		System.out.println("  [TypeCreator Fallback] ✓ Type resolved: " + servoyType.getName());
		System.out.println("  [TypeCreator Fallback] Type has " + servoyType.getMembers().size() + " members");
		if (servoyType.getMembers().size() > 0)
		{
			System.out.println("  [TypeCreator Fallback] First 5 members:");
			int count = 0;
			for (Member m : servoyType.getMembers())
			{
				System.out.println("    - " + m.getName() + " (" + m.getClass().getSimpleName() + ")");
				if (++count >= 5)
				{
					break;
				}
			}
		}

		// Get properties/calls for this identifier
		System.out.println("  [TypeCreator Fallback] Checking collector.propertiesOrCalls.get(node)...");
		List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
		System.out.println("  [TypeCreator Fallback] Result: " + (callsOrProperties != null ? callsOrProperties.size() + " items" : "null"));

		if (callsOrProperties == null || callsOrProperties.isEmpty())
		{
			System.out.println("  [TypeCreator Fallback] ✗ No properties/calls found on this specific node");
			System.out.println("  [TypeCreator Fallback] Total nodes in collector.propertiesOrCalls: " + collector.propertiesOrCalls.keySet().size());
			System.out.println("  [TypeCreator Fallback] Listing all nodes:");
			for (JSNode key : collector.propertiesOrCalls.keySet())
			{
				System.out.println("    - Node: " + key + " → " + collector.propertiesOrCalls.get(key).size() + " calls");
			}
			System.out.println("  [TypeCreator Fallback] ========== END (NO CALLS) ==========");
			return "";
		}

		System.out.println("  [TypeCreator Fallback] ✓ Found " + callsOrProperties.size() + " property/method calls");
		StringBuilder sb = new StringBuilder();
		String identifierName = node.toString();
		System.out.println("  [TypeCreator Fallback] Identifier name: " + identifierName);

		// Extract documentation for each property/method call
		int foundCount = 0;
		System.out.println("  [TypeCreator Fallback] Extracting documentation for each call:");
		for (IValueReference action : callsOrProperties)
		{
			String propertyName = action.getName();
			System.out.println("    - Looking for member: " + propertyName);
			String memberDoc = extractWebObjectMemberDocumentation(servoyType, propertyName, identifierName);
			if (!memberDoc.isEmpty())
			{
				System.out.println("      ✓ Found doc (" + memberDoc.length() + " chars)");
				sb.append(memberDoc).append("\n\n");
				foundCount++;
			}
			else
			{
				System.out.println("      ✗ No doc found");
			}
		}

		System.out.println("  [TypeCreator Fallback] Extracted documentation for " + foundCount + " out of " + callsOrProperties.size() + " members");
		System.out.println("  [TypeCreator Fallback] ========== END ==========");

		return sb.toString().trim();
	}

	/**
	 * Maps Java class names to @ServoyDocumented scriptingName values.
	 * DLTK returns class names (e.g., "JSApplication") but TypeCreator registers by scriptingName (e.g., "application").
	 * 
	 * Note: This mapping is only needed for GLOBAL Servoy API objects registered via ScriptObjectRegistry.
	 * Form-scoped variables like "controller" already use scriptingName in DLTK, so no mapping is needed.
	 * 
	 * @param className the Java class name
	 * @return the scriptingName, or null if no mapping exists
	 */
	private String mapClassNameToScriptingName(String className)
	{
		if (className == null)
		{
			return null;
		}

		// Map known Servoy global API classes to their scriptingName values from @ServoyDocumented annotations
		// These are registered via registerConstantsForScriptObject in TypeCreator.initialize()
		return switch (className)
		{
			// Core globals
			case "JSApplication" -> "application";
			case "JSDatabaseManager" -> "databaseManager";
			case "JSSecurity" -> "security";
			case "JSI18N" -> "i18n";
			case "JSUtils" -> "utils";

			// Form-scoped (actually not needed, but kept for reference)
			// "controller" is already resolved correctly by DLTK as it's injected per form scope
			case "JSForm" -> "controller";

			// Plugin-related
			case "JSEventsManager" -> "eventsManager";
			case "JSSolutionModel" -> "solutionModel";

			default -> null; // No known mapping, return null
		};
	}

	/**
	 * Extracts documentation for a single function/property from Servoy API documentation.
	 * Handles single functions, multiple overloads, properties, and constants.
	 * 
	 * @param docFile the object documentation
	 * @param propertyName the property/function name
	 * @param identifierName the base identifier name
	 * @return formatted documentation string
	 */
	private String extractFunctionDocumentation(IObjectDocumentation docFile, String propertyName, String identifierName)
	{
		if (docFile == null || propertyName == null)
		{
			return "";
		}

		// Find all functions with this name (may be overloads)
		List<IFunctionDocumentation> functions = docFile.getFunctions().stream()
			.filter(function -> propertyName.equals(function.getMainName()))
			.sorted((func1, func2) -> func1.getArguments().size() - func2.getArguments().size())
			.collect(Collectors.toList());

		if (functions.size() == 1)
		{
			IFunctionDocumentation function = functions.get(0);
			if (function.getType() == IFunctionDocumentation.TYPE_FUNCTION)
			{
				String signature = extractSignature(function);
				String description = formatFunctionSignatureAndDocs(function, function.getArgumentsTypes().length, identifierName);
				return identifierName + "." + signature + "\n" + description;
			}
			if (function.getType() == IFunctionDocumentation.TYPE_CONSTANT ||
				function.getType() == IFunctionDocumentation.TYPE_PROPERTY)
			{
				String desc = function.getDescription(ClientSupport.ng);
				return identifierName + "." + function.getMainName() + "\n" + (desc != null ? desc : "");
			}
		}
		if (functions.size() > 1)
		{
			// Multiple overloads - show the one with most parameters
			IFunctionDocumentation function = functions.get(functions.size() - 1);
			String signature = extractSignature(function);
			int minParams = functions.get(0).getArgumentsTypes().length;
			String description = formatFunctionSignatureAndDocs(function, minParams, identifierName);
			return identifierName + "." + signature + "\n" + description;
		}

		return "";
	}

	/**
	 * Extracts the function signature from IFunctionDocumentation.
	 * 
	 * @param function the function documentation
	 * @return signature string (e.g., "showMessage(title, message)")
	 */
	private String extractSignature(IFunctionDocumentation function)
	{
		if (function == null)
		{
			return "";
		}

		String fullSignature = function.getFullJSTranslatedSignature(true, false);
		if (fullSignature != null && fullSignature.contains(" "))
		{
			// Split on first space to remove return type prefix
			String[] parts = fullSignature.split(" ", 2);
			if (parts.length > 1)
			{
				return parts[1];
			}
		}
		return function.getMainName() + "()";
	}

	/**
	 * Formats function signature and documentation with parameters and return type.
	 * Ported from AI Bridge AiBridgeHandler.generateDescription() lines 350-388.
	 * Enhanced to include @sample code and deprecation information.
	 * 
	 * @param fdoc the function documentation
	 * @param mandatoryParams the number of mandatory parameters
	 * @param identifierName the base identifier name (not used in current formatting)
	 * @return formatted documentation string
	 */
	private String formatFunctionSignatureAndDocs(IFunctionDocumentation fdoc, int mandatoryParams, String identifierName)
	{
		if (fdoc == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();

		Class< ? > returnType = fdoc.getReturnedType();
		String returnDescription = fdoc.getReturnDescription();
		LinkedHashMap<String, IParameterDocumentation> parameters = fdoc.getArguments();
		String tooltip = fdoc.getDescription(ClientSupport.ng);

		// Add deprecation warning first if deprecated
		if (fdoc.isDeprecated())
		{
			sb.append("@deprecated");
			String deprecatedText = fdoc.getDeprecatedText();
			if (deprecatedText != null && !deprecatedText.trim().isEmpty())
			{
				sb.append(" ").append(deprecatedText);
			}
			sb.append("\n");
		}

		// Add description
		if (tooltip != null && !tooltip.isEmpty())
		{
			sb.append(tooltip);
		}

		// Add sample code
		String sample = fdoc.getSample(ClientSupport.ng);
		if (sample != null && !sample.trim().isEmpty())
		{
			sb.append("\n@sample\n").append(sample);
		}

		// Add parameter documentation
		if (parameters != null && !parameters.isEmpty())
		{
			int paramCount = 0;
			for (IParameterDocumentation parameter : parameters.values())
			{
				sb.append("\n@param {");
				sb.append(DocumentationUtil.getJavaToJSTypeTranslator().translateJavaClassToJSTypeName(parameter.getType()));
				sb.append("} ");
				if (paramCount >= mandatoryParams)
				{
					sb.append("[");
				}
				sb.append(parameter.getName());
				if (paramCount >= mandatoryParams)
				{
					sb.append("] optional");
				}
				sb.append(" ");
				if (parameter.getDescription() != null)
				{
					sb.append(parameter.getDescription());
				}
				paramCount++;
			}
		}

		// Add return type documentation
		if (returnType != null && returnType != Void.class && returnType != void.class)
		{
			if (fdoc.getType() == IFunctionDocumentation.TYPE_FUNCTION)
			{
				sb.append("\n@return {");
				sb.append(XMLScriptObjectAdapter.getReturnTypeString(returnType));
				sb.append("} ");
				if (returnDescription != null)
				{
					sb.append(returnDescription);
				}
			}
		}

		return sb.toString();
	}

	/**
	 * Extracts web component documentation using TypeCreator's cached Type objects.
	 * Web components have type name like "RuntimeWebComponent<componentName>".
	 * Uses TypeCreator to get Type with merged _doc.js + .spec documentation.
	 * 
	 * @param typeName the full type name (e.g., "RuntimeWebComponent<servoyextra-table>")
	 * @param node the identifier node
	 * @param collector the visitor with collected properties/calls
	 * @return formatted documentation string, or empty string if not available
	 */
	private String extractWebComponentDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || !typeName.startsWith("RuntimeWebComponent<"))
		{
			return "";
		}

		// Extract component name from type
		String componentName = typeName.substring("RuntimeWebComponent<".length(), typeName.length() - 1);
		if (componentName.endsWith("_abs"))
		{
			componentName = componentName.substring(0, componentName.length() - 4);
		}

		// Use full type name for TypeCreator lookup
		return extractWebObjectDocumentationFromTypeCreator(typeName, node, collector, "component");
	}

	/**
	 * Extracts web service documentation using TypeCreator's cached Type objects.
	 * Web services have type name like "WebService<serviceName>".
	 * Uses TypeCreator to get Type with merged _doc.js + .spec documentation.
	 * 
	 * @param typeName the full type name (e.g., "WebService<myService>")
	 * @param node the identifier node
	 * @param collector the visitor with collected properties/calls
	 * @return formatted documentation string, or empty string if not available
	 */
	private String extractWebServiceDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || !typeName.startsWith("WebService<"))
		{
			return "";
		}

		// Use full type name for TypeCreator lookup
		return extractWebObjectDocumentationFromTypeCreator(typeName, node, collector, "service");
	}

	/**
	 * Common extraction logic for web components and web services.
	 * Uses TypeCreator to get cached Type objects with merged _doc.js + .spec documentation.
	 * 
	 * @param fullTypeName the full type name
	 * @param node the identifier node
	 * @param collector the visitor with collected properties/calls
	 * @param objectKind "component" or "service" for logging
	 * @return formatted documentation string
	 */
	private String extractWebObjectDocumentationFromTypeCreator(String fullTypeName, JSNode node, IdentifierCollectingVisitor collector, String objectKind)
	{
		if (fullTypeName == null)
		{
			return "";
		}

		// Get TypeCreator instance via TypeProviderFactory
		TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
		if (typeCreator == null)
		{
			ServoyLog.logWarning("TypeCreator instance not available for " + objectKind + " documentation extraction", null);
			return "";
		}

		// Get the Type object from cache - this has merged _doc.js + .spec documentation
		// Context can be null for global types
		Type webObjectType = typeCreator.findType(null, fullTypeName);
		if (webObjectType == null)
		{
			return "";
		}

		// Get properties/calls for this identifier
		List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
		if (callsOrProperties == null || callsOrProperties.isEmpty())
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		String identifierName = node.toString();

		// Extract documentation for each property/method call
		for (IValueReference action : callsOrProperties)
		{
			String propertyName = action.getName();
			String memberDoc = extractWebObjectMemberDocumentation(webObjectType, propertyName, identifierName);
			if (!memberDoc.isEmpty())
			{
				sb.append(memberDoc).append("\n\n");
			}
		}

		return sb.toString().trim();
	}

	/**
	 * Extracts documentation for a single member (method or property) from a web object Type.
	 * 
	 * @param webObjectType the Type object from TypeCreator
	 * @param memberName the member name (method or property)
	 * @param identifierName the base identifier name
	 * @return formatted documentation string
	 */
	private String extractWebObjectMemberDocumentation(Type webObjectType, String memberName, String identifierName)
	{
		if (webObjectType == null || memberName == null)
		{
			return "";
		}

		// Search for the member in the Type's members
		for (Member member : webObjectType.getMembers())
		{
			if (memberName.equals(member.getName()))
			{
				if (member instanceof Method method)
				{
					return formatWebObjectMethod(method, identifierName);
				}
				if (member instanceof Property property)
				{
					return formatWebObjectProperty(property, identifierName);
				}
			}
		}

		return "";
	}

	/**
	 * Formats a web object method with its documentation.
	 * Method descriptions already contain merged _doc.js + .spec documentation from TypeCreator.
	 * 
	 * @param method the Method object from Type
	 * @param identifierName the base identifier name
	 * @return formatted documentation string
	 */
	private String formatWebObjectMethod(Method method, String identifierName)
	{
		if (method == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();

		// Build signature: identifier.methodName(param1, param2, ...)
		sb.append(identifierName).append(".").append(method.getName()).append("(");

		List<Parameter> params = method.getParameters();
		if (params != null && !params.isEmpty())
		{
			for (int i = 0; i < params.size(); i++)
			{
				Parameter param = params.get(i);
				sb.append(param.getName());
				if (param.getKind() == ParameterKind.OPTIONAL)
				{
					sb.append("?");
				}
				if (i < params.size() - 1)
				{
					sb.append(", ");
				}
			}
		}
		sb.append(")");

		// Add description (already contains merged documentation from _doc.js + .spec)
		String description = method.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			sb.append("\n").append(description);
		}

		return sb.toString();
	}

	/**
	 * Formats a web object property with its documentation.
	 * 
	 * @param property the Property object from Type
	 * @param identifierName the base identifier name
	 * @return formatted documentation string
	 */
	private String formatWebObjectProperty(Property property, String identifierName)
	{
		if (property == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();

		// Build: identifier.propertyName
		sb.append(identifierName).append(".").append(property.getName());

		// Add description
		String description = property.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			sb.append("\n").append(description);
		}

		return sb.toString();
	}

	/**
	 * Locates the IModelElement for a given ReferenceLocation.
	 * Uses visitor pattern to find the element by source range.
	 * Ported from AI Bridge AiBridgeHandler.locateModelElement() lines 478-507.
	 * 
	 * @param location the reference location
	 * @return IModelElement or null if not found
	 */
	private IModelElement locateModelElement(ReferenceLocation location)
	{
		if (location == null)
		{
			return null;
		}

		ISourceModule module = location.getSourceModule();
		if (module != null)
		{
			try
			{
				ScriptModelUtil.reconcile(module);
				module.accept(new ModelElementVisitor(location.getNameStart(), location.getNameEnd()));
			}
			catch (ModelException e)
			{
				if (DLTKCore.DEBUG)
				{
					ServoyLog.logError("Error locating model element", e);
				}
			}
			catch (ModelElementFound found)
			{
				return found.element;
			}
		}
		return null;
	}

	/**
	 * Exception used to short-circuit visitor traversal when element is found.
	 */
	private static class ModelElementFound extends RuntimeException
	{
		final IModelElement element;

		public ModelElementFound(IModelElement element)
		{
			this.element = element;
		}
	}

	/**
	 * Visitor that searches for IModelElement by source range.
	 */
	private static class ModelElementVisitor implements IModelElementVisitor
	{
		private final int nameStart;
		private final int nameEnd;

		public ModelElementVisitor(int nameStart, int nameEnd)
		{
			this.nameStart = nameStart;
			this.nameEnd = nameEnd;
		}

		@Override
		public boolean visit(IModelElement element)
		{
			if (element instanceof IMember member)
			{
				try
				{
					ISourceRange range = member.getNameRange();
					if (range != null)
					{
						if (range.getOffset() == nameStart && range.getLength() == nameEnd - nameStart)
						{
							throw new ModelElementFound(element);
						}
					}
				}
				catch (ModelException e)
				{
					// Continue visiting
				}
			}
			return true;
		}
	}

	/**
	 * Check if an identifier is in the filter list.
	 * Supports nested identifier matching by extracting the base identifier from the filter.
	 * 
	 * Examples:
	 * - Filter "databaseManager.getFoundSet" → base "databaseManager" → matches identifier "databaseManager"
	 * - Filter "foundset.loadAllRecords" → base "foundset" → matches identifier "foundset"
	 * - Filter "plugins.dialogs.showInfoDialog" → base "plugins.dialogs" → matches identifier "plugins.dialogs"
	 * - Filter "JSEvent" → base "JSEvent" → matches identifier "JSEvent" (exact match)
	 * 
	 * @param filterIdentifiers the filter array
	 * @param identifierName the identifier to check
	 * @return true if identifier should be included
	 */
	private boolean containsIdentifier(String[] filterIdentifiers, String identifierName)
	{
		if (filterIdentifiers != null && identifierName != null)
		{
			for (String filter : filterIdentifiers)
			{
				if (filter != null)
				{
					// Exact match (e.g., "JSEvent" matches "JSEvent")
					if (filter.equals(identifierName))
					{
						return true;
					}

					// Extract base identifier from filter (everything before last dot)
					int lastDotIndex = filter.lastIndexOf('.');
					if (lastDotIndex > 0)
					{
						String baseIdentifier = filter.substring(0, lastDotIndex);
						// Match if extracted base equals the identifier
						// e.g., "databaseManager.getFoundSet" → base "databaseManager" matches identifier "databaseManager"
						if (baseIdentifier.equals(identifierName))
						{
							return true;
						}
					}

					// Fallback: nested match using startsWith
					// e.g., "databaseManager.getFoundSet" matches "databaseManager"
					// This handles edge cases where the above logic might not catch
					if (filter.startsWith(identifierName + "."))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Resolves the type of an identifier in a JavaScript file.
	 * This is a standalone method used by CodeAnalysisTools.
	 * 
	 * @param identifier the identifier name to resolve
	 * @param file the file containing the identifier
	 * @return formatted type information string
	 */
	public String resolveIdentifierType(String identifier, IFile file)
	{
		if (identifier == null || identifier.isBlank() || file == null || !file.exists())
		{
			return "Error: Invalid identifier or file";
		}

		String filePath = file.getFullPath().toString();

		try
		{
			String fileContent = readWorkspaceFile(filePath);
			if (fileContent == null)
			{
				return "Error: Could not read file: " + filePath;
			}

			IDocument document = new Document(fileContent);
			int offset = findIdentifierOffset(fileContent, identifier);
			if (offset == -1)
			{
				return "Error: Identifier '" + identifier + "' not found in file: " + filePath;
			}

			int lineNumber = document.getLineOfOffset(offset);
			SelectionResult selectedElements = getModelElements(filePath, offset);

			if (selectedElements != null)
			{
				return formatTypeInfo(selectedElements, identifier, filePath, lineNumber + 1, fileContent, offset);
			}

			return "Error: No type information available for identifier '" + identifier + "'";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error resolving identifier type: " + identifier, e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Read workspace file content.
	 * 
	 * @param filePath the file path
	 * @return file content as string, or null on error
	 */
	private String readWorkspaceFile(String filePath)
	{
		if (filePath == null)
		{
			return null;
		}

		if (filePath.startsWith("L/"))
		{
			filePath = filePath.substring(2);
		}

		try
		{
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));

			if (file != null && file.exists())
			{
				try (java.io.InputStream is = file.getContents())
				{
					return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error reading file: " + filePath, e);
		}
		return null;
	}

	/**
	 * Find offset of identifier in source code.
	 * Tries multiple strategies: declaration, usage, then fallback to first occurrence.
	 * 
	 * @param source the source code
	 * @param identifier the identifier to find
	 * @return offset of identifier, or -1 if not found
	 */
	private int findIdentifierOffset(String source, String identifier)
	{
		if (source == null || identifier == null)
		{
			return -1;
		}

		// Strategy 1: Find in variable declaration: var identifier = ...
		String pattern1Str = "\\bvar\\s+(" + java.util.regex.Pattern.quote(identifier) + ")\\b";
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(pattern1Str);
		java.util.regex.Matcher matcher = pattern.matcher(source);
		if (matcher.find())
		{
			return matcher.start(1);
		}

		// Strategy 2: Find in usage: identifier.something or identifier(
		String pattern2Str = "\\b(" + java.util.regex.Pattern.quote(identifier) + ")\\s*[.({]";
		pattern = java.util.regex.Pattern.compile(pattern2Str);
		matcher = pattern.matcher(source);
		if (matcher.find())
		{
			return matcher.start(1);
		}

		// Strategy 3: Fallback - just find first occurrence with word boundary check
		int index = source.indexOf(identifier);
		if (index >= 0)
		{
			boolean beforeCheck = (index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1)));
			boolean afterCheck = (index + identifier.length() >= source.length() ||
				!Character.isJavaIdentifierPart(source.charAt(index + identifier.length())));

			if (beforeCheck && afterCheck)
			{
				return index;
			}
		}

		return -1;
	}

	/**
	 * Format focused type information from SelectionResult.
	 * Returns concise type details, not full code context.
	 * 
	 * @param selectedElements the selection result
	 * @param identifier the identifier name
	 * @param filePath the file path
	 * @param lineNumber the line number (1-based)
	 * @param fileContent the file content
	 * @param offset the character offset
	 * @return formatted type information string
	 */
	private String formatTypeInfo(SelectionResult selectedElements, String identifier, String filePath, int lineNumber,
		String fileContent, int offset)
	{
		if (selectedElements == null)
		{
			return "Error: No selection result available";
		}

		StringBuilder result = new StringBuilder();
		result.append("=== TYPE RESOLUTION ===\n\n");
		result.append("IDENTIFIER: ").append(identifier).append("\n");

		// Extract from model elements (LocalVariable, etc.)
		for (IModelElement element : selectedElements.modelElements)
		{
			if (element.getElementName().equals(identifier))
			{
				if (element instanceof ILocalVariable localVariable)
				{
					String type = localVariable.getType();
					if (type != null && !type.isBlank())
					{
						result.append("TYPE: ").append(type).append("\n");
						result.append("SOURCE: Local variable\n");
						result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
						return result.toString();
					}
				}

				if (element instanceof IMethod method)
				{
					result.append("TYPE: Function\n");
					result.append("SOURCE: Method declaration\n");
					result.append("PARAMETERS: (");
					try
					{
						result.append(java.util.Arrays.stream(method.getParameters())
							.map(p -> p.getName() + ":" + p.getType())
							.collect(java.util.stream.Collectors.joining(", ")));
					}
					catch (Exception e)
					{
						result.append("unknown");
					}
					result.append(")\n");
					result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
					return result.toString();
				}

				// Other model element types - try JSDoc fallback before returning
				String jsDocType = extractJSDocType(fileContent, offset);
				if (jsDocType != null)
				{
					result.append("TYPE: ").append(jsDocType).append("\n");
					result.append("SOURCE: JSDoc @type annotation\n");
					result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
					return result.toString();
				}

				result.append("TYPE: ").append(element.getClass().getSimpleName()).append("\n");
				result.append("SOURCE: Model element\n");
				result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return result.toString();
			}
		}

		// Extract from foreign elements (Servoy API types)
		for (IRElement element : selectedElements.foreignElements)
		{
			String type = element.getName();
			if (type != null && !type.isBlank())
			{
				result.append("TYPE: ").append(type).append("\n");
				result.append("SOURCE: Servoy API type\n");

				if (element instanceof IRMethod method)
				{
					result.append("PARAMETERS: (");
					result.append(method.getParameters().stream()
						.map(p -> p.getName() + ":" + p.getType())
						.collect(java.util.stream.Collectors.joining(", ")));
					result.append(")\n");
				}

				result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return result.toString();
			}
		}

		// Fallback: Try to extract type from JSDoc @type annotation
		String jsDocType = extractJSDocType(fileContent, offset);
		if (jsDocType != null)
		{
			result.append("TYPE: ").append(jsDocType).append("\n");
			result.append("SOURCE: JSDoc @type annotation\n");
			result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
			return result.toString();
		}

		return "Error: Could not resolve type for identifier '" + identifier + "' in file: " + filePath + " at line " + lineNumber;
	}

	/**
	 * Extract type from JSDoc @type annotation in preceding text.
	 * Looks backwards from offset to find @type {TypeName} pattern.
	 * IMPORTANT: Verifies that @type belongs to THIS identifier, not a previous one.
	 * 
	 * @param fileContent the file content
	 * @param offset the character offset
	 * @return extracted type name, or null if not found
	 */
	private String extractJSDocType(String fileContent, int offset)
	{
		if (offset <= 0 || fileContent == null)
		{
			return null;
		}

		int lookbackStart = Math.max(0, offset - 300);
		String precedingText = fileContent.substring(lookbackStart, offset);

		int jsDocStart = precedingText.lastIndexOf("/**");
		if (jsDocStart == -1)
		{
			return null;
		}

		String jsDocBlock = precedingText.substring(jsDocStart);

		// Check if there's another variable declaration between the JSDoc and our identifier
		java.util.regex.Pattern varPattern = java.util.regex.Pattern.compile("\\*/\\s*\\n\\s*var\\s+\\w+");
		java.util.regex.Matcher varMatcher = varPattern.matcher(jsDocBlock);
		if (varMatcher.find())
		{
			return null;
		}

		// Look for @type {TypeName} pattern in the JSDoc block
		java.util.regex.Pattern jsDocPattern = java.util.regex.Pattern.compile("@type\\s*\\{([^}]+)\\}");
		java.util.regex.Matcher jsDocMatcher = jsDocPattern.matcher(jsDocBlock);
		if (jsDocMatcher.find())
		{
			return jsDocMatcher.group(1).trim();
		}

		return null;
	}

	/**
	 * Get model elements at a specific location using DLTK selection engine.
	 * 
	 * @param filePath the file path
	 * @param characterOffset the character offset
	 * @return SelectionResult containing model and foreign elements
	 */
	public SelectionResult getModelElements(String filePath, int characterOffset)
	{
		if (filePath == null)
		{
			return null;
		}

		try
		{
			IFile file = getFile(filePath);
			if (file == null || !file.exists())
			{
				return null;
			}

			String fileContent = readWorkspaceFile(filePath);
			if (fileContent == null)
			{
				return null;
			}

			ISourceModule sourceModule = (ISourceModule)DLTKCore.create(file);
			IModuleSource module = new ModuleSource(filePath, sourceModule, fileContent);
			JavaScriptSelectionEngine2 selectionEngine = new JavaScriptSelectionEngine2();
			int offset = ParserService.getInstance().skipWhitespaceForward(fileContent, characterOffset);
			SelectionResult selectedElements = new SelectionResult();
			Thread thread = new Thread(() -> {
				try
				{
					selectionEngine.setRequestor(new ISelectionRequestor()
					{
						@Override
						public void acceptModelElement(IModelElement element)
						{
							if (element != null)
							{
								selectedElements.modelElements.add(element);
							}
						}

						@Override
						public void acceptForeignElement(Object element)
						{
							if (element instanceof IRElement ire)
							{
								selectedElements.foreignElements.add(ire);
							}
						}

						@Override
						public void acceptElement(Object element, ISourceRange range)
						{
							if (element instanceof IModelElement modelElement)
							{
								acceptModelElement(modelElement);
							}
							else
							{
								acceptForeignElement(element);
							}
						}
					});

					selectionEngine.select(module, offset, offset);
				}
				catch (Exception e)
				{
					ServoyLog.logError("Error selecting model elements: " + e.getMessage(), e);
				}
			}, "Searching model elements -" + file.getName());

			thread.start();
			thread.join();
			return selectedElements;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error computing model elements: " + filePath, e);
			return null;
		}
	}

	/**
	 * Get IFile from file path.
	 * 
	 * @param filePath the file path
	 * @return IFile or null if not found
	 */
	private IFile getFile(String filePath)
	{
		if (filePath == null)
		{
			return null;
		}

		if (filePath.startsWith("L/"))
		{
			filePath = filePath.substring(2);
		}

		IFile file = ResourcesPlugin.getWorkspace().getRoot()
			.getFile(new Path(filePath));

		if (file != null && file.exists())
		{
			return file;
		}
		return null;
	}

	/**
	 * DTO for holding selection results from DLTK selection engine.
	 */
	public static class SelectionResult
	{
		public List<IModelElement> modelElements = new ArrayList<>();
		public List<IRElement> foreignElements = new ArrayList<>();
	}
}
