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
import java.util.HashMap;
import java.util.Arrays;
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
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
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
		List<String> pathspecs = resolveProjectPaths(projectName, filePattern);

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
		refreshAfterChange(projectName, repository);
		return "Added: " + filePattern;
	}

	public String commit(String projectName, String message)
	{
		return commit(projectName, null, message);
	}

	public String commit(String projectName, String repo, String message)
	{
		Repository repository = resolveRepository(projectName, repo);
		ReentrantLock lock = getRepositoryLock(repository);
		lock.lock();
		RevCommit commit;
		try (Git git = new Git(repository))
		{
			commit = git.commit().setMessage(message).setAllowEmpty(false).call();
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
		return "Committed: " + commit.getName() + " " + commit.getShortMessage();
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
	 */
	private List<String> resolveProjectPaths(String projectName, String pathFilter)
	{
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		RepositoryMapping mapping = RepositoryMapping.getMapping(project);
		String projectPrefix = mapping == null ? "" : mapping.getRepoRelativePath(project);

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

		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		RepositoryMapping mapping = RepositoryMapping.getMapping(project);
		String projectPrefix = mapping == null ? "" : mapping.getRepoRelativePath(project);
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
		List<String> pathspecs = resolveProjectPaths(projectName, filePattern);

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
		refreshAfterChange(projectName, repository);
		return "Unstaged: " + filePattern;
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
