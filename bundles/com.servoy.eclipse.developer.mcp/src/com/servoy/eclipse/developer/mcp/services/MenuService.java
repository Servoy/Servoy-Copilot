/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;

@Creatable
public class MenuService
{
	public String listMenus(String scope)
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = model.getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		String activeSolutionName = activeProject.getEditingSolution().getName();
		List<MenuEntry> menus = new ArrayList<>();
		boolean currentOnly = "current".equalsIgnoreCase(scope);

		collectMenus(activeProject, menus);

		if (!currentOnly)
		{
			for (ServoyProject module : model.getModulesOfActiveProject())
			{
				if (module != null && module.getEditingSolution() != null && !module.equals(activeProject))
				{
					collectMenus(module, menus);
				}
			}
		}

		if (menus.isEmpty())
			return "No menus found" + (currentOnly ? " in '" + activeSolutionName + "'" : " in the active solution and modules");

		StringBuilder result = new StringBuilder();
		result.append("Menus in ").append(currentOnly ? "'" + activeSolutionName + "'" : "solution '" + activeSolutionName + "' and modules")
			.append(" (").append(menus.size()).append("):\n\n");

		int count = 1;
		for (MenuEntry entry : menus)
		{
			result.append(count++).append(". ").append(entry.name);
			if (!entry.solutionName.equals(activeSolutionName))
				result.append(" [").append(entry.solutionName).append("]");
			if (entry.styleClass != null && !entry.styleClass.isEmpty())
				result.append(" (styleClass: ").append(entry.styleClass).append(")");
			int itemCount = entry.itemCount;
			result.append(" - ").append(itemCount).append(" item").append(itemCount != 1 ? "s" : "");
			result.append("\n");
		}
		return result.toString();
	}

	public String getMenuStructure(String menuName)
	{
		if (menuName == null || menuName.isBlank())
			return "Error: menuName is required";

		Menu menu = findMenu(menuName);
		if (menu == null)
			return "Error: Menu '" + menuName + "' not found";

		StringBuilder result = new StringBuilder();
		result.append("Menu: ").append(menu.getName()).append("\n");
		if (menu.getStyleClass() != null && !menu.getStyleClass().isEmpty())
			result.append("  styleClass: ").append(menu.getStyleClass()).append("\n");
		result.append("  encapsulation: ").append(menu.getEncapsulation() == 0 ? "public" : "module_private").append("\n");
		result.append("\n  Items:\n");

		appendMenuItems(menu, result, "    ");

		return result.toString();
	}

	public String createMenu(String name, String styleClass, String encapsulation)
	{
		if (name == null || name.isBlank())
			return "Error: Menu name is required";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		Solution solution = project.getEditingSolution();
		Iterator<Menu> existing = solution.getMenus(false);
		while (existing.hasNext())
		{
			if (name.equals(existing.next().getName()))
				return "Error: Menu '" + name + "' already exists";
		}

		try
		{
			IValidateName validator = model.getNameValidator();
			Menu menu = solution.createNewMenu(validator, name);

			if (styleClass != null && !styleClass.isBlank())
				menu.setStyleClass(styleClass);

			if (encapsulation != null && !encapsulation.isBlank())
			{
				if ("module_private".equalsIgnoreCase(encapsulation) || "module".equalsIgnoreCase(encapsulation))
					menu.setEncapsulation(2);
				else
					menu.setEncapsulation(0);
			}

			project.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			return "Menu '" + name + "' created successfully.";
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error creating menu: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	public String createMenuItem(String menuName, String itemName, String parentItemName, String text,
		String toolTipText, String styleClass, String iconStyleClass, String enabled)
	{
		if (menuName == null || menuName.isBlank())
			return "Error: menuName is required";
		if (itemName == null || itemName.isBlank())
			return "Error: itemName is required";

		Menu menu = findMenu(menuName);
		if (menu == null)
			return "Error: Menu '" + menuName + "' not found";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null)
			return "Error: No active Servoy solution project found";

		try
		{
			MenuItem newItem;
			if (parentItemName != null && !parentItemName.isBlank())
			{
				MenuItem parent = findMenuItemRecursive(menu, parentItemName);
				if (parent == null)
					return "Error: Parent menu item '" + parentItemName + "' not found in menu '" + menuName + "'";
				newItem = parent.createNewMenuItem(itemName);
			}
			else
			{
				newItem = menu.createNewMenuItem(itemName);
			}

			if (text != null && !text.isBlank())
				newItem.setText(text);
			if (toolTipText != null && !toolTipText.isBlank())
				newItem.setToolTipText(toolTipText);
			if (styleClass != null && !styleClass.isBlank())
				newItem.setStyleClass(styleClass);
			if (iconStyleClass != null && !iconStyleClass.isBlank())
				newItem.setIconStyleClass(iconStyleClass);
			if (enabled != null && !enabled.isBlank())
				newItem.setEnabled(Boolean.parseBoolean(enabled));

			project.saveEditingSolutionNodes(new IPersist[] { menu }, true);

			StringBuilder result = new StringBuilder();
			result.append("MenuItem '").append(itemName).append("' created in menu '").append(menuName).append("'");
			if (parentItemName != null && !parentItemName.isBlank())
				result.append(" under parent '").append(parentItemName).append("'");
			result.append(".");
			return result.toString();
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error creating menu item: " + itemName, e);
			return "Error: " + e.getMessage();
		}
	}

	public String updateMenu(String name, String styleClass, String encapsulation)
	{
		if (name == null || name.isBlank())
			return "Error: Menu name is required";

		Menu menu = findMenu(name);
		if (menu == null)
			return "Error: Menu '" + name + "' not found";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null)
			return "Error: No active Servoy solution project found";

		boolean changed = false;

		if (styleClass != null)
		{
			menu.setStyleClass(styleClass.isBlank() ? null : styleClass);
			changed = true;
		}

		if (encapsulation != null && !encapsulation.isBlank())
		{
			if ("module_private".equalsIgnoreCase(encapsulation) || "module".equalsIgnoreCase(encapsulation))
				menu.setEncapsulation(2);
			else
				menu.setEncapsulation(0);
			changed = true;
		}

		if (!changed)
			return "No properties to update. Provide styleClass or encapsulation.";

		try
		{
			project.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			return "Menu '" + name + "' updated successfully.";
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error updating menu: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	public String updateMenuItem(String menuName, String itemName, String text, String toolTipText,
		String styleClass, String iconStyleClass, String enabled)
	{
		if (menuName == null || menuName.isBlank())
			return "Error: menuName is required";
		if (itemName == null || itemName.isBlank())
			return "Error: itemName is required";

		Menu menu = findMenu(menuName);
		if (menu == null)
			return "Error: Menu '" + menuName + "' not found";

		MenuItem item = findMenuItemRecursive(menu, itemName);
		if (item == null)
			return "Error: MenuItem '" + itemName + "' not found in menu '" + menuName + "'";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null)
			return "Error: No active Servoy solution project found";

		boolean changed = false;

		if (text != null)
		{
			item.setText(text.isBlank() ? null : text);
			changed = true;
		}
		if (toolTipText != null)
		{
			item.setToolTipText(toolTipText.isBlank() ? null : toolTipText);
			changed = true;
		}
		if (styleClass != null)
		{
			item.setStyleClass(styleClass.isBlank() ? null : styleClass);
			changed = true;
		}
		if (iconStyleClass != null)
		{
			item.setIconStyleClass(iconStyleClass.isBlank() ? null : iconStyleClass);
			changed = true;
		}
		if (enabled != null && !enabled.isBlank())
		{
			item.setEnabled(Boolean.parseBoolean(enabled));
			changed = true;
		}

		if (!changed)
			return "No properties to update.";

		try
		{
			project.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			return "MenuItem '" + itemName + "' in menu '" + menuName + "' updated successfully.";
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error updating menu item: " + itemName, e);
			return "Error: " + e.getMessage();
		}
	}

	public String deleteMenu(String name)
	{
		if (name == null || name.isBlank())
			return "Error: Menu name is required";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			return "Error: No active Servoy solution project found";

		Solution solution = project.getEditingSolution();
		Menu menu = null;
		Iterator<Menu> iter = solution.getMenus(false);
		while (iter.hasNext())
		{
			Menu m = iter.next();
			if (name.equals(m.getName()))
			{
				menu = m;
				break;
			}
		}

		if (menu == null)
			return "Error: Menu '" + name + "' not found";

		solution.removeChild(menu);
		try
		{
			project.saveEditingSolutionNodes(new IPersist[] { menu }, false);
			return "Menu '" + name + "' deleted successfully.";
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error deleting menu: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	public String deleteMenuItem(String menuName, String itemName)
	{
		if (menuName == null || menuName.isBlank())
			return "Error: menuName is required";
		if (itemName == null || itemName.isBlank())
			return "Error: itemName is required";

		Menu menu = findMenu(menuName);
		if (menu == null)
			return "Error: Menu '" + menuName + "' not found";

		MenuItem item = findMenuItemRecursive(menu, itemName);
		if (item == null)
			return "Error: MenuItem '" + itemName + "' not found in menu '" + menuName + "'";

		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null)
			return "Error: No active Servoy solution project found";

		ISupportChilds parent = (ISupportChilds)item.getParent();
		parent.removeChild(item);
		try
		{
			project.saveEditingSolutionNodes(new IPersist[] { menu }, true);
			return "MenuItem '" + itemName + "' deleted from menu '" + menuName + "' successfully.";
		}
		catch (RepositoryException e)
		{
			ServoyLog.logError("Error deleting menu item: " + itemName, e);
			return "Error: " + e.getMessage();
		}
	}

	private Menu findMenu(String menuName)
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject project = model.getActiveProject();
		if (project == null || project.getEditingSolution() == null)
			return null;

		Solution solution = project.getEditingSolution();
		Iterator<Menu> iter = solution.getMenus(false);
		while (iter.hasNext())
		{
			Menu m = iter.next();
			if (menuName.equals(m.getName()))
				return m;
		}

		for (ServoyProject module : model.getModulesOfActiveProject())
		{
			if (module != null && module.getEditingSolution() != null && !module.equals(project))
			{
				Iterator<Menu> moduleIter = module.getEditingSolution().getMenus(false);
				while (moduleIter.hasNext())
				{
					Menu m = moduleIter.next();
					if (menuName.equals(m.getName()))
						return m;
				}
			}
		}
		return null;
	}

	private MenuItem findMenuItemRecursive(ISupportChilds parent, String name)
	{
		Iterator<IPersist> iter = parent.getAllObjects();
		while (iter.hasNext())
		{
			IPersist child = iter.next();
			if (child instanceof MenuItem)
			{
				MenuItem item = (MenuItem)child;
				if (name.equals(item.getName()))
					return item;
				MenuItem found = findMenuItemRecursive(item, name);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	private void collectMenus(ServoyProject project, List<MenuEntry> menus)
	{
		Solution solution = project.getEditingSolution();
		if (solution == null) return;
		String solutionName = solution.getName();
		Iterator<Menu> iter = solution.getMenus(false);
		while (iter.hasNext())
		{
			Menu m = iter.next();
			int itemCount = countMenuItems(m);
			menus.add(new MenuEntry(m.getName(), solutionName, m.getStyleClass(), itemCount));
		}
	}

	private int countMenuItems(ISupportChilds parent)
	{
		int count = 0;
		Iterator<IPersist> iter = parent.getAllObjects();
		while (iter.hasNext())
		{
			IPersist child = iter.next();
			if (child instanceof MenuItem)
			{
				count++;
				count += countMenuItems((MenuItem)child);
			}
		}
		return count;
	}

	private void appendMenuItems(ISupportChilds parent, StringBuilder sb, String indent)
	{
		Iterator<IPersist> iter = parent.getAllObjects();
		while (iter.hasNext())
		{
			IPersist child = iter.next();
			if (child instanceof MenuItem)
			{
				MenuItem item = (MenuItem)child;
				sb.append(indent).append("- ").append(item.getName());
				if (item.getText() != null && !item.getText().isEmpty())
					sb.append(" (text: \"").append(item.getText()).append("\")");
				if (item.getIconStyleClass() != null && !item.getIconStyleClass().isEmpty())
					sb.append(" [icon: ").append(item.getIconStyleClass()).append("]");
				if (!item.getEnabled())
					sb.append(" [DISABLED]");
				if (item.getStyleClass() != null && !item.getStyleClass().isEmpty())
					sb.append(" {styleClass: ").append(item.getStyleClass()).append("}");
				if (item.getToolTipText() != null && !item.getToolTipText().isEmpty())
					sb.append(" tooltip: \"").append(item.getToolTipText()).append("\"");
				sb.append("\n");
				appendMenuItems(item, sb, indent + "  ");
			}
		}
	}

	private static class MenuEntry
	{
		final String name;
		final String solutionName;
		final String styleClass;
		final int itemCount;

		MenuEntry(String name, String solutionName, String styleClass, int itemCount)
		{
			this.name = name;
			this.solutionName = solutionName;
			this.styleClass = styleClass;
			this.itemCount = itemCount;
		}
	}
}
