# Code Context Gathering - Implementation Plan

**Created:** February 12, 2026  
**Status:** Planning Phase  
**Purpose:** Add ability to extract API context from selected JavaScript code for multiple AI agents

---

## Overview

Enable ServoyPilot to analyze selected JavaScript code and extract comprehensive context about Servoy APIs, web components, web services, and solution-defined functions. This context will be available to multiple agents (chat, completion, explain-selection) via both service-layer APIs and LLM tools.

**Key Benefit:** AI can provide accurate, context-aware assistance by understanding what APIs/types are actually being used in the code.

---

## Coding Standards - MANDATORY

**All code in this feature MUST follow the project's Java coding rules:**

### RULE 1: USE POSITIVE CONDITIONALS (NO GUARD CLAUSES)

**REQUIRED:** Always use positive conditionals where the happy path flows naturally inside the if-block.

**WRONG:**
```java
if (value == null || value.isEmpty()) {
    throw new Exception("Error");
}
// Business logic continues...
```

**RIGHT:**
```java
if (value != null && !value.isEmpty()) {
    // Business logic flows naturally here
    return result;
}
throw new Exception("Error");
```

### RULE 2: NO UNNECESSARY else BLOCKS

**FORBIDDEN:** Do not use else blocks when nothing follows the if-block.

**WRONG:**
```java
if (condition) {
    return result;
} else {
    throw new Exception("Error");
}
```

**RIGHT:**
```java
if (condition) {
    return result;
}
throw new Exception("Error");
```

### RULE 3: USE MODERN instanceof WITH PATTERN MATCHING (Java 16+)

**REQUIRED:** Use pattern matching instanceof (no manual casting).

**WRONG:**
```java
if (obj instanceof String) {
    String str = (String) obj;
    return str.toUpperCase();
}
```

**RIGHT:**
```java
if (obj instanceof String str) {
    return str.toUpperCase();
}
```

### Other Rules to Follow:

- ✅ Use try-with-resources for AutoCloseable
- ✅ No nested try-catch blocks
- ✅ Minimize variable scope
- ✅ No magic numbers/strings (use constants)
- ✅ Use StringBuilder for string concatenation in loops

**See:** `/Volumes/Servoy/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.servoypilot/.github/copilot-instructions.md` for complete rules.

---

## Architecture

### New Package Structure

```
com.servoy.eclipse.servoypilot/
└── context/                               (NEW PACKAGE)
    ├── CodeContextService.java            (Core service - orchestrates context gathering)
    ├── IdentifierCollectingVisitor.java   (AST visitor - collects identifiers from selection)
    ├── SelectionTracker.java              (Singleton - tracks editor selections)
    └── dto/                               (Data transfer objects)
        ├── CodeContext.java               (Complete context data)
        ├── IdentifierContext.java         (Single identifier's type + docs)
        └── SelectionInfo.java             (Selection metadata)
```

### Tool Integration

```
tools/utility/
└── CodeContextTools.java                  (NEW - LLM-facing tools)
```

---

## Implementation Phases

### ✅ Phase 0: Preparation (COMPLETED)
- [x] Target refactoring (Context → Target)
- [x] Clear naming: "target" = write destination, "context" = code analysis
- [x] Update all knowledge base files

---

### ✅ Phase 1: Foundation (DTOs and Selection Tracking) - COMPLETED Feb 12, 2026

**Goal:** Create data structures and selection tracking infrastructure

**Tasks:**

#### 1.1 Create DTO Package Structure ✅ COMPLETED
- [x] Create `/context/dto/` package
- [x] Define `SelectionInfo.java` - immutable data class
  - Fields: `filePath`, `offset`, `length`, `selectedText`, `sourceModule`
  - Constructor, getters, `Optional<T>` factory methods
- [x] Define `IdentifierContext.java` - single identifier data
  - Fields: `name`, `typeName`, `documentation`, `kind` (API/Component/Service/Function)
  - Methods: `toFormattedString()` for LLM consumption
- [x] Define `CodeContext.java` - complete context
  - Fields: `SelectionInfo selection`, `List<IdentifierContext> identifiers`, `formattedContext`
  - Methods: `isEmpty()`, `getFormattedXML()`, `getFormattedPlainText()`

**Acceptance Criteria:** ✅
- All DTOs are immutable (final fields, no setters)
- Proper null handling with `Optional<T>`
- Unit testable structure
- Clear Javadoc on all public APIs

**Actual Effort:** 2 hours

