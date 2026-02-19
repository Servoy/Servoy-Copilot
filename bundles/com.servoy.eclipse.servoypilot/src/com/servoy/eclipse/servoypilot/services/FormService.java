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

import java.awt.Dimension;
import java.util.Iterator;
import java.util.Map;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.core.ai.AiBridge;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.IForm;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;

/**
 * Service for form operations - create, update, and query forms.
 * Migrated from knowledgebase.mcp FormToolHandler.
 * 
 * Provides reusable business logic for FormTools.
 */
public class FormService
{
	// Selection mode constants
	private static final int SELECTION_MODE_DEFAULT = IForm.SELECTION_MODE_DEFAULT;
	private static final int SELECTION_MODE_SINGLE = IForm.SELECTION_MODE_SINGLE;
	private static final int SELECTION_MODE_MULTI = IForm.SELECTION_MODE_MULTI;

	// Scrollbars constants (bitset)
	private static final int SCROLLBARS_HORIZONTAL = 1;
	private static final int SCROLLBARS_VERTICAL = 2;
	private static final int SCROLLBARS_BOTH = 3;

	/**
	 * Creates a new form in a specific project (active solution or module).
	 * 
	 * @param targetProject The project where form will be created
	 * @param name Form name
	 * @param width Form width
	 * @param height Form height
	 * @param style Form style ('css' or 'responsive')
	 * @param dataSource Database datasource (optional)
	 * @return The created form
	 * @throws RepositoryException If creation fails
	 */
	public static Form createFormInProject(ServoyProject targetProject, String name, int width, int height,
		String style, String dataSource) throws RepositoryException
	{
		ServoyLog.logInfo("[FormService] Creating form in " + targetProject.getProject().getName() + ": " + name);

		if (targetProject == null)
		{
			throw new RepositoryException("Target project is null");
		}

		if (targetProject.getEditingSolution() == null)
		{
			throw new RepositoryException("Cannot get editing solution from target project");
		}

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		IValidateName validator = servoyModel.getNameValidator();

		// Create the form
		Dimension size = new Dimension(width, height);
		Form form = targetProject.getEditingSolution().createNewForm(validator, null, name, dataSource, true, size);

		boolean isResponsive = "responsive".equalsIgnoreCase(style);

		if (!isResponsive)
		{
			// Create default CSS-positioned form
			form.createNewPart(Part.BODY, height);
			form.setUseCssPosition(Boolean.TRUE);
		}
		else
		{
			// Create responsive layout form
			form.setResponsiveLayout(true);
		}

		// Save the form
		targetProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form }, true);

		ServoyLog.logInfo("[FormService] Form created and saved in " + targetProject.getProject().getName() + ": " + name);

		return form;
	}

	/**
	 * Applies properties to a form.
	 * 
	 * @param form The form to update
	 * @param properties Map of properties to update
	 * @throws RepositoryException If update fails
	 */
	public static void applyFormProperties(Form form, Map<String, Object> properties) throws RepositoryException
	{
		if (properties == null || properties.isEmpty())
		{
			return;
		}

		ServoyLog.logInfo("[FormService] Applying form properties: " + form.getName());

		Object propValue = null;
		String propName = null;

		try
		{
			for (Map.Entry<String, Object> entry : properties.entrySet())
			{
				propName = entry.getKey();
				propValue = entry.getValue();

				switch (propName)
				{
					case "width" :
						if (propValue instanceof Number)
						{
							form.setWidth(((Number)propValue).intValue());
						}
						break;

					case "height" :
						if (propValue instanceof Number)
						{
							form.setHeight(((Number)propValue).intValue());
						}
						break;

					case "minWidth" :
					case "useMinWidth" :
						if (propValue instanceof Boolean)
						{
							form.setUseMinWidth((Boolean)propValue);
						}
						break;

					case "minHeight" :
					case "useMinHeight" :
						if (propValue instanceof Boolean)
						{
							form.setUseMinHeight((Boolean)propValue);
						}
						break;

					case "dataSource" :
						if (propValue != null)
						{
							form.setDataSource(propValue.toString());
						}
						break;

					case "showInMenu" :
						if (propValue instanceof Boolean)
						{
							form.setShowInMenu((Boolean)propValue);
						}
						break;

					case "styleName" :
						if (propValue != null)
						{
							form.setStyleName(propValue.toString());
						}
						break;

					case "initialSort" :
						if (propValue != null)
						{
							form.setInitialSort(propValue.toString());
						}
						break;

					case "deprecated" :
						if (propValue != null)
						{
							form.setDeprecated(propValue.toString());
						}
						break;

					case "namedFoundSet" :
						if (propValue != null)
						{
							form.setNamedFoundSet(propValue.toString());
						}
						break;

					case "selectionMode" :
						if (propValue != null)
						{
							int mode = parseSelectionMode(propValue.toString());
							form.setSelectionMode(mode);
						}
						break;

					case "styleClass" :
						if (propValue != null)
						{
							form.setStyleClass(propValue.toString());
						}
						break;

					case "titleText" :
						if (propValue != null)
						{
							form.setTitleText(propValue.toString());
						}
						break;

					case "transparent" :
						if (propValue instanceof Boolean)
						{
							form.setTransparent((Boolean)propValue);
						}
						break;

					case "scrollbars" :
						if (propValue != null)
						{
							int scrollbars = parseScrollbars(propValue.toString());
							form.setScrollbars(scrollbars);
						}
						break;

					default :
						ServoyLog.logInfo("[FormService] Unknown property: " + propName);
						break;
				}
			}
		}
		catch (Exception e)
		{
			throw new RepositoryException("Error setting property '" + propName + "': " + e.getMessage());
		}
	}

	/**
	 * Sets the parent form (inheritance).
	 * 
	 * @param form The form to update
	 * @param parentFormName Parent form name
	 * @param servoyProject The project
	 * @throws RepositoryException If parent form not found
	 */
	public static void setFormParent(Form form, String parentFormName, ServoyProject servoyProject) throws RepositoryException
	{
		Form parentForm = servoyProject.getEditingSolution().getForm(parentFormName);

		if (parentForm == null)
		{
			throw new RepositoryException("Parent form '" + parentFormName + "' not found");
		}

		AiBridge.setFormExtendsID(form, parentForm);
		servoyProject.saveEditingSolutionNodes(new IPersist[] { servoyProject.getEditingSolution() }, true);

		ServoyLog.logInfo("[FormService] Set parent form '" + parentFormName + "' for form '" + form.getName() + "'");
	}

	/**
	 * Applies events to a form, auto-creating methods if they don't exist.
	 * 
	 * @param form The form to update
	 * @param events Map of events to update (event name -> method name)
	 * @param servoyProject The project containing the form
	 * @throws RepositoryException If update fails
	 */
	public static void applyFormEvents(Form form, Map<String, String> events, ServoyProject servoyProject) throws RepositoryException
	{
		if (events == null || events.isEmpty())
		{
			return;
		}

		String eventValue = null;
		String eventName = null;

		try
		{
			IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
			IValidateName validator = servoyModel.getNameValidator();

			for (Map.Entry<String, String> entry : events.entrySet())
			{
				eventName = entry.getKey();
				eventValue = entry.getValue();

				if (eventValue != null && !eventValue.trim().isEmpty())
				{
					String methodName = eventValue.trim();
					String methodUUID = resolveMethodUUID(form, methodName);

					if (methodUUID == null)
					{
						ScriptMethod method = createFormMethod(form, methodName, eventName, validator);
						methodUUID = method.getUUID().toString();
					}

					if (methodUUID != null)
					{
						AiBridge.applyEventMethod(form, eventName, methodUUID);
					}
				}
			}
			servoyProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
		}
		catch (Exception e)
		{
			throw new RepositoryException("Error setting event '" + eventName + "': " + e.getMessage());
		}
	}

	/**
	 * Creates a new script method on a form with skeleton code based on event type.
	 * 
	 * @param form The form to add method to
	 * @param methodName The method name
	 * @param eventName The event name (for generating appropriate skeleton code)
	 * @param validator Name validator
	 * @return The created ScriptMethod
	 * @throws RepositoryException If creation fails
	 */
	private static ScriptMethod createFormMethod(Form form, String methodName, String eventName, IValidateName validator)
		throws RepositoryException
	{
		ScriptMethod method = form.createNewScriptMethod(validator, methodName);

		String skeleton = getMethodSkeletonCode(eventName, methodName);
		method.setDeclaration(skeleton);

		ServoyLog.logInfo("[FormService] Created method '" + methodName + "' with skeleton code for event '" + eventName + "'");

		return method;
	}

	/**
	 * Generates skeleton code for a method based on event type.
	 * 
	 * @param eventName The event name
	 * @param methodName The method name
	 * @return JavaScript skeleton code as string
	 */
	private static String getMethodSkeletonCode(String eventName, String methodName)
	{
		if (eventName != null)
		{
			switch (eventName)
			{
				case "onLoad" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Initialize form data and setup\n");

				case "onShow" :
					return generateMethodDeclaration(methodName, "firstShow, event",
						"// TODO: Refresh display data\n");

				case "onHide" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Cleanup or save pending changes\n");

				case "onBeforeHide" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Validate before hiding, return false to prevent\n\treturn true;\n");

				case "onRecordSelection" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Handle record selection\n");

				case "onBeforeRecordSelection" :
					return generateMethodDeclaration(methodName, "oldSelection, newSelection, event",
						"// TODO: Validate selection change, return false to prevent\n\treturn true;\n");

				case "onRecordEditStart" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Handle edit start\n\treturn true;\n");

				case "onRecordEditStop" :
					return generateMethodDeclaration(methodName, "record, event",
						"// TODO: Validate record before save, return false to prevent\n\treturn true;\n");

				case "onElementDataChange" :
					return generateMethodDeclaration(methodName, "oldValue, newValue, event",
						"// TODO: Validate data change, return false to reject\n\treturn true;\n");

				case "onElementFocusGained" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Handle focus gained\n\treturn true;\n");

				case "onElementFocusLost" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Handle focus lost\n\treturn true;\n");

				case "onResize" :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Handle form resize\n");

				case "onSort" :
					return generateMethodDeclaration(methodName, "dataProviderID, asc, event",
						"// TODO: Handle sort command\n");

				default :
					return generateMethodDeclaration(methodName, "event",
						"// TODO: Implement " + eventName + " handler\n");
			}
		}

		return generateMethodDeclaration(methodName, "event", "// TODO: Implement method\n");
	}

	/**
	 * Generates a JavaScript method declaration with JSDoc comment.
	 * 
	 * @param methodName The method name
	 * @param params The parameter list
	 * @param body The method body
	 * @return Complete method declaration string
	 */
	private static String generateMethodDeclaration(String methodName, String params, String body)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("/**\n");

		// Generate @param for each parameter based on the params string
		if (params != null && !params.trim().isEmpty())
		{
			String[] paramArray = params.split(",");
			for (String param : paramArray)
			{
				String trimmedParam = param.trim();
				if (!trimmedParam.isEmpty())
				{
					// Determine the type based on parameter name conventions
					String paramType = getParameterType(trimmedParam);
					sb.append(" * @param {").append(paramType).append("} ").append(trimmedParam).append("\n");
				}
			}
		}

		sb.append(" */\n");
		sb.append("function ").append(methodName).append("(").append(params).append(") {\n");
		sb.append("\t").append(body);
		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * Determines the JSDoc type for a parameter based on its name.
	 * 
	 * @param paramName The parameter name
	 * @return The JSDoc type string
	 */
	private static String getParameterType(String paramName)
	{
		if (paramName.equals("event"))
		{
			return "JSEvent";
		}
		if (paramName.equals("record"))
		{
			return "JSRecord";
		}
		if (paramName.equals("oldValue") || paramName.equals("newValue"))
		{
			return "Object";
		}
		if (paramName.equals("oldSelection") || paramName.equals("newSelection"))
		{
			return "JSRecord|JSRecord[]";
		}
		if (paramName.equals("firstShow"))
		{
			return "Boolean";
		}
		if (paramName.equals("asc"))
		{
			return "Boolean";
		}
		if (paramName.equals("dataProviderID"))
		{
			return "String";
		}
		// Default to generic type
		return "*";
	}

	/**
	 * Resolves a method name to its UUID.
	 * 
	 * @param form The form containing the method
	 * @param methodName The method name
	 * @return Method UUID string or null if not found
	 */
	private static String resolveMethodUUID(Form form, String methodName)
	{
		if (methodName == null || methodName.trim().isEmpty())
		{
			return null;
		}

		Iterator<ScriptMethod> methods = form.getScriptMethods(false);
		while (methods.hasNext())
		{
			ScriptMethod method = methods.next();
			if (methodName.equals(method.getName()))
			{
				return method.getUUID().toString();
			}
		}

		return null;
	}

	/**
	 * Parses selection mode string to constant.
	 * 
	 * @param value The selection mode value ("default", "single", "multi")
	 * @return Selection mode constant
	 */
	private static int parseSelectionMode(String value)
	{
		if (value != null)
		{
			String normalized = value.toLowerCase().trim();
			if ("single".equals(normalized))
			{
				return SELECTION_MODE_SINGLE;
			}
			if ("multi".equals(normalized))
			{
				return SELECTION_MODE_MULTI;
			}
		}
		return SELECTION_MODE_DEFAULT;
	}

	/**
	 * Parses scrollbars string to bitset value.
	 * 
	 * @param value The scrollbars value ("horizontal", "vertical", "both")
	 * @return Scrollbars bitset constant
	 */
	private static int parseScrollbars(String value)
	{
		if (value != null)
		{
			String normalized = value.toLowerCase().trim();
			if ("horizontal".equals(normalized))
			{
				return SCROLLBARS_HORIZONTAL;
			}
			if ("vertical".equals(normalized))
			{
				return SCROLLBARS_VERTICAL;
			}
			if ("both".equals(normalized))
			{
				return SCROLLBARS_BOTH;
			}
		}
		return 0;
	}
}

