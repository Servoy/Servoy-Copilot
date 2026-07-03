# Spec: SVY-21195 — screenshotForm should return the error when a form is invalid

## 1. Goal

When the `screenshotForm` MCP tool is invoked on a form that contains validation errors (e.g. invalid property values in the `.frm` file), it should return a text error message describing the problems instead of taking an expensive screenshot. Reading an image response costs significantly more tokens than a text error, and a blank/broken screenshot provides no useful feedback to the AI agent.

## 2. Background

### 2.1 Current behaviour

The `screenshotForm` tool in `FormPreviewService` (line 114–216) performs these steps:
1. Validates form name is not null/empty
2. Checks an active Servoy project exists
3. Checks the form exists in the editing solution (`getEditingSolution().getForm(formName)`)
4. Builds a preview URL: `http://localhost:{port}/solution/{solutionName}/index.html?formpreview={formName}`
5. Launches a headless Playwright/Chromium browser to visit the URL
6. Waits `waitSeconds`, takes a screenshot, saves the PNG
7. Returns the file path to the screenshot

There is no check for form validity before step 5. If the `.frm` file contains invalid data (e.g. `"enabled": "%%poison%%"`), the Servoy NG client renders a blank white page. The screenshot is still captured and returned, wasting tokens when the AI reads the image.

### 2.2 Servoy build markers

The Servoy Builder (`ServoyBuilder`) produces Eclipse `IMarker.PROBLEM` markers on form resources when validation errors are detected. These markers are available on the `.frm` file resource at path `forms/{formName}.frm` within the project. The `IdeStateService.getCompilationErrors` method already demonstrates how to query these markers.

### 2.3 Form resource location

Forms are stored at `{projectRoot}/forms/{formName}.frm`. The `IProject` is available from `ServoyProject.getProject()`, and the form resource is found via `project.getFile("forms/" + formName + ".frm")` or by looking up the resource through `IResource.DEPTH_ZERO` on the form file.

### 2.4 Playwright console errors

When a form is invalid, the NG client may also emit runtime JavaScript errors visible in the browser console. Currently, the Playwright script does not capture console output.

## 3. Design

### 3.1 Pre-screenshot Eclipse marker validation

Before launching Playwright, query the form's `.frm` file and `.js` script file for ERROR-severity markers. If any exist, return them as a formatted text error message and skip the screenshot entirely.

This is the primary mechanism because:
- It catches the exact scenario in the ticket (invalid .frm property values)
- It also catches script errors in the form's `.js` file
- It's fast (no process spawn, no browser launch)
- It's cheap (returns text, not an image)
- The error messages from the Servoy Builder are descriptive

### 3.2 Return format

When errors are found pre-screenshot:
```
Error: Form '{formName}' has validation errors. Fix these before taking a screenshot:
- [ERROR] {marker message 1} (line {lineNumber})
- [ERROR] {marker message 2} (line {lineNumber})
```

### 3.3 Scope of marker check

Check markers on:
1. The `.frm` file itself (`forms/{formName}.frm`) at `DEPTH_ZERO`
2. The form's directory (`forms/{formName}/`) at `DEPTH_INFINITE` for element-level errors
3. The form's `.js` file (`forms/{formName}.js`) at `DEPTH_ZERO` for script errors

Filter for `IMarker.SEVERITY_ERROR` only — warnings should not block screenshots.

## 4. Implementation plan

1. **Modify `FormPreviewService.screenshotForm`** (lines 129–133 area): After the form existence check and before the Playwright launch, add a marker validation step:
   - Get the `IProject` from `activeProject.getProject()`
   - Find the form `.frm` resource: `project.getFile("forms/" + formName + ".frm")`
   - Find the form `.js` resource: `project.getFile("forms/" + formName + ".js")`
   - Also check form directory: `project.getFolder("forms/" + formName)`
   - Call `resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)` on the files and `DEPTH_INFINITE` on the folder
   - Filter for `IMarker.SEVERITY_ERROR`
   - If errors found, format and return them as text, skip the screenshot

2. **Add imports** to `FormPreviewService`: `org.eclipse.core.resources.IMarker`, `org.eclipse.core.resources.IResource`, `org.eclipse.core.resources.IProject`, `org.eclipse.core.resources.IFile`, `org.eclipse.core.resources.IFolder`.

3. **Update unit test** `ServoyTestingServerTest`: Verify that the tool description mentions error detection behaviour.

4. **Add integration test** in `CypressFormTestingIntegrationTest`: Test that `screenshotForm` on a form with known builder errors returns a text error (not a screenshot path).

## 5. Acceptance criteria

- [ ] `screenshotForm` returns a text error (not a screenshot file path) when the form's `.frm` or `.js` file has ERROR-severity Eclipse markers
- [ ] The returned error includes the specific marker messages so the AI can understand what's wrong
- [ ] When the form has no markers, the tool continues to work as before (returns screenshot path)
- [ ] Token cost is reduced: text error responses are returned instead of blank PNG screenshots for invalid forms
- [ ] Existing `screenshotForm` integration tests continue to pass for valid forms

## 6. Out of scope

- Validating form semantics beyond what the Servoy Builder already checks
- Fixing the Servoy Builder to detect more error types
- Changing the `showFormInBrowser` tool (it's user-facing, not AI-facing — the user can see the blank page themselves)
- Returning inline image data instead of file paths (separate concern)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should WARNING-severity markers also block the screenshot, or only ERRORs? | Product | resolved — ERROR-only |
| Should the tool also check the form's `.js` file for script errors? | Dev | resolved — yes, check `.frm` and `.js` |
| Should we delete the screenshot PNG if console errors are detected post-render? | Dev | removed — scenario unlikely; no post-render console capture needed |
