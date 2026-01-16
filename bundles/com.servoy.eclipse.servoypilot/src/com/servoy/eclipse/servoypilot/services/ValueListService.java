package com.servoy.eclipse.servoypilot.services;

import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.PersistEncapsulation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ValueList;

/**
 * Service for valuelist operations - create, update, and query valuelists.
 * Migrated from knowledgebase.mcp ValueListService.
 * 
 * Supports all 4 valuelist types:
 * - CUSTOM_VALUES: Fixed list of display/real value pairs
 * - DATABASE_VALUES (TABLE_VALUES): Values from database table
 * - DATABASE_VALUES (RELATED_VALUES): Values from related table via relation
 * - GLOBAL_METHOD_VALUES: Dynamic values from global method
 */
public class ValueListService
{
	/**
	 * Creates a new valuelist in a specific project (active solution or module).
	 * 
	 * @param targetProject The project where valuelist will be created
	 * @param name ValueList name
	 * @param customValues Custom values (for CUSTOM type)
	 * @param dataSource Database datasource (for DATABASE type)
	 * @param relationName Relation name (for RELATED type)
	 * @param globalMethod Global method name (for GLOBAL_METHOD type)
	 * @param displayColumn Display column name
	 * @param returnColumn Return column name
	 * @param properties Optional map of valuelist properties
	 * @return The created valuelist
	 * @throws RepositoryException If creation fails
	 */
	public static ValueList createValueListInProject(ServoyProject targetProject, String name, List<String> customValues, String dataSource,
		String relationName, String globalMethod, String displayColumn, String returnColumn, Map<String, Object> properties) throws RepositoryException
	{
		ServoyLog.logInfo("[ValueListService] Creating valuelist in " + targetProject.getProject().getName() + ": " + name);

		if (targetProject == null)
		{
			throw new RepositoryException("Target project is null");
		}

		if (targetProject.getEditingSolution() == null)
		{
			throw new RepositoryException("Cannot get editing solution from target project");
		}

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();

		// Create the valuelist in target solution
		ValueList valueList = targetProject.getEditingSolution().createNewValueList(servoyModel.getNameValidator(), name);

		// Determine type and configure
		if (globalMethod != null && !globalMethod.trim().isEmpty())
		{
			configureGlobalMethodValueList(valueList, globalMethod);
		}
		else if (relationName != null && !relationName.trim().isEmpty())
		{
			configureRelatedValueList(valueList, relationName, displayColumn, returnColumn);
		}
		else if (dataSource != null && !dataSource.trim().isEmpty())
		{
			configureDatabaseValueList(valueList, dataSource, displayColumn, returnColumn);
		}
		else if (customValues != null && !customValues.isEmpty())
		{
			configureCustomValueList(valueList, customValues);
		}

		// Apply additional properties
		applyValueListProperties(valueList, properties);

		// Save the valuelist synchronously (runAsJob = false)
		targetProject.saveEditingSolutionNodes(new IPersist[] { valueList }, true, false);
		
		// WORKAROUND: ValueListEditor data binding bug - clear changed flag after initialization
		final ValueList valueListRef = valueList;
		Display.getDefault().timerExec(100, () -> {
			valueListRef.clearChanged();
			ServoyLog.logInfo("[ValueListService] Cleared changed flag after editor initialization for: " + valueListRef.getName());
		});
		
		ServoyLog.logInfo("[ValueListService] ValueList created and saved in " + targetProject.getProject().getName() + ": " + name);

		return valueList;
	}

	/**
	 * Updates properties of an existing valuelist.
	 */
	public static void updateValueListProperties(ValueList valueList, Map<String, Object> properties) throws RepositoryException
	{
		if (properties == null || properties.isEmpty())
		{
			return;
		}
		
		ServoyLog.logInfo("[ValueListService] Updating valuelist properties: " + valueList.getName());
		
		applyValueListProperties(valueList, properties);
		
		// Save the valuelist
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();
		servoyProject.saveEditingSolutionNodes(new IPersist[] { valueList }, true);
		
		ServoyLog.logInfo("[ValueListService] ValueList properties updated and saved: " + valueList.getName());
	}
	
