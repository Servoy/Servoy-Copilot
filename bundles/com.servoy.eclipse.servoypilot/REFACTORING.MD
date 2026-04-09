Analysis: services/ and tools/ — Overlaps, Misplacements & Common Patterns

🔴 Overlapping Functionality

1. Dual file-resolution logic Two completely separate file resolvers exist in parallel:

	•  `FilePathResolver` — in services/, used by codeanalysis/ tools; handles form/scope name resolution, DLTK-aware
	•  FileReadingToolsHelper.resolveFile() — in tools/filereading/; independent implementation using workspace root + active project; no form/scope awareness

Both resolve IFile from a path string but with different strategies and no shared code.

2. Duplicate target resolution logic Three places implement "find ServoyProject for current target":

	•  `CoreToolsHelper.resolveTargetProject()`
	•  `ComponentToolsHelper.resolveTargetProject()`
	•  `TargetService` (the actual service)

CoreToolsHelper and ComponentToolsHelper are near-identical — both call TargetService.getCurrentTarget(), iterate modules, fall back to active project. The only difference is CoreToolsHelper returns null on miss, ComponentToolsHelper throws.

3. JSON/input parsing duplication

	•  `EclipseToolsHelper.normalizeFileNamePatterns()` — parses JSON array OR comma-separated strings for file patterns
	•  `KnowledgeToolsHelper.parseQueries()` — parses JSON array OR comma-separated strings for queries

Identical parsing logic, separated into two helpers for no structural reason.

4. `TargetTools` vs `ITargetTool` — near-identical classes

	•  `tools/utility/TargetTools.java` — a concrete class with @Tool annotated methods (getTarget, setTarget)
	•  `tools/utility/ITargetTool.java` — an interface with the same two default methods

The logic inside both is essentially the same. One of them is dead code or was left over from a migration.

-----------------------------------------------------

🟡 Misplaced Classes

5. `ResourceService` and `SearchService` belong in `services/` Currently sitting in tools/ root (flat, not in any sub-package). They are not tool interfaces — they are pure Eclipse workspace/search infrastructure:

	•  ResourceService — glob-based file finding, problem markers
	•  SearchService — text/regex search + search-and-replace engine

Neither has @Tool, neither is a LangChain4j interface. They are used by Eclipse tool interfaces as backing services. They belong in services/ alongside FilePathResolver.

6. `ResourceDescriptor`, `ResourceToolResult`, `ResourceUtilities` in `tools/` root

	•  ResourceDescriptor — a URI-based resource descriptor with caching metadata
	•  ResourceToolResult — a wrapper pairing a descriptor with string content
	•  ResourceUtilities — utilities for reading file lines, creating folders, reading input streams

ResourceDescriptor + ResourceToolResult appear to be an in-progress caching framework that no current tool actually uses (all tools return plain String, not ResourceToolResult). These are either premature infrastructure or abandoned work.

ResourceUtilities is a general utility class (file I/O, folder creation) — better placed in services/ or a util/ package, not mixed into the tool package root.

7. `DocumentationValidator` is in `services/documentation/` — but it only validates data consumed by tools This is fine architecturally, but it creates an asymmetry: DocumentationToolsHelper is in tools/documentation/ while DocumentationValidator is in services/documentation/. They're a pair — consider co-locating.

-----------------------------------------------------

🟢 Base Interface Candidate (Common Tool Patterns)

Looking across all tool interfaces, there are two recurring patterns worth extracting:

Pattern A — File-path resolution + error response Every interface in codeanalysis/ and filereading/ follows:

```
resolve file → check exists → call service → return result or error
```

A base interface IFileResolvingTool with a default helper like resolveOrError(String pathOrName) returning an Either<IFile, String> (or similar) would eliminate the repeated null-check/not-found pattern.

Pattern B — Core CRUD triplet (get/open/delete) The 4 core sub-packages (forms/, relation/, valuelist/, style/) are structurally identical:

	•  IGetXxxTool → UIThreadHelper.syncExec → XxxToolsHelper.listXxxImpl(scope)
	•  IOpenXxxTool → UIThreadHelper.syncExec → XxxToolsHelper.openXxxImpl(name)
	•  IDeleteXxxTool → UIThreadHelper.syncExec → XxxToolsHelper.deleteXxxImpl(names)

A base interface IServoyEntityTool with scope-aware listing, open, and delete signatures would be a natural parent — but given LangChain4j reads @Tool annotations from declared methods only, this only makes sense as a documentation/organizational contract, not for ToolComposer registration.

-------------------------------------------------------

Steps

	1. Merge CoreToolsHelper.resolveTargetProject() and ComponentToolsHelper.resolveTargetProject() — both should just delegate to TargetService; eliminate ComponentToolsHelper entirely or fold it in.
	2. Move ResourceService and SearchService from tools/ root into services/ to reflect their non-tool nature.
	3. Unify FileReadingToolsHelper.resolveFile() with `FilePathResolver` — one canonical resolver with form/scope awareness used by all tools.
	4. Merge EclipseToolsHelper.normalizeFileNamePatterns() and KnowledgeToolsHelper.parseQueries() into a single shared InputParsingHelper (or add to a common utility class).
	5. Remove TargetTools.java (concrete class) — it duplicates ITargetTool exactly; decide on the interface-only pattern and delete the dead class.
	6. Evaluate ResourceDescriptor / ResourceToolResult — if the caching layer is not being used, consider removing it, or committing to it and wiring all tools through it.

Further Considerations

	1. `ResourceUtilities` — large utility class in wrong place; could merge readFileLines into FileReadingToolsHelper and move folder utilities to a dedicated services/util/ package.
	2. `DocumentationToolsHelper` location — currently tools/documentation/ but also calls FilePathResolver from services/; with the resolver unification it would become a thin adapter — is it still needed as a separate class?
	3. Base interface for file tools — worth doing only if it carries shared default logic (e.g., file resolution + error formatting); a marker interface with no defaults adds ceremony without value.



