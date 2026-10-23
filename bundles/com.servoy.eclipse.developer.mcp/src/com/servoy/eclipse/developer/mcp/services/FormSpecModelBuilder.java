/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.cypress.services.FormSpecModel;
import com.servoy.eclipse.cypress.services.FormSpecModel.Effect;
import com.servoy.eclipse.cypress.services.FormSpecModel.EffectKind;
import com.servoy.eclipse.cypress.services.FormSpecModel.Gesture;
import com.servoy.eclipse.cypress.services.FormSpecModel.Handler;
import com.servoy.eclipse.cypress.services.FormSpecModel.Kind;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;

/**
 * Builds the neutral {@link FormSpecModel} (consumed by the Cypress spec generator) from the interactive elements of a form and the effects that
 * {@link HandlerEffectRecognizer} finds in the bound handler methods.
 */
public class FormSpecModelBuilder
{
	/**
	 * Resolves the form like {@link FormInteractiveElementsService#getElements} does and builds the model.
	 *
	 * @return the model, or {@code null} when there is no active solution or the form is not found (callers then fall back to the legacy spec)
	 */
	public FormSpecModel build(String formName)
	{
		if (formName == null || formName.isBlank())
		{
			return null;
		}
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null)
		{
			return null;
		}
		FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		if (fs == null)
		{
			return null;
		}
		Form form = fs.getForm(formName.trim());
		if (form == null)
		{
			return null;
		}
		return fromElements(formName, new FormInteractiveElementsService().collect(form, fs, true));
	}

	/**
	 * Pure part of the builder, no workspace needed. Unnamed and spec-missing elements are dropped (they cannot be targeted by {@code data-cy}), the
	 * first occurrence of a duplicate name is kept.
	 */
	public static FormSpecModel fromElements(String formName, List<InteractiveElement> elements)
	{
		Map<String, InteractiveElement> byName = new LinkedHashMap<>();
		if (elements != null)
		{
			for (InteractiveElement element : elements)
			{
				if (element == null || element.isUnnamed() || element.specMissing())
				{
					continue;
				}
				byName.putIfAbsent(element.name(), element);
			}
		}

		List<FormSpecModel.Element> result = new ArrayList<>();
		for (InteractiveElement element : byName.values())
		{
			boolean hasDataProvider = element.dataProvider() != null;
			List<Handler> handlers = new ArrayList<>();
			for (HandlerBinding binding : element.handlers())
			{
				handlers.add(toHandler(binding, element.type(), hasDataProvider, byName));
			}
			result.add(new FormSpecModel.Element(element.name(), element.type(), toKind(element.kind()), hasDataProvider, element.visible(), handlers));
		}
		return new FormSpecModel(formName, result);
	}

	private static Handler toHandler(HandlerBinding binding, String elementType, boolean hasDataProvider, Map<String, InteractiveElement> names)
	{
		Gesture gesture = Gesture.forHandler(binding.handler(), elementType, hasDataProvider);
		if (binding.unresolved() || gesture == Gesture.NONE)
		{
			return new Handler(binding.handler(), binding.method(), binding.owner(), gesture, binding.unresolved(), false, List.of());
		}

		HandlerEffectRecognizer.Result analysis = HandlerEffectRecognizer.analyze(binding.declaration());
		List<Effect> effects = new ArrayList<>();
		for (int i = 0; i < analysis.effects().size(); i++)
		{
			Effect effect = analysis.effects().get(i);
			if (effect.kind() != EffectKind.OFF_FORM && !names.containsKey(effect.target()))
			{
				effect = new Effect(EffectKind.OFF_FORM, null, analysis.sources().get(i));
			}
			effects.add(effect);
		}
		return new Handler(binding.handler(), binding.method(), binding.owner(), gesture, false, analysis.branching(), effects);
	}

	private static Kind toKind(String kind)
	{
		if (InteractiveElement.KIND_CONTROL.equals(kind))
		{
			return Kind.CONTROL;
		}
		if (InteractiveElement.KIND_INPUT.equals(kind))
		{
			return Kind.INPUT;
		}
		if (InteractiveElement.KIND_CONTAINER.equals(kind))
		{
			return Kind.CONTAINER;
		}
		return Kind.STATIC;
	}
}
