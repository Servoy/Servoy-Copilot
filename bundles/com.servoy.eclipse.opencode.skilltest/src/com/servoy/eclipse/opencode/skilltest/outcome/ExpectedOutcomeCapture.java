/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode.skilltest.outcome;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;

/**
 * Pre-fills a baseline's form expectations from the <b>live workspace</b>: for
 * each {@code kind:form} assertion that names a form present in the active
 * project's editing flattened solution, it captures that form's {@code .frm}
 * JSON, strips the volatile bits ({@link FormStructureNormalizer}), and stores
 * the result inline as the assertion's expected {@code form} (SVY-21366).
 * <p>
 * This is the capture behind both authoring entry points - <i>Edit expected
 * outcomes</i> on an existing baseline and the import/confirm screen after a
 * session export - so the editor opens pre-filled with the real form that is in
 * the workspace, which the tester then tweaks (trims keys, turns a literal into
 * a SQL-{@code LIKE} pattern) rather than hand-writing it.
 * </p>
 * <p>
 * Forms are resolved against the <b>editing flattened</b> solution
 * ({@link ServoyProject#getEditingFlattenedSolution()}), not the active
 * solution's own objects, so a form that lives in a module is found - the same
 * rule the runtime, the designer and the checker follow.
 * </p>
 */
public final class ExpectedOutcomeCapture {

	private ExpectedOutcomeCapture() {
	}

	/**
	 * Returns a copy of {@code assertions} with each form assertion's inline
	 * expected {@code .frm} filled in from the live workspace form of the same
	 * name. A non-form assertion, a form not found in the workspace, or (when
	 * {@code overwrite} is {@code false}) a form assertion that already carries an
	 * expected {@code .frm} is passed through unchanged.
	 *
	 * @param assertions the current assertions (may be {@code null} / empty)
	 * @param overwrite  {@code true} to re-capture even when the assertion already
	 *                   has an expected {@code .frm} (an explicit "capture from
	 *                   workspace, overwrite my edits"); {@code false} to only fill
	 *                   the ones that are still empty (the safe default for Edit)
	 * @return a new list; never {@code null}
	 */
	public static List<OutcomeAssertion> prefillFromWorkspace(List<OutcomeAssertion> assertions, boolean overwrite) {
		List<OutcomeAssertion> out = new ArrayList<>();
		if (assertions == null) {
			return out;
		}
		FlattenedSolution fs = activeFlattenedSolution();
		for (OutcomeAssertion a : assertions) {
			out.add(prefillOne(a, fs, overwrite));
		}
		return out;
	}

	private static OutcomeAssertion prefillOne(OutcomeAssertion a, FlattenedSolution fs, boolean overwrite) {
		if (fs == null || a == null || a.kind() != PersistKind.FORM || a.name() == null || a.name().isBlank()) {
			return a;
		}
		if (a.structure() != null && !overwrite) {
			return a; // keep the tester's tweaks
		}
		Form form = fs.getForm(a.name());
		if (form == null) {
			return a; // not in the workspace (yet) - leave the assertion as it is
		}
		ArrayNode captured = FormStructureNormalizer.captureStrippedItems(form);
		if (captured == null) {
			return a;
		}
		// Keep any inference placeholder children (the synthetic
		// <replace-with-component-type> components): they are a signal to the LLM
		// generation to replace each with the real component type read from the
		// captured form, not noise to drop (SVY-21366).
		return a.withStructure(captured);
	}

	/**
	 * @return the active project's editing flattened solution (includes modules),
	 *         or {@code null} when there is no active project or the model is
	 *         unavailable (e.g. outside a running workbench)
	 */
	private static FlattenedSolution activeFlattenedSolution() {
		try {
			IServoyModel model = ServoyModelFinder.getServoyModel();
			ServoyProject active = model != null ? model.getActiveProject() : null;
			return active != null ? active.getEditingFlattenedSolution() : null;
		} catch (RuntimeException | LinkageError ex) {
			return null;
		}
	}
}
