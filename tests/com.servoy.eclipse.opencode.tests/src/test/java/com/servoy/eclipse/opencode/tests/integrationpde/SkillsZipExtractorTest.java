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

package com.servoy.eclipse.opencode.tests.integrationpde;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.OpenCodeUtil;
import com.servoy.eclipse.opencode.SkillsZipExtractor;

/**
 * Unit tests for the {@link SkillsZipExtractor} and
 * {@link OpenCodeUtil} utility classes.
 * <p>
 * These are the ones that require the OSGi runtime.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class SkillsZipExtractorTest {

	/** HTTPS URL is returned with plugin_version appended -- no file-existence check is performed. */
	@Test
	public void getSkillsZipSource_httpsUrl_returnsUrlWithPluginVersion() {
		String url = "https://cloud.servoy.com/skills.zip?loginToken=abc123";
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, url);
		try {
			String result = SkillsZipExtractor.getSkillsZipSource();
			assertTrue(result.startsWith(url), "HTTPS URL must start with original URL");
			assertTrue(result.contains("&plugin_version="), "HTTPS URL must contain plugin_version parameter");
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/** HTTP URL is returned with plugin_version appended -- no file-existence check is performed. */
	@Test
	public void getSkillsZipSource_httpUrl_returnsUrlWithPluginVersion() {
		String url = "http://example.com/skills.zip?loginToken=abc123";
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, url);
		try {
			String result = SkillsZipExtractor.getSkillsZipSource();
			assertTrue(result.startsWith(url), "HTTP URL must start with original URL");
			assertTrue(result.contains("&plugin_version="), "HTTP URL must contain plugin_version parameter");
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}

	/**
	 * AC: Pre-set SERVOY_SKILLS_ZIP with an HTTPS URL is preserved by
	 * getSkillsZipSource (with plugin_version appended).
	 */
	@Test
	public void getSkillsZipSource_preSetToHttpsUrl_preservesValue() {
		String customUrl = "https://my-server.com/custom-skills.zip";
		System.setProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY, customUrl);
		try {
			String result = SkillsZipExtractor.getSkillsZipSource();
			assertTrue(result.startsWith(customUrl), "Pre-set HTTPS URL must start with original URL");
			assertTrue(result.contains("&plugin_version="), "Pre-set HTTPS URL must contain plugin_version parameter");
		} finally {
			System.clearProperty(SkillsZipExtractor.SKILLS_ZIP_PROPERTY);
		}
	}
	
}