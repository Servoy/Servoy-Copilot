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
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.egit.core.credentials.CredentialsStore;
import org.eclipse.egit.core.credentials.UserPasswordCredentials;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

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
	 * One lock per repository working directory, so concurrent MCP tool calls cannot corrupt the
	 * index or fail on a locked DirCache. Ported from AssistAI's {@code GitService} (upstream commit
	 * {@code 629fdf2}): the MCP server can receive overlapping git calls, and JGit's index is not
	 * safe for concurrent writers. Keyed by the absolute git-dir path so the same repository reached
	 * through different projects (or through the repository-centric {@code repo} path) shares a lock.
	 * <p>
	 * Only mutating operations take the lock. The lock is released before the Eclipse workspace
	 * refresh, so a refresh never runs while the repository is held.
	 * </p>
	 */
	private static final ConcurrentHashMap<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

	private static ReentrantLock getRepositoryLock(Repository repository)
	{
		return repoLocks.computeIfAbsent(repository.getDirectory().getAbsolutePath(), k -> new ReentrantLock());
	}


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
	 * path (absolute or workspace-relative). When neither matches, the target is tried as an Eclipse
	 * project name and resolved through its {@link RepositoryMapping} - so a caller (typically an LLM)
	 * that passes a solution/project name as {@code repo}, rather than the repository's own folder name,
	 * still reaches the right working tree instead of being refused (SVY-21366). Only a target that
	 * matches nothing at all - or matches more than one repository by name - is refused, with a message
	 * that lists the available repositories so the caller can pick the real one (SVY-21524).
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
			// Nothing matched a repository name/path. Fall back to treating the target as an Eclipse
			// project name (e.g. the Servoy solution the caller was working in) and resolve the repository
			// that project is mapped to - the same resolution the projectName-centric path uses. An LLM
			// naturally passes the solution name it knows rather than the repository's folder name, and
			// that solution lives inside one of the open repositories (SVY-21366).
			Repository viaProject = tryResolveByProjectName(target);
			if (viaProject != null)
			{
				return viaProject;
			}
			throw new RuntimeException("No repository matches '" + target + "'." + availableRepositoriesHint());
		}
		throw new RuntimeException("Repository '" + target + "' is ambiguous - it matches " + matches.size() +
			" repositories by name. Use the working-tree path instead." + availableRepositoriesHint());
	}

	/**
	 * Attempts to resolve {@code target} as an Eclipse project name mapped to a Git repository. Returns
	 * the repository, or {@code null} when the target is not an existing, mapped project - letting the
	 * caller fall through to its own "no match" error rather than masking it (SVY-21366).
	 */
	private Repository tryResolveByProjectName(String target)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(target);
		if (!project.exists())
		{
			return null;
		}
		RepositoryMapping mapping = RepositoryMapping.getMapping(project);
		return mapping == null ? null : mapping.getRepository();
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
		return getLog(projectName, repo, maxCount, null, null);
	}

	/**
	 * Lists the most recent commits reachable from a revision - the current branch by default -
	 * optionally only those touching some project-relative paths. The {@code revision} and
	 * {@code pathFilter} parameters are ported from AssistAI (upstream {@code aca70c0}).
	 *
	 * @param revision a branch, tag or commit to walk back from; {@code null} means the current branch
	 * @param pathFilter optional comma-separated project-relative paths; only commits touching them are listed
	 */
	@SuppressWarnings("deprecation")
	public String getLog(String projectName, String repo, int maxCount, String revision, String pathFilter)
	{
		Repository repository = resolveRepository(projectName, repo);
		List<String> repositoryPaths = resolveProjectPaths(projectName, pathFilter);
		try (Git git = new Git(repository))
		{
			var logCommand = git.log().setMaxCount(maxCount);
			if (revision != null && !revision.isBlank())
			{
				logCommand.add(resolveRevision(repository, revision.trim()));
			}
			for (String repositoryPath : repositoryPaths)
			{
				logCommand.addPath(repositoryPath);
			}
			var log = logCommand.call();
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

	/**
	 * Stages project-relative files or folders. {@code filePattern} is comma-separated, and
	 * {@code "."} means the whole project.
	 * <p>
	 * The pattern is resolved through {@link #resolveProjectPaths(String, String)} rather than handed
	 * to JGit as given: JGit reads a pathspec from the repository root, so for a project under
	 * {@code bundles/} a path copied out of {@code gitStatus} used to match nothing and still report
	 * success (SVY-21303, upstream {@code aca70c0}).
	 * </p>
	 */
	public String addFiles(String projectName, String repo, String filePattern)
	{
		Objects.requireNonNull(filePattern, "filePattern is required");

		Repository repository = resolveRepository(projectName, repo);
		String result = stageIn(repository, resolveProjectPaths(projectName, filePattern), filePattern);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/**
	 * {@link #addFiles} on an already-resolved repository with already-resolved pathspecs, so a plain
	 * test can drive it. The pathspecs are REPOSITORY-relative - that translation is the whole point
	 * of {@link #resolveProjectPaths(String, String)} - and an empty list means the whole tree.
	 */
	public static String stageIn(Repository repository, List<String> pathspecs, String reportedPattern)
	{
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			var add = git.add();
			var addUpdate = git.add().setUpdate(true);
			if (pathspecs.isEmpty())
			{
				// The project IS the repository root, so the whole tree is the pattern.
				add.addFilepattern(".");
				addUpdate.addFilepattern(".");
			}
			else
			{
				for (String pathspec : pathspecs)
				{
					add.addFilepattern(pathspec);
					addUpdate.addFilepattern(pathspec);
				}
			}
			add.call();
			addUpdate.call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to add files: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		return "Added: " + reportedPattern;
	}

	public String commit(String projectName, String message)
	{
		return commit(projectName, null, message);
	}

	public String commit(String projectName, String repo, String message)
	{
		return commit(projectName, repo, message, false, false);
	}

	/**
	 * Commits the staged changes.
	 * <p>
	 * {@code all} stages every tracked file's modifications and deletions first, repository-wide
	 * ({@code git commit -a}; new files still need {@code gitAdd}). {@code amend} replaces the
	 * previous commit instead of adding one - history rewriting, so it is only safe on a commit that
	 * has not been pushed. Both flags ported from AssistAI (upstream {@code aca70c0}).
	 * </p>
	 */
	public String commit(String projectName, String repo, String message, boolean amend, boolean all)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		RevCommit commit;
		try (Git git = new Git(repository))
		{
			var command = git.commit().setMessage(message).setAllowEmpty(false);
			if (amend)
			{
				command.setAmend(true);
			}
			if (all)
			{
				command.setAll(true);
			}
			commit = command.call();
		}
		catch (org.eclipse.jgit.api.errors.EmptyCommitException e)
		{
			return "Nothing to commit - no staged changes.";
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to commit: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		return (amend ? "Amended: " : "Committed: ") + commit.getName() + " " + commit.getShortMessage();
	}

	public String getDiff(String projectName, boolean staged)
	{
		return getDiff(projectName, null, staged, null, false);
	}

	public String getDiff(String projectName, String repo, boolean staged)
	{
		return getDiff(projectName, repo, staged, null, false);
	}

	/**
	 * Renders a unified diff, optionally limited to some project-relative paths and optionally
	 * ignoring whitespace.
	 * <p>
	 * Driven by a {@link DiffFormatter} rather than JGit's {@code DiffCommand}, because
	 * {@code DiffCommand} exposes no way to set a diff comparator - so {@code ignoreWhitespace}
	 * ({@link RawTextComparator#WS_IGNORE_ALL}) is not expressible through it at all. Ported from
	 * AssistAI's {@code GitService} (upstream commit {@code f2f162f}), which introduced both filters
	 * the same way.
	 * </p>
	 * <p>
	 * Both sides of the comparison are named explicitly instead of relying on {@code DiffCommand}'s
	 * implicit defaults: unstaged compares the index against the working tree, staged compares
	 * {@code HEAD} against the index.
	 * </p>
	 *
	 * @param projectName the Eclipse project name (legacy path); may be {@code null} when {@code repo} is given
	 * @param repo a repository name or working-tree path (repository-centric path); may be {@code null}
	 * @param staged {@code true} to diff HEAD against the index, {@code false} for index against the working tree
	 * @param pathFilter optional comma-separated project-relative paths to limit the diff to; may be {@code null}
	 * @param ignoreWhitespace {@code true} to ignore all whitespace differences
	 */
	public String getDiff(String projectName, String repo, boolean staged, String pathFilter, boolean ignoreWhitespace)
	{
		Repository repository = resolveRepository(projectName, repo);
		return renderDiff(repository, staged, resolveProjectPaths(projectName, pathFilter), ignoreWhitespace);
	}

	/**
	 * Compares two revisions, or a revision with the working tree when {@code toRevision} is omitted.
	 * When only {@code toRevision} is given, {@code fromRevision} defaults to {@code HEAD}. Ported
	 * from AssistAI (upstream {@code aca70c0}), which added revision comparison to {@code gitDiff}.
	 *
	 * @param fromRevision the older side; {@code null} means {@code HEAD} when {@code toRevision} is given
	 * @param toRevision the newer side; {@code null} means the working tree
	 */
	public String getRevisionDiff(String projectName, String repo, String fromRevision, String toRevision,
		String pathFilter, boolean ignoreWhitespace)
	{
		return renderRevisionDiff(resolveRepository(projectName, repo), fromRevision, toRevision,
			resolveProjectPaths(projectName, pathFilter), ignoreWhitespace);
	}

	/** {@link #getRevisionDiff} on an already-resolved repository, so a plain test can drive it. */
	public static String renderRevisionDiff(Repository repository, String fromRevision, String toRevision,
		List<String> repositoryPaths, boolean ignoreWhitespace)
	{
		String from = fromRevision == null || fromRevision.isBlank() ? Constants.HEAD : fromRevision.trim();

		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			DiffFormatter formatter = new DiffFormatter(out))
		{
			formatter.setRepository(repository);
			formatter.setDiffComparator(ignoreWhitespace ? RawTextComparator.WS_IGNORE_ALL : RawTextComparator.DEFAULT);
			formatter.setDetectRenames(true);
			if (!repositoryPaths.isEmpty())
			{
				formatter.setPathFilter(PathFilterGroup.createFromStrings(repositoryPaths));
			}

			AbstractTreeIterator oldTree = prepareTreeParser(repository, resolveRevision(repository, from));
			AbstractTreeIterator newTree = (toRevision == null || toRevision.isBlank())
				? new FileTreeIterator(repository)
				: prepareTreeParser(repository, resolveRevision(repository, toRevision.trim()));

			List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
			for (DiffEntry diff : diffs)
			{
				formatter.format(diff);
			}
			String result = out.toString(StandardCharsets.UTF_8);
			return result.isEmpty() ? "No changes." : result;
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
		}
	}

	/**
	 * Renders the diff of an already-resolved repository. This is the whole diff algorithm, split out
	 * from {@link #getDiff(String, String, boolean, String, boolean)} so it can be exercised against a
	 * plain JGit repository without an Eclipse workspace: the public entry points do the
	 * workspace-dependent work (resolving the repository and translating project-relative paths) and
	 * then delegate here.
	 *
	 * @param repository the repository to diff, already resolved
	 * @param staged {@code true} to diff HEAD against the index, {@code false} for index against the working tree
	 * @param repositoryPaths repository-relative paths to limit the diff to; empty for no path filter
	 * @param ignoreWhitespace {@code true} to ignore all whitespace differences
	 */
	public static String renderDiff(Repository repository, boolean staged, List<String> repositoryPaths, boolean ignoreWhitespace)
	{
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			DiffFormatter formatter = new DiffFormatter(out))
		{
			ObjectId head = repository.resolve("HEAD");
			if (head == null)
			{
				return "No commits yet.";
			}

			formatter.setRepository(repository);
			formatter.setDiffComparator(ignoreWhitespace ? RawTextComparator.WS_IGNORE_ALL : RawTextComparator.DEFAULT);
			formatter.setDetectRenames(true);
			if (repositoryPaths != null && !repositoryPaths.isEmpty())
			{
				formatter.setPathFilter(PathFilterGroup.createFromStrings(repositoryPaths));
			}

			AbstractTreeIterator oldTree = staged ? prepareTreeParser(repository, head) : prepareIndexTreeParser(repository);
			AbstractTreeIterator newTree = staged ? prepareIndexTreeParser(repository) : new FileTreeIterator(repository);

			List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
			for (DiffEntry diff : diffs)
			{
				formatter.format(diff);
			}
			String result = out.toString(StandardCharsets.UTF_8);
			return result.isEmpty() ? "No changes." : result;
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
		}
	}

	/**
	 * Translates a comma-separated list of project-relative paths into repository-relative pathspecs.
	 * <p>
	 * Every tool in this service takes project-relative paths and {@code gitStatus} reports them that
	 * way, but JGit reads a pathspec from the REPOSITORY root. For a project that does not sit at the
	 * repository root - which is every project here, since they live under {@code bundles/} and
	 * {@code tests/} - the two differ, and Git does not fail on a pathspec that matches nothing. So a
	 * path copied out of {@code gitStatus} and handed straight to JGit silently matches nothing and
	 * reports success. Upstream AssistAI hit the same thing and named it in commit {@code aca70c0}
	 * (SVY-21303); this helper is the single place that resolution happens.
	 * </p>
	 * <p>
	 * {@code "."} (or a blank filter) means "the whole project", which resolves to the project's own
	 * prefix rather than the repository root, so staging "." in one project cannot stage another's
	 * files. A path that escapes the project (absolute, {@code ..}, or a Windows drive prefix) is
	 * rejected rather than silently resolved outside it.
	 * </p>
	 * <p>
	 * On the repository-centric path (SVY-21524) {@code projectName} is {@code null}: the caller
	 * named a {@code repo} instead, so there is no project to resolve a prefix from and the paths are
	 * already repository-relative. They are then used as given - no prefix is prepended - rather than
	 * handed to {@code getProject(null)}, which throws a NullPointerException inside
	 * {@code WorkspaceRoot}.
	 * </p>
	 */
	private List<String> resolveProjectPaths(String projectName, String pathFilter)
	{
		String projectPrefix = projectPrefixOrNull(projectName);

		if (pathFilter == null || pathFilter.isBlank() || ".".equals(pathFilter.trim()))
		{
			// "the whole project": its own prefix, never the repository root, so one project's "."
			// cannot reach another project's files.
			return projectPrefix == null || projectPrefix.isBlank() ? List.of() : List.of(projectPrefix);
		}

		return Arrays.stream(pathFilter.split(","))
			.map(String::trim)
			.filter(path -> !path.isEmpty())
			.map(path -> {
				String input = path.replace('\\', '/');
				Path normalizedPath = Path.of(input).normalize();
				String normalized = normalizedPath.toString().replace('\\', '/');
				if (normalizedPath.isAbsolute() || normalized.equals("..") || normalized.startsWith("../") ||
					input.matches("^[A-Za-z]:.*"))
				{
					throw new IllegalArgumentException("Diff path must be relative to the Eclipse project: " + path);
				}
				return projectPrefix == null || projectPrefix.isBlank() ? normalized : projectPrefix + "/" + normalized;
			})
			.toList();
	}
	/**
	 * The repository-relative prefix of an Eclipse project, or {@code null} when there is no project
	 * to ask.
	 * <p>
	 * {@code projectName} is {@code null} or blank on the repository-centric path (SVY-21524), where
	 * the caller named a {@code repo} instead. {@code WorkspaceRoot.getProject(null)} throws a
	 * NullPointerException from deep inside Eclipse ({@code ConcurrentHashMap.get}), so the absence
	 * is answered here instead: no project, no prefix, and the caller's paths are taken as already
	 * repository-relative.
	 * </p>
	 */
	public static String projectPrefixOrNull(String projectName)
	{
		if (projectName == null || projectName.trim().isEmpty()) return null;
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		RepositoryMapping mapping = RepositoryMapping.getMapping(project);
		return mapping == null ? "" : mapping.getRepoRelativePath(project);
	}


	/**
	 * Resolves a revision string to a commit, refusing one that does not exist rather than returning
	 * {@code null} for a caller to trip over. Ported from AssistAI (upstream {@code aca70c0}).
	 */
	private static ObjectId resolveRevision(Repository repository, String revision) throws IOException
	{
		if (revision == null || revision.isBlank())
		{
			throw new IllegalArgumentException("A revision is required.");
		}
		ObjectId objectId = repository.resolve(revision);
		if (objectId == null || !repository.getObjectDatabase().has(objectId))
		{
			throw new IllegalArgumentException("Git revision could not be resolved: " + revision);
		}
		return objectId;
	}

	public String listTags(String projectName)
	{
		return listTags(projectName, null);
	}

	/**
	 * Lists the repository's tags, marking the annotated ones and showing their message. Ported from
	 * AssistAI's {@code listTags} (upstream {@code aca70c0}); reported as prose rather than upstream's
	 * {@code GitTagListResponse}.
	 */
	public String listTags(String projectName, String repo)
	{
		return renderTagList(resolveRepository(projectName, repo));
	}

	/** {@link #listTags} on an already-resolved repository, so a plain test can drive it. */
	public static String renderTagList(Repository repository)
	{
		try (Git git = new Git(repository))
		{
			var tags = git.tagList().call();
			if (tags.isEmpty())
			{
				return "No tags.";
			}
			StringBuilder sb = new StringBuilder();
			for (org.eclipse.jgit.lib.Ref ref : tags)
			{
				sb.append(describeTag(repository, ref)).append("\n");
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list tags: " + e.getMessage(), e);
		}
	}

	/** One tag as a line of prose: name, the commit it points at, and an annotated tag's message. */
	private static String describeTag(Repository repository, org.eclipse.jgit.lib.Ref ref) throws IOException
	{
		org.eclipse.jgit.lib.Ref peeled = repository.getRefDatabase().peel(ref);
		ObjectId objectId = ref.getObjectId();
		ObjectId peeledId = peeled.getPeeledObjectId();
		boolean annotated = peeledId != null;

		String message = null;
		if (annotated)
		{
			try (RevWalk walk = new RevWalk(repository))
			{
				org.eclipse.jgit.revwalk.RevObject object = walk.parseAny(objectId);
				if (object instanceof org.eclipse.jgit.revwalk.RevTag tag)
				{
					message = tag.getFullMessage() == null ? null : tag.getFullMessage().strip();
				}
			}
		}

		String name = Repository.shortenRefName(ref.getName());
		String target = annotated ? peeledId.getName() : (objectId == null ? "?" : objectId.getName());
		StringBuilder line = new StringBuilder(name)
			.append(annotated ? " (annotated)" : "")
			.append(" -> ")
			.append(target.length() >= 7 ? target.substring(0, 7) : target);
		if (message != null && !message.isEmpty())
		{
			line.append(" : ").append(message.lines().findFirst().orElse(""));
		}
		return line.toString();
	}

	public String createTag(String projectName, String tagName, String message, String revision)
	{
		return createTag(projectName, null, tagName, message, revision);
	}

	/**
	 * Creates a tag at a revision (HEAD by default): annotated when a message is given, lightweight
	 * otherwise. Ported from AssistAI's {@code createTag} (upstream {@code aca70c0}).
	 */
	public String createTag(String projectName, String repo, String tagName, String message, String revision)
	{
		return createTagIn(resolveRepository(projectName, repo), tagName, message, revision);
	}

	/** {@link #createTag} on an already-resolved repository, so a plain test can drive it. */
	public static String createTagIn(Repository repository, String tagName, String message, String revision)
	{
		Objects.requireNonNull(tagName, "tagName is required");

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository); RevWalk walk = new RevWalk(repository))
		{
			ObjectId target = resolveRevision(repository,
				revision == null || revision.isBlank() ? Constants.HEAD : revision);
			org.eclipse.jgit.revwalk.RevObject object = walk.parseAny(target);
			boolean annotated = message != null && !message.isBlank();

			var command = git.tag().setName(tagName).setObjectId(object).setAnnotated(annotated);
			if (annotated)
			{
				command.setMessage(message);
			}
			org.eclipse.jgit.lib.Ref ref = command.call();
			return "Created " + (annotated ? "annotated" : "lightweight") + " tag: " + describeTag(repository, ref);
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to create tag: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
	}

	public String deleteTag(String projectName, String tagName)
	{
		return deleteTag(projectName, null, tagName);
	}

	/**
	 * Deletes a local tag, reporting what it was before it went. Ported from AssistAI's
	 * {@code deleteTag} (upstream {@code aca70c0}).
	 */
	public String deleteTag(String projectName, String repo, String tagName)
	{
		return deleteTagIn(resolveRepository(projectName, repo), tagName);
	}

	/** {@link #deleteTag} on an already-resolved repository, so a plain test can drive it. */
	public static String deleteTagIn(Repository repository, String tagName)
	{
		Objects.requireNonNull(tagName, "tagName is required");

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			org.eclipse.jgit.lib.Ref ref = repository.findRef(Constants.R_TAGS + tagName);
			if (ref == null)
			{
				throw new IllegalArgumentException("No tag named '" + tagName + "'.");
			}
			String described = describeTag(repository, ref);
			git.tagDelete().setTags(tagName).call();
			return "Deleted tag: " + described;
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to delete tag: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
	}

	public String listRemotes(String projectName)
	{
		return listRemotes(projectName, null);
	}

	/**
	 * Lists the configured remotes with their fetch and push URLs, so a caller can discover a valid
	 * remote name instead of guessing {@code origin}. Ported from AssistAI's {@code listRemotes}
	 * (upstream {@code aca70c0}).
	 */
	public String listRemotes(String projectName, String repo)
	{
		return renderRemoteList(resolveRepository(projectName, repo));
	}

	/** {@link #listRemotes} on an already-resolved repository, so a plain test can drive it. */
	public static String renderRemoteList(Repository repository)
	{
		try (Git git = new Git(repository))
		{
			var configs = git.remoteList().call();
			if (configs.isEmpty())
			{
				return "No remotes configured.";
			}
			StringBuilder sb = new StringBuilder();
			for (org.eclipse.jgit.transport.RemoteConfig config : configs)
			{
				String fetchUrl = config.getURIs().isEmpty() ? null : config.getURIs().get(0).toString();
				String pushUrl = config.getPushURIs().isEmpty() ? fetchUrl : config.getPushURIs().get(0).toString();
				sb.append(config.getName()).append("\n")
					.append("  fetch: ").append(fetchUrl == null ? "(none)" : fetchUrl).append("\n")
					.append("  push:  ").append(pushUrl == null ? "(none)" : pushUrl).append("\n");
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to list remotes: " + e.getMessage(), e);
		}
	}

	public String showCommit(String projectName, String revision, String pathFilter)
	{
		return showCommit(projectName, null, revision, pathFilter);
	}

	/**
	 * Shows one commit and the change it introduced against its first parent - what {@code git show}
	 * prints. A root commit is diffed against an empty tree, and a merge commit reports both parents
	 * while the diff still follows the first. Ported from AssistAI's {@code show} (upstream
	 * {@code aca70c0}); rendered as prose rather than upstream's {@code GitShowResponse}.
	 *
	 * @param revision the branch, tag or commit to show; {@code null} means {@code HEAD}
	 * @param pathFilter optional comma-separated project-relative paths to limit the diff to
	 */
	public String showCommit(String projectName, String repo, String revision, String pathFilter)
	{
		return renderCommit(resolveRepository(projectName, repo), revision,
			resolveProjectPaths(projectName, pathFilter));
	}

	/** {@link #showCommit} on an already-resolved repository, so a plain test can drive it. */
	public static String renderCommit(Repository repository, String revision, List<String> repositoryPaths)
	{
		String requested = revision == null || revision.isBlank() ? Constants.HEAD : revision.trim();

		try (RevWalk walk = new RevWalk(repository);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			DiffFormatter formatter = new DiffFormatter(out))
		{
			RevCommit commit = walk.parseCommit(resolveRevision(repository, requested));

			StringBuilder sb = new StringBuilder();
			sb.append("commit ").append(commit.getName()).append("\n");
			sb.append("Author: ").append(commit.getAuthorIdent().getName())
				.append(" <").append(commit.getAuthorIdent().getEmailAddress()).append(">\n");
			sb.append("Date:   ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z")
				.format(commit.getAuthorIdent().getWhen())).append("\n");
			if (commit.getParentCount() > 0)
			{
				StringBuilder parents = new StringBuilder();
				for (RevCommit parent : commit.getParents())
				{
					parents.append(parents.length() == 0 ? "" : " ").append(parent.getName());
				}
				sb.append("Parents: ").append(parents).append("\n");
			}
			sb.append("\n").append(commit.getFullMessage().strip()).append("\n\n");

			AbstractTreeIterator oldTree = commit.getParentCount() == 0
				? new org.eclipse.jgit.treewalk.EmptyTreeIterator()
				: prepareTreeParser(repository, commit.getParent(0).getId());
			AbstractTreeIterator newTree = prepareTreeParser(repository, commit.getId());

			formatter.setRepository(repository);
			formatter.setDetectRenames(true);
			if (!repositoryPaths.isEmpty())
			{
				formatter.setPathFilter(PathFilterGroup.createFromStrings(repositoryPaths));
			}

			List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
			formatter.format(diffs);
			sb.append(out.toString(StandardCharsets.UTF_8));
			return sb.toString();
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to show commit: " + e.getMessage(), e);
		}
	}

	public String discardChanges(String projectName, String filePattern)
	{
		return discardChanges(projectName, null, filePattern);
	}

	/**
	 * Discards uncommitted modifications of tracked files by restoring their working-tree content
	 * from the index ({@code git checkout -- <path>}). Staged changes and untracked files are left
	 * alone. Ported from AssistAI's {@code discardChanges} (upstream {@code aca70c0}).
	 * <p>
	 * <b>This cannot be undone</b> - the discarded content was never committed and is not in Local
	 * History either, so the tool description tells the caller to stash first when in doubt.
	 * </p>
	 */
	public String discardChanges(String projectName, String repo, String filePattern)
	{
		Objects.requireNonNull(filePattern, "filePattern is required");

		Repository repository = resolveRepository(projectName, repo);
		String result = discardChangesIn(repository, resolveProjectPaths(projectName, filePattern), filePattern);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/** {@link #discardChanges} on an already-resolved repository, so a plain test can drive it. */
	public static String discardChangesIn(Repository repository, List<String> pathspecs, String reportedPattern)
	{
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		List<String> restored = new ArrayList<>();
		try (Git git = new Git(repository))
		{
			var before = git.status().call();
			Set<String> candidates = new TreeSet<>();
			for (String path : before.getModified())
			{
				if (matchesPathspec(path, pathspecs)) candidates.add(path);
			}
			for (String path : before.getMissing())
			{
				if (matchesPathspec(path, pathspecs)) candidates.add(path);
			}

			if (!candidates.isEmpty())
			{
				var command = git.checkout();
				if (pathspecs.isEmpty())
				{
					command.setAllPaths(true);
				}
				else
				{
					pathspecs.forEach(command::addPath);
				}
				command.call();

				var after = git.status().call();
				for (String path : candidates)
				{
					if (!after.getModified().contains(path) && !after.getMissing().contains(path))
					{
						restored.add(path);
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to discard changes: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		return restored.isEmpty()
			? "No modified tracked files matched: " + reportedPattern
			: "Discarded changes in " + restored.size() + " file(s):\n  " + String.join("\n  ", restored);
	}

	public String resetToRevision(String projectName, String revision, String mode)
	{
		return resetToRevision(projectName, null, revision, mode);
	}

	/**
	 * Moves the checked-out branch to a revision ({@code git reset}). {@code SOFT} keeps the index and
	 * working tree, so the commits between become staged changes; {@code MIXED} (the default) also
	 * resets the index; {@code HARD} also rewrites the working tree, which <b>discards uncommitted
	 * work</b> and is how a conflicted merge or cherry-pick is abandoned. Ported from AssistAI's
	 * {@code resetToRevision} (upstream {@code aca70c0}).
	 */
	public String resetToRevision(String projectName, String repo, String revision, String mode)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = resetToRevisionIn(repository, revision, mode);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/** {@link #resetToRevision} on an already-resolved repository, so a plain test can drive it. */
	public static String resetToRevisionIn(Repository repository, String revision, String mode)
	{
		String requested = revision == null || revision.isBlank() ? Constants.HEAD : revision.trim();
		org.eclipse.jgit.api.ResetCommand.ResetType resetType;
		try
		{
			resetType = mode == null || mode.isBlank()
				? org.eclipse.jgit.api.ResetCommand.ResetType.MIXED
				: org.eclipse.jgit.api.ResetCommand.ResetType.valueOf(mode.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Unknown reset mode '" + mode + "'. Use SOFT, MIXED or HARD.");
		}

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		String previousHead;
		try (Git git = new Git(repository))
		{
			resolveRevision(repository, requested);
			previousHead = headSha(repository);
			git.reset().setMode(resetType).setRef(requested).call();
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to reset: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		return "Reset (" + resetType + ") to " + requested + ".\n  was: " + previousHead + "\n  now: " +
			headSha(repository);
	}

	/** The current HEAD commit sha, or {@code "(none)"} in a repository with no commits. */
	private static String headSha(Repository repository)
	{
		try
		{
			ObjectId head = repository.resolve(Constants.HEAD);
			return head == null ? "(none)" : head.getName();
		}
		catch (IOException e)
		{
			return "(unknown)";
		}
	}

	public String removeFromIndex(String projectName, String filePattern)
	{
		return removeFromIndex(projectName, null, filePattern);
	}

	/**
	 * Drops index entries while leaving the files on disk ({@code git rm --cached}), so Git stops
	 * tracking them and they become untracked.
	 * <p>
	 * Fills a real gap: neither {@link #addFiles} nor {@link #resetFiles} can stop Git tracking a
	 * file that HEAD contains - staging puts it back and resetting restores HEAD's entry. The entries
	 * are removed from the {@link DirCache} directly rather than through JGit's {@code RmCommand},
	 * whose path filter cannot express {@code "."} for a project at the repository root. Ported from
	 * AssistAI's {@code GitService} (upstream commit {@code a97fdcd}); purely additive.
	 * </p>
	 *
	 * @param projectName the Eclipse project name (legacy path); may be {@code null} when {@code repo} is given
	 * @param repo a repository name or working-tree path (repository-centric path); may be {@code null}
	 * @param filePattern comma-separated project-relative files or folders, or {@code "."} for everything
	 */
	public String removeFromIndex(String projectName, String repo, String filePattern)
	{
		Objects.requireNonNull(filePattern, "filePattern is required");

		Repository repository = resolveRepository(projectName, repo);
		List<String> pathspecs = resolveProjectPaths(projectName, filePattern);

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		int removed = 0;
		try
		{
			DirCache dirCache = repository.lockDirCache();
			try
			{
				DirCacheEditor editor = dirCache.editor();
				Set<String> paths = new TreeSet<>();
				for (int i = 0; i < dirCache.getEntryCount(); i++)
				{
					String path = dirCache.getEntry(i).getPathString();
					if (matchesPathspec(path, pathspecs) && paths.add(path))
					{
						editor.add(new DirCacheEditor.DeletePath(path));
					}
				}
				editor.commit();
				removed = paths.size();
			}
			finally
			{
				dirCache.unlock();
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to remove files from the index: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		return removed == 0
			? "No tracked files matched: " + filePattern
			: "Removed from the index (files kept on disk): " + removed;
	}

	/**
	 * Whether a repository-relative index path is covered by the given pathspecs. An empty pathspec
	 * list means "everything", matching {@code git rm --cached .}; a pathspec matches the path itself
	 * or anything beneath it when it names a directory.
	 */
	private static boolean matchesPathspec(String path, List<String> pathspecs)
	{
		if (pathspecs == null || pathspecs.isEmpty())
		{
			return true;
		}
		for (String pathspec : pathspecs)
		{
			if (path.equals(pathspec) || path.startsWith(pathspec + "/"))
			{
				return true;
			}
		}
		return false;
	}

	public String readFileAtRevision(String projectName, String filePath, String revision)
	{
		return readFileAtRevision(projectName, null, filePath, revision);
	}

	/**
	 * Reads a UTF-8 text file out of a Git revision without touching the working tree.
	 * <p>
	 * Fills a real gap: there was no way to see what a file looked like at {@code HEAD}, on a branch,
	 * at a commit, or as currently staged, short of checking it out. Ported from AssistAI's
	 * {@code GitService} (upstream commit {@code 07d6f07}); purely additive.
	 * </p>
	 *
	 * @param projectName the Eclipse project name (legacy path); may be {@code null} when {@code repo} is given
	 * @param repo a repository name or working-tree path (repository-centric path); may be {@code null}
	 * @param filePath the file path relative to the Eclipse project
	 * @param revision a branch, tag or commit, or {@code INDEX} to read the staged version; {@code null} means {@code HEAD}
	 */
	public String readFileAtRevision(String projectName, String repo, String filePath, String revision)
	{
		Objects.requireNonNull(filePath, "filePath is required");
		String effectiveRevision = (revision == null || revision.isBlank()) ? "HEAD" : revision.trim();

		Repository repository = resolveRepository(projectName, repo);
		String repositoryPath = toRepositoryRelativePath(projectName, filePath);

		try
		{
			ObjectLoader loader;
			if ("INDEX".equalsIgnoreCase(effectiveRevision))
			{
				DirCacheEntry entry = repository.readDirCache().getEntry(repositoryPath);
				if (entry == null)
				{
					throw new IllegalArgumentException("File '" + filePath + "' was not found in the Git index.");
				}
				loader = repository.open(entry.getObjectId());
			}
			else
			{
				ObjectId treeId = repository.resolve(effectiveRevision + "^{tree}");
				if (treeId == null)
				{
					throw new IllegalArgumentException("Git revision could not be resolved: " + effectiveRevision);
				}
				try (TreeWalk treeWalk = TreeWalk.forPath(repository, repositoryPath, treeId))
				{
					if (treeWalk == null)
					{
						throw new IllegalArgumentException(
							"File '" + filePath + "' was not found at revision '" + effectiveRevision + "'.");
					}
					loader = repository.open(treeWalk.getObjectId(0));
				}
			}

			if (loader.getSize() > MAX_REVISION_READ_BYTES)
			{
				throw new IllegalArgumentException("Git file is larger than the 5 MiB read limit: " + filePath);
			}

			byte[] content = loader.getBytes();
			for (byte value : content)
			{
				if (value == 0)
				{
					throw new IllegalArgumentException("Git file appears to be binary: " + filePath);
				}
			}
			return new String(content, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to read Git file: " + e.getMessage(), e);
		}
	}

	/**
	 * Translates one project-relative path into a repository-relative path, rejecting a path that
	 * escapes the project (absolute, {@code ..}, or a Windows drive prefix) rather than silently
	 * resolving outside it.
	 */
	private String toRepositoryRelativePath(String projectName, String filePath)
	{
		String input = filePath.replace('\\', '/');
		Path normalizedPath = Path.of(input).normalize();
		String normalized = normalizedPath.toString().replace('\\', '/');
		if (input.isBlank() || normalizedPath.isAbsolute() || normalized.equals("..") ||
			normalized.startsWith("../") || input.matches("^[A-Za-z]:.*"))
		{
			throw new IllegalArgumentException("File path must be relative to the Eclipse project: " + filePath);
		}

		// projectName is null on the repository-centric path, where the path is already
		// repository-relative - see projectPrefixOrNull.
		String projectPrefix = projectPrefixOrNull(projectName);
		return projectPrefix == null || projectPrefix.isBlank() ? normalized : projectPrefix + "/" + normalized;
	}

	/** Size cap for {@link #readFileAtRevision}, so a huge blob cannot be pulled into a tool response. */
	private static final long MAX_REVISION_READ_BYTES = 5L * 1024 * 1024;

	/** The index as a tree iterator, so a diff can name the index as one of its sides explicitly. */
	private static AbstractTreeIterator prepareIndexTreeParser(Repository repository) throws IOException
	{
		try (ObjectInserter inserter = repository.newObjectInserter();
			var reader = repository.newObjectReader())
		{
			var treeId = repository.readDirCache().writeTree(inserter);
			return new CanonicalTreeParser(null, reader, treeId);
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
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			var cmd = git.branchCreate().setName(branchName);
			if (startPoint != null && !startPoint.isEmpty())
			{
				cmd.setStartPoint(startPoint);
			}
			cmd.call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to create branch: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		return "Created branch: " + branchName;
	}

	public String deleteBranch(String projectName, String branchName, boolean force)
	{
		return deleteBranch(projectName, null, branchName, force);
	}

	public String deleteBranch(String projectName, String repo, String branchName, boolean force)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
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
		finally
		{
			lock.unlock();
		}
	}

	public String checkoutBranch(String projectName, String branchName)
	{
		return checkoutBranch(projectName, null, branchName);
	}

	public String checkoutBranch(String projectName, String repo, String branchName)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			git.checkout().setName(branchName).call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to checkout branch: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		return "Switched to branch: " + branchName;
	}

	public String resetFiles(String projectName, String filePattern)
	{
		return resetFiles(projectName, null, filePattern);
	}

	/**
	 * Unstages project-relative files or folders. {@code filePattern} is comma-separated, and
	 * {@code "."} means the whole project. Resolved through
	 * {@link #resolveProjectPaths(String, String)} for the same reason as {@link #addFiles}: a
	 * repository-root pathspec would silently match nothing for a nested project.
	 */
	public String resetFiles(String projectName, String repo, String filePattern)
	{
		Objects.requireNonNull(filePattern, "filePattern is required");

		Repository repository = resolveRepository(projectName, repo);
		String result = unstageIn(repository, resolveProjectPaths(projectName, filePattern), filePattern);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/**
	 * {@link #resetFiles} on an already-resolved repository with already-resolved pathspecs, so a
	 * plain test can drive it. The pathspecs are REPOSITORY-relative; an empty list means the whole
	 * tree.
	 */
	public static String unstageIn(Repository repository, List<String> pathspecs, String reportedPattern)
	{
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			var reset = git.reset();
			if (pathspecs.isEmpty())
			{
				reset.addPath(".");
			}
			else
			{
				for (String pathspec : pathspecs)
				{
					reset.addPath(pathspec);
				}
			}
			reset.call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to reset files: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		return "Unstaged: " + reportedPattern;
	}

	public String stash(String projectName, String message)
	{
		return stash(projectName, null, message);
	}

	public String stash(String projectName, String repo, String message)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		org.eclipse.jgit.lib.ObjectId stashRef;
		try (Git git = new Git(repository))
		{
			var cmd = git.stashCreate();
			if (message != null && !message.isEmpty())
			{
				cmd.setWorkingDirectoryMessage(message);
			}
			stashRef = cmd.call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to stash: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		if (stashRef == null)
		{
			return "No local changes to stash.";
		}
		return "Stashed working directory: " + stashRef.getName();
	}

	public String stashPop(String projectName)
	{
		return stashPop(projectName, null);
	}

	public String stashPop(String projectName, String repo)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		try (Git git = new Git(repository))
		{
			git.stashApply().call();
			git.stashDrop().call();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to pop stash: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}
		refreshAfterChange(projectName, repository);
		return "Applied and dropped stash.";
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

	// -------------------------------------------------------------------------
	// Merging, rebasing and replaying commits (SVY-21303, upstream aca70c0)
	// -------------------------------------------------------------------------

	/**
	 * The conflicting paths of the repository, sorted, as {@code git status} would report them.
	 * Ported from AssistAI's {@code conflictingFiles} (upstream {@code aca70c0}); it resolves each
	 * path to an Eclipse project there, while we report the repository-relative path, since our
	 * prose returns carry no project mapping.
	 */
	private static List<String> conflictingPaths(Git git) throws Exception
	{
		return new ArrayList<>(new TreeSet<>(git.status().call().getConflicting()));
	}

	/** The sorted paths of a JGit failing-paths map, which is {@code null} when nothing failed. */
	private static List<String> failingPaths(Map<String, ?> failing)
	{
		return failing == null || failing.isEmpty() ? List.of() : new ArrayList<>(new TreeSet<>(failing.keySet()));
	}

	/** The sorted paths of a JGit path list, which is {@code null} for a status that carries none. */
	private static List<String> pathList(java.util.Collection<String> paths)
	{
		return paths == null || paths.isEmpty() ? List.of() : new ArrayList<>(new TreeSet<>(paths));
	}

	/** Appends {@code "  <path>\n"} for every path, so a caller sees exactly which files are involved. */
	private static void appendPaths(StringBuilder sb, List<String> paths)
	{
		for (String path : paths)
		{
			sb.append("  ").append(path).append("\n");
		}
	}

	/** The current branch name, or {@code "(unknown)"} when it cannot be read. */
	private static String currentBranchName(Repository repository)
	{
		try
		{
			String branch = repository.getBranch();
			return branch == null ? "(unknown)" : branch;
		}
		catch (IOException e)
		{
			return "(unknown)";
		}
	}

	/**
	 * The commits reachable from {@code newHead} but not from {@code previousHead}, oldest first.
	 * Ported from AssistAI's {@code commitsBetween} (upstream {@code aca70c0}).
	 */
	private static List<RevCommit> commitsBetween(Git git, String previousHead, String newHead) throws Exception
	{
		if (newHead == null || newHead.equals(previousHead) || "(none)".equals(newHead) || "(unknown)".equals(newHead))
		{
			return List.of();
		}
		var log = git.log();
		if (previousHead == null || "(none)".equals(previousHead) || "(unknown)".equals(previousHead))
		{
			log.add(ObjectId.fromString(newHead));
		}
		else
		{
			log.addRange(ObjectId.fromString(previousHead), ObjectId.fromString(newHead));
		}
		List<RevCommit> commits = new ArrayList<>();
		for (RevCommit commit : log.call())
		{
			commits.add(commit);
		}
		Collections.reverse(commits);
		return commits;
	}

	public String merge(String projectName, String ref, String fastForwardMode, boolean squash, String message)
	{
		return merge(projectName, null, ref, fastForwardMode, squash, message);
	}

	/**
	 * Merges a branch, tag or commit into the checked-out branch. Ported from AssistAI's
	 * {@code merge} (upstream {@code aca70c0}).
	 * <p>
	 * Every outcome that needs a different next step from the caller - conflict markers in the
	 * working tree, local changes that would be overwritten, a squash left staged - is reported as
	 * a status and the files involved rather than thrown, because the repository is left in that
	 * state and the caller has to act on it. Upstream carries this in a {@code GitMergeResponse};
	 * we keep the same information in prose (the {@code a6dc32d} structured-result model is a
	 * recorded divergence).
	 * </p>
	 *
	 * @param ref the branch, tag or commit to merge in; required
	 * @param fastForwardMode FF (the default), NO_FF or FF_ONLY
	 * @param squash {@code true} to leave the merged result staged instead of committing it
	 * @param message an optional message for the merge commit
	 */
	public String merge(String projectName, String repo, String ref, String fastForwardMode, boolean squash,
		String message)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = mergeIn(repository, ref, fastForwardMode, squash, message);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/** {@link #merge} on an already-resolved repository, so a plain test can drive it. */
	public static String mergeIn(Repository repository, String ref, String fastForwardMode, boolean squash,
		String message)
	{
		Objects.requireNonNull(ref, "ref is required");
		MergeCommand.FastForwardMode mode;
		try
		{
			mode = fastForwardMode == null || fastForwardMode.isBlank()
				? MergeCommand.FastForwardMode.FF
				: MergeCommand.FastForwardMode.valueOf(fastForwardMode.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Unknown fastForward mode '" + fastForwardMode +
				"'. Use FF, NO_FF or FF_ONLY.");
		}

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		String previousHead;
		MergeResult result;
		List<String> conflicting = List.of();
		try (Git git = new Git(repository))
		{
			ObjectId target = resolveRevision(repository, ref);
			previousHead = headSha(repository);

			MergeCommand command = git.merge().setFastForward(mode).setSquash(squash);
			org.eclipse.jgit.lib.Ref namedRef = repository.findRef(ref);
			if (namedRef != null)
			{
				command.include(namedRef);
			}
			else
			{
				command.include(ref, target);
			}
			if (message != null && !message.isBlank())
			{
				command.setMessage(message);
			}
			result = command.call();
			if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING)
			{
				conflicting = conflictingPaths(git);
			}
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (org.eclipse.jgit.api.errors.WrongRepositoryStateException e)
		{
			throw new IllegalStateException("The repository is mid-operation, so it cannot merge: " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to merge: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}

		String newHead = headSha(repository);
		StringBuilder sb = new StringBuilder();
		switch (result.getMergeStatus())
		{
			case FAST_FORWARD -> sb.append("Merge FAST_FORWARD: '").append(ref).append("'\n");
			case ALREADY_UP_TO_DATE -> sb.append("Merge ALREADY_UP_TO_DATE: '").append(ref).append("'\n");
			case MERGED -> sb.append("Merge MERGED: '").append(ref).append("'\n");
			case FAST_FORWARD_SQUASHED, MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED, MERGED_NOT_COMMITTED -> sb
				.append("Merge MERGED_NOT_COMMITTED: '").append(ref)
				.append("' - the merged result is staged; gitCommit it.\n");
			case CONFLICTING ->
			{
				sb.append("Merge CONFLICTED: '").append(ref).append("' left conflict markers in ")
					.append(conflicting.size()).append(" file(s):\n");
				appendPaths(sb, conflicting);
				sb.append("Resolve them, gitAdd them and gitCommit to finish the merge, or gitResetToRevision HEAD" +
					" with mode HARD to abandon it.\n");
			}
			case CHECKOUT_CONFLICT ->
			{
				List<String> blocking = pathList(result.getCheckoutConflicts());
				sb.append("Merge BLOCKED: local changes to ").append(blocking.size())
					.append(" file(s) would be overwritten by merging '").append(ref)
					.append("'. Nothing was merged.\n");
				appendPaths(sb, blocking);
				sb.append("Commit, stash or discard them first.\n");
			}
			case FAILED ->
			{
				List<String> blocking = failingPaths(result.getFailingPaths());
				sb.append("Merge BLOCKED: merging '").append(ref).append("' was refused because of ")
					.append(blocking.size()).append(" dirty file(s). Nothing was merged.\n");
				appendPaths(sb, blocking);
				sb.append("Commit, stash or discard them first.\n");
			}
			default -> sb.append("Merge FAILED: JGit reported merge status ").append(result.getMergeStatus())
				.append(".\n");
		}
		sb.append("  was: ").append(previousHead).append("\n  now: ").append(newHead).append("\n");
		return sb.toString();
	}

	public String rebase(String projectName, String upstream, String operation)
	{
		return rebase(projectName, null, upstream, operation);
	}

	/**
	 * Rebases the checked-out branch onto an upstream, or drives a rebase already in progress.
	 * Ported from AssistAI's {@code rebase} (upstream {@code aca70c0}).
	 * <p>
	 * A rebase pauses on a conflict and stays paused across calls, so every call reports where the
	 * repository now is - which commit it stopped on and which files conflict - and the caller
	 * answers with the next operation: {@code CONTINUE} once the conflicts are resolved and staged,
	 * {@code SKIP} to drop that commit, or {@code ABORT} to put the branch back.
	 * </p>
	 *
	 * @param upstream the branch, tag or commit to rebase onto; required for {@code BEGIN}, ignored otherwise
	 * @param operation BEGIN (the default), CONTINUE, SKIP or ABORT
	 */
	public String rebase(String projectName, String repo, String upstream, String operation)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = rebaseIn(repository, upstream, operation);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/** {@link #rebase} on an already-resolved repository, so a plain test can drive it. */
	public static String rebaseIn(Repository repository, String upstream, String operation)
	{
		RebaseCommand.Operation op;
		try
		{
			op = operation == null || operation.isBlank()
				? RebaseCommand.Operation.BEGIN
				: RebaseCommand.Operation.valueOf(operation.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Unknown rebase operation '" + operation +
				"'. Use BEGIN, CONTINUE, SKIP or ABORT.");
		}
		if (op == RebaseCommand.Operation.BEGIN && (upstream == null || upstream.isBlank()))
		{
			throw new IllegalArgumentException("An upstream is required to begin a rebase.");
		}
		String onto = op == RebaseCommand.Operation.BEGIN ? upstream.trim() : null;

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		String previousHead;
		RebaseResult result;
		List<String> conflicting = List.of();
		try (Git git = new Git(repository))
		{
			previousHead = headSha(repository);
			RebaseCommand command = git.rebase();
			if (op == RebaseCommand.Operation.BEGIN)
			{
				resolveRevision(repository, onto);
				command.setUpstream(onto);
			}
			else
			{
				command.setOperation(op);
			}
			result = command.call();
			if (result.getStatus() == RebaseResult.Status.STOPPED)
			{
				conflicting = conflictingPaths(git);
			}
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (org.eclipse.jgit.api.errors.WrongRepositoryStateException e)
		{
			throw new IllegalStateException(op == RebaseCommand.Operation.BEGIN
				? "The repository is mid-operation, so a rebase cannot start: " + e.getMessage()
				: "No rebase is in progress to " + op.name().toLowerCase() + ": " + e.getMessage(), e);
		}
		catch (org.eclipse.jgit.api.errors.RefNotFoundException e)
		{
			throw new IllegalArgumentException("Git revision could not be resolved: " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to rebase: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Rebase ").append(op.name()).append(" ");
		switch (result.getStatus())
		{
			case OK -> sb.append("OK");
			case UP_TO_DATE -> sb.append("UP_TO_DATE");
			case FAST_FORWARD -> sb.append("FAST_FORWARD");
			case ABORTED -> sb.append("ABORTED");
			case NOTHING_TO_COMMIT -> sb.append("NOTHING_TO_COMMIT - the replayed commit became empty;" +
				" SKIP it or ABORT the rebase");
			case STOPPED ->
			{
				RevCommit stoppedAt = result.getCurrentCommit();
				sb.append("STOPPED on ")
					.append(stoppedAt == null ? "a commit" : stoppedAt.getName() + " " + stoppedAt.getShortMessage())
					.append(", leaving ").append(conflicting.size()).append(" conflicting file(s):\n");
				appendPaths(sb, conflicting);
				sb.append("Resolve and gitAdd them, then call gitRebase with operation CONTINUE - or SKIP that" +
					" commit, or ABORT to put the branch back");
			}
			case UNCOMMITTED_CHANGES ->
			{
				List<String> blocking = pathList(result.getUncommittedChanges());
				sb.append("UNCOMMITTED_CHANGES: ").append(blocking.size())
					.append(" uncommitted change(s) stop the rebase:\n");
				appendPaths(sb, blocking);
				sb.append("Commit or stash them first");
			}
			case CONFLICTS ->
			{
				List<String> blocking = pathList(result.getConflicts());
				sb.append("BLOCKED: local changes to ").append(blocking.size())
					.append(" file(s) would be overwritten:\n");
				appendPaths(sb, blocking);
				sb.append("Commit, stash or discard them first");
			}
			case FAILED ->
			{
				List<String> blocking = failingPaths(result.getFailingPaths());
				sb.append("FAILED on ").append(blocking.size()).append(" file(s):\n");
				appendPaths(sb, blocking);
				sb.append("The original HEAD was restored");
			}
			default -> sb.append("FAILED: JGit reported rebase status ").append(result.getStatus())
				.append(", which this tool does not drive");
		}
		sb.append("\n  onto:   ").append(onto == null ? "(in progress)" : onto)
			.append("\n  branch: ").append(currentBranchName(repository))
			.append("\n  was:    ").append(previousHead)
			.append("\n  now:    ").append(headSha(repository)).append("\n");
		return sb.toString();
	}

	public String cherryPick(String projectName, String commits)
	{
		return cherryPick(projectName, null, commits);
	}

	/**
	 * Applies the changes of existing commits to the checked-out branch as new commits. Ported from
	 * AssistAI's {@code cherryPick} (upstream {@code aca70c0}).
	 *
	 * @param commits comma-separated commit shas or refs, oldest first
	 */
	public String cherryPick(String projectName, String repo, String commits)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = applyCommitsIn(repository, true, commits);
		refreshAfterChange(projectName, repository);
		return result;
	}

	public String revert(String projectName, String commits)
	{
		return revert(projectName, null, commits);
	}

	/**
	 * Undoes the changes of existing commits with new commits that apply the reverse patch, leaving
	 * history intact. Ported from AssistAI's {@code revert} (upstream {@code aca70c0}).
	 *
	 * @param commits comma-separated commit shas or refs
	 */
	public String revert(String projectName, String repo, String commits)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = applyCommitsIn(repository, false, commits);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/**
	 * {@link #cherryPick} and {@link #revert} on an already-resolved repository, so a plain test can
	 * drive them. Ported from AssistAI's {@code applyCommits} (upstream {@code aca70c0}), which
	 * shares one implementation between the two for the same reason: both replay commits and both
	 * stop the same way.
	 *
	 * @param cherryPick {@code true} to cherry-pick, {@code false} to revert
	 */
	public static String applyCommitsIn(Repository repository, boolean cherryPick, String commits)
	{
		String operation = cherryPick ? "Cherry-pick" : "Revert";
		List<String> requested = commits == null
			? List.of()
			: Arrays.stream(commits.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
		if (requested.isEmpty())
		{
			throw new IllegalArgumentException("At least one commit is required.");
		}

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		String previousHead;
		String status;
		List<String> conflicting = List.of();
		List<String> blocking = List.of();
		List<RevCommit> created = List.of();
		try (Git git = new Git(repository))
		{
			previousHead = headSha(repository);
			List<ObjectId> ids = new ArrayList<>();
			for (String commit : requested)
			{
				ids.add(resolveRevision(repository, commit));
			}

			Map<String, ?> failing = null;
			if (cherryPick)
			{
				var command = git.cherryPick();
				ids.forEach(command::include);
				CherryPickResult result = command.call();
				status = switch (result.getStatus())
				{
					case OK -> "APPLIED";
					case CONFLICTING -> "CONFLICTED";
					default -> "BLOCKED";
				};
				failing = result.getFailingPaths();
			}
			else
			{
				RevertCommand command = git.revert();
				ids.forEach(command::include);
				RevCommit newHead = command.call();
				if (newHead != null)
				{
					status = "APPLIED";
				}
				else if (!command.getUnmergedPaths().isEmpty())
				{
					status = "CONFLICTED";
				}
				else
				{
					status = "BLOCKED";
					failing = command.getFailingResult() == null ? null : command.getFailingResult().getFailingPaths();
				}
			}

			if ("CONFLICTED".equals(status))
			{
				conflicting = conflictingPaths(git);
			}
			else if ("BLOCKED".equals(status))
			{
				blocking = failingPaths(failing);
			}
			created = commitsBetween(git, previousHead, headSha(repository));
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (org.eclipse.jgit.api.errors.WrongRepositoryStateException e)
		{
			throw new IllegalStateException("The repository is mid-operation, so it cannot " +
				operation.toLowerCase() + ": " + e.getMessage(), e);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to " + operation.toLowerCase() + ": " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}

		StringBuilder sb = new StringBuilder();
		sb.append(operation).append(" ").append(status).append(": ").append(requested.size())
			.append(" commit(s) requested.\n");
		if (!created.isEmpty())
		{
			sb.append("Created ").append(created.size()).append(" commit(s):\n");
			for (RevCommit commit : created)
			{
				sb.append("  ").append(commit.getName()).append(" ").append(commit.getShortMessage()).append("\n");
			}
		}
		if ("CONFLICTED".equals(status))
		{
			sb.append("A commit did not apply cleanly; ").append(conflicting.size())
				.append(" file(s) hold conflict markers:\n");
			appendPaths(sb, conflicting);
			sb.append("Resolve and gitAdd them and gitCommit, or gitResetToRevision HEAD with mode HARD to abandon" +
				" the operation.\n");
		}
		else if ("BLOCKED".equals(status))
		{
			sb.append("Local changes to ").append(blocking.size()).append(" file(s) block the operation:\n");
			appendPaths(sb, blocking);
			sb.append("Commit, stash or discard them first.\n");
		}
		sb.append("  was: ").append(previousHead).append("\n  now: ").append(headSha(repository)).append("\n");
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// Remotes: fetch, pull and push (SVY-21303, upstream aca70c0)
	// -------------------------------------------------------------------------

	/**
	 * Credentials for an HTTP(S) remote, from EGit's secure store - the same place the IDE's own
	 * fetch and push look, so a remote that works from the Git Repositories view works here. SSH
	 * remotes need nothing: EGit installs its SSH session factory into JGit at startup, so keys and
	 * agents behave as they do in the IDE. Ported from AssistAI's {@code credentialsFor} (upstream
	 * {@code aca70c0}).
	 * <p>
	 * Returns {@code null} - "no stored credentials, let JGit try anonymously" - for anything it
	 * cannot answer, including running outside an OSGi container, which is what lets a plain test
	 * drive {@link #fetchIn}/{@link #pullIn}/{@link #pushIn} against a local remote.
	 * </p>
	 */
	private static CredentialsProvider credentialsFor(Repository repository, String remoteName)
	{
		try
		{
			RemoteConfig config = new RemoteConfig(repository.getConfig(), remoteName);
			List<URIish> uris = config.getURIs();
			if (uris.isEmpty())
			{
				return null;
			}
			URIish uri = uris.get(0);
			if (uri.getScheme() == null || !uri.getScheme().startsWith("http"))
			{
				return null;
			}

			var bundle = FrameworkUtil.getBundle(GitService.class);
			BundleContext context = bundle == null ? null : bundle.getBundleContext();
			if (context == null)
			{
				return null;
			}
			ServiceReference<CredentialsStore> reference = context.getServiceReference(CredentialsStore.class);
			if (reference == null)
			{
				return null;
			}
			try
			{
				CredentialsStore store = context.getService(reference);
				UserPasswordCredentials credentials = store == null ? null : store.getCredentials(uri);
				return credentials == null
					? null
					: new UsernamePasswordCredentialsProvider(credentials.getUser(), credentials.getPassword());
			}
			finally
			{
				context.ungetService(reference);
			}
		}
		catch (Exception e)
		{
			Platform.getLog(GitService.class).warn(
				"Could not read the stored Git credentials for remote '" + remoteName + "': " + e.getMessage());
			return null;
		}
	}

	/**
	 * The remote-tracking refs a fetch actually moved, rendered one per line. Refs JGit reports as
	 * {@code NO_CHANGE} are left out, so "up to date" is an empty list rather than noise. Ported
	 * from AssistAI's {@code refUpdates} (upstream {@code aca70c0}).
	 */
	private static List<String> refUpdateLines(FetchResult result)
	{
		List<String> updates = new ArrayList<>();
		for (TrackingRefUpdate update : result.getTrackingRefUpdates())
		{
			if (update.getResult() == RefUpdate.Result.NO_CHANGE)
			{
				continue;
			}
			updates.add(update.getLocalName() + " (" + update.getResult().name() + "): " +
				shaOrNone(update.getOldObjectId()) + " -> " + shaOrNone(update.getNewObjectId()));
		}
		return updates;
	}

	private static String shaOrNone(ObjectId objectId)
	{
		return objectId == null || ObjectId.zeroId().equals(objectId) ? "(none)" : objectId.getName();
	}

	public String fetch(String projectName, String remote, boolean prune)
	{
		return fetch(projectName, null, remote, prune);
	}

	/**
	 * Fetches from a remote without touching the working tree, and reports every remote-tracking ref
	 * that moved. Ported from AssistAI's {@code fetch} (upstream {@code aca70c0}).
	 * <p>
	 * A remote that cannot be reached, or that refuses the credentials, is reported rather than
	 * thrown: it is the expected outcome of an offline run and the caller needs the reason, not a
	 * stack trace.
	 * </p>
	 *
	 * @param remote the remote name; {@code origin} when {@code null}
	 * @param prune whether to drop remote-tracking refs the remote no longer has
	 */
	public String fetch(String projectName, String repo, String remote, boolean prune)
	{
		return fetchIn(resolveRepository(projectName, repo), remote, prune);
	}

	/** {@link #fetch} on an already-resolved repository, so a plain test can drive it. */
	public static String fetchIn(Repository repository, String remote, boolean prune)
	{
		String remoteName = remote == null || remote.isBlank() ? Constants.DEFAULT_REMOTE_NAME : remote.trim();
		try (Git git = new Git(repository))
		{
			FetchCommand command = git.fetch().setRemote(remoteName).setRemoveDeletedRefs(prune);
			CredentialsProvider credentials = credentialsFor(repository, remoteName);
			if (credentials != null)
			{
				command.setCredentialsProvider(credentials);
			}
			FetchResult result = command.call();
			List<String> updates = refUpdateLines(result);
			StringBuilder sb = new StringBuilder();
			if (updates.isEmpty())
			{
				sb.append("Fetch UP_TO_DATE from '").append(remoteName).append("' - nothing moved.\n");
			}
			else
			{
				sb.append("Fetch UPDATED from '").append(remoteName).append("': ").append(updates.size())
					.append(" ref(s) moved.\n");
				appendPaths(sb, updates);
			}
			String messages = result.getMessages();
			if (messages != null && !messages.isBlank())
			{
				sb.append("Remote said: ").append(messages.strip()).append("\n");
			}
			return sb.toString();
		}
		catch (org.eclipse.jgit.api.errors.InvalidRemoteException e)
		{
			return "Fetch FAILED: no remote named '" + remoteName + "': " + e.getMessage() +
				"\nUse gitRemoteList to see the configured remotes.\n";
		}
		catch (org.eclipse.jgit.api.errors.TransportException e)
		{
			return "Fetch FAILED: the remote '" + remoteName + "' could not be fetched from: " + e.getMessage() + "\n";
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to fetch: " + e.getMessage(), e);
		}
	}

	public String pull(String projectName, String remote, String remoteBranch, boolean rebase)
	{
		return pull(projectName, null, remote, remoteBranch, rebase);
	}

	/**
	 * Fetches and then merges - or, with {@code rebase}, rebases onto - the upstream of the
	 * checked-out branch. Ported from AssistAI's {@code pull} (upstream {@code aca70c0}).
	 *
	 * @param remote the remote name; the branch's configured remote, else {@code origin}, when {@code null}
	 * @param remoteBranch the branch on the remote; the branch's configured upstream when {@code null}
	 * @param rebase {@code true} to rebase local commits onto the fetched ones instead of merging
	 */
	public String pull(String projectName, String repo, String remote, String remoteBranch, boolean rebase)
	{
		Repository repository = resolveRepository(projectName, repo);
		String result = pullIn(repository, remote, remoteBranch, rebase);
		refreshAfterChange(projectName, repository);
		return result;
	}

	/** {@link #pull} on an already-resolved repository, so a plain test can drive it. */
	public static String pullIn(Repository repository, String remote, String remoteBranch, boolean rebase)
	{
		String branch = currentBranchName(repository);
		StoredConfig config = repository.getConfig();
		String configuredRemote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
			ConfigConstants.CONFIG_KEY_REMOTE);
		String configuredMerge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
			ConfigConstants.CONFIG_KEY_MERGE);
		String remoteName = remote != null && !remote.isBlank() ? remote.trim()
			: configuredRemote != null ? configuredRemote : Constants.DEFAULT_REMOTE_NAME;
		String remoteBranchName = remoteBranch != null && !remoteBranch.isBlank() ? remoteBranch.trim()
			: configuredMerge != null ? Repository.shortenRefName(configuredMerge) : null;

		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		String previousHead = headSha(repository);
		PullResult result = null;
		String failure = null;
		List<String> conflicting = List.of();
		try (Git git = new Git(repository))
		{
			PullCommand command = git.pull().setRemote(remoteName).setRebase(rebase);
			if (remoteBranchName != null)
			{
				command.setRemoteBranchName(remoteBranchName);
			}
			CredentialsProvider credentials = credentialsFor(repository, remoteName);
			if (credentials != null)
			{
				command.setCredentialsProvider(credentials);
			}
			result = command.call();

			boolean stoppedOnConflicts = (result.getRebaseResult() != null &&
				result.getRebaseResult().getStatus() == RebaseResult.Status.STOPPED) ||
				(result.getMergeResult() != null &&
					result.getMergeResult().getMergeStatus() == MergeResult.MergeStatus.CONFLICTING);
			if (stoppedOnConflicts)
			{
				conflicting = conflictingPaths(git);
			}
		}
		catch (org.eclipse.jgit.api.errors.InvalidRemoteException | org.eclipse.jgit.api.errors.TransportException e)
		{
			failure = "Pull FAILED: the remote '" + remoteName + "' could not be pulled from: " + e.getMessage() + "\n";
		}
		catch (org.eclipse.jgit.api.errors.InvalidConfigurationException e)
		{
			failure = "Pull FAILED: the branch '" + branch + "' has no upstream configured and no remoteBranch was" +
				" given: " + e.getMessage() + "\nPass remoteBranch, or gitPush with setUpstream=true first.\n";
		}
		catch (org.eclipse.jgit.api.errors.RefNotAdvertisedException e)
		{
			// Upstream lets this one through as a RuntimeException. It is the ordinary "that branch is
			// not on the remote (yet)" case - a branch never pushed, or a stale configured upstream -
			// so it is reported with what to do next instead.
			failure = "Pull FAILED: the remote '" + remoteName + "' has no branch '" +
				(remoteBranchName == null ? branch : remoteBranchName) + "': " + e.getMessage() +
				"\nPass a remoteBranch that exists, or gitPush with setUpstream=true to create it.\n";
		}
		catch (org.eclipse.jgit.api.errors.WrongRepositoryStateException e)
		{
			failure = "Pull FAILED: the repository is mid-operation: " + e.getMessage() + "\n";
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to pull: " + e.getMessage(), e);
		}
		finally
		{
			lock.unlock();
		}

		if (failure != null)
		{
			return failure;
		}

		int fetched = result.getFetchResult() == null ? 0 : refUpdateLines(result.getFetchResult()).size();
		StringBuilder sb = new StringBuilder();
		sb.append("Pull ");
		if (result.getRebaseResult() != null)
		{
			RebaseResult rebaseResult = result.getRebaseResult();
			switch (rebaseResult.getStatus())
			{
				case OK -> sb.append("REBASED");
				case UP_TO_DATE -> sb.append("ALREADY_UP_TO_DATE");
				case FAST_FORWARD -> sb.append("FAST_FORWARD");
				case STOPPED ->
				{
					sb.append("CONFLICTED: the rebase stopped with ").append(conflicting.size())
						.append(" conflicting file(s):\n");
					appendPaths(sb, conflicting);
					sb.append("Resolve and gitAdd them, then gitRebase with operation CONTINUE - or ABORT");
				}
				case UNCOMMITTED_CHANGES ->
				{
					List<String> blocking = pathList(rebaseResult.getUncommittedChanges());
					sb.append("BLOCKED: ").append(blocking.size())
						.append(" uncommitted change(s) stop the rebase:\n");
					appendPaths(sb, blocking);
					sb.append("Commit or stash them first");
				}
				case CONFLICTS ->
				{
					List<String> blocking = pathList(rebaseResult.getConflicts());
					sb.append("BLOCKED: local changes to ").append(blocking.size())
						.append(" file(s) would be overwritten:\n");
					appendPaths(sb, blocking);
					sb.append("Commit, stash or discard them first");
				}
				default -> sb.append("FAILED: JGit reported rebase status ").append(rebaseResult.getStatus());
			}
		}
		else if (result.getMergeResult() != null)
		{
			MergeResult mergeResult = result.getMergeResult();
			switch (mergeResult.getMergeStatus())
			{
				case FAST_FORWARD -> sb.append("FAST_FORWARD");
				case MERGED -> sb.append("MERGED");
				case ALREADY_UP_TO_DATE -> sb.append("ALREADY_UP_TO_DATE");
				case CONFLICTING ->
				{
					sb.append("CONFLICTED: the merge left ").append(conflicting.size())
						.append(" conflicting file(s):\n");
					appendPaths(sb, conflicting);
					sb.append("Resolve and gitAdd them and gitCommit, or gitResetToRevision HEAD with mode HARD to" +
						" abandon it");
				}
				case CHECKOUT_CONFLICT ->
				{
					List<String> blocking = pathList(mergeResult.getCheckoutConflicts());
					sb.append("BLOCKED: local changes to ").append(blocking.size())
						.append(" file(s) would be overwritten:\n");
					appendPaths(sb, blocking);
					sb.append("Commit, stash or discard them first");
				}
				case FAILED ->
				{
					List<String> blocking = failingPaths(mergeResult.getFailingPaths());
					sb.append("BLOCKED: the merge was refused because of ").append(blocking.size())
						.append(" dirty file(s):\n");
					appendPaths(sb, blocking);
					sb.append("Commit, stash or discard them first");
				}
				default -> sb.append("FAILED: JGit reported merge status ").append(mergeResult.getMergeStatus());
			}
		}
		else
		{
			sb.append("ALREADY_UP_TO_DATE");
		}
		sb.append("\n  remote: ").append(remoteName)
			.append("\n  branch: ").append(remoteBranchName == null ? "(configured upstream)" : remoteBranchName)
			.append("\n  mode:   ").append(rebase ? "rebase" : "merge")
			.append("\n  fetched ref(s): ").append(fetched)
			.append("\n  was: ").append(previousHead)
			.append("\n  now: ").append(headSha(repository)).append("\n");
		return sb.toString();
	}

	public String push(String projectName, String remote, String branch, boolean setUpstream, boolean force)
	{
		return push(projectName, null, remote, branch, setUpstream, force);
	}

	/**
	 * Pushes a local branch to a remote. Ported from AssistAI's {@code push} (upstream
	 * {@code aca70c0}).
	 * <p>
	 * A rejection is the normal outcome when the remote has commits the local branch does not, so it
	 * is reported with what to do next rather than thrown. {@code setUpstream} records the pushed
	 * branch as the local branch's upstream, so later pulls and pushes need no arguments.
	 * </p>
	 *
	 * @param remote the remote name; {@code origin} when {@code null}
	 * @param branch the local branch to push; the checked-out one when {@code null}
	 * @param setUpstream whether to record the remote branch as the local branch's upstream
	 * @param force whether to overwrite the remote branch even when it has commits the local one does not
	 */
	public String push(String projectName, String repo, String remote, String branch, boolean setUpstream,
		boolean force)
	{
		return pushIn(resolveRepository(projectName, repo), remote, branch, setUpstream, force);
	}

	/** {@link #push} on an already-resolved repository, so a plain test can drive it. */
	public static String pushIn(Repository repository, String remote, String branch, boolean setUpstream,
		boolean force)
	{
		String remoteName = remote == null || remote.isBlank() ? Constants.DEFAULT_REMOTE_NAME : remote.trim();
		try (Git git = new Git(repository))
		{
			String localBranch = branch == null || branch.isBlank() ? repository.getBranch() : branch.trim();
			if (localBranch == null || repository.findRef(Constants.R_HEADS + localBranch) == null)
			{
				throw new IllegalArgumentException("No local branch named '" + localBranch + "' to push.");
			}

			PushCommand command = git.push().setRemote(remoteName).setForce(force)
				.add(Constants.R_HEADS + localBranch);
			CredentialsProvider credentials = credentialsFor(repository, remoteName);
			if (credentials != null)
			{
				command.setCredentialsProvider(credentials);
			}

			List<String> updates = new ArrayList<>();
			boolean pushed = false;
			boolean rejected = false;
			boolean failed = false;
			for (PushResult result : command.call())
			{
				for (RemoteRefUpdate update : result.getRemoteUpdates())
				{
					updates.add(update.getRemoteName() + " (" + update.getStatus().name() + ")" +
						(update.getMessage() == null ? "" : ": " + update.getMessage()));
					switch (update.getStatus())
					{
						case OK -> pushed = true;
						case UP_TO_DATE ->
						{
							// nothing to do - the remote already has it
						}
						case REJECTED_NONFASTFORWARD, REJECTED_NODELETE, REJECTED_REMOTE_CHANGED,
							REJECTED_OTHER_REASON -> rejected = true;
						default -> failed = true;
					}
				}
			}

			String status = failed ? "FAILED" : rejected ? "REJECTED" : pushed ? "PUSHED" : "UP_TO_DATE";
			StringBuilder sb = new StringBuilder();
			sb.append("Push ").append(status).append(": '").append(localBranch).append("' to '").append(remoteName)
				.append("'.\n");
			appendPaths(sb, updates);
			if (rejected)
			{
				sb.append("The remote refused the push - usually because it has commits '").append(localBranch)
					.append("' does not. gitPull (or gitFetch and gitRebase) first, or push with force=true to" +
						" overwrite the remote branch.\n");
			}
			else if (failed)
			{
				sb.append("The push did not complete; see the per-ref status above.\n");
			}

			if (setUpstream && ("PUSHED".equals(status) || "UP_TO_DATE".equals(status)))
			{
				StoredConfig config = repository.getConfig();
				config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, localBranch,
					ConfigConstants.CONFIG_KEY_REMOTE, remoteName);
				config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, localBranch,
					ConfigConstants.CONFIG_KEY_MERGE, Constants.R_HEADS + localBranch);
				config.save();
				sb.append("Recorded '").append(remoteName).append("/").append(localBranch)
					.append("' as the upstream of '").append(localBranch).append("'.\n");
			}
			return sb.toString();
		}
		catch (org.eclipse.jgit.api.errors.InvalidRemoteException e)
		{
			return "Push FAILED: no remote named '" + remoteName + "': " + e.getMessage() +
				"\nUse gitRemoteList to see the configured remotes.\n";
		}
		catch (org.eclipse.jgit.api.errors.TransportException e)
		{
			return "Push FAILED: the remote '" + remoteName + "' could not be pushed to: " + e.getMessage() + "\n";
		}
		catch (IllegalArgumentException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to push: " + e.getMessage(), e);
		}
	}

	// --- Private helpers ---

	public String stagePatch(String projectName, String patch)
	{
		return stagePatch(projectName, null, patch);
	}

	/**
	 * Stages selected changes from a unified diff into the index without leaving the working tree
	 * altered.
	 * <p>
	 * The working tree is deliberately disturbed to do this: the target paths are checked out from
	 * HEAD, the patch is applied on top, the resulting blobs are written into the index, and only then
	 * is the caller's uncommitted content put back. So the saved content is the ONLY copy of the
	 * caller's work between the checkout and the restore, and the restore MUST run on every exit path
	 * - including a failing {@code apply()}, which is the ordinary outcome for a patch with stale
	 * context. Restoring inside the try (with a catch that only rethrows) silently destroys
	 * uncommitted work; upstream AssistAI shipped exactly that bug and named it in commit
	 * {@code a6dc32d} (SVY-21303). Hence the restore lives in its own {@code finally}.
	 * </p>
	 */
	public String stagePatch(String projectName, String repo, String patch)
	{
		Repository repository = resolveRepository(projectName, repo);
		return applyPatchToIndex(repository, patch, () -> refreshAfterChange(projectName, repository));
	}

	/**
	 * Stages a patch into an already-resolved repository's index. Split out from
	 * {@link #stagePatch(String, String, String)} so the working-tree-preservation contract can be
	 * exercised against a plain JGit repository without an Eclipse workspace: the public entry point
	 * resolves the repository and supplies the workspace refresh, and this does the git work.
	 *
	 * @param repository the repository to stage into, already resolved
	 * @param patch the unified diff to stage
	 * @param afterChange run once the index and working tree are settled; may be {@code null}
	 */
	public static String applyPatchToIndex(Repository repository, String patch, Runnable afterChange)
	{
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		// Populated before the checkout below destroys the working-tree content, and written back in
		// the finally - never inside the try, where a throw would skip it.
		Map<java.io.File, byte[]> savedWorkingTree = new HashMap<>();
		// The index as it stood before any of this. JGit's apply() partially mutates the index before
		// it throws, so a failed patch otherwise leaves the index claiming changes that never happened
		// (a phantom staged deletion, in the case that surfaced this). Snapshotting the index FILE is
		// the faithful way to undo that: restoring those bytes restores every entry at once.
		java.io.File indexFile = repository.getIndexFile();
		byte[] savedIndex = readIndexFileOrNull(indexFile);
		boolean stagedSuccessfully = false;
		try (Git gitCmd = new Git(repository))
		{
			java.io.File workTree = repository.getWorkTree();

			org.eclipse.jgit.patch.Patch parsedPatch = new org.eclipse.jgit.patch.Patch();
			parsedPatch.parse(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)));

			if (parsedPatch.getFiles().isEmpty())
			{
				return "No files affected by patch.";
			}

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

			stagedSuccessfully = true;
			return "Patch staged successfully. Files: " + result.getUpdatedFiles().size();
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to stage patch: " + e.getMessage(), e);
		}
		finally
		{
			// Roll the index back when the patch did not make it all the way through, so a failure
			// cannot leave entries that describe changes which never happened.
			if (!stagedSuccessfully && savedIndex != null)
			{
				try
				{
					Files.write(indexFile.toPath(), savedIndex);
				}
				catch (IOException indexProblem)
				{
					// Reported below with any working-tree problem, so it cannot mask the real failure.
				}
			}
			// Put the caller's uncommitted content back whatever happened. A failure here must not mask
			// the original one, so each file is attempted independently and any problem is collected.
			List<String> restoreFailures = new java.util.ArrayList<>();
			for (var entry : savedWorkingTree.entrySet())
			{
				try
				{
					Files.write(entry.getKey().toPath(), entry.getValue());
				}
				catch (IOException restoreProblem)
				{
					restoreFailures.add(entry.getKey() + " (" + restoreProblem.getMessage() + ")");
				}
			}
			lock.unlock();
			if (afterChange != null)
			{
				try
				{
					afterChange.run();
				}
				catch (RuntimeException refreshProblem)
				{
					// A refresh problem must not replace a real failure either.
				}
			}
			if (!restoreFailures.isEmpty())
			{
				throw new IllegalStateException(
					"Failed to restore the working tree after staging a patch; uncommitted content may be lost in: " +
						String.join(", ", restoreFailures));
			}
		}
	}

	/**
	 * The raw bytes of the index file, or {@code null} when there is no index yet (a repository with
	 * nothing ever staged) or it cannot be read - in which case there is nothing to roll back to.
	 */
	private static byte[] readIndexFileOrNull(java.io.File indexFile)
	{
		if (indexFile == null || !indexFile.isFile())
		{
			return null;
		}
		try
		{
			return Files.readAllBytes(indexFile.toPath());
		}
		catch (IOException e)
		{
			return null;
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