	/**
	 * Configures a custom valuelist with fixed values.
	 */
	private static void configureCustomValueList(ValueList valueList, List<String> customValues)
	{
		valueList.setValueListType(0); // CUSTOM_VALUES
		
		StringBuilder customValuesStr = new StringBuilder();
		for (String value : customValues)
		{
			if (value != null && !value.trim().isEmpty())
			{
				if (customValuesStr.length() > 0) customValuesStr.append("\n");
				customValuesStr.append(value.trim());
			}
		}
		valueList.setCustomValues(customValuesStr.toString());
		
		ServoyLog.logInfo("[ValueListService] Configured as CUSTOM_VALUES with " + customValues.size() + " values");
	}
	
	/**
	 * Configures a database valuelist with table values.
	 */
	private static void configureDatabaseValueList(ValueList valueList, String dataSource,
		String displayColumn, String returnColumn) throws RepositoryException
	{
		valueList.setValueListType(1); // DATABASE_VALUES
		
		dataSource = validateAndCorrectDataSource(dataSource);
		valueList.setDataSource(dataSource);
		
		boolean hasDisplayColumn = (displayColumn != null && !displayColumn.trim().isEmpty());
		boolean hasReturnColumn = (returnColumn != null && !returnColumn.trim().isEmpty());
		
		if (hasDisplayColumn && hasReturnColumn && !displayColumn.equals(returnColumn))
		{
			valueList.setDataProviderID1(displayColumn);
			valueList.setDataProviderID2(returnColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(2);
		}
		else if (hasDisplayColumn)
		{
			valueList.setDataProviderID1(displayColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(1);
		}
		else if (hasReturnColumn)
		{
			valueList.setDataProviderID1(returnColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(1);
		}
		
		ServoyLog.logInfo("[ValueListService] Configured as DATABASE_VALUES (table) with dataSource: " + dataSource);
	}
	
	/**
	 * Configures a database valuelist with related values.
	 */
	private static void configureRelatedValueList(ValueList valueList, String relationName,
		String displayColumn, String returnColumn)
	{
		valueList.setValueListType(1); // DATABASE_VALUES
		valueList.setRelationName(relationName);
		
		boolean hasDisplayColumn = (displayColumn != null && !displayColumn.trim().isEmpty());
		boolean hasReturnColumn = (returnColumn != null && !returnColumn.trim().isEmpty());
		
		if (hasDisplayColumn && hasReturnColumn && !displayColumn.equals(returnColumn))
		{
			valueList.setDataProviderID1(displayColumn);
			valueList.setDataProviderID2(returnColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(2);
		}
		else if (hasDisplayColumn)
		{
			valueList.setDataProviderID1(displayColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(1);
		}
		else if (hasReturnColumn)
		{
			valueList.setDataProviderID1(returnColumn);
			valueList.setShowDataProviders(1);
			valueList.setReturnDataProviders(1);
		}
		
		ServoyLog.logInfo("[ValueListService] Configured as DATABASE_VALUES (related) with relation: " + relationName);
	}
	
	/**
	 * Configures a global method valuelist.
	 */
	private static void configureGlobalMethodValueList(ValueList valueList, String globalMethod)
	{
		valueList.setValueListType(4); // GLOBAL_METHOD_VALUES
		valueList.setCustomValues(globalMethod);
		
		ServoyLog.logInfo("[ValueListService] Configured as GLOBAL_METHOD_VALUES with method: " + globalMethod);
	}
	
	/**
	 * Applies properties to a valuelist.
	 */
	private static void applyValueListProperties(ValueList valueList, Map<String, Object> properties)
	{
		if (properties == null || properties.isEmpty()) return;
		
		for (Map.Entry<String, Object> entry : properties.entrySet())
		{
			String propName = entry.getKey();
			Object propValue = entry.getValue();
			
			ServoyLog.logInfo("[ValueListService] Applying property: " + propName + " = " + propValue);
			
			try
			{
				switch (propName)
				{
					case "lazyLoading":
						valueList.setLazyLoading(propValue instanceof Boolean ? (Boolean)propValue : Boolean.parseBoolean(propValue.toString()));
						break;
					case "displayValueType":
						valueList.setDisplayValueType(propValue instanceof Number ? ((Number)propValue).intValue() : Integer.parseInt(propValue.toString()));
						break;
					case "realValueType":
						valueList.setRealValueType(propValue instanceof Number ? ((Number)propValue).intValue() : Integer.parseInt(propValue.toString()));
						break;
					case "separator":
						if (propValue != null && !propValue.toString().trim().isEmpty()) valueList.setSeparator(propValue.toString());
						break;
					case "sortOptions":
						if (propValue != null && !propValue.toString().trim().isEmpty()) valueList.setSortOptions(propValue.toString());
						break;
					case "useTableFilter":
						valueList.setUseTableFilter(propValue instanceof Boolean ? (Boolean)propValue : Boolean.parseBoolean(propValue.toString()));
						break;
					case "addEmptyValue":
						if (propValue instanceof Number)
						{
							valueList.setAddEmptyValue(((Number)propValue).intValue());
						}
						else if (propValue instanceof Boolean)
						{
							valueList.setAddEmptyValue((Boolean)propValue ? 1 : 2);
						}
						else
						{
							String strVal = propValue.toString().toLowerCase();
							valueList.setAddEmptyValue("always".equals(strVal) || "true".equals(strVal) ? 1 : 2);
						}
						break;
// for now disable this, how does the AI know what the property needs to be of the valuelist? shouldn't it just give a fallback valuelist name
// where we then lookup the id/uuid from?
// also in the LTS releases of 2024 and 2025 this is a integer id an in the latest releaes this is a uuid..
//					case "fallbackValueListID":
//					case "fallbackValueList":
//						if (propValue != null && !propValue.toString().trim().isEmpty()) valueList.setFallbackValueListID(propValue.toString());
//						break;
					case "deprecated":
						if (propValue != null && !propValue.toString().trim().isEmpty()) valueList.setDeprecated(propValue.toString());
						break;
					case "encapsulation":
						if (propValue != null) valueList.setEncapsulation(parseEncapsulation(propValue.toString()));
						break;
					case "comment":
						if (propValue != null && !propValue.toString().trim().isEmpty()) valueList.setComment(propValue.toString());
						break;
					default:
						ServoyLog.logInfo("[ValueListService] Unknown property: " + propName);
						break;
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("[ValueListService] Error setting property " + propName + ": " + e.getMessage(), e);
			}
		}
	}
	
	/**
	 * Parses encapsulation string to int value.
	 */
	private static int parseEncapsulation(String encapsulationStr)
	{
		if (encapsulationStr == null) return PersistEncapsulation.DEFAULT;
		
		String normalized = encapsulationStr.toLowerCase().trim();
		switch (normalized)
		{
			case "hide":
			case "hide in scripting":
			case "hide_in_scripting":
				return PersistEncapsulation.HIDE_IN_SCRIPTING_MODULE_SCOPE;
			case "module":
			case "module scope":
			case "module_scope":
				return PersistEncapsulation.MODULE_SCOPE;
			case "public":
			default:
				return PersistEncapsulation.DEFAULT;
		}
	}
	
	/**
	 * Validates datasource format and auto-corrects if needed.
	 */
	public static String validateAndCorrectDataSource(String dataSource) throws RepositoryException
	{
		if (dataSource == null || dataSource.trim().isEmpty())
		{
			throw new RepositoryException("Datasource cannot be empty");
		}
		
		if (!dataSource.startsWith("db:/"))
		{
			if (dataSource.contains("/"))
			{
				return "db:/" + dataSource;
			}
			else
			{
				throw new RepositoryException("Invalid datasource format: '" + dataSource +
					"'. Please provide format 'db:/server_name/table_name' or 'server_name/table_name'");
			}
		}
		
		return dataSource;
	}
}