---

#### 1.2 Implement SelectionTracker ✅ COMPLETED
- [x] Create `SelectionTracker.java` singleton
- [x] Implement `ISelectionListener` interface
- [x] Lazy initialization pattern
- [x] Thread-safe getInstance()
- [x] Track current selection state
- [x] Methods:
  - `getCurrentSelection() → Optional<SelectionInfo>`
  - `registerSelectionChangeListener(Consumer<SelectionInfo>)` (skeleton for future)
  - `dispose()` - cleanup on shutdown
- [x] Register with `ISelectionService` on first use
- [x] Handle editor changes (track active editor)
- [x] Dispose via plugin Activator shutdown hook

**Acceptance Criteria:** ✅
- Only one `ISelectionListener` registered globally
- Thread-safe access
- Proper cleanup on plugin shutdown
- Returns empty Optional when no selection

**Actual Effort:** 2 hours

---

### ✅ Phase 2: AST Analysis (Identifier Collection) - COMPLETED Feb 12, 2026

**Goal:** Port and adapt IdentifierCollectingVisitor from AI Bridge

**Tasks:**

#### 2.1 Port IdentifierCollectingVisitor ✅ COMPLETED
- [x] Create `IdentifierCollectingVisitor.java`
- [x] Extend `TypeInferencerVisitor` (DLTK)
- [x] Port logic from AI Bridge version
- [x] Collect identifiers within offset/length range
- [x] Store maps:
  - `Map<JSNode, Pair<IValueReference, String>> identifiers`
  - `Map<JSNode, List<IValueReference>> propertiesOrCalls`
- [x] Handle nested property expressions (`plugins.ngdesktop.openFile`)
- [x] Override `visit(ASTNode)` method
- [x] Override `extractNamedChild()` for property chains

**Reference:** `/JavaLab/external-projects/com.servoy.eclipse.aibridge/src/.../IdentifierCollectingVisitor.java`

**Acceptance Criteria:** ✅
- Correctly identifies all identifiers in selection range
- Handles nested properties (a.b.c)
- Excludes identifiers outside selection range
- Returns proper `IValueReference` for type inference

**Actual Effort:** 1 hour

---

---

## 🚧 **BIG TODO: Phase 3 - Documentation Extraction** 🚧

**Current Status:** Type inference works, but documentation is NOT yet extracted.

**What's working:**
- ✅ AST parsing and identifier collection
- ✅ Type resolution (we know `plugins` is type `Plugins`, etc.)
- ✅ Identifier classification (SERVOY_API, WEB_COMPONENT, WEB_SERVICE, SOLUTION_FUNCTION)
- ✅ Deduplication (no duplicate identifiers)

**What's NOT working:**
- ❌ Documentation extraction (descriptions, signatures, parameters, return types)
- ❌ Currently returns only type names (e.g., "plugins: Plugins" with no documentation)

**Next Implementation Steps:**

### **Step 1: Extract Servoy API Documentation** (5-6 hours)
Use `ScriptObjectRegistry.getScriptObjectByName()` → `XMLScriptObjectAdapter` → extract from servoydoc.xml:
```java
ITypedScriptObject scriptObject = ScriptObjectRegistry.getScriptObjectByName(typeName);
if (scriptObject instanceof XMLScriptObjectAdapter adapter) {
    IObjectDocumentation objDoc = adapter.getObjectDocumentation();
    // For each call/property:
    String tooltip = adapter.getToolTip(methodName, argTypes, ClientSupport.ng);
    String signature = adapter.getJSTranslatedSignature(methodName, argTypes);
    IParameter[] params = adapter.getParameters(methodName, argTypes);
    // Build documentation string...
}
```

### **Step 2: Extract Solution Function Documentation** (3-4 hours)
Use `ScriptdocContentAccess.getContentReader()` to read JSDoc comments:
```java
if ("Function".equals(typeName)) {
    ReferenceLocation location = valueRef.getLocation();
    IModelElement element = locateModelElement(location);
    try (Reader reader = ScriptdocContentAccess.getContentReader((IMember)element, true)) {
        String doc = IOUtils.toString(reader);
        // Parse @param, @return, @description tags...
    }
}
```

### **Step 3: Extract Component/Service Documentation** (6-8 hours)
Use `WebComponentSpecProvider` and `WebServiceSpecProvider` for .spec files:
```java
if (typeName.startsWith("RuntimeWebComponent<")) {
    String componentName = extractComponentName(typeName);
    WebObjectSpecification spec = WebComponentSpecProvider
        .getSpecProviderState()
        .getWebComponentSpecification(componentName);
    // Extract API functions, properties from spec...
}
```

