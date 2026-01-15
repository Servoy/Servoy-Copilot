package com.servoy.eclipse.servoypilot.services;

import org.json.JSONObject;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Bean;
import com.servoy.j2db.persistence.IPersist;

/**
 * Service for Bootstrap component operations.
 * Migrated from knowledgebase.mcp BootstrapComponentService.
 * 
 * PILOT: Minimal implementation with button component support.
 * Provides reusable methods for working with bootstrap components (buttons, labels, etc).
 */
public class BootstrapComponentService
{
	private static final String BOOTSTRAP_BUTTON_TYPE = "bootstrapcomponents-button";

	/**
	 * Check if a persist is a bootstrap button component.
	 * 
	 * @param persist The persist to check
	 * @return true if it's a button component
	 */
	public static boolean isButtonComponent(IPersist persist)
	{
		if (!(persist instanceof Bean))
		{
			return false;
		}

		Bean bean = (Bean)persist;
		String beanClassName = bean.getBeanClassName();
		return BOOTSTRAP_BUTTON_TYPE.equals(beanClassName);
	}

	/**
	 * Get button component information as JSON.
	 * 
	 * @param persist The button component persist
	 * @return JSON object with button properties
	 */
	public static JSONObject getButtonInfo(IPersist persist)
	{
		if (!isButtonComponent(persist))
		{
			return null;
		}

		try
		{
			Bean bean = (Bean)persist;
			JSONObject info = new JSONObject();

			info.put("name", bean.getName());
			info.put("type", "button");

			// Get custom properties from bean XML
			String beanXML = bean.getBeanXML();
			if (beanXML != null && !beanXML.trim().isEmpty())
			{
				// Parse basic properties from XML
				// PILOT: Simplified extraction - full implementation would parse XML properly
				info.put("text", extractXMLProperty(beanXML, "text", "Button"));
				info.put("styleClass", extractXMLProperty(beanXML, "styleClass", ""));
			}

			// Location and size
			if (bean.getLocation() != null)
			{
				info.put("location", bean.getLocation().toString());
			}
			if (bean.getSize() != null)
			{
				info.put("size", bean.getSize().toString());
			}

			return info;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[BootstrapComponentService] Error getting button info", e);
			return null;
		}
	}

	/**
	 * Simple XML property extraction helper.
	 * PILOT: Simplified implementation - production code should use proper XML parsing.
	 */
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
				if (endIdx > startIdx)
				{
					return xml.substring(startIdx + startTag.length(), endIdx).trim();
				}
			}
		}
		catch (Exception e)
		{
			// Ignore parsing errors, return default
		}
		return defaultValue;
	}
}
