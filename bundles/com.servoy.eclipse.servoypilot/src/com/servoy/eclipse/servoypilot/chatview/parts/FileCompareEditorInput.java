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
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/**
 * Compare editor input for showing original vs modified file content.
 * Uses only public Eclipse Compare API to avoid access restrictions.
 */
public class FileCompareEditorInput extends CompareEditorInput
{
	private final String fileName;
	private final String originalContent;
	private final String modifiedContent;

	public FileCompareEditorInput(String fileName, String originalContent, String modifiedContent)
	{
		super(new CompareConfiguration());
		this.fileName = fileName;
		this.originalContent = originalContent;
		this.modifiedContent = modifiedContent;

		CompareConfiguration config = getCompareConfiguration();
		config.setLeftLabel("Original");
		config.setRightLabel("Modified");
		config.setLeftEditable(false);
		config.setRightEditable(false);
	}

	@Override
	protected Object prepareInput(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
	{
		// Create compare elements using only public API
		CompareElement left = new CompareElement(fileName + " (Original)", originalContent);
		CompareElement right = new CompareElement(fileName + " (Modified)", modifiedContent);

		// Use reflection to create DiffNode with proper constructor
		// DiffNode(int kind, IDiffContainer parent, ITypedElement left, ITypedElement right)
		try
		{
			Class<?> diffNodeClass = Class.forName("org.eclipse.compare.structuremergeviewer.DiffNode");
			Class<?> diffContainerClass = Class.forName("org.eclipse.compare.structuremergeviewer.IDiffContainer");
			
			// Find constructor: DiffNode(ITypedElement left, ITypedElement right)
			// This is a simpler constructor that doesn't require parent container
			java.lang.reflect.Constructor<?> constructor = null;
			for (java.lang.reflect.Constructor<?> c : diffNodeClass.getConstructors())
			{
				Class<?>[] paramTypes = c.getParameterTypes();
				if (paramTypes.length == 2 && 
					ITypedElement.class.isAssignableFrom(paramTypes[0]) &&
					ITypedElement.class.isAssignableFrom(paramTypes[1]))
				{
					constructor = c;
					break;
				}
			}
			
			if (constructor != null)
			{
				return constructor.newInstance(left, right);
			}
			
			// Fallback: try the full constructor with kind and parent
			// DiffNode(int kind, IDiffContainer parent, ITypedElement left, ITypedElement right)
			constructor = diffNodeClass.getConstructor(
				int.class, 
				diffContainerClass,
				ITypedElement.class, 
				ITypedElement.class);
			
			// CHANGE = 2 (from Differencer.CHANGE)
			return constructor.newInstance(2, null, left, right);
		}
		catch (Exception e)
		{
			throw new InvocationTargetException(e, "Failed to create compare input: " + e.getMessage());
		}
	}

	/**
	 * Element for compare viewer - implements only public interfaces
	 */
	private static class CompareElement implements ITypedElement, IStreamContentAccessor
	{
		private final String name;
		private final String content;

		public CompareElement(String name, String content)
		{
			this.name = name;
			this.content = content;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public Image getImage()
		{
			return null;
		}

		@Override
		public String getType()
		{
			return TEXT_TYPE;
		}

		@Override
		public InputStream getContents() throws CoreException
		{
			return new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
	}
}