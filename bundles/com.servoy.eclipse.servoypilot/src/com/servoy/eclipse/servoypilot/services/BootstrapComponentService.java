package com.servoy.eclipse.servoypilot.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Bean;
import com.servoy.j2db.persistence.IPersist;

/**
 * Service for Bootstrap component operations.
 * Migrated from knowledgebase.mcp BootstrapComponentService.
 * 
 * COMPLETE: Full CRUD operations for bootstrap components.
 * Handles adding, updating, deleting, and querying components in form files.
 */
public class BootstrapComponentService
{
	private static final String BOOTSTRAP_BUTTON_TYPE = "bootstrapcomponents-button";
	private static final String BOOTSTRAP_LABEL_TYPE = "bootstrapcomponents-label";

	// =============================================
	// LEGACY METHODS (from pilot - for IPersist-based operations)
	// =============================================

	/**
	 * Check if a persist is a bootstrap button component.
	 */
	public static boolean isButtonComponent(IPersist persist)
	{
		if (!(persist instanceof Bean)) return false;
		Bean bean = (Bean)persist;
		String beanClassName = bean.getBeanClassName();
		return BOOTSTRAP_BUTTON_TYPE.equals(beanClassName);
	}

	/**
	 * Get button component information as JSON.
	 */
	public static JSONObject getButtonInfo(IPersist persist)
	{
		if (!isButtonComponent(persist)) return null;

		try
		{
			Bean bean = (Bean)persist;
			JSONObject info = new JSONObject();
			info.put("name", bean.getName());
			info.put("type", "button");

			String beanXML = bean.getBeanXML();
			if (beanXML != null && !beanXML.trim().isEmpty())
			{
				info.put("text", extractXMLProperty(beanXML, "text", "Button"));
				info.put("styleClass", extractXMLProperty(beanXML, "styleClass", ""));
			}

			if (bean.getLocation() != null) info.put("location", bean.getLocation().toString());
			if (bean.getSize() != null) info.put("size", bean.getSize().toString());

			return info;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error getting button info", e);
			return null;
		}
	}

	private static String extractXMLProperty(String xml, String propertyName, String defaultValue)
	{
		try
		{
			String startTag = "<" + propertyName + ">";
			String endTag = "</" + propertyName + ">";
			int startIdx = xml.indexOf(startTag);
			if (startIdx >= 0)
			{
				int endIdx = xml.indexOf(endTag, startIdx);
				if (endIdx > startIdx) return xml.substring(startIdx + startTag.length(), endIdx).trim();
			}
		}
		catch (Exception e) { }
		return defaultValue;
	}

	// =============================================
	// FILE-BASED CRUD OPERATIONS (from knowledgebase.mcp)
	// =============================================

