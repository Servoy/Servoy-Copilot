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
 * Endpoint: {@code /svymcp/servoy-git}
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

	/** Default constructor — required by E4 DI (ContextInjectionFactory.make). */
	public ServoyGitServer() { }

	/** Testing constructor — initialises services directly without E4 DI. */
	ServoyGitServer(GitService gitService)
	{
		this.gitService = gitService;
	}

	@Tool(name = "gitStatus", description = "Shows the working tree status of the Git repository associated with the project. Displays staged, unstaged, untracked files and current branch info.", type = "object")
	public String gitStatus(
		@ToolParam(name = "projectName", description = "The Eclipse project name (use listProjects to find it)", required = true) String projectName)
	{
		return gitService.getStatus(projectName);
	}

	@Tool(name = "gitLog", description = "Shows the commit history of the Git repository associated with the project.", type = "object")
	public String gitLog(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "maxCount", description = "Maximum number of commits to show (default: 20)", required = false) String maxCount)
	{
		int count = Optional.ofNullable(maxCount).map(Integer::parseInt).orElse(20);
		return gitService.getLog(projectName, count);
	}

	@Tool(name = "gitAdd", description = "Stages files for the next commit. Use '.' to stage all changes (new, modified, and deleted files).", type = "object")
	public String gitAdd(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "filePattern", description = "File pattern to add (e.g., '.' for all, 'src/com/example/MyClass.java' for a specific file)", required = true) String filePattern)
	{
		return gitService.addFiles(projectName, filePattern);
	}

	@Tool(name = "gitCommit", description = "Commits the currently staged changes with the given message.", type = "object")
	public String gitCommit(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "message", description = "The commit message", required = true) String message)
	{
		return gitService.commit(projectName, message);
	}

	@Tool(name = "gitDiff", description = "Shows the diff of changes in unified diff format. By default shows unstaged (working tree) changes; set staged to 'true' to see staged changes.", type = "object")
	public String gitDiff(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "staged", description = "If 'true', shows staged (cached) changes instead of unstaged. Default: false", required = false) String staged)
	{
		boolean isStagedDiff = Optional.ofNullable(staged).map(Boolean::parseBoolean).orElse(false);
		return gitService.getDiff(projectName, isStagedDiff);
	}

	@Tool(name = "gitBranch", description = "Lists branches in the repository. The current branch is marked with an asterisk (*).", type = "object")
	public String gitBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "includeRemote", description = "If 'true', includes remote-tracking branches. Default: false", required = false) String includeRemote)
	{
		boolean remote = Optional.ofNullable(includeRemote).map(Boolean::parseBoolean).orElse(false);
		return gitService.listBranches(projectName, remote);
	}

	@Tool(name = "gitCreateBranch", description = "Creates a new branch. Does not switch to it - use gitCheckout to switch.", type = "object")
	public String gitCreateBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "branchName", description = "Name of the new branch to create", required = true) String branchName,
		@ToolParam(name = "startPoint", description = "Optional start point (branch name, tag, or commit SHA). Defaults to HEAD.", required = false) String startPoint)
	{
		return gitService.createBranch(projectName, branchName, startPoint);
	}

	@Tool(name = "gitDeleteBranch", description = "Deletes a branch. Cannot delete the currently checked-out branch.", type = "object")
	public String gitDeleteBranch(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "branchName", description = "Name of the branch to delete", required = true) String branchName,
		@ToolParam(name = "force", description = "If 'true', force-deletes even if the branch is not fully merged. Default: false", required = false) String force)
	{
		boolean forceDelete = Optional.ofNullable(force).map(Boolean::parseBoolean).orElse(false);
		return gitService.deleteBranch(projectName, branchName, forceDelete);
	}

	@Tool(name = "gitCheckout", description = "Checks out a branch, switching the working tree to that branch.", type = "object")
	public String gitCheckout(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "branchName", description = "The branch name to checkout", required = true) String branchName)
	{
		return gitService.checkoutBranch(projectName, branchName);
	}

	@Tool(name = "gitReset", description = "Unstages files from the index (equivalent to 'git reset HEAD <file>'). Does not modify the working tree.", type = "object")
	public String gitReset(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "filePattern", description = "File pattern to unstage (e.g., '.' for all, or a specific file path)", required = true) String filePattern)
	{
		return gitService.resetFiles(projectName, filePattern);
	}

	@Tool(name = "gitStash", description = "Stashes the current working directory and index changes, reverting the working tree to HEAD.", type = "object")
	public String gitStash(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "message", description = "Optional message to describe the stash", required = false) String message)
	{
		return gitService.stash(projectName, message);
	}

	@Tool(name = "gitStashPop", description = "Applies and removes the most recent stash entry, restoring previously stashed changes.", type = "object")
	public String gitStashPop(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
	{
		return gitService.stashPop(projectName);
	}

	@Tool(name = "gitStashList", description = "Lists all stash entries.", type = "object")
	public String gitStashList(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName)
	{
		return gitService.stashList(projectName);
	}

	@Tool(name = "gitStagePatch", description = "Stages specific changes from a unified diff patch into the index without modifying the working tree. Use this to stage partial file changes for selective commits. The patch must be in standard unified diff format with file headers (--- a/path and +++ b/path) and @@ hunk headers.", type = "object")
	public String gitStagePatch(
		@ToolParam(name = "projectName", description = "The Eclipse project name", required = true) String projectName,
		@ToolParam(name = "patch", description = "A unified diff patch string to stage. Must include file headers (--- a/path, +++ b/path) and @@ hunk headers.", required = true) String patch)
	{
		return gitService.stagePatch(projectName, patch);
	}
}
