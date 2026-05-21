# Dummy Tools Roadmap — `servoy-ide` endpoint

Status of all 20 dummy tools in `com.servoy.eclipse.developer.mcp` (`ServoyIdeServer`).

## Legend

- **Da** — can be implemented using DLTK (available in Servoy Developer runtime)
- **Parțial** — possible but complex / incomplete equivalent
- **Nu** — no equivalent, not applicable in Servoy Developer context

## Tools

| # | Tool name | Category | DLTK implementable? | Source in servoypilot | Notes |
|---|---|---|---|---|---|
| 1 | `getSource` | JDT | **Da** | `getCodeChunk` / `ISourceModule` | Read JS file source via DLTK |
| 2 | `getClassOutline` | JDT | **Da** | `FileStructureService.analyzeFile()` | Extract symbols (functions, variables, JSDoc status) |
| 3 | `getMethodSource` | JDT | **Da** | `CodeChunkReader` with `symbolName` | Return function source by name via AST |
| 4 | `getFilteredSource` | JDT | **Da** | `analyzeFileStructure` + `getCodeChunk` | Source with selective method expansion |
| 5 | `findReferences` | JDT | **Da** | `CodeContextService` / DLTK `SearchEngine` | Find all references to a JS symbol |
| 6 | `executeQuickFix` | JDT | **Da** | `IGeneratedCodeValidationTool` + DLTK parser | Apply fix to a compilation problem |
| 7 | `getMethodCallHierarchy` | JDT | **Parțial** | Not in servoypilot | DLTK `CallHierarchyCore` — complex |
| 8 | `getTypeHierarchy` | JDT | **Parțial** | `DocumentationToolsHelper` (type lookup) | DLTK type info — `IRClassType` |
| 9 | `getJavaDoc` | JDT | **Nu** (Java-specific) | — | Not applicable for JS/Servoy |
| 10 | `formatCode` | JDT | **Posibil** | DLTK formatter | Depends on DLTK JS formatter config |
| 11 | `getImportSuggestions` | JDT | **Nu** (Java-specific) | — | JS has no imports in the Java sense |
| 12 | `findTestClasses` | JUnit | **Nu** | — | Servoy Developer does not run JUnit |
| 13 | `runAllTests` | JUnit | **Nu** | — | Servoy Developer does not run JUnit |
| 14 | `runPackageTests` | JUnit | **Nu** | — | Servoy Developer does not run JUnit |
| 15 | `runClassTests` | JUnit | **Nu** | — | Servoy Developer does not run JUnit |
| 16 | `runTestMethod` | JUnit | **Nu** | — | Servoy Developer does not run JUnit |
| 17 | `listMavenProjects` | Maven | **Nu** | — | Servoy Developer does not use Maven |
| 18 | `runMavenBuild` | Maven | **Nu** | — | Servoy Developer does not use Maven |
| 19 | `getEffectivePom` | Maven | **Nu** | — | Servoy Developer does not use Maven |
| 20 | `getProjectDependencies` | Maven | **Nu** | — | Servoy Developer does not use Maven |

## Implementation phases (proposed)

### Phase 1 — Direct port from servoypilot (low risk, high value)

Tools 1–3: `getSource`, `getClassOutline`, `getMethodSource`

### Phase 2 — New implementation based on DLTK

Tools 4–5: `getFilteredSource`, `findReferences`

Plus `resolveIdentifierType` (new tool on `servoy-dev` endpoint, ported from `CodeContextService`)

### Phase 3 — Servoy-specific (via `servoy-dev` endpoint)

`getCodeContext` — full port from `CodeContextToolsHelper.codeContextImpl()` (TypeCreator, Form, Relation, ValueList awareness)

### Not planned

Tools 9, 11–20: Java-specific, JUnit, Maven — remain as dummies (throw "not available").

Tools 7–8, 10: deferred — evaluate later based on need.
