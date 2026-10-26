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

import java.util.Collections;
import java.util.List;

import com.servoy.eclipse.opencode.skilltest.Baseline;

/**
 * The verification strategy an AI baseline-authoring agent
 * ({@link VerificationPlanAgent}) proposes for a freshly imported session export
 * (SVY-21366): which of the three check kinds fit this run and the concrete,
 * generated content for each. The human reviews, edits and approves it before
 * the baseline is written.
 * <p>
 * A plan may carry any combination of the three checks (and, in the degenerate
 * fallback, only {@link #persists()}). Each is independently nullable/empty so
 * the import flow shows an editor only for the checks the agent actually
 * proposed:
 * </p>
 * <ul>
 * <li>{@link #persists()} &rarr; {@code expect.persists} - deterministic
 * structural artifacts (a valuelist's values, a relation, a scope method, a
 * form's datasource binding). Empty when the agent judged structure too volatile
 * to assert.</li>
 * <li>{@link #acceptance()} &rarr; {@code verify.acceptanceCriteria} - an LLM
 * judge grading natural-language criteria, for LLM-built visual/layout UI whose
 * structure legitimately varies run-to-run. {@code null} when not proposed.</li>
 * <li>{@link #jsunit()} &rarr; {@code verify.jsunit} - a generated, name-tolerant
 * JSUnit script proving behaviour the structure cannot. {@code null} when not
 * proposed.</li>
 * </ul>
 *
 * @param persists   the proposed {@code expect.persists} assertions (never
 *                   {@code null}; empty = no structural check)
 * @param acceptance the proposed acceptance-criteria check, or {@code null}
 * @param jsunit     the proposed JSUnit check, or {@code null}
 * @param rationale  the agent's one-paragraph justification for the chosen mix,
 *                   shown to the human in the review UI (may be {@code null})
 */
public record VerificationPlan(List<OutcomeAssertion> persists, Baseline.AcceptanceVerify acceptance,
		JsUnitPlan jsunit, String rationale) {

	public VerificationPlan {
		persists = persists == null ? Collections.emptyList() : List.copyOf(persists);
	}

	/** @return {@code true} when the plan proposes a structural persist check. */
	public boolean hasPersists() {
		return !persists.isEmpty();
	}

	/** @return {@code true} when the plan proposes an acceptance-criteria check. */
	public boolean hasAcceptance() {
		return acceptance != null && acceptance.hasCriteria();
	}

	/** @return {@code true} when the plan proposes a JSUnit check. */
	public boolean hasJsUnit() {
		return jsunit != null && jsunit.hasScript();
	}

	/** @return a plan carrying only the given persists (the offline fallback). */
	public static VerificationPlan persistsOnly(List<OutcomeAssertion> persists, String rationale) {
		return new VerificationPlan(persists, null, null, rationale);
	}

	/**
	 * A proposed JSUnit check: the <b>source</b> of a generated, name-tolerant
	 * {@code test_} script (not a path - the script does not exist on disk yet; the
	 * import flow writes it into the baseline folder), plus the scope/form it runs
	 * in and whether it gates the baseline.
	 *
	 * @param scope        the scope/form/{@code ALL}/{@code MODULES}/{@code FORMS}
	 *                     the script's tests run in (e.g. a global scope name)
	 * @param scriptName   the base file name to store the script as (e.g.
	 *                     {@code skilltest_verify}); the {@code .js} suffix and the
	 *                     solution-relative location are supplied by the writer
	 * @param scriptSource the generated JavaScript ({@code test_} functions), which
	 *                     MUST query the model/runtime rather than hard-code
	 *                     LLM-chosen element names
	 * @param method       an optional single {@code test_} method to run, or
	 *                     {@code null}/blank to run every test in {@code scope}
	 * @param required     if {@code false}, a JSUnit failure is reported but does
	 *                     not fail the baseline
	 */
	public record JsUnitPlan(String scope, String scriptName, String scriptSource, String method, boolean required) {

		/** @return {@code true} when there is generated script source to write. */
		public boolean hasScript() {
			return scriptSource != null && !scriptSource.isBlank();
		}
	}
}
