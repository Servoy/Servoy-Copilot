/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.unit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.ProjectService;

public class ProjectServiceTest
{
	private ProjectService service;
	private NullProgressMonitor monitor = new NullProgressMonitor();
	private Path tempDir;

	@Before
	public void setUp() throws IOException
	{
		service = new ProjectService();
		tempDir = Files.createTempDirectory("projectServiceTest");
	}

	@After
	public void tearDown() throws CoreException, IOException
	{
		for (String name : new String[] { "ExistingProjectTest", "NoProjectFileTest" })
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (project.exists())
			{
				project.delete(false, true, monitor);
			}
		}
		deleteRecursively(tempDir.toFile());
	}

	@Test
	public void testOpenProjectWithExistingProjectFile() throws IOException
	{
		Path projectDir = tempDir.resolve("ExistingProjectTest");
		Files.createDirectories(projectDir);

		String dotProject = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<projectDescription>\n"
			+ "    <name>ExistingProjectTest</name>\n"
			+ "    <comment></comment>\n"
			+ "    <projects></projects>\n"
			+ "    <buildSpec></buildSpec>\n"
			+ "    <natures></natures>\n"
			+ "</projectDescription>\n";
		Files.writeString(projectDir.resolve(".project"), dotProject);

		String result = service.openProject(projectDir.toAbsolutePath().toString());

		assertTrue("Expected success message, got: " + result, result.contains("imported and opened successfully"));

		String projects = service.listProjects();
		assertTrue("Project should appear in listProjects", projects.contains("ExistingProjectTest"));
	}

	@Test
	public void testOpenProjectWithoutProjectFile() throws IOException
	{
		Path projectDir = tempDir.resolve("NoProjectFileTest");
		Files.createDirectories(projectDir);
		Files.writeString(projectDir.resolve("hello.txt"), "hello world");

		assertFalse(".project should not exist before openProject", Files.exists(projectDir.resolve(".project")));

		String result = service.openProject(projectDir.toAbsolutePath().toString());

		assertTrue("Expected success message, got: " + result, result.contains("imported and opened successfully"));

		String projects = service.listProjects();
		assertTrue("Project should appear in listProjects", projects.contains("NoProjectFileTest"));
	}

	@Test
	public void testOpenProjectNonExistentDirectory()
	{
		String result = service.openProject("/non/existent/path/xyz123");
		assertTrue("Expected error about non-existent directory, got: " + result, result.contains("Directory does not exist"));
	}

	@Test
	public void testOpenProjectAlreadyOpen() throws IOException
	{
		Path projectDir = tempDir.resolve("NoProjectFileTest");
		Files.createDirectories(projectDir);

		service.openProject(projectDir.toAbsolutePath().toString());
		String result = service.openProject(projectDir.toAbsolutePath().toString());

		assertTrue("Expected already open message, got: " + result, result.contains("already open"));
	}

	private void deleteRecursively(File file)
	{
		if (file.isDirectory())
		{
			File[] children = file.listFiles();
			if (children != null)
			{
				for (File child : children)
				{
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}
}
