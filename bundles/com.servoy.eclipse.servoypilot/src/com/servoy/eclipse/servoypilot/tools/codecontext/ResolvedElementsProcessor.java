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
package com.servoy.eclipse.servoypilot.tools.codecontext;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.core.IField;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.IType;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.internal.core.SourceRefElement;
import org.eclipse.dltk.javascript.typeinfo.IRConstructor;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMember;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.dltk.javascript.typeinfo.IRProperty;
import org.eclipse.dltk.javascript.typeinfo.IRRecordMember;
import org.eclipse.dltk.javascript.typeinfo.IRTypeDeclaration;
import org.eclipse.dltk.javascript.typeinfo.IRVariable;
import org.eclipse.dltk.javascript.typeinfo.model.Element;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.sablo.specification.WebObjectSpecification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyResourcesProject;
import com.servoy.eclipse.model.repository.DataModelManager;
import com.servoy.eclipse.model.repository.SolutionSerializer;
import com.servoy.eclipse.servoypilot.services.CodeContextService.SelectionResult;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IFormElement;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.PersistHelper;
import com.servoy.j2db.util.UUID;
import com.servoy.j2db.util.Utils;

/**
 * Processes SelectionResult (model + foreign elements) into structured JSON.
 * Separated from CodeContextToolsHelper for testability.
 */
public class ResolvedElementsProcessor
{
	private static final ResolvedElementsProcessor INSTANCE = new ResolvedElementsProcessor();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ResolvedElementsProcessor()
	{
	}

	public static ResolvedElementsProcessor getInstance()
	{
		return INSTANCE;
	}

	public String toJson(String filePath, SelectionResult selectedElements) throws JsonProcessingException, ModelException, Exception
	{
		ObjectNode result = MAPPER.createObjectNode();
		ArrayNode resolvedElements = result.putArray("resolvedElements");
		processModelElements(filePath, resolvedElements, selectedElements);
		processForeignElements(resolvedElements, selectedElements);
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
	}

	public void processModelElements(String filePath, ArrayNode resolvedElements, SelectionResult selectedElements)
		throws ModelException, Exception, BadLocationException
	{
		for (IModelElement element : selectedElements.modelElements)
		{
			ObjectNode node = MAPPER.createObjectNode();
			node.put("name", element.getElementName());
			node.put("source", "model");

			if (element instanceof ILocalVariable localVariable)
			{
				node.put("kind", "localVariable");
				if (localVariable.getType() != null)
				{
					node.put("type", localVariable.getType());
				}
			}
			else if (element instanceof IField field)
			{
				node.put("kind", "field");
				if (field.getType() != null)
				{
					node.put("type", field.getType());
				}
				if (field.getDeclaringType() != null)
				{
					node.put("declaringType", field.getDeclaringType().getElementName());
				}
			}
			else if (element instanceof IType type)
			{
				node.put("kind", "type");
				String[] superClasses = type.getSuperClasses();
				if (superClasses != null && superClasses.length > 0)
				{
					ArrayNode supers = node.putArray("superClasses");
					for (String sc : superClasses)
					{
						supers.add(sc);
					}
				}
				if (type.getDeclaringType() != null)
				{
					node.put("declaringType", type.getDeclaringType().getElementName());
				}
			}
			else if (element instanceof IMethod method)
			{
				node.put("kind", "method");
				ArrayNode params = node.putArray("parameters");
				for (var p : method.getParameters())
				{
					ObjectNode param = params.addObject();
					param.put("name", p.getName());
					if (p.getType() != null)
					{
						param.put("type", p.getType());
					}
				}
				if (method.getDeclaringType() != null)
				{
					node.put("declaringType", method.getDeclaringType().getElementName());
				}
			}
			if (filePath != null && !filePath.replace("L/", "/").equals(element.getPath().toString()))
			{
				node.put("file", element.getPath().toString());
				if (element instanceof SourceRefElement sourceRefElement)
				{
					int offset = sourceRefElement.getSourceRange().getOffset();
					String content = CodeContextToolsHelper.getInstance().readWorkspaceFile(element.getPath().toString());
					IDocument doc = new Document(content);
					int line = doc.getLineOfOffset(offset);
					if (line >= 0)
					{
						node.put("line", line + 1);
					}
					node.put("offset", offset);
				}
			}
			resolvedElements.add(node);
		}
	}

