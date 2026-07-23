# Spec: SVY-21236 – Auto-init Git repo on solution creation

## 1. Goal

Automatically initialize a Git repository at the workspace root when a new Servoy solution is created via the `createSolution` MCP tool. This ensures every new solution starts with version control from the beginning, without requiring the user to manually run `git init`.

## 2. Background

### 2.1 Current behavior

When a solution is created via `ServoyDevServer.createSolution`, the workspace has no Git repository unless the user explicitly initializes one. AI agents and developers must remember to run `git init` separately.

### 2.2 Desired behavior

After `createSolution` completes, if no Git repository exists at the workspace root:
1. Initialize a new Git repository (`git init`)
2. Create a sensible `.gitignore`
3. Connect all open workspace projects to the repository via EGit
4. Create an initial commit with all tracked files

If a Git repository already exists, the new project is simply connected to it.

## 3. Design

### 3.1 New `gitInit` MCP tool

A new `gitInit` tool is exposed in `ServoyGitServer`:

```java
@Tool public String gitInit(String projectName)
```

**Parameters:**
- `projectName` – the project to use as context (validates the project exists)

**Behavior:**
- If a `.git` directory already exists at workspace root and the project is already connected → returns info message
- If a `.git` directory exists but the project is not connected → connects the project via `ConnectProviderOperation`
- If no `.git` directory exists → initializes repo, creates `.gitignore`, connects all open projects, stages all files, creates initial commit

### 3.2 `GitService.initRepository()`

Core logic lives in `GitService.initRepository(String projectName)`:

1. Validates the project exists
2. Checks for existing `.git` at workspace root
3. If existing repo: connects the project if not already connected
4. If no repo:
   - `Git.init()` at workspace root
   - Writes default `.gitignore` (covers `.metadata/`, `*.log`, `node_modules/`, etc.)
   - Connects **all** open workspace projects via `ConnectProviderOperation`
   - `git add .` + `git commit -m "Initial commit"`

### 3.3 Auto-trigger in `createSolution`

`ServoyDevServer.createSolution` calls `gitService.initRepository(solutionName)` after the solution is successfully created. This is a best-effort operation — if it fails, the solution creation still succeeds.

## 4. Default `.gitignore`

```
.metadata/
*.log
*.bak
*.tmp
node_modules/
.angular/
dist/
target/
```

## 5. Testing

`GitServiceInitTest` covers:
- Init on a fresh workspace (no existing repo)
- Idempotent behavior when repo already exists
- Project connection when repo exists but project is not connected
- Error handling for non-existent project names

## 6. Edge cases

| Scenario | Behavior |
|----------|----------|
| Workspace already has `.git` | Connects project, does not re-init |
| Project already connected | Returns info message, no-op |
| Project does not exist | Returns error message |
| `.gitignore` already exists | Does not overwrite |
| Multiple open projects | All connected to the new repo |
