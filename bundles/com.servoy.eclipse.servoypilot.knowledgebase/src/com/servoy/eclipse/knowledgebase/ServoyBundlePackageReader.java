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

package com.servoy.eclipse.knowledgebase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;

import org.osgi.framework.Bundle;
import org.sablo.specification.Package.IPackageReader;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * IPackageReader implementation that reads from OSGi bundle's resources directory.
 * Used to load default knowledge base from knowledgebase bundle when solution doesn't have .servoy directory.
 */
public class ServoyBundlePackageReader implements IPackageReader
{
	private final Bundle bundle;
	private final String basePath;

	/**
	 * Create a package reader for an OSGi bundle's resources
	 * @param bundle the OSGi bundle containing resources
	 * @param basePath the base path within the bundle (e.g., "resources/")
	 */
	public ServoyBundlePackageReader(Bundle bundle, String basePath)
	{
		if (bundle != null)
		{
			this.bundle = bundle;
			this.basePath = basePath != null ? basePath : "";
		} else {
			throw new IllegalArgumentException("Bundle cannot be null");
		}
		
		
	}

	@Override
	public String getName()
	{
		return bundle.getSymbolicName() + "-default";
	}

	@Override
	public String getPackageName()
	{
		return getName();
	}

	@Override
	public String getPackageDisplayname()
	{
		return "Default Knowledge Base";
	}

	@Override
	public String getVersion()
	{
		return bundle.getVersion().toString();
	}

	@Override
	public Manifest getManifest()
	{
		return null;
	}

	@Override
	public String getPackageType()
	{
		return "knowledge-base";
	}

	@Override
	public URL getPackageURL()
	{
		return bundle.getEntry(basePath);
	}

	@Override
	public URL getUrlForPath(String path)
	{
		if (path == null)
		{
			return null;
		}
		
		String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
		String fullPath = basePath.isEmpty() ? normalizedPath : basePath + "/" + normalizedPath;
		
		URL url = bundle.getEntry(fullPath);
		
		if (url == null)
		{
			ServoyLog.logInfo("[ServoyBundlePackageReader] Resource not found: " + fullPath);
		}
		
		return url;
	}

	@Override
	public java.io.File getResource()
	{
		// Bundle resources cannot be accessed as File objects
		return null;
	}

	@Override
	public String readTextFile(String path, Charset charset) throws IOException
	{
		if (path == null)
		{
			return null;
		}
		
		URL url = getUrlForPath(path);
		if (url == null)
		{
			return null;
		}
		
		Charset charsetToUse = charset != null ? charset : StandardCharsets.UTF_8;
		
		try (InputStream is = url.openStream();
			 InputStreamReader isr = new InputStreamReader(is, charsetToUse);
			 BufferedReader reader = new BufferedReader(isr))
		{
			StringBuilder content = new StringBuilder();
			String line;
			
			if (reader.ready())
			{
				line = reader.readLine();
				if (line != null)
				{
					content.append(line);
				}
			}
			
			while ((line = reader.readLine()) != null)
			{
				content.append('\n');
				content.append(line);
			}
			
			return content.toString();
		}
	}

	@Override
	public void reportError(String specpath, Exception e)
	{
		ServoyLog.logError("[ServoyBundlePackageReader] Error reading " + specpath + ": " + e.getMessage(), e);
	}

	@Override
	public void clearError()
	{
		// No-op
	}
}
