package com.servoy.eclipse.servoypilot.services;

import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.ClientVersion;
import com.servoy.j2db.IForm;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.StaticContentSpecLoader;

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
		if (properties == null || properties.isEmpty()) return;

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
					case "width":
						if (propValue instanceof Number)
						{
							form.setWidth(((Number)propValue).intValue());
						}
						break;

					case "height":
						if (propValue instanceof Number)
						{
							form.setHeight(((Number)propValue).intValue());
						}
						break;

					case "minWidth":
					case "useMinWidth":
						if (propValue instanceof Boolean)
						{
							form.setUseMinWidth((Boolean)propValue);
						}
						break;

					case "minHeight":
					case "useMinHeight":
						if (propValue instanceof Boolean)
						{
							form.setUseMinHeight((Boolean)propValue);
						}
						break;

					case "dataSource":
						if (propValue != null)
						{
							form.setDataSource(propValue.toString());
						}
						break;

					case "showInMenu":
						if (propValue instanceof Boolean)
						{
							form.setShowInMenu((Boolean)propValue);
						}
						break;

					case "styleName":
						if (propValue != null)
						{
							form.setStyleName(propValue.toString());
						}
						break;

					case "navigatorID":
					case "navigator":
						if (propValue != null)
						{
							form.setNavigatorID(propValue.toString());
						}
						break;

					case "initialSort":
						if (propValue != null)
						{
							form.setInitialSort(propValue.toString());
						}
						break;

					case "deprecated":
						if (propValue != null)
						{
							form.setDeprecated(propValue.toString());
						}
						break;

					case "namedFoundSet":
						if (propValue != null)
						{
							form.setNamedFoundSet(propValue.toString());
						}
						break;

					case "selectionMode":
						if (propValue != null)
						{
							int mode = parseSelectionMode(propValue.toString());
							form.setSelectionMode(mode);
						}
						break;

					case "styleClass":
						if (propValue != null)
						{
							form.setStyleClass(propValue.toString());
						}
						break;

					case "titleText":
						if (propValue != null)
						{
							form.setTitleText(propValue.toString());
						}
						break;

					case "transparent":
						if (propValue instanceof Boolean)
						{
							form.setTransparent((Boolean)propValue);
						}
						break;

					case "scrollbars":
						if (propValue != null)
						{
							int scrollbars = parseScrollbars(propValue.toString());
							form.setScrollbars(scrollbars);
						}
						break;

					default:
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

		try
		{
			if (ClientVersion.getMajorVersion() >= 2025 && ClientVersion.getMiddleVersion() >= 12)
			{
				Method setExtendsID = Form.class.getMethod("setExtendsID", String.class);
				setExtendsID.invoke(form, parentForm.getUUID().toString());
			}
			else
			{
				Method setExtendsID = Form.class.getMethod("setExtendsID", int.class);
				Method getID = Form.class.getMethod("getID");
				setExtendsID.invoke(form, getID.invoke(parentForm));
			}
			servoyProject.saveEditingSolutionNodes(new IPersist[] { servoyProject.getEditingSolution() }, true);
		}
		catch (NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException e)
		{
			ServoyLog.logError("Error setExtendsID on solution of form " + form  , e);
		}
	}

	/**
	 * Applies events to a form.
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

		ServoyLog.logInfo("[FormService] Applying form events: " + form.getName());

		String eventValue = null;
		String eventName = null;

		try
		{
			for (Map.Entry<String, String> entry : events.entrySet())
			{
				eventName = entry.getKey();
				eventValue = entry.getValue();

				if (eventValue != null && !eventValue.trim().isEmpty())
				{
					String methodUUID = resolveMethodUUID(form, eventValue.trim());
					if (methodUUID != null)
					{
						applyEventMethod(form, eventName, methodUUID);
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new RepositoryException("Error setting event '" + eventName + "': " + e.getMessage());
		}
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

		ServoyLog.logInfo("[FormService] Method not found: " + methodName);
		return null;
	}

	/**
	 * Applies a specific event method to the form.
	 * 
	 * @param form The form to update
	 * @param eventName The event name
	 * @param methodUUID The method UUID
	 */
	private static void applyEventMethod(Form form, String eventName, String methodUUID)
	{
		switch (eventName)
		{
			case "onLoad":
				form.setOnLoadMethodID(methodUUID);
				break;
			case "onUnLoad":
				form.setOnUnLoadMethodID(methodUUID);
				break;
			case "onShow":
				form.setOnShowMethodID(methodUUID);
				break;
			case "onHide":
				form.setOnHideMethodID(methodUUID);
				break;
			case "onBeforeHide":
				form.setOnBeforeHideMethodID(methodUUID);
				break;
			case "onRecordSelection":
				form.setOnRecordSelectionMethodID(methodUUID);
				break;
			case "onBeforeRecordSelection":
				form.setOnBeforeRecordSelectionMethodID(methodUUID);
				break;
			case "onRecordEditStart":
				form.setOnRecordEditStartMethodID(methodUUID);
				break;
			case "onRecordEditStop":
				form.setOnRecordEditStopMethodID(methodUUID);
				break;
			case "onElementDataChange":
				form.setOnElementDataChangeMethodID(methodUUID);
				break;
			case "onElementFocusGained":
				form.setOnElementFocusGainedMethodID(methodUUID);
				break;
			case "onElementFocusLost":
				form.setOnElementFocusLostMethodID(methodUUID);
				break;
			case "onResize":
				form.setOnResizeMethodID(methodUUID);
				break;
			case "onSort":
				form.setOnSortCmdMethodID(methodUUID);
				break;
			default:
				ServoyLog.logInfo("[FormService] Unknown event: " + eventName);
				break;
		}
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

