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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/**
 * Provides Git operations via EGit/JGit for MCP tools.
 * <p>
 * Ported from AssistAI's {@code GitService}. Differences:
 * <ul>
 *   <li>No DI ({@code @Inject}, {@code @Creatable}) - plain POJO.</li>
 *   <li>No {@code UISynchronizeCallable} - not needed for headless MCP.</li>
 *   <li>{@code getCurrentDiff()} removed - it required UI thread access and is not exposed as a tool.</li>
 * </ul>
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
@SuppressWarnings("restriction")
public class GitService
{

	/**
	 * Resolves the Git repository to operate on. When {@code repo} is given it takes precedence and is
	 * matched against the workspace's repositories directly (by name or working-tree path), independent
	 * of any Eclipse project; this is the repository-centric path (SVY-21524). When {@code repo} is
	 * {@code null}/blank the legacy project-centric path is used, resolving the repository from the
	 * Eclipse project named by {@code projectName}.
	 *
	 * @param projectName the Eclipse project name (legacy path); may be {@code null} when {@code repo} is given
	 * @param repo a repository name or working-tree path (repository-centric path); may be {@code null}
	 * @return the resolved repository, never {@code null}
	 */
	private Repository resolveRepository(String projectName, String repo)
	{
		if (repo != null && !repo.trim().isEmpty())
		{
			return getRepositoryByNameOrPath(repo.trim());
		}
		return getRepository(projectName);
	}

	private Repository getRepository(String projectName)
	{
		if (projectName == null || projectName.trim().isEmpty())
		{
			throw new RuntimeException("No project name given." + availableRepositoriesHint());
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists())
		{
			throw new RuntimeException("Project not found: " + projectName + availableRepositoriesHint());
		}
		try
		{
			RepositoryMapping mapping = RepositoryMapping.getMapping(project);
			if (mapping == null)
			{
				throw new RuntimeException("Project is not mapped to a Git repository: " + projectName + availableRepositoriesHint());
			}
			Repository repo = mapping.getRepository();
			if (repo == null)
			{
				throw new RuntimeException("Could not obtain Git repository for project: " + projectName + availableRepositoriesHint());
			}
			return repo;
		}
		catch (RuntimeException e)
		{
			if (e.getMessage() != null) throw e;
			throw new RuntimeException("Failed to get Git repository for project: " + projectName, e);
		}
	}

	/**
	 * Matches a repository name (its working-tree folder name) or working-tree path against the
	 * repositories known to the workspace. The match is exact on name first, then on the working-tree
	 * path (absolute or workspace-relative). A target that matches nothing - or matches more than one
	 * repository - is refused with a message that lists the available repositories, so the caller can
	 * pick the real one instead of silently operating on the wrong tree (SVY-21524).
	 */
	private Repository getRepositoryByNameOrPath(String target)
	{
		Repository[] all = org.eclipse.egit.core.RepositoryCache.INSTANCE.getAllRepositories();
		if (all.length == 0)
		{
			throw new RuntimeException("No Git repositories are open in the workspace.");
		}

		java.util.List<Repository> matches = new java.util.ArrayList<>();
		String normalizedTarget = normalizePath(target);
		for (Repository r : all)
		{
			String name = org.eclipse.egit.core.RepositoryUtil.INSTANCE.getRepositoryName(r);
			java.io.File workTree = r.isBare() ? r.getDirectory() : r.getWorkTree();
			String workTreePath = workTree == null ? null : normalizePath(workTree.getAbsolutePath());
			if (target.equals(name) || (workTreePath != null && workTreePath.equals(normalizedTarget)))
			{
				matches.add(r);
			}
		}

		if (matches.size() == 1)
		{
			return matches.get(0);
		}
		if (matches.isEmpty())
		{
			throw new RuntimeException("No repository matches '" + target + "'." + availableRepositoriesHint());
		}
		throw new RuntimeException("Repository '" + target + "' is ambiguous - it matches " + matches.size() +
			" repositories by name. Use the working-tree path instead." + availableRepositoriesHint());
	}

	private static String normalizePath(String path)
	{
		if (path == null) return null;
		String p = path.replace('\\', '/');
		if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
		return p;
	}

	/**
	 * Lists the Git repositories known to the workspace - each with its name, working-tree path and
	 * current branch - so a caller can discover a valid {@code repo} target instead of guessing a
	 * project name (SVY-21524).
	 */
	public String listRepositories()
	{
		Repository[] all = org.eclipse.egit.core.RepositoryCache.INSTANCE.getAllRepositories();
		if (all.length == 0)
		{
			return "No Git repositories are open in the workspace.";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Git repositories (").append(all.length).append("):\n");
		for (Repository r : all)
		{
			String name = org.eclipse.egit.core.RepositoryUtil.INSTANCE.getRepositoryName(r);
			java.io.File workTree = r.isBare() ? r.getDirectory() : r.getWorkTree();
			String branch;
			try
			{
				branch = r.getBranch();
			}
			catch (Exception e)
			{
				branch = "(unknown)";
			}
			sb.append("  ").append(name)
				.append("  path: ").append(workTree == null ? "(none)" : workTree.getAbsolutePath())
				.append("  branch: ").append(branch).append("\n");
		}
		return sb.toString();
	}

	/**
	 * Builds a trailing hint listing the available repository names, appended to a resolution failure so
	 * the caller can pick a real target. Best-effort: returns an empty string if the cache cannot be read.
	 */
	private String availableRepositoriesHint()
	{
		try
		{
			Repository[] all = org.eclipse.egit.core.RepositoryCache.INSTANCE.getAllRepositories();
			if (all.length == 0)
			{
				return " No Git repositories are open in the workspace.";
			}
			StringBuilder sb = new StringBuilder(" Available repositories (pass one as 'repo'): ");
			for (int i = 0; i < all.length; i++)
			{
				if (i > 0) sb.append(", ");
				sb.append(org.eclipse.egit.core.RepositoryUtil.INSTANCE.getRepositoryName(all[i]));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			return "";
		}
	}

	/**
	 * Refreshes the Eclipse workspace after a change. On the project-centric path {@code projectName}
	 * names the single project to refresh. On the repository-centric path {@code projectName} is
	 * {@code null}; the whole working tree changed, so every workspace project located under the
	 * repository's working tree is refreshed instead (SVY-21524).
	 */
	private void refreshAfterChange(String projectName, Repository repository)
	{
		if (projectName != null && !projectName.trim().isEmpty())
		{
			refreshProject(projectName);
			return;
		}
		refreshRepositoryProjects(repository);
	}

	private void refreshProject(String projectName)
	{
		try
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			project.refreshLocal(IResource.DEPTH_INFINITE, null);
		}
		catch (CoreException e)
		{
			Platform.getLog(GitService.class).error("Failed to refresh project: " + projectName, e);
		}
	}

	/**
	 * Refreshes every open workspace project whose location is inside the given repository's working
	 * tree. Used by the repository-centric path, where no single project name is available.
	 */
	private void refreshRepositoryProjects(Repository repository)
	{
		if (repository == null || repository.isBare()) return;
		java.io.File workTree = repository.getWorkTree();
		if (workTree == null) return;
		java.nio.file.Path workTreePath = workTree.toPath().toAbsolutePath().normalize();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
		{
			if (!project.isOpen() || project.getLocation() == null) continue;
			java.nio.file.Path projectPath = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
			if (projectPath.startsWith(workTreePath))
			{
				try
				{
					project.refreshLocal(IResource.DEPTH_INFINITE, null);
				}
				catch (CoreException e)
				{
					Platform.getLog(GitService.class).error("Failed to refresh project: " + project.getName(), e);
				}
			}
		}
	}

	public String getStatus(String projectName)
	{
		return getStatus(projectName, null);
	}

	public String getStatus(String projectName, String repo)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var status = git.status().call();
			var sb = new StringBuilder();

			String branch = repository.getBranch();
			sb.append("On branch ").append(branch).append("\n");

			try
			{
				BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
				if (tracking != null)
				{
					int ahead = tracking.getAheadCount();
					int behind = tracking.getBehindCount();
					if (ahead > 0 && behind > 0)
						sb.append("Your branch is ").append(ahead).append(" ahead, ").append(behind).append(" behind its remote tracking branch.\n");
					else if (ahead > 0)
						sb.append("Your branch is ").append(ahead).append(" commit(s) ahead of its remote tracking branch.\n");
					else if (behind > 0)
						sb.append("Your branch is ").append(behind).append(" commit(s) behind its remote tracking branch.\n");
				}
			}
			catch (Exception e)
			{
				// tracking info not available
			}
			sb.append("\n");

			Set<String> added = status.getAdded();
			Set<String> changed = status.getChanged();
			Set<String> removed = status.getRemoved();
			if (!added.isEmpty() || !changed.isEmpty() || !removed.isEmpty())
			{
				sb.append("Changes to be committed:\n");
				added.forEach(f -> sb.append("  new file:   ").append(f).append("\n"));
				changed.forEach(f -> sb.append("  modified:   ").append(f).append("\n"));
				removed.forEach(f -> sb.append("  deleted:    ").append(f).append("\n"));
				sb.append("\n");
			}

			Set<String> modified = status.getModified();
			Set<String> missing = status.getMissing();
			if (!modified.isEmpty() || !missing.isEmpty())
			{
				sb.append("Changes not staged for commit:\n");
				modified.forEach(f -> sb.append("  modified:   ").append(f).append("\n"));
				missing.forEach(f -> sb.append("  deleted:    ").append(f).append("\n"));
				sb.append("\n");
			}

			Set<String> untracked = status.getUntracked();
			if (!untracked.isEmpty())
			{
				sb.append("Untracked files:\n");
				untracked.forEach(f -> sb.append("  ").append(f).append("\n"));
				sb.append("\n");
			}

			Set<String> conflicting = status.getConflicting();
			if (!conflicting.isEmpty())
			{
				sb.append("Unmerged paths:\n");
				conflicting.forEach(f -> sb.append("  both modified: ").append(f).append("\n"));
				sb.append("\n");
			}

			if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()
				&& modified.isEmpty() && missing.isEmpty()
				&& untracked.isEmpty() && conflicting.isEmpty())
			{
				sb.append("nothing to commit, working tree clean\n");
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get git status: " + e.getMessage(), e);
		}
	}

	@SuppressWarnings("deprecation")
	public String getLog(String projectName, int maxCount)
	{
		return getLog(projectName, null, maxCount);
	}

	@SuppressWarnings("deprecation")
	public String getLog(String projectName, String repo, int maxCount)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var log = git.log().setMaxCount(maxCount).call();
			var sb = new StringBuilder();

			for (RevCommit commit : log)
			{
				sb.append("commit ").append(commit.getName()).append("\n");
				PersonIdent author = commit.getAuthorIdent();
				sb.append("Author: ").append(author.getName())
					.append(" <").append(author.getEmailAddress()).append(">\n");
				var sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
				sdf.setTimeZone(author.getTimeZone());
				sb.append("Date:   ").append(sdf.format(author.getWhen())).append("\n");
				sb.append("\n    ").append(commit.getFullMessage().trim().replace("\n", "\n    ")).append("\n\n");
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get git log: " + e.getMessage(), e);
		}
	}

	public String addFiles(String projectName, String filePattern)
	{
		return addFiles(projectName, null, filePattern);
	}

	public String addFiles(String projectName, String repo, String filePattern)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			git.add().addFilepattern(filePattern).call();
			git.add().setUpdate(true).addFilepattern(filePattern).call();
			refreshAfterChange(projectName, repository);
			return "Added: " + filePattern;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to add files: " + e.getMessage(), e);
		}
	}

	public String commit(String projectName, String message)
	{
		return commit(projectName, null, message);
	}

	public String commit(String projectName, String repo, String message)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			RevCommit commit = git.commit().setMessage(message).setAllowEmpty(false).call();
			refreshAfterChange(projectName, repository);
			return "Committed: " + commit.getName() + " " + commit.getShortMessage();
		}
		catch (org.eclipse.jgit.api.errors.EmptyCommitException e)
		{
			return "Nothing to commit - no staged changes.";
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to commit: " + e.getMessage(), e);
		}
	}

	public String getDiff(String projectName, boolean staged)
	{
		return getDiff(projectName, null, staged);
	}

	public String getDiff(String projectName, String repo, boolean staged)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository);
			ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ObjectId head = repository.resolve("HEAD");
			if (head == null)
			{
				return "No commits yet.";
			}

			var diffCommand = git.diff();
			diffCommand.setOutputStream(out);
			if (staged)
			{
				diffCommand.setCached(true);
				var headTree = prepareTreeParser(repository, head);
				diffCommand.setOldTree(headTree);
			}

			diffCommand.call();
			String result = out.toString("UTF-8");
			return result.isEmpty() ? "No changes." : result;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
		}
	}

	public String listBranches(String projectName, boolean includeRemote)
	{
		return listBranches(projectName, null, includeRemote);
	}

	public String listBranches(String projectName, String repo, boolean includeRemote)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			String currentBranch = repository.getBranch();
			var sb = new StringBuilder();

			var cmd = git.branchList();
			if (includeRemote)
			{
				cmd.setListMode(ListBranchCommand.ListMode.ALL);
			}

			var branches = cmd.call();
			for (var ref : branches)
			{
				String name = ref.getName();
				if (name.startsWith("refs/heads/"))
					name = name.substring("refs/heads/".length());
				else if (name.startsWith("refs/remotes/"))
					name = name.substring("refs/remotes/".length());

				boolean isCurrent = name.equals(currentBranch);
				sb.append(isCurrent ? "* " : "  ").append(name).append("\n");
			}

			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list branches: " + e.getMessage(), e);
		}
	}

	public String createBranch(String projectName, String branchName, String startPoint)
	{
		return createBranch(projectName, null, branchName, startPoint);
	}

	public String createBranch(String projectName, String repo, String branchName, String startPoint)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var cmd = git.branchCreate().setName(branchName);
			if (startPoint != null && !startPoint.isEmpty())
			{
				cmd.setStartPoint(startPoint);
			}
			cmd.call();
			refreshAfterChange(projectName, repository);
			return "Created branch: " + branchName;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to create branch: " + e.getMessage(), e);
		}
	}

	public String deleteBranch(String projectName, String branchName, boolean force)
	{
		return deleteBranch(projectName, null, branchName, force);
	}

	public String deleteBranch(String projectName, String repo, String branchName, boolean force)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var cmd = git.branchDelete().setBranchNames(branchName).setForce(force);
			var deleted = cmd.call();
			return "Deleted branch: " + String.join(", ", deleted);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to delete branch: " + e.getMessage(), e);
		}
	}

	public String checkoutBranch(String projectName, String branchName)
	{
		return checkoutBranch(projectName, null, branchName);
	}

	public String checkoutBranch(String projectName, String repo, String branchName)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			git.checkout().setName(branchName).call();
			refreshAfterChange(projectName, repository);
			return "Switched to branch: " + branchName;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to checkout branch: " + e.getMessage(), e);
		}
	}

	public String resetFiles(String projectName, String filePattern)
	{
		return resetFiles(projectName, null, filePattern);
	}

	public String resetFiles(String projectName, String repo, String filePattern)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			git.reset().addPath(filePattern).call();
			refreshAfterChange(projectName, repository);
			return "Unstaged: " + filePattern;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to reset files: " + e.getMessage(), e);
		}
	}

	public String stash(String projectName, String message)
	{
		return stash(projectName, null, message);
	}

	public String stash(String projectName, String repo, String message)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var cmd = git.stashCreate();
			if (message != null && !message.isEmpty())
			{
				cmd.setWorkingDirectoryMessage(message);
			}
			var stashRef = cmd.call();
			refreshAfterChange(projectName, repository);
			if (stashRef == null)
			{
				return "No local changes to stash.";
			}
			return "Stashed working directory: " + stashRef.getName();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to stash: " + e.getMessage(), e);
		}
	}

	public String stashPop(String projectName)
	{
		return stashPop(projectName, null);
	}

	public String stashPop(String projectName, String repo)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			git.stashApply().call();
			git.stashDrop().call();
			refreshAfterChange(projectName, repository);
			return "Applied and dropped stash.";
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to pop stash: " + e.getMessage(), e);
		}
	}

	public String stashList(String projectName)
	{
		return stashList(projectName, null);
	}

	public String stashList(String projectName, String repo)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git git = new Git(repository))
		{
			var stashes = git.stashList().call();
			if (!stashes.iterator().hasNext())
			{
				return "No stashes found.";
			}
			var sb = new StringBuilder();
			int index = 0;
			for (RevCommit stash : stashes)
			{
				sb.append("stash@{").append(index++).append("}: ")
					.append(stash.getShortMessage()).append("\n");
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list stashes: " + e.getMessage(), e);
		}
	}

	// --- Private helpers ---

	public String stagePatch(String projectName, String patch)
	{
		return stagePatch(projectName, null, patch);
	}

	public String stagePatch(String projectName, String repo, String patch)
	{
		Repository repository = resolveRepository(projectName, repo);
		try (Git gitCmd = new Git(repository))
		{
			java.io.File workTree = repository.getWorkTree();

			org.eclipse.jgit.patch.Patch parsedPatch = new org.eclipse.jgit.patch.Patch();
			parsedPatch.parse(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)));

			if (parsedPatch.getFiles().isEmpty())
			{
				return "No files affected by patch.";
			}

			Map<java.io.File, byte[]> savedWorkingTree = new HashMap<>();
			for (var fileHeader : parsedPatch.getFiles())
			{
				java.io.File file = new java.io.File(workTree, fileHeader.getNewPath());
				if (file.exists())
					savedWorkingTree.put(file, Files.readAllBytes(file.toPath()));
			}

			var checkoutCmd = gitCmd.checkout();
			for (var fileHeader : parsedPatch.getFiles())
				checkoutCmd.addPath(fileHeader.getNewPath());
			checkoutCmd.call();

			org.eclipse.jgit.api.ApplyResult result = gitCmd.apply()
				.setPatch(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)))
				.call();

			DirCache dirCache = repository.lockDirCache();
			try
			{
				DirCacheEditor editor = dirCache.editor();
				ObjectInserter inserter = repository.newObjectInserter();

				for (var file : result.getUpdatedFiles())
				{
					byte[] content = Files.readAllBytes(file.toPath());
					ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);
					String repoRelativePath = workTree.toPath()
						.relativize(file.toPath()).toString().replace('\\', '/');

					editor.add(new DirCacheEditor.PathEdit(repoRelativePath)
					{
						@Override
						public void apply(DirCacheEntry ent)
						{
							ent.setObjectId(blobId);
							ent.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
							ent.setLength(content.length);
							ent.setLastModified(java.time.Instant.now());
						}
					});
				}

				inserter.flush();
				editor.commit();
			}
			finally
			{
				dirCache.unlock();
			}

			for (var entry : savedWorkingTree.entrySet())
				Files.write(entry.getKey().toPath(), entry.getValue());

			refreshAfterChange(projectName, repository);
			return "Patch staged successfully. Files: " + result.getUpdatedFiles().size();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to stage patch: " + e.getMessage(), e);
		}
	}

	private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException
	{
		try (RevWalk walk = new RevWalk(repository))
		{
			var commit = walk.parseCommit(objectId);
			var treeId = commit.getTree().getId();
			try (var reader = repository.newObjectReader())
			{
				return new CanonicalTreeParser(null, reader, treeId);
			}
		}
	}

	private static final String DEFAULT_GITIGNORE = ".metadata/\n.equo/\n.angular/\n*.log\n.DS_Store\nThumbs.db\nnode_modules/\n.opencode/\nAGENTS.md\nopencode.json\n";

	public String initRepository(String projectName)
	{
		try
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.exists())
			{
				return "Error: Project not found: " + projectName;
			}

			java.io.File workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
			java.io.File gitDir = new java.io.File(workspaceRoot, ".git");

			if (gitDir.exists())
			{
				RepositoryMapping mapping = RepositoryMapping.getMapping(project);
				if (mapping != null)
				{
					return "Git repository already exists at workspace root. Project '" + projectName + "' is already connected.";
				}
				try (Repository repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(gitDir))
				{
					org.eclipse.egit.core.op.ConnectProviderOperation connectOp = new org.eclipse.egit.core.op.ConnectProviderOperation(project, repo.getDirectory());
					connectOp.execute(new org.eclipse.core.runtime.NullProgressMonitor());
				}
				return "Git repository already exists at workspace root. Connected project '" + projectName + "'.";
			}

			try (Git git = Git.init().setDirectory(workspaceRoot).call())
			{
				Repository repo = git.getRepository();

				java.io.File gitignoreFile = new java.io.File(workspaceRoot, ".gitignore");
				if (!gitignoreFile.exists())
				{
					Files.write(gitignoreFile.toPath(), DEFAULT_GITIGNORE.getBytes(StandardCharsets.UTF_8));
				}

				IProject[] allProjects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
				for (IProject p : allProjects)
				{
					if (p.isOpen())
					{
						try
						{
							org.eclipse.egit.core.op.ConnectProviderOperation connectOp = new org.eclipse.egit.core.op.ConnectProviderOperation(p, repo.getDirectory());
							connectOp.execute(new org.eclipse.core.runtime.NullProgressMonitor());
						}
						catch (Exception e)
						{
							// non-fatal: skip projects that fail to connect
						}
					}
				}

				git.add().addFilepattern(".").call();
				git.commit().setMessage("Initial commit").call();

				return "Initialized Git repository at workspace root. Connected all projects. Created .gitignore and initial commit.";
			}
		}
		catch (Exception e)
		{
			return "Error initializing Git repository: " + e.getMessage();
		}
	}


}
