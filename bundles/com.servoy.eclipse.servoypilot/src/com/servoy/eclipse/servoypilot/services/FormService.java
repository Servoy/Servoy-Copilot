package com.servoy.eclipse.servoypilot.services;

import java.awt.Dimension;
import java.util.Map;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.RepositoryException;

/**
 * Service for form operations - create, update, and query forms.
 * Migrated from knowledgebase.mcp FormToolHandler.
 * 
 * Provides reusable business logic for FormTools.
 */
public class FormService
{
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

		form.setExtendsID(parentForm.getUUID().toString());
	}
}
