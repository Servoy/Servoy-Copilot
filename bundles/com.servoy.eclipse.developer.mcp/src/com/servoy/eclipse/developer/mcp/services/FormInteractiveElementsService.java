/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.json.JSONObject;
import org.sablo.specification.PropertyDescription;
import org.sablo.specification.SpecProviderState;
import org.sablo.specification.WebComponentSpecProvider;
import org.sablo.specification.WebObjectSpecification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsFormatter.FormHeader;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.AbstractBase;
import com.servoy.j2db.persistence.AbstractContainer;
import com.servoy.j2db.persistence.BaseComponent;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IFlattenedPersistWrapper;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRepository;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.ISupportDataProviderID;
import com.servoy.j2db.persistence.ISupportExtendsID;
import com.servoy.j2db.persistence.Portal;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.TabPanel;
import com.servoy.j2db.persistence.WebComponent;

/**
 * Lists the interactive elements of ONE form (name, type, bound handlers, {@code data-cy} selector) from the
 * in-memory design-time model. Nothing is cached and nothing is run; only the requested form is walked.
 * <p>
 * Two layers: the collector ({@link #collect}) needs a real {@link Form} and {@link FlattenedSolution} and is
 * tested with a workspace; the serialization lives in the pure {@link FormInteractiveElementsFormatter}.
 * </p>
 */
@Creatable
public class FormInteractiveElementsService
{
	static final String ERR_FORM_NAME_REQUIRED = "Error: formName parameter is required";
	static final String ERR_NO_ACTIVE_SOLUTION = "Error: No active Servoy solution project found";

	private static final String DATAPROVIDER_TYPE = "dataprovider";

	/**
	 * Resolves the form by exact name against the editing flattened solution (so modules resolve) and returns the
	 * JSON payload, or a plain {@code Error: ...} string.
	 *
	 * @param maxElements already clamped by the caller; clamped again here so direct callers are safe too.
	 */
	public String getElements(String formName, boolean includeStatic, int maxElements)
	{
		if (formName == null || formName.isBlank()) return ERR_FORM_NAME_REQUIRED;

		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null) return ERR_NO_ACTIVE_SOLUTION;
		FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		if (fs == null) return ERR_NO_ACTIVE_SOLUTION;

		String name = formName.trim();
		Form form = fs.getForm(name);
		if (form == null) return formNotFoundError(name);