	public void processForeignElements(ArrayNode resolvedElements, SelectionResult selectedElements)
	{
		for (IRElement element : selectedElements.foreignElements)
		{
			ObjectNode node = MAPPER.createObjectNode();
			node.put("name", element.getName());
			node.put("source", "typeinfo");
			node.put("deprecated", element.isDeprecated());

			if (element instanceof IRTypeDeclaration typeDecl)
			{
				node.put("kind", "type");
				node.put("typeKind", typeDecl.getKind().toString());
				if (typeDecl.isGeneric())
				{
					node.put("generic", true);
				}
				if (typeDecl.isParameterized() && typeDecl.getActualTypeArguments() != null && !typeDecl.getActualTypeArguments().isEmpty())
				{
					ArrayNode typeArgs = node.putArray("typeArguments");
					typeDecl.getActualTypeArguments().forEach(t -> typeArgs.add(t.getName()));
				}
				if (typeDecl.getSuperType() != null)
				{
					node.put("superType", typeDecl.getSuperType().getName());
				}
				if (typeDecl.getTraits() != null && !typeDecl.getTraits().isEmpty())
				{
					ArrayNode traits = node.putArray("traits");
					typeDecl.getTraits().forEach(t -> traits.add(t.getName()));
				}
				if (typeDecl.getMembers() != null && !typeDecl.getMembers().isEmpty())
				{
					ArrayNode members = node.putArray("members");
					for (IRMember m : typeDecl.getMembers())
					{
						ObjectNode memberNode = members.addObject();
						memberNode.put("name", m.getName());
						memberNode.put("kind", m instanceof IRMethod ? "method" : "property");
						if (m.getType() != null)
						{
							memberNode.put("type", m.getType().toString());
						}
					}
				}
				if (typeDecl.getConstructors() != null && !typeDecl.getConstructors().isEmpty())
				{
					ArrayNode constructors = node.putArray("constructors");
					for (IRConstructor c : typeDecl.getConstructors())
					{
						ObjectNode ctorNode = constructors.addObject();
						ctorNode.put("name", c.getName());
						ArrayNode params = ctorNode.putArray("parameters");
						c.getParameters().forEach(p -> {
							ObjectNode param = params.addObject();
							param.put("name", p.getName());
							param.put("type", String.valueOf(p.getType()));
						});
					}
				}
				if (typeDecl.getStaticConstructor() != null)
				{
					IRConstructor sc = typeDecl.getStaticConstructor();
					ObjectNode scNode = node.putObject("staticConstructor");
					scNode.put("name", sc.getName());
					ArrayNode params = scNode.putArray("parameters");
					sc.getParameters().forEach(p -> {
						ObjectNode param = params.addObject();
						param.put("name", p.getName());
						param.put("type", String.valueOf(p.getType()));
					});
				}
			}
			else if (element instanceof IRMember member)
			{
				if (member.getType() != null)
				{
					node.put("type", member.getType().toString());
				}
				node.put("static", member.isStatic());
				if (member.getVisibility() != null)
				{
					node.put("visibility", member.getVisibility().toString());
				}
				if (element instanceof IRConstructor constructor)
				{
					node.put("kind", "constructor");
					ArrayNode params = node.putArray("parameters");
					constructor.getParameters().forEach(p -> {
						ObjectNode param = params.addObject();
						param.put("name", p.getName());
						param.put("type", String.valueOf(p.getType()));
					});
				}
				else if (element instanceof IRMethod method)
				{
					node.put("kind", "method");
					node.put("abstract", method.isAbstract());
					node.put("generic", method.isGeneric());
					node.put("typed", method.isTyped());
					ArrayNode params = node.putArray("parameters");
					method.getParameters().forEach(p -> {
						ObjectNode param = params.addObject();
						param.put("name", p.getName());
						param.put("type", String.valueOf(p.getType()));
					});
				}
				else if (element instanceof IRProperty property)
				{
					node.put("kind", "property");
					node.put("readOnly", property.isReadOnly());
				}
				else if (element instanceof IRRecordMember recordMember)
				{
					node.put("kind", "recordMember");
					node.put("optional", recordMember.isOptional());
				}
				else if (element instanceof IRVariable)
				{
					node.put("kind", "variable");
				}
				if (member.getDeclaringType() != null)
				{
					node.put("declaringType", member.getDeclaringType().getName());
				}
			}

			if (element.getSource() instanceof Element elementSource)
			{
				processElementSource(node, elementSource);
			}
			resolvedElements.add(node);
		}
	}