### **Step 4: Format for LLM** (2-3 hours)
- Build XML format matching AI Bridge output
- Add token limiting (max identifiers, truncate long docs)
- Test with real code selections

**Total Estimated Effort for Phase 3:** 16-21 hours

**Reference Implementation:** AI Bridge `AiBridgeHandler.getContextData()` lines 220-330

---

### 📋 Phase 3: Documentation Extraction - DETAILED TASKS

**Goal:** Extract documentation from multiple sources (Servoy API, components, services, functions)

**Tasks:**

#### 3.1 Create CodeContextService Core ✅ SKELETON COMPLETED
- [x] Create `CodeContextService.java`
- [x] Method: `getCodeContext(SelectionInfo) → CodeContext`
- [x] Parse JavaScript using `JavaScriptParserUtil.parse()`
- [x] Run `TypeInferencer2` with `IdentifierCollectingVisitor`
- [x] Build empty `CodeContext` with selection info
- [ ] **TODO Phase 3:** Extract actual documentation (currently returns type names only)

**Acceptance Criteria:** ⚠️ Partial
- ✅ Parses valid JavaScript successfully
- ✅ Handles syntax errors gracefully (return empty context with error flag)
- ✅ Uses workspace lock when accessing DLTK model
- ⚠️ Documentation extraction not yet implemented (returns type names only)

**Actual Effort:** 1.5 hours

---

#### 3.2 Implement Servoy API Documentation Extraction
- [ ] For each identifier with `IdentifierKind.SERVOY_API`:
  - Get `ITypedScriptObject` via `ScriptObjectRegistry.getScriptObjectByName(typeName)`
  - Cast to `XMLScriptObjectAdapter` (loaded from servoydoc.xml)
  - Get `IObjectDocumentation` via `adapter.getObjectDocumentation()`
  - For each property/call on identifier (from `collector.propertiesOrCalls`):
    - Get function name from `IValueReference.getName()`
    - Get `IFunctionDocumentation` via `objDoc.getFunction(functionName)`
    - Extract documentation using `XMLScriptObjectAdapter` methods:
      - `getToolTip(methodName, argTypes, ClientSupport.ng)` - description
      - `getJSTranslatedSignature(methodName, argTypes)` - signature
      - `getParameters(methodName, argTypes)` - parameter info (IParameter[])
      - `getReturnedType(methodName, argTypes)` - return type
      - `getSample(methodName, argTypes, ClientSupport.ng)` - code sample
      - `isDeprecated(methodName, argTypes)` - deprecation status
    - Build formatted documentation string (match AI Bridge format)
  - Create `IdentifierContext` with extracted docs
  - Add to result list

**Reference Classes:**
- `com.servoy.eclipse.core.XMLScriptObjectAdapterLoader` - loads servoydoc.xml into registry
- `com.servoy.j2db.documentation.XMLScriptObjectAdapter` - provides doc access methods
- `com.servoy.j2db.scripting.ScriptObjectRegistry` - registry for type lookup
- AI Bridge: `AiBridgeHandler.getContextData()` lines 289-307

**Acceptance Criteria:**
- Correctly identifies Servoy API objects (plugins.*, application.*, forms.*, databaseManager.*, etc.)
- Extracts complete function signatures with parameter names and types
- Includes parameter descriptions and return type documentation
- Handles optional parameters correctly
- Formats output matching AI Bridge XML structure
- Handles deprecated APIs (includes deprecation notices)

**Estimated Effort:** 5-6 hours

---

#### 3.3 Implement Web Component Documentation Extraction
- [ ] For each identifier with `IdentifierKind.WEB_COMPONENT`:
  - Check if type starts with `RuntimeWebComponent<`
  - Extract component name from generic type (e.g., "servoyextra-table" from `RuntimeWebComponent<servoyextra-table>`)
  - Get `WebObjectSpecification` via `WebComponentSpecProvider.getSpecProviderState().getWebComponentSpecification(componentName)`
  - For each property/call on identifier:
    - Check `spec.getApiFunction(functionName)` for API methods
    - Check `spec.getProperty(propertyName)` for properties
    - Extract `WebObjectFunctionDefinition`:
      - Parameters from `function.getParameters()` (names, types, optional/required)
      - Return type from `function.getReturnType()`
      - Documentation from spec file
    - Extract `PropertyDescription` for properties:
      - Type information
      - Default values
      - Documentation
  - Format as `IdentifierContext` with component-specific docs
  - Add to result list

