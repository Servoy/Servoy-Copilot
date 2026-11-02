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

import java.util.Locale;

import com.servoy.base.persistence.constants.IRepositoryConstants;

/**
 * The kinds of Servoy persist an AI skill run can create or modify, used by the
 * outcome model to assert on the real in-memory Servoy persist tree rather than
 * on the transcript or on {@code .frm}/{@code .val} file text (SVY-21366).
 * <p>
 * Each kind maps to a Servoy {@link IRepositoryConstants} type id so the
 * {@code PersistOutcomeChecker} can identify a matching persist generically
 * (via {@code AbstractBase.getTypeID()}) and, for root persists, look it up by
 * name on the active {@code Solution}. The enum is deliberately transcript/
 * file-format independent: it names <em>outcomes</em> (a form, a value list, a
 * global method, a component bound to a dataprovider), not tools.
 * </p>
 */
public enum PersistKind {

	/** A form. Solution-level root persist, looked up by name. */
	FORM(IRepositoryConstants.FORMS, true),
	/** A value list. Root, by name. */
	VALUELIST(IRepositoryConstants.VALUELISTS, true),
	/** A relation. Root, by name. */
	RELATION(IRepositoryConstants.RELATIONS, true),
	/** A menu. Root, by name. */
	MENU(IRepositoryConstants.MENUS, true),
	/** A media entry. Root, by name. */
	MEDIA(IRepositoryConstants.MEDIA, true),
	/**
	 * A script method - a global/scope method or a form method. Located by name
	 * (and optional scope) rather than as a solution root.
	 */
	SCRIPTMETHOD(IRepositoryConstants.METHODS, false),
	/**
	 * A script variable - a global/scope variable or a form variable. Located by
	 * name (and optional scope).
	 */
	SCRIPTVARIABLE(IRepositoryConstants.SCRIPTVARIABLES, false),
	/** A script calculation. */
	CALCULATION(IRepositoryConstants.SCRIPTCALCULATIONS, false),
	/** An aggregate variable. */
	AGGREGATE(IRepositoryConstants.AGGREGATEVARIABLES, false),
	/**
	 * A web component on a form. A child persist, matched inside a form by its
	 * {@code dataProviderID}/name/type.
	 */
	COMPONENT(IRepositoryConstants.WEBCOMPONENTS, false),
	/** A legacy field element. Child of a form. */
	FIELD(IRepositoryConstants.FIELDS, false),
	/** A graphical component / legacy label. Child of a form. */
	GRAPHICALCOMPONENT(IRepositoryConstants.GRAPHICALCOMPONENTS, false),
	/** A layout container in a responsive form. */
	LAYOUTCONTAINER(IRepositoryConstants.LAYOUTCONTAINERS, false),
	/** Any persist kind not modelled above - matched by type id only when known. */
	OTHER(-1, false);

	private final int typeId;
	private final boolean rootByName;

	PersistKind(int typeId, boolean rootByName) {
		this.typeId = typeId;
		this.rootByName = rootByName;
	}

	/**
	 * @return the Servoy {@link IRepositoryConstants} type id for this kind, or
	 *         {@code -1} for {@link #OTHER}
	 */
	public int typeId() {
		return typeId;
	}

	/**
	 * @return {@code true} if this kind is a solution-level root persist that can
	 *         be looked up by name (form, value list, relation, menu, media)
	 */
	public boolean isRootByName() {
		return rootByName;
	}

	/**
	 * Parses a kind from its sidecar token (case-insensitive), tolerating a few
	 * common aliases. Unknown tokens map to {@link #OTHER}.
	 *
	 * @param token the {@code kind} value from {@code baseline.json}
	 * @return the matching kind (never {@code null})
	 */
	public static PersistKind fromToken(String token) {
		if (token == null) {
			return OTHER;
		}
		String t = token.trim().toLowerCase(Locale.ROOT);
		switch (t) {
		case "form": //$NON-NLS-1$
			return FORM;
		case "valuelist": //$NON-NLS-1$
		case "value_list": //$NON-NLS-1$
		case "vl": //$NON-NLS-1$
			return VALUELIST;
		case "relation": //$NON-NLS-1$
			return RELATION;
		case "menu": //$NON-NLS-1$
			return MENU;
		case "media": //$NON-NLS-1$
			return MEDIA;
		case "scriptmethod": //$NON-NLS-1$
		case "method": //$NON-NLS-1$
		case "function": //$NON-NLS-1$
			return SCRIPTMETHOD;
		case "scriptvariable": //$NON-NLS-1$
		case "variable": //$NON-NLS-1$
			return SCRIPTVARIABLE;
		case "calculation": //$NON-NLS-1$
		case "calc": //$NON-NLS-1$
			return CALCULATION;
		case "aggregate": //$NON-NLS-1$
			return AGGREGATE;
		case "component": //$NON-NLS-1$
		case "webcomponent": //$NON-NLS-1$
			return COMPONENT;
		case "field": //$NON-NLS-1$
			return FIELD;
		case "label": //$NON-NLS-1$
		case "graphicalcomponent": //$NON-NLS-1$
			return GRAPHICALCOMPONENT;
		case "layoutcontainer": //$NON-NLS-1$
		case "container": //$NON-NLS-1$
			return LAYOUTCONTAINER;
		default:
			return OTHER;
		}
	}

	/** @return the lower-case token used for this kind in {@code baseline.json} */
	public String token() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * A concrete, readable hint telling the acceptance judge exactly which
	 * solution file(s) to open for an artifact of this kind named {@code name},
	 * rather than a vague "find it by name" (which has no file-path recipe and
	 * left the judge with nothing to read - SVY-21366). Servoy stores each root
	 * persist at a predictable path under the solution project:
	 * <ul>
	 * <li>form -&gt; {@code forms/<name>.frm} (+ its {@code .js}/{@code .less});</li>
	 * <li>value list -&gt; {@code valuelists/<name>.val};</li>
	 * <li>relation -&gt; {@code relations/<name>.rel};</li>
	 * <li>menu -&gt; {@code menus/<name>.men};</li>
	 * <li>media -&gt; {@code medias/<name>} (and the {@code medias.obj} index);</li>
	 * <li>scope/global method or variable -&gt; the scope file {@code <scope>.js}
	 * at the solution root.</li>
	 * </ul>
	 *
	 * @param name the artifact name
	 * @return a short phrase naming the file(s) to read, or {@code null} when the
	 *         kind has no single predictable file (e.g. a child component, whose
	 *         file is its owning form's {@code .frm})
	 */
	public String sourceHint(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		switch (this) {
		case FORM:
			return "`forms/" + name + ".frm` (plus its `forms/" + name + ".js` and `forms/" + name //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ ".less` when present)"; //$NON-NLS-1$
		case VALUELIST:
			return "`valuelists/" + name + ".val`"; //$NON-NLS-1$ //$NON-NLS-2$
		case RELATION:
			return "`relations/" + name + ".rel`"; //$NON-NLS-1$ //$NON-NLS-2$
		case MENU:
			return "`menus/" + name + ".men`"; //$NON-NLS-1$ //$NON-NLS-2$
		case MEDIA:
			return "`medias/" + name + "` (and the `medias.obj` index)"; //$NON-NLS-1$ //$NON-NLS-2$
		default:
			return null;
		}
	}
}