	public void processElementSource(ObjectNode node, Element elementSource)
	{
		String description = elementSource.getDescription();
		if (description != null && !description.isBlank())
		{
			node.put("description", description);
		}

		Object resource = elementSource.getAttribute(TypeCreator.RESOURCE);
		if (resource == null)
		{
			resource = elementSource.getAttribute(TypeCreator.LAZY_VALUECOLLECTION);
		}

		if (resource instanceof Form frm)
		{
			resource = extractFormInfo(node, frm);
		}
		else if (resource instanceof ValueList valuelist)
		{
			resource = extractValuelistContext(node, valuelist);
		}
		else if (resource instanceof Relation relation)
		{
			resource = extractRelationContext(node, relation);
		}
		else if (resource instanceof WebObjectSpecification spec)
		{
			extractWebComponentContext(node, spec);
			return;
		}
		else if (resource instanceof IFormElement formElement)
		{
			extractFormElementContext(node, formElement);
			return;
		}

		if (resource instanceof String resourcePath)
		{
			IPath path = Path.fromPortableString(resourcePath.replace('\\', '/'));
			IFile sourceFile = path.isAbsolute()
				? ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path)
				: ResourcesPlugin.getWorkspace().getRoot().getFile(path);
			if (sourceFile != null && sourceFile.exists())
			{
				resource = sourceFile;
			}
		}