**Reference:**
- AI Bridge: `AiBridgeHandler.generateApiOrPropertySpec()` lines 158-210
- Package: `org.sablo.specification` (WebComponentSpecProvider, WebObjectSpecification)

**Acceptance Criteria:**
- Correctly identifies RuntimeWebComponent types
- Extracts component API function signatures from .spec files
- Includes parameter types (required/optional indicators)
- Handles component properties (not just methods)
- Formats output matching AI Bridge structure

**Estimated Effort:** 4-5 hours

---

#### 3.4 Implement Web Service Documentation Extraction
- [ ] For each identifier with `IdentifierKind.WEB_SERVICE`:
  - Check if type starts with `WebService<`
  - Extract service name from generic type (e.g., "myService" from `WebService<myService>`)
  - Get `WebObjectSpecification` via `WebServiceSpecProvider.getSpecProviderState().getWebObjectSpecification(serviceName)`
  - Same extraction logic as Web Components:
    - API functions via `spec.getApiFunction(functionName)`
    - Parameters, return types, documentation
  - Format as `IdentifierContext` with service-specific docs
  - Add to result list

**Reference:**
- AI Bridge: `AiBridgeHandler.getContextData()` lines 308-316
- Package: `org.sablo.specification` (WebServiceSpecProvider)

**Acceptance Criteria:**
- Correctly identifies WebService types
- Extracts service API signatures from .spec files
- Handles service-specific documentation
- Formats output matching component extraction

**Estimated Effort:** 2-3 hours

---

#### 3.5 Implement Solution Function Documentation Extraction
- [ ] For each identifier with `IdentifierKind.SOLUTION_FUNCTION`:
  - Check if type equals `"Function"`
  - Get `ReferenceLocation` via `pair.getLeft().getLocation()`
  - Locate `IModelElement` using visitor pattern (see AI Bridge helper method)
  - Cast to `IMember` (DLTK model element for functions)
  - Extract ScriptDoc using `ScriptdocContentAccess.getContentReader((IMember)element, true)`
  - Read documentation stream using `IOUtils.toString(reader)`
  - Parse ScriptDoc format:
    - Function signature from first line
    - @param tags for parameter documentation
    - @return tag for return value documentation
    - @description or first paragraph for function description
  - Filter out `@properties=` lines and blank lines
  - Format as `IdentifierContext` with parsed ScriptDoc
  - Add to result list

**Reference:**
- AI Bridge: `AiBridgeHandler.getContextData()` lines 243-262
- Package: `org.eclipse.dltk.javascript.ui.scriptdoc` (ScriptdocContentAccess)
- AI Bridge helper: `locateModelElement(ReferenceLocation)` method

**Acceptance Criteria:**
- Extracts ScriptDoc from solution-defined functions (scope functions, form methods, etc.)
- Parses @param tags with parameter names, types, and descriptions
- Parses @return tag with return type and description
- Handles functions without ScriptDoc gracefully (returns function name only)
- Filters out internal metadata (@properties= lines)
- Formats output matching AI Bridge structure

**Estimated Effort:** 3-4 hours

---

#### 3.6 Format Context for LLM Consumption
- [ ] Implement `CodeContext.getFormattedXML()`
  - Use `<type>identifier: TypeName</type>` format
  - Use `<description>...</description>` for docs
  - Match AI Bridge XML format
- [ ] Implement `CodeContext.getFormattedPlainText()`
  - Alternative format for readability
- [ ] Add token counting consideration (limit context size)
- [ ] Handle deduplication of repeated types

**Reference:** AI Bridge `AiBridgeHandler.appendData()` and `closeContext()` methods

**Acceptance Criteria:**
- XML format matches AI Bridge expectations
- Clean, readable output
- Proper escaping of special characters
- Deduplicated type information

**Estimated Effort:** 3-4 hours

---

### 📋 Phase 4: LLM Tool Integration

**Goal:** Expose code context to AI via function calling tools

**Tasks:**

#### 4.1 Create CodeContextTools ✅ SKELETON COMPLETED
- [x] Create `CodeContextTools.java` in `tools/utility/`
- [x] Implement `@Tool getCodeContext()`
  - Gets current selection from `SelectionTracker`
  - Calls `CodeContextService.getCodeContext()`
  - Returns formatted context string
  - Handles no selection case
