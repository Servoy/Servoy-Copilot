/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.opencode;

/**
 * Holds the branding CSS and JS injection constants for the embedded OpenCode view.
 * <p>
 * Extracted from {@link OpenCodeView} so that tests can verify the injected scripts
 * without requiring SWT or a running workbench.
 * </p>
 */
public class OpenCodeBranding
{
	/** Tool names that should be hidden from the conversation view. */
	static final String[] HIDDEN_TOOL_PREFIXES = { "scratchpad_think", "scratchpad_completion_meta" };

	/** CSS selector for OpenCode tool-call wrapper elements. */
	static final String TOOL_WRAPPER_SELECTOR = "[data-component=\"tool-part-wrapper\"]";

	/**
	 * CSS injected into the opencode web app on every page load to apply Servoy
	 * branding.
	 * <p>
	 * The v2 theme system uses a colour scale ({@code --v2-blue-100} through
	 * {@code --v2-blue-1200}) that all accent semantic tokens reference. By
	 * overriding the blue scale with a Servoy-orange scale centred on
	 * {@code #f5a623}, the entire UI picks up orange automatically.
	 * </p>
	 * <p>
	 * Legacy (pre-v2) variable overrides are kept as a fallback for any elements
	 * still using the old token names.
	 * </p>
	 */
	static final String BRAND_CSS = """
			:root {
			  /* ===== v2 theme: override blue scale with Servoy orange ===== */
			  --v2-blue-100: #fef6e8 !important;
			  --v2-blue-200: #fdecc8 !important;
			  --v2-blue-300: #fce0a4 !important;
			  --v2-blue-400: #f8c46a !important;
			  --v2-blue-500: #f7b342 !important;
			  --v2-blue-600: #f5a623 !important;
			  --v2-blue-700: #d4891e !important;
			  --v2-blue-800: #b5741a !important;
			  --v2-blue-900: #965f16 !important;
			  --v2-blue-1000: #7a4d12 !important;
			  --v2-blue-1100: #5e3b0e !important;
			  --v2-blue-1200: #462c0a !important;

			  /* ===== Legacy (pre-v2) fallback overrides ===== */
			  --surface-brand-base: #f5a623 !important;
			  --surface-brand-hover: #d4891e !important;
			  --surface-interactive-base: rgba(245, 166, 35, 0.15) !important;
			  --surface-interactive-hover: rgba(245, 166, 35, 0.25) !important;
			  --surface-interactive-weak: rgba(245, 166, 35, 0.08) !important;
			  --surface-interactive-weak-hover: rgba(245, 166, 35, 0.15) !important;
			  --text-interactive-base: #f8c46a !important;
			  --border-interactive-base: #f5a623 !important;
			  --border-interactive-hover: #d4891e !important;
			  --border-interactive-active: #b5741a !important;
			  --icon-strong-base: #f5a623 !important;
			  --icon-strong-hover: #d4891e !important;
			  --icon-strong-active: #b5741a !important;
			  --icon-brand-base: #f5a623 !important;
			  --icon-interactive-base: #f8c46a !important;
			}
			/* ===== Send button: override contrast bg to Servoy orange ===== */
			/* New layout send button uses inline style with --v2-background-bg-contrast */
			[data-action="prompt-submit"] {
			  --v2-background-bg-contrast: #f5a623 !important;
			  color: #ffffff !important;
			}
			[data-action="prompt-submit"]:hover {
			  --v2-background-bg-contrast: #d4891e !important;
			}
			[data-action="prompt-submit"] svg {
			  color: #ffffff !important;
			}
			/* Old layout primary icon button */
			[data-component="icon-button"][data-variant="primary"]:not(:disabled) {
			  background-color: #f5a623 !important;
			}
			[data-component="icon-button"][data-variant="primary"]:not(:disabled):hover {
			  background-color: #d4891e !important;
			}
			/* Hide the session sidebar toggle button - panel is opened via JS */
			[data-component="icon-button"][data-icon="menu"].titlebar-icon {
			  display: none !important;
			}
			/* Hide the terminal toggle - not needed in the embedded view */
			[aria-controls="terminal-panel"] {
			  display: none !important;
			}
			/* Hide sidebar rail content (project switcher, settings, help)
			   but keep its width so the layout does not shift */
			[data-component="sidebar-rail"] {
			  visibility: hidden !important;
			}
			/* Hide the OpenCode watermark SVG and logo-splash SVG */
			svg.text-v2-background-bg-inverse,
			svg[data-component="logo-splash"] {
			  display: none !important;
			}
			/* Style for the Servoy replacement text injected by JS */
			.servoy-brand-text {
			  font-size: 2rem;
			  font-weight: 800;
			  color: #f5a623;
			  letter-spacing: 0.05em;
			  user-select: none;
			  pointer-events: none;
			  text-align: center;
			  width: 100%;
			  padding: 1rem 0;
			}
			""";

