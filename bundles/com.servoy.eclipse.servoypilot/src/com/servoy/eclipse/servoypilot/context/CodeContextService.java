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
package com.servoy.eclipse.servoypilot.context;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IMember;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.IModelElementVisitor;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.core.ScriptModelUtil;
import org.eclipse.dltk.internal.javascript.ti.TypeInferencer2;
import org.eclipse.dltk.javascript.ast.JSNode;
import org.eclipse.dltk.javascript.ast.Script;
import org.eclipse.dltk.javascript.parser.JavaScriptParserUtil;
import org.eclipse.dltk.javascript.typeinference.IValueReference;
import org.eclipse.dltk.javascript.typeinference.ReferenceLocation;
import org.eclipse.dltk.javascript.typeinfo.IRClassType;
import org.eclipse.dltk.javascript.typeinfo.IRType;
import org.eclipse.dltk.javascript.typeinfo.JSTypeSet;
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.ParameterKind;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.dltk.javascript.typeinfo.model.Type;
import org.eclipse.dltk.javascript.ui.scriptdoc.ScriptdocContentAccess;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.model.util.ServoyLog;
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
				// Debug header
				System.out.println("\n=== CODE CONTEXT EXTRACTION ===");
				System.out.println("File: " + selectionInfo.getFilePath());
				System.out.println("Selection: offset=" + selectionInfo.getOffset() + ", length=" + selectionInfo.getLength());
				System.out.println("--------------------------------\n");

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
							selectionInfo.getLength());

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

					// Print simple list of all identifiers with classification
					System.out.println("Detected identifiers:");
					for (IdentifierContext ctx : identifierContexts)
					{
						System.out.println("  " + ctx.getName() + " -> " + ctx.getKind() + " (" + ctx.getTypeName() + ")");
					}

					System.out.println("--------------------------------");
					System.out.println("Total: " + identifierContexts.size() + " identifiers");
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
							System.out.println(member.getElementName() + " -> SOLUTION_FUNCTION\n" + formattedDoc + "\n");
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
	 * @param typeName the API type name (e.g., "Plugins", "Application")
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

		ITypedScriptObject scriptObject = ScriptObjectRegistry.getScriptObjectByName(typeName);
		if (scriptObject != null && scriptObject.getObjectDocumentation() != null)
		{
			List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
			if (callsOrProperties != null && !callsOrProperties.isEmpty())
			{
				IObjectDocumentation docFile = scriptObject.getObjectDocumentation();
				StringBuilder sb = new StringBuilder();
				String identifierName = node.toString();

				for (IValueReference action : callsOrProperties)
				{
					String propertyName = action.getName();
					String funcDoc = extractFunctionDocumentation(docFile, propertyName, identifierName);
					if (!funcDoc.isEmpty())
					{
						System.out.println(identifierName + "." + propertyName + " -> SERVOY_API\n" + funcDoc + "\n");
						sb.append(funcDoc).append("\n\n");
					}
				}

				return sb.toString().trim();
			}
		}
		return "";
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
				System.out.println(identifierName + "." + propertyName + " -> WEB_" + objectKind.toUpperCase() + "\n" + memberDoc + "\n");
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
}
