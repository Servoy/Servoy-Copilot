/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sablo.specification.PackageSpecification;
import org.sablo.specification.PropertyDescription;
import org.sablo.specification.SpecProviderState;
import org.sablo.specification.WebComponentSpecProvider;
import org.sablo.specification.WebObjectApiFunctionDefinition;
import org.sablo.specification.WebObjectFunctionDefinition;
import org.sablo.specification.WebObjectSpecification;
import org.sablo.specification.WebServiceSpecProvider;
import org.sablo.specification.Package.IPackageReader;
import org.sablo.specification.property.IPropertyType;

import com.servoy.j2db.util.Utils;

/**
 * Introspects the loaded Servoy web-component and web-service specifications (the parsed
 * {@code .spec} files) to expose component/service metadata and documentation to MCP clients.
 * <p>
 * Reads directly from the in-memory {@link WebComponentSpecProvider}/{@link WebServiceSpecProvider}
 * spec providers, so it reflects exactly what is installed in the active workspace for the running
 * Servoy version - no zip cracking or offline doc generation required.
 */
@Creatable
public class ComponentSpecService
{
	/**
	 * Lists all web components (or, when {@code services} is true, all web services), optionally
	 * filtered to a single package. Each entry carries identity metadata plus counts of model
	 * properties, handlers and api functions.
	 *
	 * @param packageName optional package name filter (e.g. "bootstrapcomponents"). Null/empty = all packages.
	 * @param services when true returns services instead of components.
	 */
	public JSONArray listObjects(String packageName, boolean services)
	{
		SpecProviderState state = getState(services);
		JSONArray result = new JSONArray();
		if (state == null) return result;

		Map<String, PackageSpecification<WebObjectSpecification>> packages = state.getWebObjectSpecifications();
		// stable, alphabetical ordering for predictable output
		for (Map.Entry<String, PackageSpecification<WebObjectSpecification>> pkgEntry : new TreeMap<>(packages).entrySet())
		{
			if (packageName != null && !packageName.isEmpty() && !packageName.equals(pkgEntry.getKey())) continue;

			PackageSpecification<WebObjectSpecification> pkg = pkgEntry.getValue();
			for (WebObjectSpecification spec : new TreeMap<>(pkg.getSpecifications()).values())
			{
				result.put(describeSummary(spec, pkgEntry.getKey()));
			}
		}
		return result;
	}

	/**
	 * Returns the full spec of a single component/service: model properties (with type, default,
	 * tags, scope, allowed values), handlers and api functions. This is the structured equivalent
	 * of reading the {@code .spec} file plus knowing the Servoy property-type semantics.
	 *
	 * @param objectName the spec name (e.g. "bootstrapcomponents-button").
	 * @param services when true looks up a service instead of a component.
	 * @return the spec JSON, or null if not found.
	 */
	public JSONObject getObjectSpec(String objectName, boolean services)
	{
		SpecProviderState state = getState(services);
		if (state == null) return null;
		WebObjectSpecification spec = state.getWebObjectSpecification(objectName);
		if (spec == null) return null;

		JSONObject json = describeSummary(spec, spec.getPackageName());

		// model properties
		JSONArray props = new JSONArray();
		Map<String, PropertyDescription> model = spec.getProperties();
		if (model != null)
		{
			for (PropertyDescription pd : new TreeMap<>(model).values())
			{
				props.put(describeProperty(pd));
			}
		}
		json.put("properties", props);

		// handlers (events)
		JSONArray handlers = new JSONArray();
		for (Map.Entry<String, WebObjectFunctionDefinition> h : new TreeMap<String, WebObjectFunctionDefinition>(spec.getHandlers()).entrySet())
		{
			handlers.put(describeFunction(h.getValue()));
		}
		json.put("handlers", handlers);

		// api functions (scripting; not set in .frm but useful context)
		JSONArray apis = new JSONArray();
		Map<String, WebObjectApiFunctionDefinition> apiFns = spec.getApiFunctions();
		if (apiFns != null)
		{
			for (Map.Entry<String, WebObjectApiFunctionDefinition> a : new TreeMap<String, WebObjectApiFunctionDefinition>(apiFns).entrySet())
			{
				apis.put(describeFunction(a.getValue()));
			}
		}
		json.put("apis", apis);

		return json;
	}