		if (resource instanceof IFile file)
		{
			node.put("file", file.getFullPath().toString());
			ArrayNode hints = getOrCreateHints(node);
			hints.add("Use readPersistFile tool to read this file");
		}
	}

	private void extractWebComponentContext(ObjectNode node, WebObjectSpecification spec)
	{
		node.put("resourceKind", "webComponent");
		node.put("componentName", spec.getName());
		if (spec.getDisplayName() != null)
		{
			node.put("displayName", spec.getDisplayName());
		}
		if (spec.getCategoryName() != null)
		{
			node.put("category", spec.getCategoryName());
		}
	}

	private void extractFormElementContext(ObjectNode node, IFormElement formElement)
	{
		node.put("resourceKind", "formElement");
		node.put("elementName", formElement.getName());
		if (formElement instanceof com.servoy.j2db.persistence.WebComponent webComponent)
		{
			String typeName = webComponent.getTypeName();
			if (typeName != null)
			{
				node.put("componentType", typeName);
			}
		}
		IPersist parent = formElement.getParent();
		if (parent instanceof Form parentForm)
		{
			node.put("form", parentForm.getName());
			String scriptPath = SolutionSerializer.getScriptPath(parentForm, false);
			if (scriptPath != null)
			{
				node.put("formFile", scriptPath);
			}
			if (parentForm.getDataSource() != null)
			{
				node.put("formDataSource", parentForm.getDataSource());
			}
		}
	}

	private String extractFormInfo(ObjectNode node, Form frm)
	{
		node.put("resourceKind", "form");
		if (frm.getDataSource() != null)
		{
			node.put("dataSource", frm.getDataSource());
		}
		if (Boolean.TRUE.equals(frm.isFormComponent()))
		{
			node.put("formComponent", true);
		}
		IPersist superForm = PersistHelper.getSuperPersist(frm);
		if (superForm != null)
		{
			node.put("parentForm", SolutionSerializer.getScriptPath(superForm, false));
			ArrayNode hints = getOrCreateHints(node);
			hints.add("Check the parent form for more context");
		}
		return SolutionSerializer.getScriptPath(frm, false);
	}

	private String extractValuelistContext(ObjectNode node, ValueList valuelist)
	{
		node.put("resourceKind", "valuelist");
		node.put("valuelistName", valuelist.getName());
		int valueListType = valuelist.getValueListType();
		String valueListTypeName = switch (valueListType)
		{
			case 1 -> "TABLE_VALUES";
			case 2 -> "GLOBAL_METHOD";
			case 4 -> "GLOBAL_METHOD_VALUES";
			default -> "CUSTOM_VALUES";
		};
		node.put("valueListType", valueListTypeName);
		if (valuelist.getDataSource() != null)
		{
			node.put("dataSource", valuelist.getDataSource());
		}
		if (valuelist.getRelationName() != null)
		{
			node.put("relation", valuelist.getRelationName());
		}
		if (valuelist.getDisplayValueType() != 0)
		{
			node.put("displayValueType", valuelist.getDisplayValueType());
		}
		if (valuelist.getRealValueType() != 0)
		{
			node.put("realValueType", valuelist.getRealValueType());
		}
		if (valuelist.getCustomValues() != null && !valuelist.getCustomValues().isEmpty())
		{
			if (valuelist.getCustomValues().split("\n").length == 1)
			{
				UUID uuid = Utils.getAsUUID(valuelist.getCustomValues().split("\n")[0], false);
				if (uuid != null)
				{
					IPersist persist = ServoyModelFinder.getServoyModel().getFlattenedSolution().searchPersist(uuid);
					if (persist instanceof ScriptMethod scriptMethod)
					{
						ObjectNode methodRef = node.putObject("globalMethodValuelist");
						methodRef.put("name", scriptMethod.getName());
						methodRef.put("file", SolutionSerializer.getFileName(persist, false));
						methodRef.put("lineOffset", scriptMethod.getLineNumberOffset());
					}
					else
					{
						node.put("customValues", valuelist.getCustomValues());
					}
				}
				else
				{
					node.put("customValues", valuelist.getCustomValues());
				}
			}
			else
			{
				node.put("customValues", valuelist.getCustomValues());
			}
		}
		Pair<String, String> filePathPair = SolutionSerializer.getFilePath(valuelist, false);
		return filePathPair.getLeft() + filePathPair.getRight();
	}

	private String extractRelationContext(ObjectNode node, Relation relation)
	{
		node.put("resourceKind", "relation");
		node.put("relationName", relation.getName());
		if (relation.getPrimaryDataSource() != null)
		{
			node.put("primaryDataSource", relation.getPrimaryDataSource());
		}
		if (relation.getForeignDataSource() != null)
		{
			node.put("foreignDataSource", relation.getForeignDataSource());
		}
		int joinType = relation.getJoinType();
		node.put("joinType", joinType == 1 ? "LEFT_OUTER_JOIN" : "INNER_JOIN");
		node.put("allowCreationRelatedRecords", relation.getAllowCreationRelatedRecords());
		if (relation.getDeleteRelatedRecords())
		{
			node.put("deleteRelatedRecords", true);
		}
		ArrayNode hints = getOrCreateHints(node);
		hints.add("Use readPersistFile tool to read the .rel file and .dbi files to check record properties");

		ServoyResourcesProject activeResourcesProject = ServoyModelFinder.getServoyModel().getActiveResourcesProject();
		String resourceProject = activeResourcesProject.getProject().getName();
		DataModelManager dm = ServoyModelFinder.getServoyModel().getDataModelManager();
		if (dm != null && resourceProject != null)
		{
			if (dm.getDBIFile(relation.getPrimaryDataSource()) != null)
			{
				node.put("primaryDbiFile",
					resourceProject + "/" + dm.getDBIFile(relation.getPrimaryDataSource()).getProjectRelativePath().toString());
			}
			if (dm.getDBIFile(relation.getForeignDataSource()) != null)
			{
				node.put("foreignDbiFile",
					resourceProject + "/" + dm.getDBIFile(relation.getForeignDataSource()).getProjectRelativePath().toString());
			}
		}
		Pair<String, String> filePathPair = SolutionSerializer.getFilePath(relation, false);
		return filePathPair.getLeft() + filePathPair.getRight();
	}

	private ArrayNode getOrCreateHints(ObjectNode node)
	{
		if (node.has("hints"))
		{
			return (ArrayNode)node.get("hints");
		}
		return node.putArray("hints");
	}
}