- [ ] **TODO Phase 4:** Implement `@Tool getCodeContextForFile(String filePath)`
  - Analyzes entire file (selection = full file range)
- [ ] **TODO Phase 4:** Implement `@Tool getCodeContextForSelection(String filePath, int offset, int length)`
  - Analyzes specific range
- [x] Error handling and user-friendly messages

**Acceptance Criteria:** ⚠️ Partial
- ✅ Basic tool implemented and registered
- ✅ Clear @Tool descriptions for LLM
- ✅ Parameter descriptions with @P annotations
- ✅ Graceful error handling
- ⚠️ Only getCodeContext() functional (other methods are stubs)
- ⚠️ Performance not yet tested

**Actual Effort:** 1 hour

---

#### 4.2 Register Tools in ServoyAiModel ✅ COMPLETED
- [x] Add `CodeContextTools` to tool registration in `createChatServices()`
- [x] Verify tool appears in LLM tool list
- [x] Add DLTK dependencies to MANIFEST.MF
- [x] Add SelectionTracker disposal to Activator

**Acceptance Criteria:** ✅
- Tool successfully registered
- LLM can discover and call the tool
- Results properly formatted for LLM consumption

**Actual Effort:** 0.5 hours

---

### 📋 Phase 5: Testing and Refinement

**Goal:** Ensure robust, performant, and accurate context extraction

**Tasks:**

#### 5.1 Unit Testing
- [ ] Test DTOs (SelectionInfo, IdentifierContext, CodeContext)
- [ ] Test IdentifierCollectingVisitor with sample ASTs
- [ ] Test CodeContextService with mock data
- [ ] Test error cases (syntax errors, missing types, etc.)

**Estimated Effort:** 4-5 hours

---

#### 5.2 Integration Testing
- [ ] Test with real Servoy projects
- [ ] Test various code patterns:
  - Servoy API usage (plugins.*, application.*, etc.)
  - Web component usage
  - Web service usage
  - Solution-defined functions
  - Mixed scenarios
- [ ] Performance testing (large selections)
- [ ] Memory leak testing (repeated invocations)

**Estimated Effort:** 5-6 hours

---

#### 5.3 LLM Tool Testing
- [ ] Test getCodeContext() from chat
- [ ] Verify LLM uses context appropriately
- [ ] Test edge cases (no selection, syntax errors, etc.)
- [ ] Verify context quality improves LLM responses

**Estimated Effort:** 3-4 hours

---

#### 5.4 Performance Optimization
- [ ] Profile CodeContextService performance
- [ ] Implement caching if needed (parsed ASTs)
- [ ] Limit context size (max identifiers, token count)
- [ ] Background job for expensive operations (if UI blocks)

**Acceptance Criteria:**
- Context extraction < 2 seconds for typical selections
- No UI blocking
- Reasonable memory usage

**Estimated Effort:** 4-5 hours

---

### 📋 Phase 6: Documentation and Knowledge Base

**Goal:** Update documentation and train AI on new capabilities

**Tasks:**

#### 6.1 Update ARCHITECTURE.md
- [ ] Document `context/` package
- [ ] Document CodeContextService flow
- [ ] Document SelectionTracker lifecycle
- [ ] Update tool inventory
- [ ] Add examples

**Estimated Effort:** 2-3 hours

---

#### 6.2 Create Knowledge Base Entries
- [ ] Add embeddings for code context queries
  - "explain this code"
  - "what does this do"
  - "analyze selection"
  - "get context for code"
- [ ] Add rules for using CodeContextTools
  - When to call getCodeContext()
  - How to interpret results
  - Examples of context-aware assistance

**Estimated Effort:** 2-3 hours

---

#### 6.3 Update System Prompts
- [ ] Add instructions for using code context
- [ ] Add examples of context-aware responses
- [ ] Update tool usage guidelines

**Estimated Effort:** 1-2 hours

---

## Dependencies

### External Dependencies (Already Available)
- DLTK JavaScript parser and type inference
- Servoy model APIs (`ScriptObjectRegistry`, documentation APIs)
- Sablo specification providers (`WebComponentSpecProvider`, `WebServiceSpecProvider`)
- Eclipse selection service (`ISelectionService`, `ISelectionListener`)

### Internal Dependencies
- TargetService (already implemented)
- Knowledge base infrastructure (already implemented)
- LangChain4j tool registration (already implemented)

