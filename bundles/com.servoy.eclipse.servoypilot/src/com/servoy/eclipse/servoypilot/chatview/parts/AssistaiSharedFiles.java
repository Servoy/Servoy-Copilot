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

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Singleton;

@Creatable
@Singleton
public class AssistaiSharedFiles
{
	private static final String baseURI = "platform:/plugin/com.servoy.eclipse.servoypilot/";

	private final Map<String, String> cache = new HashMap<String, String>();

	public AssistaiSharedFiles()
	{
	}

	public String readFile(String platformRelativePath)
	{
		return cache.computeIfAbsent(platformRelativePath, this::readResourceString);
	}

	private URI createURI(String platformRelativePath)
	{
		var path = platformRelativePath.startsWith("/") ? platformRelativePath.substring(1) : platformRelativePath;
		return URI.create(baseURI + path);

	}

	private String readResourceString(String platformRelativePath)
	{
		return new String(readResourceBytes(platformRelativePath), StandardCharsets.UTF_8);
	}

	private String readResourceBase64(String platformRelativePath)
	{

		return Base64.getEncoder().encodeToString(readResourceBytes(platformRelativePath));
	}


	public byte[] readResourceBytes(String platformRelativePath)
	{
		try
		{
			var uri = createURI(platformRelativePath).toURL();
			try (InputStream in = FileLocator.toFileURL(uri).openStream())
			{
				var bytes = in.readAllBytes();
				return bytes;
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException("Cannot read resource file: " + platformRelativePath + ":" + e.getMessage(), e);
		}

	}

	public String readFileBase64(String platformRelativePath)
	{
		return cache.computeIfAbsent(platformRelativePath, this::readResourceBase64);
	}
}
