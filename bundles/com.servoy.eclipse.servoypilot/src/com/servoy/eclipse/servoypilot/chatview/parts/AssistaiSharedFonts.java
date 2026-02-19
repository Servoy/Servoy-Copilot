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
package com.servoy.eclipse.servoypilot.chatview.parts;

import java.io.ByteArrayInputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

import org.apache.fontbox.ttf.TTFParser;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.google.common.io.Files;

import jakarta.inject.Inject;

@Creatable
public class AssistaiSharedFonts
{
	@Inject
	private ILog logger;

	@Inject
	private AssistaiSharedFiles sharedFiles;

	private record FontInfo(String fontFamily, String fontWeight, String fontStyle, String fontDataBase64, String format)
	{
	};


	private FontInfo readFontWithFontBox(String path)
	{
		var fontWeight = "normal";
		var fontStyle = "normal";
		var fontFamily = Paths.get(path).getFileName().toString()
			.replaceFirst("\\.[^.]+$", "")
			.replaceAll("[-_](Regular|Bold|Italic|BoldItalic|Solid|Light|Thin|Duotone|Brands)$", "");
		var format = Files.getFileExtension(Paths.get(path).getFileName().toString());
		if ("ttf".equalsIgnoreCase(format))
		{
			format = "truetype";
		}

		// Infer style from filename
		String filename = Paths.get(path).getFileName().toString();
		if (filename.contains("Italic"))
		{
			fontStyle = "italic";
		}
		if (filename.contains("Bold"))
		{
			fontWeight = "bold";
		}
		if (filename.contains("solid"))
		{
			fontWeight = "900";
		}
		if (filename.contains("regular"))
		{
			fontWeight = "400";
		}

		try
		{
			var fontData = sharedFiles.readResourceBytes(path);
			var fontDataBase64 = Base64.getEncoder().encodeToString(fontData);
			var parser = new TTFParser();
			try (var is = new ByteArrayInputStream(fontData))
			{
				var ttf = parser.parseEmbedded(is);

				// Extract font name if available
				var name = ttf.getNaming().getFontFamily();
				if (name != null && !name.isEmpty())
				{
					fontFamily = name;
					// normalize family name
					fontFamily = fontFamily.replaceAll("\\s+(Regular|Solid|Light|Thin|Duotone|Brands)$", "");
				}
				// Additional metadata could be extracted here
				ttf.close();
			}
			return new FontInfo(fontFamily, fontWeight, fontStyle, fontDataBase64, format);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error parsing font " + path + ": " + e.getMessage(), e);
		}

	}


	private String toFontFaceCss(FontInfo fontInfo)
	{
		String fontFaceTemplate = """
			    @font-face {
			          font-family: '${fontFamily}';
			          src: url('data:font/${format};base64,${fontDataBase64}') format('${format}');
			          font-weight: ${fontWeight};
			          font-style: ${fontStyle};
			    }
			""";

		fontFaceTemplate = fontFaceTemplate.replace("${fontFamily}", fontInfo.fontFamily)
			.replace("${fontWeight}", fontInfo.fontWeight)
			.replace("${fontStyle}", fontInfo.fontStyle)
			.replace("${fontDataBase64}", fontInfo.fontDataBase64)
			.replace("${format}", fontInfo.format);

		return fontFaceTemplate;
	}

	/**
	 * Loads all the required fonts and generates CSS with embedded font data
	 * @return CSS containing all font definitions
	 */
	public String loadFontsCss()
	{
		String[] fontFiles = { "fonts/fa-regular-400.ttf", "fonts/fa-solid-900.ttf", "fonts/KaTeX_AMS-Regular.ttf", "fonts/KaTeX_Caligraphic-Bold.ttf", "fonts/KaTeX_Caligraphic-Regular.ttf", "fonts/KaTeX_Fraktur-Bold.ttf", "fonts/KaTeX_Fraktur-Regular.ttf", "fonts/KaTeX_Main-Bold.ttf", "fonts/KaTeX_Main-BoldItalic.ttf", "fonts/KaTeX_Main-Italic.ttf", "fonts/KaTeX_Main-Regular.ttf", "fonts/KaTeX_Math-BoldItalic.ttf", "fonts/KaTeX_Math-Italic.ttf", "fonts/KaTeX_SansSerif-Bold.ttf", "fonts/KaTeX_SansSerif-Italic.ttf", "fonts/KaTeX_SansSerif-Regular.ttf", "fonts/KaTeX_Script-Regular.ttf", "fonts/KaTeX_Size1-Regular.ttf", "fonts/KaTeX_Size2-Regular.ttf", "fonts/KaTeX_Size3-Regular.ttf", "fonts/KaTeX_Size4-Regular.ttf", "fonts/KaTeX_Typewriter-Regular.ttf"
		};
		try
		{
			var css = Arrays.stream(fontFiles)
				.map(this::readFontWithFontBox)
				.map(this::toFontFaceCss)
				.collect(Collectors.joining("\n\n"));
			return css;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}


}
