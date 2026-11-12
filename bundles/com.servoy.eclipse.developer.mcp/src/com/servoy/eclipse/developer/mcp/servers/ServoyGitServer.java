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
package com.servoy.eclipse.developer.mcp.servers;

import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.GitService;

/**
 * MCP server providing Git operations for the Servoy Developer MCP endpoint.
 * <p>
 * Endpoint: {@code /mcp/servoy-git}
 * </p>
 * <p>
 * Uses EGit/JGit APIs to interact with the Git repository associated with workspace projects.
 * </p>
 */
@Creatable
@McpServer(name = "servoy-git")
public class ServoyGitServer
{
	@Inject
	private GitService gitService;

	/** Default constructor - required by E4 DI (ContextInjectionFactory.make). */
	public ServoyGitServer() { }

	/** Testing constructor - initialises services directly without E4 DI. */
	public ServoyGitServer(GitService gitService)
	{
		this.gitService = gitService;
	}

	private static final String REPO_PARAM_DESC = "Repository to target directly, by name (its working-tree folder name) or working-tree path, independent of any project. When given it takes precedence over projectName; use gitListRepositories to discover valid values. Default: resolve from projectName.";

	@Tool(name = "gitStatus", description = "Shows the working tree status of the Git repository. Displays staged, unstaged, untracked files and current branch info. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitStatus(
		@ToolParam(name = "projectName", description = "The Eclipse project name (use listProjects to find it). Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo)
	{
		return gitService.getStatus(projectName, repo);
	}

	@Tool(name = "gitLog", description = "Shows the commit history of a Git repository. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitLog(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "maxCount", description = "Maximum number of commits to show (default: 20)", required = false) String maxCount)
	{
		int count = Optional.ofNullable(maxCount).map(Integer::parseInt).orElse(20);
		return gitService.getLog(projectName, repo, count);
	}

	@Tool(name = "gitAdd", description = "Stages files for the next commit. Use '.' to stage all changes (new, modified, and deleted files). Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitAdd(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "filePattern", description = "File pattern to add (e.g., '.' for all, 'src/com/example/MyClass.java' for a specific file)", required = true) String filePattern)
	{
		return gitService.addFiles(projectName, repo, filePattern);
	}

	@Tool(name = "gitCommit", description = "Commits the currently staged changes with the given message. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitCommit(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "message", description = "The commit message", required = true) String message)
	{
		return gitService.commit(projectName, repo, message);
	}

	@Tool(name = "gitDiff", description = "Shows the diff of changes in unified diff format. By default shows unstaged (working tree) changes; set staged to 'true' to see staged changes. Optionally limit it to some project-relative paths with 'pathFilter', and ignore whitespace-only differences with 'ignoreWhitespace'. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitDiff(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "staged", description = "If 'true', shows staged (cached) changes instead of unstaged. Default: false", required = false) String staged,
		@ToolParam(name = "pathFilter", description = "Comma-separated file or directory paths relative to the Eclipse project; when given, only those are diffed", required = false) String pathFilter,
		@ToolParam(name = "ignoreWhitespace", description = "If 'true', ignores whitespace-only differences, so a reindented line is not reported as changed. Default: false", required = false) String ignoreWhitespace)
	{
		boolean isStagedDiff = Optional.ofNullable(staged).map(Boolean::parseBoolean).orElse(false);
		boolean skipWhitespace = Optional.ofNullable(ignoreWhitespace).map(Boolean::parseBoolean).orElse(false);
		return gitService.getDiff(projectName, repo, isStagedDiff, pathFilter, skipWhitespace);
	}

	@Tool(name = "gitBranch", description = "Lists branches in the repository. The current branch is marked with an asterisk (*). Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "includeRemote", description = "If 'true', includes remote-tracking branches. Default: false", required = false) String includeRemote)
	{
		boolean remote = Optional.ofNullable(includeRemote).map(Boolean::parseBoolean).orElse(false);
		return gitService.listBranches(projectName, repo, remote);
	}

	@Tool(name = "gitCreateBranch", description = "Creates a new branch. Does not switch to it - use gitCheckout to switch. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitCreateBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "branchName", description = "Name of the new branch to create", required = true) String branchName,
		@ToolParam(name = "startPoint", description = "Start point (branch name, tag, or commit SHA). Defaults to HEAD.", required = false) String startPoint)
	{
		return gitService.createBranch(projectName, repo, branchName, startPoint);
	}

	@Tool(name = "gitDeleteBranch", description = "Deletes a branch. Cannot delete the currently checked-out branch. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitDeleteBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "branchName", description = "Name of the branch to delete", required = true) String branchName,
		@ToolParam(name = "force", description = "If 'true', force-deletes even if the branch is not fully merged. Default: false", required = false) String force)
	{
		boolean forceDelete = Optional.ofNullable(force).map(Boolean::parseBoolean).orElse(false);
		return gitService.deleteBranch(projectName, repo, branchName, forceDelete);
	}

	@Tool(name = "gitCheckout", description = "Checks out a branch, switching the working tree to that branch. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitCheckout(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "branchName", description = "The branch name to checkout", required = true) String branchName)
	{
		return gitService.checkoutBranch(projectName, repo, branchName);
	}

	@Tool(name = "gitReset", description = "Unstages files from the index (equivalent to 'git reset HEAD <file>'). Does not modify the working tree. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitReset(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "filePattern", description = "File pattern to unstage (e.g., '.' for all, or a specific file path)", required = true) String filePattern)
	{
		return gitService.resetFiles(projectName, repo, filePattern);
	}

	@Tool(name = "gitStash", description = "Stashes the current working directory and index changes, reverting the working tree to HEAD. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitStash(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "message", description = "Message to describe the stash", required = false) String message)
	{
		return gitService.stash(projectName, repo, message);
	}

	@Tool(name = "gitStashPop", description = "Applies and removes the most recent stash entry, restoring previously stashed changes. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitStashPop(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo)
	{
		return gitService.stashPop(projectName, repo);
	}

	@Tool(name = "gitStashList", description = "Lists all stash entries. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitStashList(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo)
	{
		return gitService.stashList(projectName, repo);
	}

	@Tool(name = "gitListRepositories", description = "Lists the Git repositories known to the workspace, each with its name, working-tree path and current branch. Use this to discover a valid 'repo' target for the other git tools, instead of guessing a project name.", type = "object")
	public String gitListRepositories()
	{
		return gitService.listRepositories();
	}

	@Tool(name = "gitStagePatch", description = "Stages specific changes from a unified diff patch into the index without modifying the working tree. Use this to stage partial file changes for selective commits. The patch must be in standard unified diff format with file headers (--- a/path and +++ b/path) and @@ hunk headers. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitStagePatch(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "patch", description = "A unified diff patch string to stage. Must include file headers (--- a/path, +++ b/path) and @@ hunk headers.", required = true) String patch)
	{
		return gitService.stagePatch(projectName, repo, patch);
	}

	@Tool(name = "gitReadFile", description = "Reads a UTF-8 text file from a Git revision without changing the working tree. Use revision 'INDEX' to read the staged version; otherwise revision defaults to HEAD and may be a branch, tag, or commit. The path is relative to the Eclipse project. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitReadFile(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "filePath", description = "File path relative to the Eclipse project", required = true) String filePath,
		@ToolParam(name = "revision", description = "Git branch, tag, commit, or 'INDEX' for the staged version. Default: HEAD", required = false) String revision)
	{
		return gitService.readFileAtRevision(projectName, repo, filePath, revision);
	}

	@Tool(name = "gitRemove", description = "Removes files from the index only ('git rm --cached <file>'), so Git stops tracking them while the files stay on disk as untracked. The working tree is never touched. Paths are relative to the Eclipse project and may be comma-separated; '.' covers every tracked file under the project. Target the repository with 'repo', or an Eclipse project with 'projectName'.", type = "object")
	public String gitRemove(
		@ToolParam(name = "projectName", description = "The Eclipse project name. Optional when 'repo' is given.", required = false) String projectName,
		@ToolParam(name = "repo", description = REPO_PARAM_DESC, required = false) String repo,
		@ToolParam(name = "filePattern", description = "Comma-separated files or folders relative to the Eclipse project, or '.' for every tracked file", required = true) String filePattern)
	{
		return gitService.removeFromIndex(projectName, repo, filePattern);
	}

	@Tool(name = "gitInit", description = "Initializes a Git repository in the workspace root directory and connects all workspace projects to it. Creates a default .gitignore and an initial commit. If a repository already exists, connects the specified project to it.", type = "object")
	public String gitInit(
		@ToolParam(name = "projectName", description = "Any Eclipse project name in the workspace (used to identify the workspace)", required = true) String projectName)
	{
		return gitService.initRepository(projectName);
	}
}
