/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.awt.Dimension;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IDataProvider;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.ValueList;

import com.servoy.base.query.IBaseSQLCondition;
import com.servoy.base.query.IQueryConstants;

/**
 * Service for creating Servoy solution artifacts (forms, relations, valuelists).
 * <p>
 * Ported from servoypilot's {@code FormService}, {@code RelationService}, {@code ValueListService}.
 * No UI operations (no EditorUtil calls). No AiBridge dependency — event/extends logic inlined.
 * </p>
 */
@Creatable
public class ServoyArtifactCreationService
{
	// -------------------------------------------------------------------------
	// Form creation
	// -------------------------------------------------------------------------

	public String createForm(String name, String style, int width, int height, String dataSource,
		String extendsForm, Map<String, String> events) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = servoyModel.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			throw new RepositoryException("No active Servoy solution project found");

		if (name == null || name.isBlank())
			throw new RepositoryException("Form name is required");

		// Check if form already exists
		if (project.getEditingSolution().getForm(name) != null)
			return "Form '" + name + "' already exists in the active solution.";

		IValidateName validator = servoyModel.getNameValidator();
		Dimension size = new Dimension(width > 0 ? width : 640, height > 0 ? height : 480);
		boolean isResponsive = "responsive".equalsIgnoreCase(style);

		Form form = project.getEditingSolution().createNewForm(validator, null, name, dataSource, true, size);

		if (!isResponsive)
		{
			form.createNewPart(Part.BODY, size.height);
			form.setUseCssPosition(Boolean.TRUE);
		}
		else
		{
			form.setResponsiveLayout(true);
		}

		// Set parent form if specified
		if (extendsForm != null && !extendsForm.isBlank())
		{
			Form parentForm = project.getEditingSolution().getForm(extendsForm);
			if (parentForm != null)
			{
				form.setExtendsForm(parentForm);
				form.setExtendsID(parentForm.getUUID().toString());
			}
			else
			{
				ServoyLog.logWarning("Parent form '" + extendsForm + "' not found — skipping inheritance", null);
			}
		}

		// Apply events if specified
		if (events != null && !events.isEmpty())
		{
			for (Map.Entry<String, String> entry : events.entrySet())
			{
				String eventName = entry.getKey();
				String methodName = entry.getValue();
				if (methodName != null && !methodName.isBlank())
				{
					String methodUUID = resolveOrCreateMethod(form, methodName, eventName, validator);
					if (methodUUID != null) applyEventMethod(form, eventName, methodUUID);
				}
			}
		}

		project.saveEditingSolutionNodes(new IPersist[] { form }, true);

