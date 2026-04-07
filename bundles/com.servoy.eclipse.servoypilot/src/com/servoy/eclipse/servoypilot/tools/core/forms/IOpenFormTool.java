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
package com.servoy.eclipse.servoypilot.tools.core.forms;

import java.util.List;
import java.util.Map;

import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IOpenFormTool
{
	@Tool("Opens an existing form or creates a new form. Context-aware: when create=true, form created in current context. Use 'events' parameter to set event handlers by passing a Map where keys are event names (onLoad, onShow, etc.) and values are method names. Example: if user says 'onLoad callInit, onShow refreshData', parse as events: {\"onLoad\": \"callInit\", \"onShow\": \"refreshData\"}. Methods will be auto-created if they don't exist.")
	default String openForm(
		@P(value = "Form name", required = true) String name,
		@P(value = "Create if doesn't exist (default: false)", required = false) Boolean create,
		@P(value = "Form width (default: 640)", required = false) Integer width,
		@P(value = "Form height (default: 480)", required = false) Integer height,
		@P(value = "Form style: 'css' or 'responsive' (default: 'css')", required = false) String style,
		@P(value = "DataSource (format: 'db:/server_name/table_name')", required = false) String dataSource,
		@P(value = "Parent form name (for inheritance)", required = false) String extendsForm,
		@P(value = "Set as main form (default: false)", required = false) Boolean setAsMainForm,
		@P(value = "Additional properties map", required = false) Map<String, Object> properties,
		@P(value = "Event handlers map (event name -> method name)", required = false) Map<String, String> events)
	{
		if (name != null && !name.trim().isEmpty())
		{
			boolean shouldCreate = create != null && create;
			int formWidth = width != null ? width : 640;
			int formHeight = height != null ? height : 480;
			String formStyle = style != null ? style : "css";

			return UIThreadHelper.syncExec("openForm",
				() -> FormToolsHelper.getInstance().openOrCreateForm(name, shouldCreate, formWidth, formHeight, formStyle,
					dataSource, extendsForm, setAsMainForm, properties, events));
		}

		return "Error: name parameter is required";
	}
}