		List<InteractiveElement> elements = collect(form, fs, includeStatic);
		return FormInteractiveElementsFormatter.toJson(header(form), elements, includeStatic, maxElements);
	}

	/**
	 * The elements part of the payload (no form header) for an already resolved form name, or {@code null} when there
	 * is no active solution or the form does not exist. Used by {@code locateForm}.
	 */
	public ObjectNode getElementsNode(String formName, boolean includeStatic, int maxElements)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null) return null;
		FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		if (fs == null || formName == null) return null;
		Form form = fs.getForm(formName.trim());
		if (form == null) return null;
		return FormInteractiveElementsFormatter.toElementsNode(form.getName(), collect(form, fs, includeStatic), includeStatic, maxElements);
	}

	static String formNotFoundError(String name)
	{
		return "Error: Form '" + name + "' not found in the active solution or its modules. " +
			"Use findForm to resolve a description or UI title to the internal form name.";
	}

	/** The header fields of the form, as shown in the tool payload. */
	public static FormHeader header(Form form)
	{
		String ds = form.getDataSource();
		if (ds != null && (ds.isBlank() || "-none-".equals(ds))) ds = null;
		String title = form.getTitleText();
		if (title != null && title.isBlank()) title = null;
		String module = null;
		if (form.getRootObject() instanceof Solution solution) module = solution.getName();
		return new FormHeader(form.getName(), title, module, ds, ServoySolutionService.getFormLayoutType(form));
	}

	/** Collects with the installed web component specs. */
	public List<InteractiveElement> collect(Form form, FlattenedSolution fs, boolean includeStatic)
	{
		return collect(form, fs, defaultHandlerLookup(), defaultDataProviderLookup(), includeStatic);
	}

	/**
	 * Collects with an injected spec lookup. {@code handlerNamesBySpec} maps a web component type name to its
	 * handler names; {@code null} means "spec not installed" (the element is reported with {@code specMissing}).
	 */
	public List<InteractiveElement> collect(Form form, FlattenedSolution fs, Function<String, Set<String>> handlerNamesBySpec, boolean includeStatic)
	{
		return collect(form, fs, handlerNamesBySpec, spec -> Set.of(), includeStatic);
	}

	/** As above, plus an injected lookup of the dataprovider-typed property names per web component type name. */
	public List<InteractiveElement> collect(Form form, FlattenedSolution fs, Function<String, Set<String>> handlerNamesBySpec,
		Function<String, Set<String>> dataProviderPropertiesBySpec, boolean includeStatic)
	{
		Collector collector = new Collector(form, fs, handlerNamesBySpec, dataProviderPropertiesBySpec, includeStatic);
		collector.run();
		return collector.result;
	}

	// -------------------------------------------------------------------------
	// default spec lookups
	// -------------------------------------------------------------------------

	static Function<String, Set<String>> defaultHandlerLookup()
	{
		ComponentSpecService specService = new ComponentSpecService();
		return typeName -> findSpec(typeName) == null ? null : specService.getHandlerNames(typeName);
	}

	static Function<String, Set<String>> defaultDataProviderLookup()
	{
		return typeName -> {
			Set<String> names = new TreeSet<>();
			WebObjectSpecification spec = findSpec(typeName);
			if (spec != null && spec.getProperties() != null)
			{
				for (Map.Entry<String, PropertyDescription> entry : spec.getProperties().entrySet())
				{
					PropertyDescription pd = entry.getValue();
					if (pd != null && pd.getType() != null && DATAPROVIDER_TYPE.equals(pd.getType().getName())) names.add(entry.getKey());
				}
			}
			return names;
		};
	}

	private static WebObjectSpecification findSpec(String typeName)
	{
		if (typeName == null || WebComponentSpecProvider.getInstance() == null) return null;
		SpecProviderState state = WebComponentSpecProvider.getSpecProviderState();
		return state == null ? null : state.getWebObjectSpecification(typeName);
	}

	// -------------------------------------------------------------------------
	// collector
	// -------------------------------------------------------------------------

	private static final class Collector
	{
		private final Form form;
		private final FlattenedSolution fs;
		private final Function<String, Set<String>> handlerLookup;
		private final Function<String, Set<String>> dataProviderLookup;
		private final boolean includeStatic;
		private final Map<String, ScriptMethod> methodsByUuid = new HashMap<>();
		final List<InteractiveElement> result = new ArrayList<>();

		Collector(Form form, FlattenedSolution fs, Function<String, Set<String>> handlerLookup,
			Function<String, Set<String>> dataProviderLookup, boolean includeStatic)
		{
			this.form = form;
			this.fs = fs;
			this.handlerLookup = handlerLookup;
			this.dataProviderLookup = dataProviderLookup;
			this.includeStatic = includeStatic;
		}

		void run()
		{
			// one-time UUID -> method map over the form hierarchy; scope methods go through fs.getScriptMethod
			for (Form hierarchyForm : fs.getFormHierarchy(form))
			{
				var methods = hierarchyForm.getScriptMethods(false);
				while (methods.hasNext())
				{
					ScriptMethod method = methods.next();
					methodsByUuid.putIfAbsent(method.getUUID().toString(), method);
				}
			}

			// the flattened form contains the elements of the whole hierarchy (inherited ones included)
			Form flattened = fs.getFlattenedForm(form);
			if (flattened == null) flattened = form;
			for (IPersist child : flattened.getAllObjectsAsList())
			{
				visit(child, null);
			}
		}

		private void visit(IPersist persist, String container)
		{
			if (persist instanceof WebComponent wc)
			{
				addWebComponent(wc, container);
			}
			else if (persist instanceof Portal portal)
			{
				addLegacy(portal, container);
				String childContainer = nonBlank(portal.getName()) ? portal.getName() : container;
				for (IPersist child : portal.getAllObjectsAsList())
				{
					visit(child, childContainer);
				}
			}
			else if (persist instanceof TabPanel)
			{
				addLegacy(persist, container);
			}
			else if (persist instanceof AbstractContainer layoutContainer)
			{
				String name = layoutContainer.getName();
				if (includeStatic && nonBlank(name))
				{
					result.add(new InteractiveElement(name, simpleTypeName(persist), InteractiveElement.KIND_STATIC, null, container, isInherited(persist),
						false, !isHidden(persist), List.of()));
				}
				String childContainer = nonBlank(name) ? name : container;
				for (IPersist child : layoutContainer.getAllObjectsAsList())
				{
					visit(child, childContainer);
				}
			}
			else if (persist instanceof BaseComponent)
			{
				addLegacy(persist, container);
			}
		}

		private void addWebComponent(WebComponent wc, String container)
		{
			String typeName = wc.getTypeName();
			String name = wc.getName();
			JSONObject json = wc.getFlattenedJson();
			Set<String> handlerNames = handlerLookup.apply(typeName);
			boolean inherited = isInherited(wc);

			if (handlerNames == null)
			{
				// spec not installed: handlers cannot be listed, but the element is still reported
				result.add(new InteractiveElement(name, typeName, InteractiveElement.KIND_CONTROL, null, container, inherited, true, !isHidden(wc), List.of()));
				return;
			}

			List<HandlerBinding> handlers = new ArrayList<>();
			if (json != null)
			{
				for (String handlerName : new TreeSet<>(handlerNames))
				{
					String raw = rawHandlerValue(json.opt(handlerName));
					if (raw != null) handlers.add(resolveHandler(handlerName, raw));
				}
			}

			String dataProvider = null;
			Set<String> dataProviderProps = dataProviderLookup.apply(typeName);
			if (json != null && dataProviderProps != null)
			{
				for (String prop : dataProviderProps)
				{
					Object value = json.opt(prop);
					if (value instanceof String s && !s.isBlank())
					{
						dataProvider = s;
						break;
					}
				}
			}

			String kind;
			if (isNavigationContainerType(typeName)) kind = InteractiveElement.KIND_CONTAINER;
			else if (dataProvider != null) kind = InteractiveElement.KIND_INPUT;
			else if (!handlers.isEmpty()) kind = InteractiveElement.KIND_CONTROL;
			else kind = InteractiveElement.KIND_STATIC;
			add(name, typeName, kind, dataProvider, container, inherited, !isHidden(wc), handlers);
		}

		private void addLegacy(IPersist persist, String container)
		{
			String name = persist instanceof BaseComponent component ? component.getName() : null;

			Map<String, Object> properties = null;
			if (persist instanceof ISupportExtendsID extendsId) properties = extendsId.getFlattenedPropertiesMap();
			else if (persist instanceof AbstractBase base) properties = base.getPropertiesMap();

			List<HandlerBinding> handlers = new ArrayList<>();
			if (properties != null)
			{
				for (Map.Entry<String, Object> entry : new TreeMap<>(properties).entrySet())
				{
					if (!BaseComponent.isEventOrCommandProperty(entry.getKey())) continue;
					String raw = rawHandlerValue(entry.getValue());
					if (raw != null) handlers.add(resolveHandler(entry.getKey(), raw));
				}
			}

			String dataProvider = null;
			if (persist instanceof ISupportDataProviderID dataProviderPersist)
			{
				String id = dataProviderPersist.getDataProviderID();
				if (nonBlank(id)) dataProvider = id;
			}

			String kind;
			if (persist instanceof TabPanel) kind = InteractiveElement.KIND_CONTAINER;
			else if (dataProvider != null) kind = InteractiveElement.KIND_INPUT;
			else if (!handlers.isEmpty()) kind = InteractiveElement.KIND_CONTROL;
			else kind = InteractiveElement.KIND_STATIC;
			add(name, simpleTypeName(persist), kind, dataProvider, container, isInherited(persist), !isHidden(persist), handlers);
		}

		private void add(String name, String type, String kind, String dataProvider, String container, boolean inherited, boolean visible,
			List<HandlerBinding> handlers)
		{
			if (InteractiveElement.KIND_STATIC.equals(kind) && (!includeStatic || !nonBlank(name))) return;
			result.add(new InteractiveElement(name, type, kind, dataProvider, container, inherited, false, visible, handlers));
		}

		/** True when the persist, or any container around it up to the form, has visible=false. A hidden element is not rendered at all. */
		private static boolean isHidden(IPersist persist)
		{
			for (IPersist current = persist; current != null && !(current instanceof Form); current = current.getParent())
			{
				if (isHiddenItself(current)) return true;
			}
			return false;
		}

		private static boolean isHiddenItself(IPersist persist)
		{
			IPersist target = persist instanceof IFlattenedPersistWrapper< ? > wrapper ? wrapper.getWrappedPersist() : persist;
			if (target instanceof WebComponent wc)
			{
				if (Boolean.FALSE.equals(wc.getProperty("visible"))) return true;
				JSONObject json = wc.getFlattenedJson();
				return json != null && json.has("visible") && !json.optBoolean("visible", true);
			}
			if (target instanceof AbstractBase base)
			{
				// legacy persists and layout containers: a missing property means visible
				return Boolean.FALSE.equals(base.getProperty("visible"));
			}
			return false;
		}

		private HandlerBinding resolveHandler(String handlerKey, String raw)
		{
			ScriptMethod method = methodsByUuid.get(raw);
			if (method == null)
			{
				try
				{
					method = fs.getScriptMethod(raw);
				}
				catch (RuntimeException e)
				{
					ServoyLog.logError("Could not resolve handler '" + handlerKey + "' value '" + raw + "' on form " + form.getName(), e);
				}
			}
			if (method == null) return HandlerBinding.unresolved(handlerKey, raw);
			return HandlerBinding.resolved(handlerKey, method.getName(), ownerOf(method), method.getDeclaration());
		}

		private static String ownerOf(ScriptMethod method)
		{
			ISupportChilds parent = method.getParent();
			if (parent instanceof Form ownerForm) return "forms." + ownerForm.getName();
			String scope = method.getScopeName();
			return nonBlank(scope) ? "scopes." + scope : null;
		}

		/** An element is inherited when it is defined in a super form of the requested form. */
		private boolean isInherited(IPersist persist)
		{
			IPersist target = persist instanceof IFlattenedPersistWrapper< ? > wrapper ? wrapper.getWrappedPersist() : persist;
			IPersist owner = target.getAncestor(IRepository.FORMS);
			return owner != null && !owner.getUUID().equals(form.getUUID());
		}

		private static String rawHandlerValue(Object value)
		{
			if (value == null || JSONObject.NULL.equals(value)) return null;
			String text = value.toString().trim();
			// "-1" explicitly disables an inherited handler; "0" is the legacy "no method" id
			if (text.isEmpty() || "-1".equals(text) || "0".equals(text)) return null;
			return text;
		}

		private static boolean isNavigationContainerType(String typeName)
		{
			if (typeName == null) return false;
			String lower = typeName.toLowerCase();
			return lower.contains("tabpanel") || lower.contains("splitpane") || lower.contains("accordion");
		}

		private static String simpleTypeName(IPersist persist)
		{
			IPersist target = persist instanceof IFlattenedPersistWrapper< ? > wrapper ? wrapper.getWrappedPersist() : persist;
			return target.getClass().getSimpleName();
		}

		private static boolean nonBlank(String value)
		{
			return value != null && !value.isBlank();
		}
	}
}