	/**
	 * Returns {@code true} if a tool-call element with the given text content
	 * should be hidden from the conversation view.
	 * <p>
	 * This is the Java equivalent of the JS filtering logic injected into the
	 * OpenCode DOM — used for testability.
	 *
	 * @param textContent the text content of the tool-part-wrapper element
	 */
	static boolean shouldHideToolCall(String textContent)
	{
		if (textContent == null || textContent.isEmpty()) return false;
		for (String prefix : HIDDEN_TOOL_PREFIXES)
		{
			if (textContent.contains(prefix)) return true;
		}
		return false;
	}

	/**
	 * Builds the full JavaScript IIFE that is injected on every page load.
	 * Injects branding CSS, replaces placeholder text, hides scratchpad tool
	 * calls, and registers a MutationObserver for dynamically rendered content.
	 */
	static String buildInjectScript()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("(function(){");
		// CSS: inject once
		sb.append("  if (!document.getElementById('servoy-brand')) {");
		sb.append("    var s = document.createElement('style');");
		sb.append("    s.id = 'servoy-brand';");
		sb.append("    s.textContent = ").append(toJsString(BRAND_CSS)).append(";");
		sb.append("    document.head.appendChild(s);");
		sb.append("  }");
		// Branding fixup: text replacement, logo replacement, scratchpad hiding
		sb.append("  function fixBranding() {");
		// Replace "Build anything" text (English fallback for older UI versions)
		sb.append("    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);");
		sb.append("    var n;");
		sb.append("    while ((n = w.nextNode()) !== null) {");
		sb.append("      if (n.nodeValue.trim() === 'Build anything') {");
		sb.append("        n.nodeValue = 'What should we build in Servoy today?';");
		sb.append("      }");
		sb.append("    }");
		// Replace OpenCode watermark SVG (has class text-v2-background-bg-inverse) with Servoy branding text
		sb.append("    document.querySelectorAll('svg.text-v2-background-bg-inverse').forEach(function(svg) {");
		sb.append("      if (!svg.dataset.svyReplaced) {");
		sb.append("        svg.style.display = 'none';");
		sb.append("        svg.dataset.svyReplaced = '1';");
		sb.append("        var div = document.createElement('div');");
		sb.append("        div.className = 'servoy-brand-text';");
		sb.append("        div.textContent = 'What should we build in Servoy today?';");
		sb.append("        svg.parentNode.insertBefore(div, svg);");
		sb.append("      }");
		sb.append("    });");
		// Hide logo-splash and decorative logo-mark SVGs
		sb.append("    document.querySelectorAll('svg').forEach(function(svg) {");
		sb.append("      var vb = svg.getAttribute('viewBox');");
		sb.append("      if ((vb === '0 0 80 100' || svg.getAttribute('data-component') === 'logo-splash') && !svg.dataset.svyReplaced) {");
		sb.append("        svg.style.display = 'none';");
		sb.append("        svg.dataset.svyReplaced = '1';");
		sb.append("      }");
		sb.append("      if (svg.getAttribute('data-component') === 'logo-mark' && svg.classList.contains('w-10') && !svg.dataset.svyReplaced) {");
		sb.append("        svg.style.display = 'none';");
		sb.append("        svg.dataset.svyReplaced = '1';");
		sb.append("      }");
		sb.append("    });");
		// Hide scratchpad tool calls
		sb.append("    document.querySelectorAll('").append(TOOL_WRAPPER_SELECTOR).append("').forEach(function(el) {");
		sb.append("      var txt = el.textContent || '';");
		sb.append("      if (");
		for (int i = 0; i < HIDDEN_TOOL_PREFIXES.length; i++)
		{
			if (i > 0) sb.append(" || ");
			sb.append("txt.indexOf('").append(HIDDEN_TOOL_PREFIXES[i]).append("') !== -1");
		}
		sb.append(") {");
		sb.append("        el.style.display = 'none';");
		sb.append("      }");
		sb.append("    });");
		sb.append("  }");
		sb.append("  fixBranding();");
		// MutationObserver for dynamic content
		sb.append("  if (!window._svyBrandObs) {");
		sb.append("    window._svyBrandObs = new MutationObserver(fixBranding);");
		sb.append("    window._svyBrandObs.observe(document.body, {childList: true, subtree: true});");
		sb.append("  }");
		sb.append("})();");
		return sb.toString();
	}

	/**
	 * Wraps a CSS string in a JavaScript single-quoted string literal.
	 */
	static String toJsString(String css)
	{
		String escaped = css
			.replace("\\", "\\\\")
			.replace("'", "\\'")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
		return "'" + escaped + "'";
	}
}
