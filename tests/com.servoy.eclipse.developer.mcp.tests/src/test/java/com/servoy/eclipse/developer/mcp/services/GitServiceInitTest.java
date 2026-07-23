package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GitServiceInitTest
{
	private Path tempDir;

	@Before
	public void setUp() throws Exception
	{
		tempDir = Files.createTempDirectory("git-init-test");
	}

	@After
	public void tearDown() throws Exception
	{
		if (tempDir != null)
		{
			deleteRecursive(tempDir.toFile());
		}
	}

	private void deleteRecursive(File file)
	{
		if (file.isDirectory())
		{
			File[] children = file.listFiles();
			if (children != null)
			{
				for (File child : children)
				{
					deleteRecursive(child);
				}
			}
		}
		file.delete();
	}

	@Test
	public void initCreatesRepoWithGitignoreAndCommit() throws Exception
	{
		File workspaceRoot = tempDir.toFile();
		assertFalse(new File(workspaceRoot, ".git").exists());

		try (Git git = Git.init().setDirectory(workspaceRoot).call())
		{
			assertTrue(new File(workspaceRoot, ".git").exists());

			String gitignoreContent = ".metadata/\n.equo/\n.angular/\n*.log\n.DS_Store\nThumbs.db\nnode_modules/\n.opencode/\nAGENTS.md\nopencode.json\n";
			File gitignoreFile = new File(workspaceRoot, ".gitignore");
			Files.write(gitignoreFile.toPath(), gitignoreContent.getBytes(StandardCharsets.UTF_8));
			assertTrue(gitignoreFile.exists());

			String readBack = new String(Files.readAllBytes(gitignoreFile.toPath()), StandardCharsets.UTF_8);
			assertTrue(readBack.contains(".metadata/"));
			assertTrue(readBack.contains(".equo/"));
			assertTrue(readBack.contains("node_modules/"));
			assertTrue(readBack.contains(".opencode/"));
			assertTrue(readBack.contains("AGENTS.md"));
			assertTrue(readBack.contains("opencode.json"));
			assertFalse(readBack.contains("resources/"));

			git.add().addFilepattern(".").call();
			RevCommit commit = git.commit().setMessage("Initial commit").call();

			assertEquals("Initial commit", commit.getFullMessage());
			assertEquals(0, commit.getParentCount());
		}
	}

	@Test
	public void initOnExistingRepoIsIdempotent() throws Exception
	{
		File workspaceRoot = tempDir.toFile();

		try (Git git = Git.init().setDirectory(workspaceRoot).call())
		{
			File testFile = new File(workspaceRoot, "test.txt");
			Files.write(testFile.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
			git.add().addFilepattern(".").call();
			git.commit().setMessage("First commit").call();
		}

		assertTrue(new File(workspaceRoot, ".git").exists());
		assertTrue(new File(workspaceRoot, "test.txt").exists());

		try (Git git = Git.open(workspaceRoot))
		{
			Iterable<RevCommit> log = git.log().call();
			int count = 0;
			for (RevCommit c : log)
			{
				count++;
				assertEquals("First commit", c.getFullMessage());
			}
			assertEquals(1, count);
		}
	}

	@Test
	public void existingGitignoreNotOverwritten() throws Exception
	{
		File workspaceRoot = tempDir.toFile();
		File gitignoreFile = new File(workspaceRoot, ".gitignore");
		String customContent = "custom-folder/\nmy-secret.txt\n";
		Files.write(gitignoreFile.toPath(), customContent.getBytes(StandardCharsets.UTF_8));

		try (Git git = Git.init().setDirectory(workspaceRoot).call())
		{
			assertTrue(gitignoreFile.exists());
			String readBack = new String(Files.readAllBytes(gitignoreFile.toPath()), StandardCharsets.UTF_8);
			assertEquals(customContent, readBack);
			assertFalse(readBack.contains(".metadata/"));
		}
	}

	@Test
	public void multipleSubdirectoriesTrackedAfterInit() throws Exception
	{
		File workspaceRoot = tempDir.toFile();

		File project1 = new File(workspaceRoot, "solution_a");
		File project2 = new File(workspaceRoot, "resources");
		project1.mkdirs();
		project2.mkdirs();
		Files.write(new File(project1, "test.js").toPath(), "var x = 1;".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(project2, "repo.obj").toPath(), "data".getBytes(StandardCharsets.UTF_8));

		try (Git git = Git.init().setDirectory(workspaceRoot).call())
		{
			git.add().addFilepattern(".").call();
			RevCommit commit = git.commit().setMessage("Initial commit").call();

			org.eclipse.jgit.treewalk.TreeWalk treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(git.getRepository());
			treeWalk.addTree(commit.getTree());
			treeWalk.setRecursive(true);

			java.util.Set<String> paths = new java.util.HashSet<>();
			while (treeWalk.next())
			{
				paths.add(treeWalk.getPathString());
			}
			treeWalk.close();

			assertTrue(paths.contains("solution_a/test.js"));
			assertTrue(paths.contains("resources/repo.obj"));
		}
	}
}