		StringBuilder result = new StringBuilder();
		result.append("Form '").append(name).append("' created successfully");
		result.append(" (").append(isResponsive ? "responsive" : "css").append(", ").append(size.width).append("x").append(size.height).append(")");
		if (dataSource != null) result.append("\n  DataSource: ").append(dataSource);
		if (extendsForm != null && !extendsForm.isBlank()) result.append("\n  Extends: ").append(extendsForm);
		return result.toString();
	}

	// -------------------------------------------------------------------------
	// Relation creation
	// -------------------------------------------------------------------------

	public String createRelation(String name, String primaryDataSource, String foreignDataSource,
		String primaryColumn, String foreignColumn, String joinType) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = servoyModel.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			throw new RepositoryException("No active Servoy solution project found");

		if (name == null || name.isBlank())
			throw new RepositoryException("Relation name is required");
		if (primaryDataSource == null || primaryDataSource.isBlank())
			throw new RepositoryException("primaryDataSource is required");
		if (foreignDataSource == null || foreignDataSource.isBlank())
			throw new RepositoryException("foreignDataSource is required");

		// Auto-correct datasource format
		primaryDataSource = correctDataSource(primaryDataSource);
		foreignDataSource = correctDataSource(foreignDataSource);

		int jt = "inner".equalsIgnoreCase(joinType) ? IQueryConstants.INNER_JOIN : IQueryConstants.LEFT_OUTER_JOIN;

		Relation relation = project.getEditingSolution().createNewRelation(
			servoyModel.getNameValidator(), name, primaryDataSource, foreignDataSource, jt);
		relation.setAllowCreationRelatedRecords(true);

		// Add column mapping if both columns provided
		if (primaryColumn != null && !primaryColumn.isBlank() && foreignColumn != null && !foreignColumn.isBlank())
		{
			try
			{
				ITable primaryTable = ServoyModelFinder.getServoyModel().getDataSourceManager().getDataSource(primaryDataSource);
				ITable foreignTable = ServoyModelFinder.getServoyModel().getDataSourceManager().getDataSource(foreignDataSource);
				if (primaryTable != null && foreignTable != null)
				{
					Column primaryCol = primaryTable.getColumn(primaryColumn);
					Column foreignCol = foreignTable.getColumn(foreignColumn);
					if (primaryCol != null && foreignCol != null)
					{
						relation.createNewRelationItems(
							new IDataProvider[] { primaryCol },
							new int[] { IBaseSQLCondition.EQUALS_OPERATOR },
							new Column[] { foreignCol });
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logWarning("Could not add column mapping: " + e.getMessage(), e);
			}
		}

		project.saveEditingSolutionNodes(new IPersist[] { relation }, true);

		StringBuilder result = new StringBuilder();
		result.append("Relation '").append(name).append("' created successfully");
		result.append("\n  Primary: ").append(primaryDataSource);
		result.append("\n  Foreign: ").append(foreignDataSource);
		if (primaryColumn != null && foreignColumn != null)
			result.append("\n  Mapping: ").append(primaryColumn).append(" = ").append(foreignColumn);
		result.append("\n  Join: ").append(jt == IQueryConstants.INNER_JOIN ? "INNER" : "LEFT OUTER");
		return result.toString();
	}

	// -------------------------------------------------------------------------
	// ValueList creation
	// -------------------------------------------------------------------------

	public String createValueList(String name, String type, String customValues, String dataSource,
		String relationName, String displayColumn, String returnColumn) throws RepositoryException
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = servoyModel.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			throw new RepositoryException("No active Servoy solution project found");

		if (name == null || name.isBlank())
			throw new RepositoryException("ValueList name is required");

		ValueList vl = project.getEditingSolution().createNewValueList(servoyModel.getNameValidator(), name);

		String vlType = "custom";
		if ("database".equalsIgnoreCase(type) || (dataSource != null && !dataSource.isBlank()))
		{
			String ds = correctDataSource(dataSource);
			vl.setValueListType(1); // DATABASE_VALUES
			vl.setDataSource(ds);
			if (displayColumn != null && !displayColumn.isBlank()) vl.setDataProviderID1(displayColumn);
			if (returnColumn != null && !returnColumn.isBlank() && !returnColumn.equals(displayColumn))
				vl.setDataProviderID2(returnColumn);
			vlType = "database";
		}
		else if ("related".equalsIgnoreCase(type) || (relationName != null && !relationName.isBlank()))
		{
			vl.setValueListType(1); // DATABASE_VALUES
			vl.setRelationName(relationName);
			if (displayColumn != null && !displayColumn.isBlank()) vl.setDataProviderID1(displayColumn);
			if (returnColumn != null && !returnColumn.isBlank() && !returnColumn.equals(displayColumn))
				vl.setDataProviderID2(returnColumn);
			vlType = "related";
		}
		else if ("global_method".equalsIgnoreCase(type))
		{
			vl.setValueListType(4); // GLOBAL_METHOD_VALUES
			if (customValues != null) vl.setCustomValues(customValues);
			vlType = "global_method";
		}
		else
		{
			// Default: custom values
			vl.setValueListType(0); // CUSTOM_VALUES
			if (customValues != null && !customValues.isBlank()) vl.setCustomValues(customValues);
			vlType = "custom";
		}

		project.saveEditingSolutionNodes(new IPersist[] { vl }, true);

		return "ValueList '" + name + "' created successfully (type: " + vlType + ")";
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private String correctDataSource(String ds) throws RepositoryException
	{
		if (ds == null || ds.isBlank()) throw new RepositoryException("Datasource cannot be empty");
		if (!ds.startsWith("db:/"))
		{
			if (ds.contains("/")) return "db:/" + ds;
			throw new RepositoryException("Invalid datasource format: '" + ds + "'. Use 'db:/server_name/table_name'");
		}
		return ds;
	}

	private String resolveOrCreateMethod(Form form, String methodName, String eventName, IValidateName validator)
		throws RepositoryException
	{
		// Check if method already exists
		Iterator<ScriptMethod> methods = form.getScriptMethods(false);
		while (methods.hasNext())
		{
			ScriptMethod m = methods.next();
			if (methodName.equals(m.getName())) return m.getUUID().toString();
		}

		// Create new method with skeleton
		ScriptMethod method = form.createNewScriptMethod(validator, methodName);
		String skeleton = "/**\n * @param {JSEvent} event\n */\nfunction " + methodName + "(event) {\n\t// TODO: implement\n}\n";
		method.setDeclaration(skeleton);
		return method.getUUID().toString();
	}

	private void applyEventMethod(Form form, String eventName, String methodUUID)
	{
		switch (eventName)
		{
			case "onLoad": form.setOnLoadMethodID(methodUUID); break;
			case "onUnLoad": form.setOnUnLoadMethodID(methodUUID); break;
			case "onShow": form.setOnShowMethodID(methodUUID); break;
			case "onHide": form.setOnHideMethodID(methodUUID); break;
			case "onBeforeHide": form.setOnBeforeHideMethodID(methodUUID); break;
			case "onRecordSelection": form.setOnRecordSelectionMethodID(methodUUID); break;
			case "onRecordEditStart": form.setOnRecordEditStartMethodID(methodUUID); break;
			case "onRecordEditStop": form.setOnRecordEditStopMethodID(methodUUID); break;
			case "onElementDataChange": form.setOnElementDataChangeMethodID(methodUUID); break;
			case "onResize": form.setOnResizeMethodID(methodUUID); break;
			case "onSort": form.setOnSortCmdMethodID(methodUUID); break;
			default: ServoyLog.logInfo("Unknown event: " + eventName); break;
		}
	}
}