	/**
	 * Adds a bootstrap component to a form file.
	 */
	public static String addComponentToForm(String projectPath, String formName, String componentName,
		String typeName, String cssPosition, Map<String, Object> properties)
	{
		try
		{
			Path formPath = Paths.get(projectPath, "forms", formName + ".frm");

			if (!Files.exists(formPath))
			{
				String availableForms = listAvailableForms(projectPath);
				return "Form '" + formName + "' not found. Available forms: " + availableForms;
			}

			String formContent = new String(Files.readAllBytes(formPath));
			JSONObject formJson = new JSONObject(formContent);

			if (!formJson.has("items"))
			{
				return "Invalid form structure - form '" + formName + "' is missing 'items' array";
			}

			String[] positionParts = cssPosition.split(",");
			if (positionParts.length != 6)
			{
				return "Invalid CSS position format. Expected 'top,right,bottom,left,width,height' but got: '" + cssPosition + "'";
			}

			JSONObject cssPositionObj = new JSONObject();
			cssPositionObj.put("top", positionParts[0]);
			cssPositionObj.put("right", positionParts[1]);
			cssPositionObj.put("bottom", positionParts[2]);
			cssPositionObj.put("left", positionParts[3]);
			cssPositionObj.put("width", positionParts[4]);
			cssPositionObj.put("height", positionParts[5]);

			JSONObject component = new JSONObject();
			component.put("cssPosition", cssPosition);
			component.put("name", componentName);
			component.put("typeName", typeName);
			component.put("typeid", 47);
			component.put("uuid", UUID.randomUUID().toString());

			JSONObject jsonSection = new JSONObject();
			jsonSection.put("cssPosition", cssPositionObj);

			if (properties != null && !properties.isEmpty())
			{
				for (Map.Entry<String, Object> entry : properties.entrySet())
				{
					String key = entry.getKey();
					Object value = entry.getValue();

					if ("styleClass".equals(key))
					{
						component.put("styleClass", value);
						jsonSection.put("styleClass", value);
					}
					else
					{
						jsonSection.put(key, value);
					}
				}
			}

			component.put("json", jsonSection);

			JSONArray items = formJson.getJSONArray("items");
			items.put(component);

			Path backupPath = Paths.get(formPath.toString() + ".backup");
			Files.copy(formPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			String updatedContent = formJson.toString(4);
			Files.write(formPath, updatedContent.getBytes());

			ServoyLog.logInfo("[BootstrapComponentService] Successfully added component '" + componentName +
				"' (type: " + typeName + ") to form '" + formName + "'");

			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error adding component to form: " + e.getMessage(), e);
			return "Error adding component: " + e.getMessage();
		}
	}

	/**
	 * Updates an existing component with new property values.
	 */
	public static String updateComponent(String projectPath, String formName, String componentName, Map<String, Object> propertiesToUpdate)
	{
		try
		{
			Path formPath = Paths.get(projectPath, "forms", formName + ".frm");

			if (!Files.exists(formPath))
			{
				String availableForms = listAvailableForms(projectPath);
				return "Form '" + formName + "' not found. Available forms: " + availableForms;
			}

			String formContent = new String(Files.readAllBytes(formPath));
			JSONObject formJson = new JSONObject(formContent);
			JSONArray items = formJson.getJSONArray("items");

			boolean found = false;
			for (int i = 0; i < items.length(); i++)
			{
				JSONObject item = items.getJSONObject(i);

				if (item.has("name") && componentName.equals(item.getString("name")))
				{
					found = true;

					JSONObject jsonSection = item.has("json") ? item.getJSONObject("json") : new JSONObject();

					for (Map.Entry<String, Object> entry : propertiesToUpdate.entrySet())
					{
						String key = entry.getKey();
						Object value = entry.getValue();

						if ("cssPosition".equals(key) && value instanceof String)
						{
							String cssPos = (String)value;
							String[] parts = cssPos.split(",");
							if (parts.length != 6)
							{
								return "Invalid CSS position format. Expected 'top,right,bottom,left,width,height'";
							}

							item.put("cssPosition", cssPos);

							JSONObject cssPositionObj = new JSONObject();
							cssPositionObj.put("top", parts[0]);
							cssPositionObj.put("right", parts[1]);
							cssPositionObj.put("bottom", parts[2]);
							cssPositionObj.put("left", parts[3]);
							cssPositionObj.put("width", parts[4]);
							cssPositionObj.put("height", parts[5]);
							jsonSection.put("cssPosition", cssPositionObj);
						}
						else if ("styleClass".equals(key))
						{
							item.put("styleClass", value);
							jsonSection.put("styleClass", value);
						}
						else
						{
							jsonSection.put(key, value);
						}
					}

					item.put("json", jsonSection);
					break;
				}
			}

			if (!found)
			{
				return "Component '" + componentName + "' not found in form '" + formName + "'";
			}

			Path backupPath = Paths.get(formPath.toString() + ".backup");
			Files.copy(formPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			String updatedContent = formJson.toString(4);
			Files.write(formPath, updatedContent.getBytes());

			ServoyLog.logInfo("[BootstrapComponentService] Successfully updated component '" + componentName +
				"' in form '" + formName + "'");

			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error updating component: " + e.getMessage(), e);
			return "Error updating component: " + e.getMessage();
		}
	}

	/**
	 * Deletes a component from a form.
	 */
	public static String deleteComponent(String projectPath, String formName, String componentName)
	{
		try
		{
			Path formPath = Paths.get(projectPath, "forms", formName + ".frm");

			if (!Files.exists(formPath))
			{
				String availableForms = listAvailableForms(projectPath);
				return "Form '" + formName + "' not found. Available forms: " + availableForms;
			}

			String formContent = new String(Files.readAllBytes(formPath));
			JSONObject formJson = new JSONObject(formContent);
			JSONArray items = formJson.getJSONArray("items");

			boolean found = false;
			for (int i = 0; i < items.length(); i++)
			{
				JSONObject item = items.getJSONObject(i);

				if (item.has("name") && componentName.equals(item.getString("name")))
				{
					items.remove(i);
					found = true;
					break;
				}
			}

			if (!found)
			{
				return "Component '" + componentName + "' not found in form '" + formName + "'";
			}

			Path backupPath = Paths.get(formPath.toString() + ".backup");
			Files.copy(formPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

			String updatedContent = formJson.toString(4);
			Files.write(formPath, updatedContent.getBytes());

			ServoyLog.logInfo("[BootstrapComponentService] Successfully deleted component '" + componentName +
				"' from form '" + formName + "'");

			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error deleting component: " + e.getMessage(), e);
			return "Error deleting component: " + e.getMessage();
		}
	}

	/**
	 * Lists components of a specific type in a form.
	 */
	public static String listComponentsByType(String projectPath, String formName, String typeName)
	{
		try
		{
			Path formPath = Paths.get(projectPath, "forms", formName + ".frm");

			if (!Files.exists(formPath))
			{
				String availableForms = listAvailableForms(projectPath);
				return "Form '" + formName + "' not found. Available forms: " + availableForms;
			}

			String formContent = new String(Files.readAllBytes(formPath));
			JSONObject formJson = new JSONObject(formContent);
			JSONArray items = formJson.getJSONArray("items");

			JSONArray components = new JSONArray();

			for (int i = 0; i < items.length(); i++)
			{
				JSONObject item = items.getJSONObject(i);

				if (item.has("typeName") && typeName.equals(item.getString("typeName")))
				{
					JSONObject compInfo = new JSONObject();
					compInfo.put("name", item.optString("name", ""));
					compInfo.put("typeName", item.optString("typeName", ""));
					compInfo.put("cssPosition", item.optString("cssPosition", ""));

					if (item.has("json"))
					{
						JSONObject json = item.getJSONObject("json");
						if (json.has("text")) compInfo.put("text", json.getString("text"));
						if (json.has("styleClass")) compInfo.put("styleClass", json.getString("styleClass"));
					}

					components.put(compInfo);
				}
			}

			return components.toString(2);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error listing components by type: " + e.getMessage(), e);
			return "Error listing components: " + e.getMessage();
		}
	}

	/**
	 * Gets detailed information about a specific component.
	 */
	public static String getComponentInfo(String projectPath, String formName, String componentName)
	{
		try
		{
			Path formPath = Paths.get(projectPath, "forms", formName + ".frm");

			if (!Files.exists(formPath))
			{
				String availableForms = listAvailableForms(projectPath);
				return "Form '" + formName + "' not found. Available forms: " + availableForms;
			}

			String formContent = new String(Files.readAllBytes(formPath));
			JSONObject formJson = new JSONObject(formContent);
			JSONArray items = formJson.getJSONArray("items");

			for (int i = 0; i < items.length(); i++)
			{
				JSONObject item = items.getJSONObject(i);

				if (item.has("name") && componentName.equals(item.getString("name")))
				{
					return item.toString(2);
				}
			}

			return "Component '" + componentName + "' not found in form '" + formName + "'";
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error getting component info: " + e.getMessage(), e);
			return "Error getting component info: " + e.getMessage();
		}
	}

	/**
	 * Lists all available forms in the project.
	 */
	public static String listAvailableForms(String projectPath)
	{
		try
		{
			Path formsDir = Paths.get(projectPath, "forms");

			if (!Files.exists(formsDir) || !Files.isDirectory(formsDir))
			{
				return "No forms directory found";
			}

			StringBuilder formsList = new StringBuilder();
			Files.list(formsDir)
				.filter(path -> path.toString().endsWith(".frm"))
				.forEach(path -> {
					String fileName = path.getFileName().toString();
					String formName = fileName.substring(0, fileName.length() - 4);
					if (formsList.length() > 0) formsList.append(", ");
					formsList.append(formName);
				});

			return formsList.length() > 0 ? formsList.toString() : "No forms found";
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error listing forms: " + e.getMessage(), e);
			return "Error listing forms";
		}
	}
}