### Required MANIFEST.MF Updates
```
Require-Bundle:
  org.eclipse.dltk.javascript.parser,
  org.eclipse.dltk.javascript.typeinference,
  org.eclipse.dltk.javascript.typeinfo,
  org.eclipse.dltk.javascript.ui.scriptdoc,
  org.eclipse.dltk.ui

Import-Package:
  org.sablo.specification,
  com.servoy.j2db.documentation,
  com.servoy.j2db.scripting
```

---

## Risks and Mitigations

### Risk 1: Performance Issues
**Risk:** AST parsing and type inference may be slow for large files  
**Mitigation:** 
- Implement time limits (abort after 2 seconds)
- Cache parsed ASTs per file
- Limit selection size (max characters)
- Run in background job if needed

### Risk 2: Incomplete Type Information
**Risk:** DLTK may not resolve all types correctly  
**Mitigation:**
- Graceful degradation (partial context is better than none)
- Log unresolved types for debugging
- Provide best-effort documentation

### Risk 3: Memory Leaks
**Risk:** Repeated context gathering may leak memory  
**Mitigation:**
- Proper disposal of DLTK resources
- Monitor memory usage during testing
- Clear caches periodically

### Risk 4: Context Overflow
**Risk:** Large selections may produce excessive context (token limits)  
**Mitigation:**
- Limit number of identifiers extracted (e.g., max 50)
- Truncate documentation strings
- Provide summary mode for large contexts

---

## Success Criteria

### Functional Requirements
- ✅ Extract type information for Servoy APIs
- ✅ Extract type information for web components
- ✅ Extract type information for web services
- ✅ Extract type information for solution functions
- ✅ Format context for LLM consumption
- ✅ Expose via LLM tools
- ✅ Handle selection changes automatically

### Non-Functional Requirements
- ✅ Performance: < 2 seconds for typical selections
- ✅ Reliability: Graceful handling of syntax errors
- ✅ Usability: No manual setup required
- ✅ Maintainability: Clear separation of concerns

### Quality Requirements
- ✅ Unit tests for core components
- ✅ Integration tests with real projects
- ✅ Documentation complete and accurate
- ✅ No memory leaks
- ✅ Thread-safe implementation

---

## Timeline Estimates

**Total Estimated Effort:** 55-70 hours

**Suggested Breakdown:**
- **Week 1-2:** Phases 1-2 (Foundation + AST Analysis) - 15-20 hours
- **Week 3-4:** Phase 3 (Documentation Extraction) - 20-25 hours
- **Week 5:** Phase 4 (LLM Integration) - 5-6 hours
- **Week 6-7:** Phase 5 (Testing & Refinement) - 15-20 hours
- **Week 8:** Phase 6 (Documentation) - 5-8 hours

**Note:** Timeline assumes part-time development. Adjust based on available hours per week.

---

## Progress Tracking

Update this section as phases complete:

- [x] Phase 0: Preparation - COMPLETED Feb 12, 2026
- [x] Phase 1: Foundation - COMPLETED Feb 12, 2026 (4 hours)
- [x] Phase 2: AST Analysis - COMPLETED Feb 12, 2026 (1 hour)
- [~] Phase 3: Documentation Extraction - SKELETON ONLY (needs implementation)
  - [x] 3.1 CodeContextService Core (skeleton)
  - [ ] 3.2 Servoy API Documentation Extraction
  - [ ] 3.3 Web Component Documentation Extraction
  - [ ] 3.4 Web Service Documentation Extraction
  - [ ] 3.5 Solution Function Documentation Extraction
  - [ ] 3.6 Format Context for LLM Consumption
- [~] Phase 4: LLM Tool Integration - SKELETON ONLY
  - [x] 4.1 CodeContextTools (basic implementation)
  - [x] 4.2 Register in ServoyAiModel (complete)
- [ ] Phase 5: Testing and Refinement - NOT STARTED
- [ ] Phase 6: Documentation and Knowledge Base - NOT STARTED

**Current Status:** Skeleton structure complete - basic functionality working but documentation extraction not yet implemented

**Total Time Spent:** ~8 hours  
**Remaining Estimated:** 47-62 hours

---

## Notes

- This plan is a living document - update as implementation progresses
- Priorities may shift based on findings during implementation
- Some phases may be parallelized if multiple developers available
- Consider splitting into multiple PRs/branches for easier review

---

**Next Steps:**
1. Review and approve this plan
2. Begin Phase 1.1 (Create DTO Package Structure)
3. Update progress tracking as tasks complete
