/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

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
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.developer.mcp.context.IdentifierCollectingVisitor;
import com.servoy.eclipse.developer.mcp.dto.CodeContext;
import com.servoy.eclipse.developer.mcp.dto.IdentifierContext;
import com.servoy.eclipse.developer.mcp.dto.IdentifierContext.IdentifierKind;
import com.servoy.eclipse.developer.mcp.dto.SelectionInfo;
import com.servoy.eclipse.model.util.ServoyLog;
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
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.CodeContextService}.
 * Orchestrates AST parsing, type inference, and Servoy API documentation extraction.
 * </p>
 * <p>
 * <b>Note:</b> Uses {@code org.eclipse.dltk.internal.javascript.ti.TypeInferencer2}
 * which is in an {@code x-internal} package. Works in Eclipse development mode;
 * for Tycho production builds, target platform must grant access.
 * </p>
 */
@Creatable
@SuppressWarnings("restriction")
public class CodeContextService
{
	/**
	 * Extracts code context for a selection with optional identifier filtering.
	 *
	 * @param selectionInfo     the selection to analyze
	 * @param filterIdentifiers optional array of identifier names to extract documentation for
	 *                          ({@code null} = extract all)
	 * @return {@link CodeContext} with extracted information, or error context if parsing fails
	 */
	public CodeContext getCodeContext(SelectionInfo selectionInfo, String[] filterIdentifiers)
	{
		if (selectionInfo == null) return CodeContext.error(null, "No selection provided");
		if (!selectionInfo.hasSelection()) return CodeContext.empty(selectionInfo);

		try
		{
			ISourceModule module = selectionInfo.getSourceModule();
			if (module == null) return CodeContext.success(selectionInfo, null);

			final Script script = JavaScriptParserUtil.parse(module, null);
			if (script == null) return CodeContext.error(selectionInfo, "Failed to parse JavaScript file");

			TypeInferencer2 inferencer = new TypeInferencer2();
			final IdentifierCollectingVisitor collector = new IdentifierCollectingVisitor(
				inferencer, selectionInfo.getOffset(), selectionInfo.getLength());

			inferencer.setVisitor(collector);
			inferencer.setModelElement(module);
			inferencer.doInferencing(script);

			Map<String, IdentifierContext> uniqueIdentifiers = new HashMap<>();

			collector.identifiers.forEach((node, pair) -> {
				String identifierName = pair.getRight();
				boolean shouldExtract = (filterIdentifiers == null) || containsIdentifier(filterIdentifiers, identifierName);
				if (shouldExtract)
				{
					IdentifierContext identifierContext = extractIdentifierContext(node, pair, collector);
					if (identifierContext != null)
					{
						String key = identifierContext.getName() + ":" + identifierContext.getTypeName();
						uniqueIdentifiers.putIfAbsent(key, identifierContext);
					}
				}
			});

			List<IdentifierContext> identifierContexts = new ArrayList<>(uniqueIdentifiers.values());
			return CodeContext.success(selectionInfo, identifierContexts);
		}
		catch (Exception e)
		{
			ServoyLog.logError("CodeContextService: error extracting code context", e);
			return CodeContext.error(selectionInfo, "Error analyzing code: " + e.getMessage());
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private IdentifierContext extractIdentifierContext(JSNode node, Pair<IValueReference, String> pair, IdentifierCollectingVisitor collector)
	{
		JSTypeSet types = pair.getLeft().getTypes();
		JSTypeSet declaredTypes = pair.getLeft().getDeclaredTypes();

		String typeName = null;
		if (types.size() > 0)
		{
			IRType irType = types.iterator().next();
			if (irType instanceof IRClassType clsType) irType = clsType.toItemType();
			typeName = irType.getName();
		}

		if (declaredTypes.size() > 0)
		{
			IRType irType = declaredTypes.iterator().next();
			if (irType instanceof IRClassType clsType) irType = clsType.toItemType();
			typeName = irType.getName();
		}

		if (typeName != null)
		{
			IdentifierKind kind = determineIdentifierKind(typeName);
			String documentation = "";

			if (kind == IdentifierKind.SOLUTION_FUNCTION)
				documentation = extractSolutionFunctionDocumentation(pair.getLeft());
			else if (kind == IdentifierKind.SERVOY_API)
				documentation = extractServoyApiDocumentation(typeName, node, collector);
			else if (kind == IdentifierKind.WEB_COMPONENT)
				documentation = extractWebComponentDocumentation(typeName, node, collector);
			else if (kind == IdentifierKind.WEB_SERVICE)
				documentation = extractWebServiceDocumentation(typeName, node, collector);

			return IdentifierContext.create(pair.getRight(), typeName, documentation, kind);
		}
		return null;
	}

	private IdentifierKind determineIdentifierKind(String typeName)
	{
		if (typeName.startsWith("RuntimeWebComponent<")) return IdentifierKind.WEB_COMPONENT;
		if (typeName.startsWith("WebService<")) return IdentifierKind.WEB_SERVICE;
		if ("Function".equals(typeName)) return IdentifierKind.SOLUTION_FUNCTION;
		return IdentifierKind.SERVOY_API;
	}

	private String extractSolutionFunctionDocumentation(IValueReference valueReference)
	{
		if (valueReference == null) return "";
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
							return formatSolutionFunctionDoc(doc);
					}
				}
				catch (ModelException | IOException e)
				{
					ServoyLog.logError("CodeContextService: error extracting solution function documentation", e);
				}
			}
		}
		return "";
	}

	private String formatSolutionFunctionDoc(String doc)
	{
		if (doc == null || doc.trim().isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (String line : doc.split("\n"))
		{
			String trimmed = line.trim();
			if (!trimmed.startsWith("@properties=") && !trimmed.isBlank())
				sb.append(trimmed).append("\n");
		}
		return sb.toString().trim();
	}

	private String extractServoyApiDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || typeName.trim().isEmpty()) return "";

		// PRIMARY PATH: ScriptObjectRegistry (XML-based documentation)
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
					String funcDoc = extractFunctionDocumentation(docFile, action.getName(), identifierName);
					if (!funcDoc.isEmpty()) sb.append(funcDoc).append("\n\n");
				}
				String result = sb.toString().trim();
				if (!result.isEmpty()) return result;
			}
		}

		// FALLBACK PATH: TypeCreator
		return extractServoyApiDocumentationFromTypeCreator(typeName, node, collector);
	}

	private String extractServoyApiDocumentationFromTypeCreator(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null) return "";

		TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
		if (typeCreator == null)
		{
			ServoyLog.logWarning("CodeContextService: TypeCreator not available for type: " + typeName, null);
			return "";
		}

		Type servoyType = typeCreator.findType(null, typeName);
		if (servoyType == null)
		{
			String scriptingName = mapClassNameToScriptingName(typeName);
			if (scriptingName != null && !scriptingName.equals(typeName))
				servoyType = typeCreator.findType(null, scriptingName);
		}
		if (servoyType == null) return "";

		List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
		if (callsOrProperties == null || callsOrProperties.isEmpty()) return "";

		StringBuilder sb = new StringBuilder();
		String identifierName = node.toString();
		for (IValueReference action : callsOrProperties)
		{
			String memberDoc = extractWebObjectMemberDocumentation(servoyType, action.getName(), identifierName);
			if (!memberDoc.isEmpty()) sb.append(memberDoc).append("\n\n");
		}
		return sb.toString().trim();
	}

	private String mapClassNameToScriptingName(String className)
	{
		if (className == null) return null;
		return switch (className)
		{
			case "JSApplication" -> "application";
			case "JSDatabaseManager" -> "databaseManager";
			case "JSSecurity" -> "security";
			case "JSI18N" -> "i18n";
			case "JSUtils" -> "utils";
			case "JSForm" -> "controller";
			case "JSEventsManager" -> "eventsManager";
			case "JSSolutionModel" -> "solutionModel";
			default -> null;
		};
	}

	private String extractFunctionDocumentation(IObjectDocumentation docFile, String propertyName, String identifierName)
	{
		if (docFile == null || propertyName == null) return "";

		List<IFunctionDocumentation> functions = docFile.getFunctions().stream()
			.filter(f -> propertyName.equals(f.getMainName()))
			.sorted((f1, f2) -> f1.getArguments().size() - f2.getArguments().size())
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
			IFunctionDocumentation function = functions.get(functions.size() - 1);
			String signature = extractSignature(function);
			int minParams = functions.get(0).getArgumentsTypes().length;
			String description = formatFunctionSignatureAndDocs(function, minParams, identifierName);
			return identifierName + "." + signature + "\n" + description;
		}
		return "";
	}

	private String extractSignature(IFunctionDocumentation function)
	{
		if (function == null) return "";
		String fullSignature = function.getFullJSTranslatedSignature(true, false);
		if (fullSignature != null && fullSignature.contains(" "))
		{
			String[] parts = fullSignature.split(" ", 2);
			if (parts.length > 1) return parts[1];
		}
		return function.getMainName() + "()";
	}

	private String formatFunctionSignatureAndDocs(IFunctionDocumentation fdoc, int mandatoryParams, String identifierName)
	{
		if (fdoc == null) return "";

		StringBuilder sb = new StringBuilder();
		Class<?> returnType = fdoc.getReturnedType();
		String returnDescription = fdoc.getReturnDescription();
		LinkedHashMap<String, IParameterDocumentation> parameters = fdoc.getArguments();
		String tooltip = fdoc.getDescription(ClientSupport.ng);

		if (fdoc.isDeprecated())
		{
			sb.append("@deprecated");
			String deprecatedText = fdoc.getDeprecatedText();
			if (deprecatedText != null && !deprecatedText.trim().isEmpty())
				sb.append(" ").append(deprecatedText);
			sb.append("\n");
		}

		if (tooltip != null && !tooltip.isEmpty()) sb.append(tooltip);

		String sample = fdoc.getSample(ClientSupport.ng);
		if (sample != null && !sample.trim().isEmpty())
			sb.append("\n@sample\n").append(sample);

		if (parameters != null && !parameters.isEmpty())
		{
			int paramCount = 0;
			for (IParameterDocumentation parameter : parameters.values())
			{
				sb.append("\n@param {");
				sb.append(DocumentationUtil.getJavaToJSTypeTranslator().translateJavaClassToJSTypeName(parameter.getType()));
				sb.append("} ");
				if (paramCount >= mandatoryParams) sb.append("[");
				sb.append(parameter.getName());
				if (paramCount >= mandatoryParams) sb.append("] optional");
				sb.append(" ");
				if (parameter.getDescription() != null) sb.append(parameter.getDescription());
				paramCount++;
			}
		}

		if (returnType != null && returnType != Void.class && returnType != void.class
			&& fdoc.getType() == IFunctionDocumentation.TYPE_FUNCTION)
		{
			sb.append("\n@return {");
			sb.append(XMLScriptObjectAdapter.getReturnTypeString(returnType));
			sb.append("} ");
			if (returnDescription != null) sb.append(returnDescription);
		}
		return sb.toString();
	}

	private String extractWebComponentDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || !typeName.startsWith("RuntimeWebComponent<")) return "";
		return extractWebObjectDocumentationFromTypeCreator(typeName, node, collector, "component");
	}

	private String extractWebServiceDocumentation(String typeName, JSNode node, IdentifierCollectingVisitor collector)
	{
		if (typeName == null || !typeName.startsWith("WebService<")) return "";
		return extractWebObjectDocumentationFromTypeCreator(typeName, node, collector, "service");
	}

	private String extractWebObjectDocumentationFromTypeCreator(String fullTypeName, JSNode node, IdentifierCollectingVisitor collector, String objectKind)
	{
		if (fullTypeName == null) return "";

		TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
		if (typeCreator == null)
		{
			ServoyLog.logWarning("CodeContextService: TypeCreator not available for " + objectKind + " documentation", null);
			return "";
		}

		Type webObjectType = typeCreator.findType(null, fullTypeName);
		if (webObjectType == null) return "";

		List<IValueReference> callsOrProperties = collector.propertiesOrCalls.get(node);
		if (callsOrProperties == null || callsOrProperties.isEmpty()) return "";

		StringBuilder sb = new StringBuilder();
		String identifierName = node.toString();
		for (IValueReference action : callsOrProperties)
		{
			String memberDoc = extractWebObjectMemberDocumentation(webObjectType, action.getName(), identifierName);
			if (!memberDoc.isEmpty()) sb.append(memberDoc).append("\n\n");
		}
		return sb.toString().trim();
	}

	private String extractWebObjectMemberDocumentation(Type webObjectType, String memberName, String identifierName)
	{
		if (webObjectType == null || memberName == null) return "";
		for (Member member : webObjectType.getMembers())
		{
			if (memberName.equals(member.getName()))
			{
				if (member instanceof Method method) return formatWebObjectMethod(method, identifierName);
				if (member instanceof Property property) return formatWebObjectProperty(property, identifierName);
			}
		}
		return "";
	}

	private String formatWebObjectMethod(Method method, String identifierName)
	{
		if (method == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append(identifierName).append(".").append(method.getName()).append("(");
		List<Parameter> params = method.getParameters();
		if (params != null && !params.isEmpty())
		{
			for (int i = 0; i < params.size(); i++)
			{
				Parameter param = params.get(i);
				sb.append(param.getName());
				if (param.getKind() == ParameterKind.OPTIONAL) sb.append("?");
				if (i < params.size() - 1) sb.append(", ");
			}
		}
		sb.append(")");
		String description = method.getDescription();
		if (description != null && !description.trim().isEmpty()) sb.append("\n").append(description);
		return sb.toString();
	}

	private String formatWebObjectProperty(Property property, String identifierName)
	{
		if (property == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append(identifierName).append(".").append(property.getName());
		String description = property.getDescription();
		if (description != null && !description.trim().isEmpty()) sb.append("\n").append(description);
		return sb.toString();
	}

	private IModelElement locateModelElement(ReferenceLocation location)
	{
		if (location == null) return null;
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
				if (DLTKCore.DEBUG) ServoyLog.logError("CodeContextService: error locating model element", e);
			}
			catch (ModelElementFound found)
			{
				return found.element;
			}
		}
		return null;
	}

	private static class ModelElementFound extends RuntimeException
	{
		private static final long serialVersionUID = 1L;
		final IModelElement element;
		public ModelElementFound(IModelElement element) { this.element = element; }
	}

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
					if (range != null && range.getOffset() == nameStart && range.getLength() == nameEnd - nameStart)
						throw new ModelElementFound(element);
				}
				catch (ModelException e)
				{
					// continue
				}
			}
			return true;
		}
	}

	private boolean containsIdentifier(String[] filterIdentifiers, String identifierName)
	{
		if (filterIdentifiers == null || identifierName == null) return false;
		for (String filter : filterIdentifiers)
		{
			if (filter == null) continue;
			if (filter.equals(identifierName)) return true;

			int lastDotIndex = filter.lastIndexOf('.');
			if (lastDotIndex > 0)
			{
				String baseIdentifier = filter.substring(0, lastDotIndex);
				if (baseIdentifier.equals(identifierName)) return true;
			}
			if (filter.startsWith(identifierName + ".")) return true;
		}
		return false;
	}

	/**
	 * Creates a {@link SelectionInfo} representing the entire content of a file, useful for
	 * tools that work without an active editor selection.
	 */
	public SelectionInfo createSelectionInfoFromFile(IFile file)
	{
		if (file == null || !file.exists()) return null;
		try
		{
			ISourceModule module = (ISourceModule)DLTKCore.create(file);
			if (module == null) return null;
			String source = module.getSource();
			if (source == null) return null;

			int totalLines = source.split("\r\n|\r|\n", -1).length;
			return SelectionInfo.create(
				file.getFullPath().toString(),
				0, source.length(), source, module,
				0, totalLines - 1, true).orElse(null);
		}
		catch (Exception e)
		{
			ServoyLog.logError("CodeContextService: error creating SelectionInfo from file: " + file.getFullPath(), e);
			return null;
		}
	}
}
