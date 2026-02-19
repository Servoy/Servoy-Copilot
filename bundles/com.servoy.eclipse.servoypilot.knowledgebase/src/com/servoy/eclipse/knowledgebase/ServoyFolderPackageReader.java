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
package com.servoy.eclipse.knowledgebase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.sablo.specification.Package.IPackageReader;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * IPackageReader implementation that reads from .servoy folder in solution root.
 * This is NOT an SPM package - just a plain folder with embeddings/ and rules/ subdirectories.
 */
public class ServoyFolderPackageReader implements IPackageReader
{
	private final IFolder servoyFolder;
	private final String solutionName;

	public ServoyFolderPackageReader(IFolder servoyFolder, String solutionName)
	{
		if (servoyFolder == null)
		{
			throw new IllegalArgumentException(".servoy folder cannot be null");
		}
		
		this.servoyFolder = servoyFolder;
		this.solutionName = solutionName;
	}

	@Override
	public String getName()
	{
		return solutionName + "-knowledge-base";
	}

	@Override
	public String getPackageName()
	{
		return getName();
	}

	@Override
	public String getPackageDisplayname()
	{
		return solutionName + " Knowledge Base";
	}

	@Override
	public String getVersion()
	{
		return "1.0.0";
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
		try
		{
			return servoyFolder.getLocationURI().toURL();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Override
	public URL getUrlForPath(String path)
	{
		if (path == null)
		{
			return null;
		}
		
		String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
		
		IFile file = servoyFolder.getFile(normalizedPath);
		if (file.exists())
		{
			try
			{
				return file.getLocationURI().toURL();
			}
			catch (Exception e)
			{
				ServoyLog.logError("[ServoyFolderPackageReader] Error getting URL for path: " + path, e);
			}
		}
		
		return null;
	}

	@Override
	public java.io.File getResource()
	{
		try
		{
			return servoyFolder.getLocation().toFile();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[ServoyFolderPackageReader] Error getting File resource", e);
			return null;
		}
	}

	@Override
	public String readTextFile(String path, Charset charset) throws IOException
	{
		if (path == null)
		{
			return null;
		}
		
		String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
		IFile file = servoyFolder.getFile(normalizedPath);
		
		if (!file.exists())
		{
			return null;
		}
		
		Charset charsetToUse = charset != null ? charset : StandardCharsets.UTF_8;
		
		try (InputStream is = file.getContents();
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
		catch (CoreException e)
		{
			throw new IOException("Error reading file: " + path, e);
		}
	}

	@Override
	public void reportError(String specpath, Exception e)
	{
		ServoyLog.logError("[ServoyFolderPackageReader] Error reading " + specpath + ": " + e.getMessage(), e);
	}

	@Override
	public void clearError()
	{
		// No-op
	}
	
	@Override
	public String toString()
	{
		return "ServoyFolderPackageReader[" + solutionName + "]";
	}
}