	/**
	 * Returns the documentation for a component/service. Prefers the dedicated {@code _doc.js}
	 * documentation file referenced by the spec; falls back to the inline per-property/per-handler
	 * {@code doc} descriptions collected from the spec itself.
	 *
	 * @param objectName the spec name.
	 * @param services when true looks up a service.
	 * @return a JSON object with the raw doc file (if any) and a structured per-member doc map, or null if not found.
	 */
	public JSONObject getObjectDocs(String objectName, boolean services)
	{
		SpecProviderState state = getState(services);
		if (state == null) return null;
		WebObjectSpecification spec = state.getWebObjectSpecification(objectName);
		if (spec == null) return null;

		JSONObject json = new JSONObject();
		json.put("name", spec.getName());
		json.put("displayName", spec.getDisplayName());
		String specDoc = spec.getDescriptionProcessed(true, null);
		if (!Utils.stringIsEmpty(specDoc)) json.put("description", specDoc);

		// dedicated _doc.js file referenced by the spec's "doc" key
		URL docURL = spec.getDocFileURL();
		if (docURL != null)
		{
			json.put("docFile", docURL.toString());
			IPackageReader reader = state.getPackageReader(spec.getPackageName());
			String docContent = readDocFile(reader, docURL);
			if (docContent != null) json.put("docFileContent", docContent);
		}

		// structured inline docs per property / handler / api
		JSONObject members = new JSONObject();
		if (spec.getProperties() != null)
		{
			for (PropertyDescription pd : spec.getProperties().values())
			{
				String d = pd.getDescriptionProcessed(true, null);
				if (!Utils.stringIsEmpty(d)) members.put("property:" + pd.getName(), d);
			}
		}
		for (WebObjectFunctionDefinition h : spec.getHandlers().values())
		{
			if (!Utils.stringIsEmpty(h.getDocumentation())) members.put("handler:" + h.getName(), h.getDocumentation());
		}
		if (spec.getApiFunctions() != null)
		{
			for (WebObjectFunctionDefinition a : spec.getApiFunctions().values())
			{
				if (!Utils.stringIsEmpty(a.getDocumentation())) members.put("api:" + a.getName(), a.getDocumentation());
			}
		}
		json.put("memberDocs", members);
		return json;
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private static SpecProviderState getState(boolean services)
	{
		return services ? WebServiceSpecProvider.getSpecProviderState() : WebComponentSpecProvider.getSpecProviderState();
	}

	private static JSONObject describeSummary(WebObjectSpecification spec, String packageName)
	{
		JSONObject json = new JSONObject();
		json.put("name", spec.getName());
		json.put("displayName", spec.getDisplayName());
		json.put("packageName", packageName);
		String cat = spec.getCategoryName();
		if (!Utils.stringIsEmpty(cat)) json.put("categoryName", cat);
		if (spec.isDeprecated())
		{
			json.put("deprecated", true);
			if (!Utils.stringIsEmpty(spec.getDeprecatedMessage())) json.put("deprecatedMessage", spec.getDeprecatedMessage());
			if (!Utils.stringIsEmpty(spec.getReplacement())) json.put("replacement", spec.getReplacement());
		}
		json.put("propertyCount", spec.getProperties() != null ? spec.getProperties().size() : 0);
		json.put("handlerCount", spec.getHandlers().size());
		json.put("apiCount", spec.getApiFunctions() != null ? spec.getApiFunctions().size() : 0);
		return json;
	}

	private static JSONObject describeProperty(PropertyDescription pd)
	{
		JSONObject json = new JSONObject();
		json.put("name", pd.getName());
		IPropertyType< ? > type = pd.getType();
		json.put("type", type != null ? type.getName() : "unknown");
		if (pd.hasDefault()) json.put("default", pd.getDefaultValue());
		if (pd.getInitialValue() != null) json.put("initialValue", pd.getInitialValue());
		if (pd.isOptional()) json.put("optional", true);

		Object scope = pd.getTag("scope");
		if (scope != null) json.put("scope", scope);

		List<Object> values = pd.getValues();
		if (values != null && !values.isEmpty())
		{
			json.put("values", new JSONArray(values));
		}
		if (pd.isDeprecated())
		{
			json.put("deprecated", true);
			if (!Utils.stringIsEmpty(pd.getDeprecatedMessage())) json.put("deprecatedMessage", pd.getDeprecatedMessage());
		}
		String desc = pd.getDescriptionProcessed(false, null);
		if (!Utils.stringIsEmpty(desc)) json.put("doc", desc);
		return json;
	}

	private static JSONObject describeFunction(WebObjectFunctionDefinition fn)
	{
		JSONObject json = new JSONObject();
		json.put("name", fn.getName());

		JSONArray params = new JSONArray();
		if (fn.getParameters() != null)
		{
			int count = fn.getParameters().getDefinedArgsCount();
			for (int i = 0; i < count; i++)
			{
				PropertyDescription p = fn.getParameters().getParameterDefinition(i);
				if (p == null) continue;
				JSONObject pj = new JSONObject();
				pj.put("name", p.getName());
				IPropertyType< ? > pt = p.getType();
				pj.put("type", pt != null ? pt.getName() : "unknown");
				params.put(pj);
			}
		}
		json.put("parameters", params);

		PropertyDescription rt = fn.getReturnType();
		if (rt != null && rt.getType() != null) json.put("returnType", rt.getType().getName());
		if (fn.isPrivate()) json.put("private", true);
		if (fn.isDeprecated())
		{
			json.put("deprecated", true);
			if (!Utils.stringIsEmpty(fn.getDeprecatedMessage())) json.put("deprecatedMessage", fn.getDeprecatedMessage());
		}
		if (!Utils.stringIsEmpty(fn.getDocumentation())) json.put("doc", fn.getDocumentation());
		return json;
	}

	private static String readDocFile(IPackageReader reader, URL docURL)
	{
		if (reader == null) return null;
		try
		{
			// the doc URL path is relative to the package; derive the in-package path from the URL
			String path = docURL.getPath();
			int metaInf = path.indexOf("/META-INF/");
			String inPackagePath = metaInf >= 0 ? path.substring(metaInf + 1) : path;
			return reader.readTextFile(inPackagePath, StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	List<String> packageNames(boolean services)
	{
		SpecProviderState state = getState(services);
		List<String> names = new ArrayList<>();
		if (state != null) names.addAll(state.getWebObjectSpecifications().keySet());
		return names;
	}
}
