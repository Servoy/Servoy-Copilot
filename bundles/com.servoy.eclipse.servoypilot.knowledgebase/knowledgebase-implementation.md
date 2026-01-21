# Knowledge Base Plugin - Current Implementation Status

**Last Updated:** December 31, 2025  
**Status:** PRODUCTION READY - Extension Point Architecture Complete + Optimized Rules  
**Audience:** AI Assistant Context Document (for continuation across sessions)

---

## CRITICAL CONTEXT - READ THIS FIRST

This document describes the **current state** of the knowledge base system after the December 22, 2025 refactoring sessions.

### What Happened in December 2025

**December 22, 2025 - Morning Session - Plugin Split:**
- Split monolithic `com.servoy.eclipse.knowledgebase` into TWO plugins:
  1. `com.servoy.eclipse.knowledgebase` (CORE) - Embeddings, rules, vector storage
  2. `com.servoy.eclipse.knowledgebase.mcp` (TOOLS) - MCP tool handlers and services
- Eliminated circular dependency (knowledgebase.mcp → ui, and ui actions → knowledgebase)
- aibridge now uses DIRECT IMPORTS (no reflection) for both knowledgebase plugins
- Added IStartup extension to knowledgebase to ensure early activation and solution listener registration
- Fixed initialization timing: ONNX models and knowledge bases now load at Eclipse startup, not on first tool call

**December 22, 2025 - Evening Session - Extension Point & Cleanup:**
- Replaced reflection-based calls in ui plugin with proper extension point pattern
- Created `IKnowledgeBaseOperations` interface with extension point ID
- Added optional dependency from ui to knowledgebase (follows Servoy pattern)
- LoadKnowledgeBaseAction and ReloadAllKnowledgeBasesAction now use extension point
- Removed broken `autoDiscoverRules()` static initializer from RulesCache
- RulesCache now only loads from SPM packages (no embedded rules in knowledgebase plugin)

---

## ARCHITECTURE OVERVIEW

### Three-Plugin System

```
com.servoy.eclipse.knowledgebase (CORE - Pure Knowledge Base)
│
├── KnowledgeBaseManager.java (Facade for accessing core services)
│   - static getEmbeddingService() → returns embedding service
│   - static getRulesCache() → returns rules cache class
│   - static loadKnowledgeBasesForSolution() → loads KB for solution
│   - static reloadAllKnowledgeBases() → reloads all KBs
│
├── Activator.java (Early startup via IStartup extension)
│   - Initializes ONNX models at Eclipse startup
│   - Registers solution activation listener
│   - Loads knowledge bases when solution activates
│
├── KnowledgeBaseStartup.java (IStartup implementation)
│   - Ensures plugin activates early
│   - Triggers Activator.start()
│
└── ai/ (AI components)
    ├── ServoyEmbeddingService (ONNX BGE-small-en-v1.5)
    └── RulesCache (loads rules from SPM packages)

↓ Depended on by

com.servoy.eclipse.knowledgebase.mcp (MCP TOOLS)
│
├── ToolManager.java (Facade for aibridge)
│   - static getHandlers() → returns all tool handlers
│
├── IToolHandler.java (Tool handler interface)
├── ToolHandlerRegistry.java (Registry of all 7 handlers)
│
├── handlers/ (7 handlers, 28 MCP tools)
│   ├── DatabaseToolHandler (2 tools)
│   ├── RelationToolHandler (4 tools)
│   ├── ValueListToolHandler (3 tools)
│   ├── FormToolHandler (5 tools)
│   ├── ButtonComponentHandler (5 tools)
│   ├── LabelComponentHandler (5 tools)
│   └── StyleHandler (4 tools)
│
└── services/ (6 business logic services)
    ├── RelationService
    ├── ValueListService
    ├── FormService
    ├── StyleService
    ├── BootstrapComponentService
    └── DatabaseSchemaService

↓ Both depended on by

com.servoy.eclipse.aibridge (MCP Protocol Layer)
│
├── McpServletProvider.java (MCP HTTP server)
│   - Direct imports: ServoyEmbeddingService, RulesCache (from knowledgebase)
│   - Direct imports: ToolManager, IToolHandler (from knowledgebase.mcp)
│   - NO REFLECTION - all compile-time dependencies
│
└── Original AI Bridge Functionality
    ├── AiBridgeView (chat interface)
    ├── AiBridgeHandler (context menu actions)
    └── AiBridgeStartup (early startup)
```

### Key Design Decisions

1. **No Circular Dependencies:** 
   - knowledgebase.mcp depends on ui (for EditorUtil)
   - ui has OPTIONAL dependency on knowledgebase (for IKnowledgeBaseOperations interface)
   - ui actions use EXTENSION POINT to call knowledgebase (type-safe, no reflection)
   - aibridge uses direct imports for both knowledgebase plugins
   
2. **Extension Point Pattern:**
   - knowledgebase defines extension point: `com.servoy.eclipse.knowledgebase.knowledgeBaseOperations`
   - Provides `IKnowledgeBaseOperations` interface with 3 methods
   - Default implementation: `KnowledgeBaseOperationsProvider`
   - UI actions get provider via `Platform.getExtensionRegistry()` (standard Eclipse pattern)
   - Follows same pattern as `IExportSolutionWizardProvider` in Servoy codebase
   
2. **Early Activation:**
   - knowledgebase has IStartup extension (KnowledgeBaseStartup)
   - Ensures Activator.start() runs at Eclipse startup
   - ONNX models and knowledge bases load early, not on first tool call
   
3. **Solution Activation Listener:**
   - Registered in Activator.start()
   - Automatically reloads knowledge bases when solution changes
   
4. **Direct Imports (No Reflection in aibridge):**
   - aibridge → knowledgebase (direct Require-Bundle)
   - aibridge → knowledgebase.mcp (direct Require-Bundle)
   - Clean, type-safe, compile-time dependencies

5. **Rules Loading Architecture:**
   - RulesCache has NO static initializer
   - NO embedded rules in knowledgebase plugin
   - Rules come ONLY from SPM packages (like servoy-rules)
   - Loaded dynamically via KnowledgeBaseManager when solution activates
   - knowledgebase has IStartup extension (KnowledgeBaseStartup)
   - Ensures Activator.start() runs at Eclipse startup
   - ONNX models and knowledge bases load early, not on first tool call
   
3. **Solution Activation Listener:**
   - Registered in Activator.start()
   - Automatically reloads knowledge bases when solution changes
   
4. **Direct Imports (No Reflection):**
   - aibridge → knowledgebase (direct Require-Bundle)
   - aibridge → knowledgebase.mcp (direct Require-Bundle)
   - Clean, type-safe, compile-time dependencies

---

## FILE INVENTORY

### knowledgebase Plugin (CORE - Knowledge Base Storage)

**Java Files:**
- `src/com/servoy/eclipse/knowledgebase/Activator.java` - Plugin lifecycle, solution listener
- `src/com/servoy/eclipse/knowledgebase/KnowledgeBaseStartup.java` - IStartup for early activation
- `src/com/servoy/eclipse/knowledgebase/KnowledgeBaseManager.java` - Facade for core services
- `src/com/servoy/eclipse/knowledgebase/IKnowledgeBaseOperations.java` - Extension point interface
- `src/com/servoy/eclipse/knowledgebase/KnowledgeBaseOperationsProvider.java` - Default extension point implementation
- `src/com/servoy/eclipse/knowledgebase/ai/ServoyEmbeddingService.java` - ONNX embeddings
- `src/com/servoy/eclipse/knowledgebase/ai/RulesCache.java` - Rule storage (NO static initializer)

**Configuration:**
- `META-INF/MANIFEST.MF` - Exports: knowledgebase, knowledgebase.ai
- `plugin.xml` - IStartup extension + extension point definition + default provider
- `schema/knowledgeBaseOperations.exsd` - Extension point schema
- `build.properties` - Includes schema/ in binary build

**Dependencies:**
- ✅ `com.servoy.eclipse.core`
- ✅ `com.servoy.eclipse.model`
- ✅ `servoy_shared`
- ✅ `onnx-models-bge-small-en`
- ❌ NO dependency on `com.servoy.eclipse.ui`
- ❌ NO MCP-related dependencies

---

### knowledgebase.mcp Plugin (MCP TOOLS)

**Java Files:**
- `src/com/servoy/eclipse/knowledgebase/mcp/ToolManager.java` - Facade for aibridge
- `src/com/servoy/eclipse/knowledgebase/mcp/IToolHandler.java` - Tool handler interface
- `src/com/servoy/eclipse/knowledgebase/mcp/ToolHandlerRegistry.java` - Registry
- `src/com/servoy/eclipse/knowledgebase/mcp/handlers/*.java` - 7 handler classes
- `src/com/servoy/eclipse/knowledgebase/mcp/services/*.java` - 6 service classes

**Configuration:**
- `META-INF/MANIFEST.MF` - Exports: knowledgebase.mcp, handlers, services
- `plugin.xml` - Empty but required for OSGi
- `build.properties`

**Dependencies:**
- ✅ `com.servoy.eclipse.knowledgebase` (core)
- ✅ `com.servoy.eclipse.ui` (for EditorUtil)
- ✅ `com.servoy.eclipse.core`
- ✅ `com.servoy.eclipse.model`
- ✅ `servoy_shared`
- ✅ MCP protocol dependencies (io.modelcontextprotocol.*)

---

### aibridge Plugin (MCP Protocol Layer)

**Java Files (Core):**
- `src/com/servoy/eclipse/mcp/McpServletProvider.java` - MCP HTTP server, calls knowledgebase

**Java Files (Original AI Bridge - Unchanged):**
- `src/com/servoy/eclipse/aibridge/Activator.java`
- `src/com/servoy/eclipse/aibridge/AiBridgeView.java` - Chat interface
- `src/com/servoy/eclipse/aibridge/AiBridgeHandler.java` - Context menu
- `src/com/servoy/eclipse/aibridge/AiBridgeManager.java` - State management
- `src/com/servoy/eclipse/aibridge/AiBridgeMenu.java`
- `src/com/servoy/eclipse/aibridge/AiBridgeTokenizer.java`
- `src/com/servoy/eclipse/aibridge/actions/` (7 action classes)
- `src/com/servoy/eclipse/aibridge/dto/` (Completion, Response)
- `src/com/servoy/eclipse/aibridge/editors/` (DualEditor, etc.)

**Configuration:**
- `META-INF/MANIFEST.MF` - Requires both knowledgebase and knowledgebase.mcp bundles
- `plugin.xml` - UI extensions, MCP service provider registration, IStartup extension
- `build.properties`

**Dependencies:**
- ✅ `com.servoy.eclipse.knowledgebase` (core) - Direct imports
- ✅ `com.servoy.eclipse.knowledgebase.mcp` (tools) - Direct imports
- ✅ NO reflection needed - all compile-time dependencies

---

## CRITICAL DEPENDENCIES

### knowledgebase MANIFEST.MF (December 22, 2025)

**Key Points:**
- Export-Package: `com.servoy.eclipse.knowledgebase`, `com.servoy.eclipse.knowledgebase.ai`
- Import-Package: ai.onnxruntime, ai.onnxruntime.extensions, dev.langchain4j.*, org.sablo.specification
- Bundle-ActivationPolicy: lazy (but activated early via IStartup extension)
- NO dependency on ui or mcp plugins
- NO MCP protocol imports

**Platform-specific ONNX Extensions Required:**
- onnxruntime-extensions.macosx.aarch64
- onnxruntime-extensions.macosx.x86_64  
- onnxruntime-extensions.linux.x86_64
- onnxruntime-extensions.win32.x86_64

---

### knowledgebase.mcp MANIFEST.MF (December 22, 2025)

**Key Points:**
- Export-Package: `com.servoy.eclipse.knowledgebase.mcp`, `handlers`, `services`
- Require-Bundle: Includes `com.servoy.eclipse.knowledgebase`, `com.servoy.eclipse.ui`
- Import-Package: MCP protocol classes, ui.util (EditorUtil), Jackson
- Bundle-ActivationPolicy: lazy
- plugin.xml: Empty but REQUIRED for OSGi resolution

---

### aibridge MANIFEST.MF (December 22, 2025)

**Key Points:**
- Require-Bundle: Includes `com.servoy.eclipse.knowledgebase` AND `com.servoy.eclipse.knowledgebase.mcp`
- Import-Package: MCP protocol, Jackson, DLTK, etc. (40+ entries)
- DOES NOT import: ai.onnxruntime, dev.langchain4j.* (those are only in knowledgebase)
- Uses DIRECT IMPORTS for both knowledgebase plugins (NO reflection)

**Direct Imports in McpServletProvider:**
```java
import com.servoy.eclipse.knowledgebase.ai.ServoyEmbeddingService;
import com.servoy.eclipse.knowledgebase.ai.RulesCache;
import com.servoy.eclipse.knowledgebase.mcp.ToolManager;
import com.servoy.eclipse.knowledgebase.mcp.IToolHandler;
```

---

### ui Plugin MANIFEST.MF (December 22, 2025 - Evening)

**Key Points:**
- Require-Bundle: Includes `com.servoy.eclipse.knowledgebase;resolution:=optional`
- Optional dependency allows importing IKnowledgeBaseOperations without circular dependency
- If knowledgebase is not installed, ui plugin still works (optional)
- LoadKnowledgeBaseAction and ReloadAllKnowledgeBasesAction use extension point API

**Extension Point Usage in Actions:**
```java
import com.servoy.eclipse.knowledgebase.IKnowledgeBaseOperations;

IExtensionRegistry reg = Platform.getExtensionRegistry();
IExtensionPoint ep = reg.getExtensionPoint(IKnowledgeBaseOperations.EXTENSION_ID);
// Create executable extension and invoke methods
```

**Pattern:**
- Follows same approach as `IExportSolutionWizardProvider` in Servoy codebase
- Type-safe constant usage: `IKnowledgeBaseOperations.EXTENSION_ID`
- Extension ID format: `Activator.PLUGIN_ID + ".knowledgeBaseOperations"`

---

## MCP TOOLS INVENTORY (28 TOOLS)

### Database Tools (2)
1. `listTables` - Lists all tables in database server
2. `getTableInfo` - Gets table structure with columns, PKs

### Relation Tools (4)
3. `openRelation` - Create/update relation with properties (8 properties supported)
4. `getRelations` - List relations with optional scope parameter ('current' or 'all')
5. `deleteRelations` - Delete multiple relations (array support)
6. `discoverDbRelations` - Discover FKs for potential relations

### ValueList Tools (3)
7. `openValueList` - Create/update valuelist (4 types, 11+ properties)
8. `getValueLists` - List valuelists with optional scope parameter ('current' or 'all')
9. `deleteValueLists` - Delete multiple valuelists (array support)

### Form Tools (5)
10. `getCurrentForm` - Get currently opened form in editor
11. `openForm` - Create/open form with property management
12. `setMainForm` - Set solution's main/startup form
13. `listForms` - List forms with optional scope parameter ('current' or 'all')
14. `getFormProperties` - Get detailed form properties
10. `getCurrentForm` - Get currently opened form in editor
11. `openForm` - Create/open form with property management
12. `setMainForm` - Set solution's main/startup form
13. `listForms` - List all forms in solution
14. `getFormProperties` - Get detailed form properties

### Button Component Tools (5)
15. `addButton` - Add button to form
16. `updateButton` - Update button properties
17. `deleteButton` - Delete button from form
18. `listButtons` - List all buttons in form
19. `getButtonInfo` - Get detailed button info

### Label Component Tools (5)
20. `addLabel` - Add label to form
21. `updateLabel` - Update label properties
22. `deleteLabel` - Delete label from form
23. `listLabels` - List all labels in form
24. `getLabelInfo` - Get detailed label info

### Style Tools (4)
25. `addStyle` - Add/update CSS class in LESS files
26. `getStyle` - Retrieve CSS class content
27. `listStyles` - List all CSS classes
28. `deleteStyle` - Delete CSS class

---

## KNOWLEDGE BASE LOADING STRATEGY

### Current Status: EARLY STARTUP WITH SOLUTION ACTIVATION LISTENER

**As of December 22, 2025:**
- Knowledgebase plugin has IStartup extension (`KnowledgeBaseStartup`)
- Activates EARLY during Eclipse startup (not on first tool call)
- `Activator.start()` runs immediately:
  1. Initializes ONNX models (ServoyEmbeddingService.getInstance())
  2. Registers solution activation listener (IActiveProjectListener)
  3. Loads knowledge bases for currently active solution (if any)
- When solution changes, listener automatically reloads knowledge bases
- Knowledge bases are READY before first MCP tool is called

**Why IStartup Extension:**
- Bundle-ActivationPolicy is `lazy` by default
- Without IStartup, plugin would only activate when first class is imported
- First import happens when MCP tool is called (too late)
- IStartup ensures Activator.start() runs at Eclipse startup

**Startup Flow:**
```
1. Eclipse starts
2. KnowledgeBaseStartup.earlyStartup() called
3. Activator.getDefault() triggers bundle activation
4. Activator.start() runs:
   - Initialize ONNX models (2-3 seconds)
   - Register solution activation listener
   - Load knowledge bases for active solution
5. Solution activation → listener fires → reload knowledge bases
6. MCP tool called → knowledge base already loaded (fast!)
```

### SPM Package Loading (IMPLEMENTED)

**Intended Architecture (IMPLEMENTED):**
1. Knowledge bases (rules + embeddings) come from SPM packages
2. Packages have `Knowledge-Base: true` in MANIFEST.MF
3. Auto-loaded when solution activates via IActiveProjectListener
4. `KnowledgeBaseManager.loadKnowledgeBasesForSolution()` discovers and loads packages
5. Discovery uses `NGPackageManager.getAllPackageReaders()` from active solution

**Current Reality:**
- SPM package support is fully implemented
- `servoy-rules` package is the reference implementation
- Packages are discovered and loaded automatically on solution activation
- See servoy-rules plugin package section below for details

---

## CRITICAL FIXES APPLIED (DECEMBER 19, 2025)

### Fix 1: OSGi Bundle Resolution - plugin.xml Required

**Problem:** knowledgebase couldn't resolve ai.onnxruntime.extensions imports  
**Root Cause:** Missing plugin.xml file - Eclipse PDE needs it for proper OSGi wiring  
**Solution:** Created empty plugin.xml and added to build.properties  
**Status:** FIXED

### Fix 2: Uses Constraint Violation - wst.jsdt.ui Removal

**Problem:** Cascade of OSGi uses constraint violations preventing startup  
**Root Cause Chain:**
1. aibridge had org.eclipse.wst.jsdt.ui in Require-Bundle (unnecessary)
2. wst.jsdt.ui requires wst.jsdt.core
3. wst.jsdt.core depends on both com.google.javascript and com.google.guava
4. Both bundles export org.jspecify.annotations (conflict)
5. OSGi resolver couldn't decide which to use → uses constraint violation

**Solution:** Removed org.eclipse.wst.jsdt.ui from aibridge Require-Bundle (not used)  
**Status:** FIXED - Resolved by user during Dec 19 session

### Fix 3: Missing onnxruntime-extensions Bundles

**Problem:** knowledgebase couldn't resolve ai.onnxruntime.extensions imports  
**Root Cause:** Platform-specific onnxruntime-extensions bundles not in launch config  
**Solution:** User manually enabled the bundles in launch configuration  
**Bundles Added:**
- onnxruntime-extensions.macosx.aarch64 (0.15.0)
- onnxruntime-extensions.macosx.x86_64 (0.15.0)
- onnxruntime-extensions.linux.x86_64 (0.15.0)
- onnxruntime-extensions.win32.* (0.15.0)

**Status:** FIXED - Eclipse auto-selects correct platform bundle at runtime

### Fix 4: Removed Unnecessary Import - com.networknt.schema

**Problem:** aibridge had com.networknt.schema import causing OSGi resolution error  
**Root Cause:** Import was from old MCP implementation (now in knowledgebase)  
**Solution:** Removed from aibridge MANIFEST.MF Import-Package  
**Status:** FIXED

### Fix 5: ClassCastException in Activator - IServoyModel Interface

**Problem:** ClassCastException when registering solution activation listener:
```
class com.servoy.eclipse.core.DelegatingServoyModel cannot be cast to 
class com.servoy.eclipse.core.ServoyModel
```

**Root Cause:** Activator was casting `ServoyModelFinder.getServoyModel()` to concrete `ServoyModel` class, but at runtime it returns `DelegatingServoyModel`

**Solution:** Changed to use `IServoyModel` interface instead:
```java
// Before:
import com.servoy.eclipse.core.ServoyModel;
ServoyModel servoyModel = (ServoyModel)ServoyModelFinder.getServoyModel();

// After:
import com.servoy.eclipse.model.extensions.IServoyModel;
IServoyModel servoyModel = ServoyModelFinder.getServoyModel();
```

**Files Changed:**
- `Activator.java` - Changed import and removed cast

**Status:** FIXED - Plugin now starts without exceptions

### Fix 6: Metadata Key Mismatch - "intent" vs "category"

**Problem:** Similarity search working but returning `intent=null`, causing no rules to be returned  
**Root Cause:** Embeddings stored with `"category"` metadata key, but McpServletProvider looking for `"intent"`  

**Solution:** Changed `loadEmbeddingsFromReader()` to use `"intent"` instead of `"category"`:
```java
// Line 660 in ServoyEmbeddingService.java
embed(line, "intent", category);  // Was: embed(line, "category", category);
```

**Status:** FIXED - Rules now properly matched and returned

### Fix 7: Plugin Split to Eliminate Circular Dependencies (December 22, 2025)

**Problem:** Circular dependency between knowledgebase and ui plugins:
- Handlers needed EditorUtil from ui (knowledgebase → ui)
- UI actions needed to call knowledgebase (ui → knowledgebase)
- Creates circular dependency cycle

**Solution:** Split knowledgebase into TWO plugins:
1. `com.servoy.eclipse.knowledgebase` (CORE) - Embeddings, rules, vector storage
   - NO dependency on ui
   - NO MCP tools
2. `com.servoy.eclipse.knowledgebase.mcp` (TOOLS) - MCP handlers and services
   - Depends on knowledgebase (core)
   - Depends on ui (for EditorUtil)

**Dependency Flow (NO CYCLES):**
```
knowledgebase (core) ← knowledgebase.mcp ← aibridge
                    ↑                    ↑
                    └──────────────────┘
                           (direct)
                           
ui → knowledgebase (via reflection, avoids cycle)
```

**Files Moved:**
- Moved handlers/, services/, IToolHandler, ToolHandlerRegistry to knowledgebase.mcp
- Created ToolManager facade in knowledgebase.mcp
- Updated aibridge to use direct imports (NO reflection)

**Status:** FIXED - Clean three-plugin architecture with no circular dependencies

### Fix 8: Early Activation Issue (December 22, 2025)

**Problem:** Knowledge bases not loading on solution activation, only on first MCP tool call
**Root Cause:** 
- knowledgebase plugin had `Bundle-ActivationPolicy: lazy`
- Activator.start() only triggered when first class imported
- First import happened when MCP tool was called (too late)

**Solution:** Added IStartup extension to knowledgebase plugin
1. Created `KnowledgeBaseStartup` implementing `IStartup`
2. Added `org.eclipse.ui.startup` extension to plugin.xml
3. `earlyStartup()` triggers `Activator.getDefault()` → bundle activation

**Result:**
- Plugin activates at Eclipse startup (not on first tool call)
- ONNX models initialize early
- Solution activation listener registers immediately
- Knowledge bases load when solution activates

**Files Changed:**
- `KnowledgeBaseStartup.java` (new)
- `plugin.xml` (added IStartup extension)

**Status:** FIXED - Knowledge bases load immediately on solution activation

### Fix 9: Extension Point Architecture (December 22, 2025 - Evening)

**Problem:** UI actions using reflection to call knowledgebase methods
**Root Cause:** 
- LoadKnowledgeBaseAction and ReloadAllKnowledgeBasesAction used `Platform.getBundle()` and reflection
- Not type-safe, breaks at runtime if API changes
- Doesn't follow Servoy extension point pattern

**Solution:** Implemented proper extension point architecture
1. Created `IKnowledgeBaseOperations` interface in knowledgebase plugin
2. Created `KnowledgeBaseOperationsProvider` default implementation
3. Created `schema/knowledgeBaseOperations.exsd` extension point schema
4. Registered extension point in knowledgebase/plugin.xml
5. Added optional dependency from ui to knowledgebase: `com.servoy.eclipse.knowledgebase;resolution:=optional`
6. Updated LoadKnowledgeBaseAction to use `IExtensionRegistry` API
7. Updated ReloadAllKnowledgeBasesAction to use `IExtensionRegistry` API

**Files Changed:**
- knowledgebase: `IKnowledgeBaseOperations.java` (new)
- knowledgebase: `KnowledgeBaseOperationsProvider.java` (new)
- knowledgebase: `schema/knowledgeBaseOperations.exsd` (new)
- knowledgebase: `plugin.xml` (added extension-point and extension)
- knowledgebase: `build.properties` (added schema/ to binary build)
- ui: `MANIFEST.MF` (added optional dependency on knowledgebase)
- ui: `LoadKnowledgeBaseAction.java` (replaced reflection with extension point)
- ui: `ReloadAllKnowledgeBasesAction.java` (replaced reflection with extension point)

**Benefits:**
- Type-safe compile-time checking (no runtime reflection errors)
- Follows Servoy pattern (matches `IExportSolutionWizardProvider`)
- Uses constant: `IKnowledgeBaseOperations.EXTENSION_ID = Activator.PLUGIN_ID + ".knowledgeBaseOperations"`
- Optional dependency prevents circular dependency issues

**Status:** FIXED - Extension point pattern fully implemented

### Fix 10: RulesCache Static Initializer Removal (December 22, 2025 - Evening)

**Problem:** RulesCache had broken static initializer trying to load from non-existent embedded rules
**Root Cause:**
- Static block: `static { autoDiscoverRules(); }`
- Tried to load from `/main/resources/rules/rules.list` which DOES NOT EXIST
- knowledgebase plugin should NOT have embedded rules
- Rules come from SPM packages (like servoy-rules)
- Would silently fail on every startup (exception caught and logged)

**Solution:** Removed entire static initialization mechanism
1. Removed `static { autoDiscoverRules(); }` block
2. Removed `autoDiscoverRules()` method
3. Removed `loadRule()` helper method
4. Updated Javadoc to reflect actual architecture

**Architecture Clarification:**
- RulesCache is a CACHE ONLY (no initialization logic)
- Rules loaded by KnowledgeBaseManager via `RulesCache.loadFromPackageReader()`
- Loading happens when solution activates (via IActiveProjectListener)
- servoy-rules/ folder is a SEPARATE sample project (not bundled with knowledgebase)

**Files Changed:**
- `RulesCache.java` - Removed static initializer and helper methods

**Status:** FIXED - Clean cache-only implementation

---

## CONTEXT-AWARENESS IMPLEMENTATION (December 30, 2025)

### Overview

All write operations (CREATE) in the MCP tools are now context-aware, allowing AI models to target specific solutions or modules when creating Servoy objects.

### Context Management Service

**Location:** `com.servoy.eclipse.knowledgebase.mcp/src/.../services/ContextService.java`

**Purpose:** Singleton service that manages the current context (which solution/module receives new items)

**API:**
```java
ContextService.getInstance().getCurrentContext()  // Returns "active" or module name
ContextService.getInstance().setCurrentContext(String context)  // Set to "active" or module name
ContextService.getInstance().resetToActiveSolution()  // Reset to "active"
ContextService.getInstance().getAvailableContexts(ServoyProject activeProject)  // List available targets
```

**Default Context:** `"active"` (active solution)

### Context Tools for AI Models

**1. getContext** - Shows current context and available solutions/modules
   - Handler: `ContextToolHandler`
   - Returns: Current context name + list of available contexts

**2. setContext** - Switches to a different solution or module
   - Handler: `ContextToolHandler`
   - Parameter: `context` (string) - "active" or module name like "Module_A"
   - Persists until changed or solution activated

### Context-Aware Tool Handlers

All 6 write tool handlers use context via `resolveTargetProject()` method:

**1. RelationToolHandler** - `openRelation` (CREATE)
   - Creates new relations in current context
   - Tool description updated with [CONTEXT-AWARE for CREATE] tag

**2. FormToolHandler** - `openForm` (CREATE with create=true)
   - Creates new forms in current context
   - Tool description updated with context information

**3. ValueListToolHandler** - `openValueList` (CREATE)
   - Creates new valuelists in current context
   - Tool description updated with context information

**4. StyleHandler** - `addStyle` (CREATE)
   - Creates styles in current context's LESS file (medias/ai-generated.less)
   - Tool description updated with context information

**5. ButtonComponentHandler** - All 5 tools (addButton, updateButton, deleteButton, listButtons, getButtonInfo)
   - Smart fallback: searches for form across all contexts
   - Auto-switches if form found in exactly one location
   - Returns error if form exists in multiple locations
   - Tool descriptions updated with smart fallback explanation

**6. LabelComponentHandler** - All 5 tools (addLabel, updateLabel, deleteLabel, listLabels, getLabelInfo)
   - Same smart fallback behavior as buttons
   - Tool descriptions updated with smart fallback explanation

### Implementation Pattern

Each handler has `resolveTargetProject()` method:

```java
private ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel) throws RepositoryException
{
    String context = ContextService.getInstance().getCurrentContext();
    
    if ("active".equals(context))
    {
        return servoyModel.getActiveProject();
    }
    
    // Find module by name
    ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
    for (ServoyProject module : modules)
    {
        if (module.getProject().getName().equals(context))
        {
            return module;
        }
    }
    
    throw new RepositoryException("Context '" + context + "' not found...");
}
```

### Smart Fallback (Components Only)

**For file-based operations** (buttons, labels on forms):
1. Try current context first
2. If form not found, search all solutions/modules
3. If found in exactly ONE place: auto-switch context and perform operation
4. If found in MULTIPLE places: return error listing all locations
5. User must use `setContext` to be explicit

**For persistence-based operations** (relations, forms, valuelists, styles):
- No smart fallback
- Always use current context
- User must set context explicitly if targeting a module

### Read Operations Scope Parameter (December 31, 2025)

**Purpose:** Allow users to retrieve items from current context only or from all contexts (active solution + modules).

**Affected Tools:**
- `getRelations({scope: "current" | "all"})` - Default: "all"
- `listForms({scope: "current" | "all"})` - Default: "all"
- `getValueLists({scope: "current" | "all"})` - Default: "all"

**Scope Values:**
- `"current"` - Returns items from current context only (active solution or specific module based on current context setting)
- `"all"` - Returns items from active solution and all modules (DEFAULT)

**Behavior:**
- When scope="current", queries use current ContextService context
- When scope="all", queries include modules (existing behavior)
- Origin information always shown: `(in: active solution)` or `(in: Module_A)`
- Header adapts based on scope:
  - scope="current": "Relations in 'Module_A':" or "Relations in 'MainSolution':"
  - scope="all": "Relations in solution 'MainSolution' and modules:"

**Example Usage:**
```
# Get all relations across active solution and modules
getRelations()  or  getRelations({scope: "all"})

# Get only relations in current context
setContext({context: "Module_A"})
getRelations({scope: "current"})  → Returns only Module_A relations

# Get only relations in active solution
setContext({context: "active"})
getRelations({scope: "current"})  → Returns only active solution relations
```

**Implementation Details:**
- RelationToolHandler: Uses `getRelations(false)` for current, `getRelations(true)` for all
- FormToolHandler: Uses `getForms(null, false)` for current, `getForms(null, true)` for all
- ValueListToolHandler: Uses `getValueLists(false)` for current, `getValueLists(true)` for all
- All three handlers reuse existing `resolveTargetProject()` method for current scope

### Rules Documentation Updates

All 6 rule files updated with context management sections:

**Files Updated (December 30, 2025):**
1. `/servoy-rules/rules/relations.md` - Context section + examples
2. `/servoy-rules/rules/valuelists.md` - Context section + examples
3. `/servoy-rules/rules/forms.md` - Context section + examples
4. `/servoy-rules/rules/styles.md` - Context section + examples (file paths per module)
5. `/servoy-rules/rules/bootstrap/buttons.md` - Smart fallback explained + examples
6. `/servoy-rules/rules/bootstrap/labels.md` - Smart fallback explained + examples

Each rule file now includes:
- Context Management section explaining getContext/setContext
- Updated workflows to check context first
- Examples showing context switching for module targeting
- Response message documentation

### Tool Descriptions Updates

All 16 write tool descriptions updated (December 30, 2025):

**Tag Added:** `[CONTEXT-AWARE]` or `[CONTEXT-AWARE for CREATE]`

**Information Added:**
- How context affects the operation
- Instructions to use getContext/setContext
- Smart fallback behavior (for components)
- Where items will be created

**Files Updated:**
- ButtonComponentHandler.java (5 tools)
- LabelComponentHandler.java (5 tools)
- RelationToolHandler.java (1 tool - openRelation)
- FormToolHandler.java (1 tool - openForm)
- ValueListToolHandler.java (1 tool - openValueList)
- StyleHandler.java (1 tool - addStyle)
- ContextToolHandler.java (2 tools - getContext, setContext)

### Testing

**Test Prompts:** 50 comprehensive test scenarios in `/com.servoy.eclipse.knowledgebase.mcp/src/main/resources/doc/test-prompts-context-aware.md`

**Test Coverage:**
- Context management basics (getContext, setContext)
- Relations in different contexts
- Forms in different contexts
- ValueLists in different contexts
- Components (buttons/labels) in different contexts with smart fallback
- Styles in different contexts
- Cross-module workflows
- Context reset on solution activation
- Error handling and edge cases

**Status:** Implementation complete, ready for testing (December 30, 2025)

---

## SERVOY-RULES PLUGIN PACKAGE (DECEMBER 19, 2025)

### Overview
The servoy-rules plugin is a Servoy Package Manager (SPM) package containing:
- Embeddings (short prompts for intent detection)
- Rules (detailed instructions for AI operations)
- Metadata for package identification

**Location:** `/com.servoy.eclipse.knowledgebase/servoy-rules/`

### Package Structure

```
servoy-rules/
├── .project                      # Eclipse project descriptor
├── servoy-rules.spec            # SPM package specification (REQUIRED)
├── META-INF/
│   └── MANIFEST.MF              # OSGi manifest with Knowledge-Base: true
├── embeddings/
│   ├── embeddings.list          # List of embedding files to load
│   ├── forms.txt                # Form-related prompts
│   ├── relations.txt            # Relation-related prompts
│   ├── valuelists.txt           # ValueList-related prompts
│   ├── styles.txt               # Style-related prompts
│   └── bootstrap/               # Bootstrap component prompts
│       ├── buttons.txt
│       └── labels.txt
├── rules/
│   ├── rules.list               # List of rule files to load
│   ├── forms.md                 # Form operation rules
│   ├── relations.md             # Relation operation rules
│   ├── valuelists.md            # ValueList operation rules
│   ├── styles.md                # Style operation rules
│   └── bootstrap/               # Bootstrap component rules
│       ├── buttons.md
│       └── labels.md
├── package.json                 # NPM build configuration
├── scripts/
│   └── build.js                 # Zip creation script
└── node_modules/                # NPM dependencies (gitignored)
    └── adm-zip/
```

### Building the Release Zip

The package uses an npm-based build system (mirroring ngdesktopfile pattern):

**Files:**
- `package.json` - Defines `make_release` script
- `scripts/build.js` - Node.js script that creates the zip
- `.npmrc` - Disables package-lock.json

**Commands:**
```bash
cd servoy-rules/

# First time setup
npm install

# Create servoy-rules.zip
npm run make_release
```

**Output:** `servoy-rules.zip` (33KB) containing:
- `.project`
- `servoy-rules.spec` (at root level)
- `META-INF/MANIFEST.MF`
- `embeddings/` folder
- `rules/` folder

### MANIFEST.MF Structure (CRITICAL)

The MANIFEST.MF follows the pattern required by Servoy Package Manager:

```
Manifest-Version: 1.0
Bundle-Name: Servoy Rules
Bundle-SymbolicName: servoy-rules
Package-Type: Web-Service
Bundle-Version: 1.0.0
Knowledge-Base: true

Name: servoy-rules.spec
Web-Service: True
```

**Key Requirements:**
1. `Knowledge-Base: true` - Identifies this as a knowledge base package
2. Blank line before `Name:` entry (required MANIFEST format)
3. `Name: servoy-rules.spec` - Points to spec file location in zip
4. `Web-Service: True` (capital S) - SPM recognition flag

### Package Recognition Requirements

For Servoy Package Manager to recognize and display the package:

1. MUST have `servoy-rules.spec` file at root of zip
2. MANIFEST.MF MUST have `Name: servoy-rules.spec` entry
3. MANIFEST.MF MUST have `Knowledge-Base: true`
4. MUST have either `embeddings/embeddings.list` OR `rules/rules.list`

### Installation Methods

**Via Servoy Package Manager (Primary):**
1. Import servoy-rules.zip into workspace
2. Package appears in Servoy Packages view
3. Load via "Load Knowledge Base" context menu

**Via Workspace Project (Development):**
1. Copy servoy-rules folder into workspace
2. Import as existing project
3. Knowledge base auto-discovered when solution activates

### Discovery Mechanism

The knowledge base is discovered by `KnowledgeBaseManager.discoverKnowledgeBasePackagesInSolution()`:

1. Scans all NG packages in active solution via `ServoyProject.getNGPackageProjects()`
2. Scans all NG packages in modules
3. For each package, checks `isKnowledgeBasePackage()`:
   - Verifies `META-INF/MANIFEST.MF` exists
   - Checks for `Knowledge-Base: true` attribute
   - Verifies `embeddings/embeddings.list` OR `rules/rules.list` exists
4. Creates `DirPackageReader` for each discovered package
5. Loads embeddings and rules via `ServoyEmbeddingService` and `RulesCache`

### Debugging Package Recognition

Added comprehensive debugging output in `KnowledgeBaseManager` (Dec 19, 2025):

**In loadKnowledgeBase():**
- Lists ALL packages in solution
- Shows string comparisons for package name matching
- Lists all modules and their packages
- Shows detailed package discovery flow

**In isKnowledgeBasePackage():**
- Shows if MANIFEST.MF exists
- Shows actual `Knowledge-Base` attribute value
- Shows if embeddings.list exists
- Shows if rules.list exists
- Shows final decision (IS/NOT knowledge base)

**To debug package recognition issues:**
1. Check Eclipse console for `[KnowledgeBaseManager]` output
2. Verify package name appears in the list
3. Check if MANIFEST.MF is found
4. Verify `Knowledge-Base` attribute value
5. Confirm embeddings.list or rules.list existence

---

## STARTUP PERFORMANCE NOTES

### 2+ Minute Startup Delay Investigation (December 19, 2025)

**Symptoms:**
- Eclipse startup takes 2+ minutes (used to be 20-25 seconds)
- Initial console message appears instantly
- Long delay before log4j and plugin activation messages

**Root Causes Identified:**

1. **ONNX Model Loading (RESOLVED):**
   - ServoyEmbeddingService eager initialization in Activator
   - Loading BGE-small-en-v1.5 ONNX model from bundle took time
   - Solution: Deferred initialization (loads on first MCP tool use)

2. **Platform-Wide Issues (NOT RESOLVED - Not Our Problem):**
   - org.eclipse.wst.jsdt.core uses constraint violations
   - Multiple bundles failing to resolve due to Eclipse 2025-12 changes
   - Likely related to Eclipse platform upgrade, not aibridge/knowledgebase

**Current Status:**
- aibridge and knowledgebase now resolve correctly
- If 2+ minute delay persists, it's a platform-wide Eclipse issue
- Not caused by our plugins

---

## TESTING STATUS

### What Works (Verified December 19, 2025)

✅ **aibridge Plugin:**
- Compiles without errors
- Depends on knowledgebase correctly
- McpServletProvider can call KnowledgeBaseManager.getHandlers()
- Original AI Bridge chat functionality intact

✅ **knowledgebase Plugin:**
- Compiles without errors
- All 7 handlers registered
- All 6 services available
- plugin.xml present and included in build
- Exports all required packages
- Imports all required packages (including ai.onnxruntime.extensions)

✅ **OSGi Resolution:**
- No bundle resolution errors for aibridge
- No bundle resolution errors for knowledgebase
- onnxruntime-extensions bundles resolve (when in launch config)

### What Needs Testing (Not Yet Verified)

⚠️ **Runtime Functionality:**
- MCP server starts correctly
- getContext tool works
- All 28 MCP tools execute without errors
- Embedding service initializes on first use
- Rules cache loads correctly

⚠️ **Integration:**
- Copilot can connect to MCP server
- Tool calls reach handlers
- Handlers can access services
- Services can manipulate Servoy objects

---

## COMMON ISSUES & SOLUTIONS

### Issue 1: "Could not resolve module: com.servoy.eclipse.knowledgebase"

**Symptom:** OSGi can't resolve knowledgebase bundle  
**Cause:** Missing plugin.xml or onnxruntime-extensions bundles not in launch config  
**Solution:**
1. Verify plugin.xml exists in knowledgebase root
2. Verify build.properties includes plugin.xml
3. Check launch configuration includes platform-specific onnxruntime-extensions bundles

### Issue 2: "Unresolved requirement: Import-Package: ai.onnxruntime.extensions"

**Symptom:** knowledgebase bundle won't start  
**Cause:** Platform-specific onnxruntime-extensions bundle not in launch config  
**Solution:** Add the appropriate bundle to launch configuration:
- Mac ARM64: onnxruntime-extensions.macosx.aarch64
- Mac x86_64: onnxruntime-extensions.macosx.x86_64
- Linux: onnxruntime-extensions.linux.x86_64
- Windows: onnxruntime-extensions.win32.x86_64

### Issue 3: Uses Constraint Violation with org.jspecify.annotations

**Symptom:** Multiple bundles fail with uses constraint violations  
**Cause:** Eclipse 2025-12 update introduced conflicting bundle versions  
**Solution:** Platform-wide issue. For aibridge specifically, ensure org.eclipse.wst.jsdt.ui is NOT in Require-Bundle

### Issue 4: 2+ Minute Startup Delay

**Symptom:** Eclipse takes very long to start  
**Possible Causes:**
1. Eager embedding service initialization (FIXED - now deferred)
2. Platform-wide bundle resolution issues (not our problem)
3. Target platform cache issues

**Solution:**
1. Verify Activator.start() does NOT call ServoyEmbeddingService.getInstance()
2. Clean workspace, rebuild projects
3. Check for platform-wide bundle errors in .log file

---

## FUTURE WORK / TODO

### High Priority

1. **Test Runtime Functionality:**
   - Verify all 28 MCP tools work end-to-end
   - Test knowledge base loading on solution activation
   - Verify Copilot can connect and use tools

2. **Performance Optimization:**
   - Profile embedding service initialization time
   - Consider async/background loading for large knowledge bases
   - Optimize ONNX model loading if needed

### Medium Priority

3. **Error Handling:**
   - Improve error messages when bundles not found
   - Add validation in KnowledgeBaseManager
   - Better handling of missing ONNX models

4. **Code Cleanup:**
   - Remove any remaining dead code
   - Consolidate duplicate utility methods
   - Improve logging consistency

### Low Priority

5. **Additional MCP Tools:**
   - Implement more component handlers (tabs, grids, etc.)
   - Add database manipulation tools
   - Add solution-level tools

---

## IMPORTANT NOTES FOR AI ASSISTANTS

### When Continuing This Work

1. **Read This Document First:** It contains the most up-to-date architecture decisions
2. **Check Dates:** This doc is Dec 22, 2025 (Evening) - most recent
3. **Verify Before Changing:**
   - Don't merge knowledgebase and knowledgebase.mcp plugins (circular dependency will return)
   - Don't remove IStartup extension from knowledgebase (needed for early activation)
   - Don't remove plugin.xml from knowledgebase or knowledgebase.mcp (REQUIRED for OSGi)
   - Don't use reflection in ui actions (use extension point API)
   - Don't add static initializer to RulesCache (rules come from SPM packages only)

### Key Files to Check

**Always verify these files are correct:**
- `/com.servoy.eclipse.knowledgebase/META-INF/MANIFEST.MF`
- `/com.servoy.eclipse.knowledgebase/plugin.xml` (must have IStartup extension + extension point!)
- `/com.servoy.eclipse.knowledgebase/schema/knowledgeBaseOperations.exsd`
- `/com.servoy.eclipse.knowledgebase/src/.../IKnowledgeBaseOperations.java`
- `/com.servoy.eclipse.knowledgebase/src/.../KnowledgeBaseOperationsProvider.java`
- `/com.servoy.eclipse.knowledgebase/src/.../KnowledgeBaseStartup.java`
- `/com.servoy.eclipse.knowledgebase/src/.../ai/RulesCache.java` (NO static initializer!)
- `/com.servoy.eclipse.knowledgebase.mcp/META-INF/MANIFEST.MF`
- `/com.servoy.eclipse.knowledgebase.mcp/plugin.xml` (must exist!)
- `/com.servoy.eclipse.aibridge/META-INF/MANIFEST.MF` (requires both KB plugins)
- `/com.servoy.eclipse.ui/META-INF/MANIFEST.MF` (optional dependency on knowledgebase)

---

## REVISION HISTORY

**December 31, 2025 - Servoy Rules Optimization & Standardization**
- Comprehensive optimization of 4 core rule files (relations, valuelists, forms, styles)
- **Eliminated 500+ lines of duplication** across all files
- **forms.md**: Eliminated 203 lines of duplicated validation rules (604 → 557 lines)
- **styles.md**: Eliminated 121 lines of CSS syntax duplication (626 → 548 lines)
- **valuelists.md**: Fixed severe structural duplication, added type selection guide (506 → 630 lines)
- **relations.md**: Consolidated validation, added quick reference (368 → 437 lines)
- **Standardized structure** across all rule files:
  1. Quick Reference: Available Tools (table)
  2. Tool Details (each tool documented)
  3. How to Present Results to User (formatting rules)
  4. Context Management (where things get created)
  5. Domain-Specific Sections (varies by domain)
  6. Complete Workflows (5-8 concise workflows)
  7. Critical Rules (unique, non-duplicated)
  8. Comprehensive Examples (8-13 examples with context)
- **Created RULE_TEMPLATE.md**: 16KB standardized template for all future rule files
- **Updated servoy-rules/README.md**: Reference to template and compliant files
- All examples now demonstrate context handling (setContext)
- Removed broken tool references (listVariants in styles.md)
- Added presentation formatting guides (consistent with all read operations)
- No contradictions - each concept appears once per file
- Modified files:
  - /servoy-rules/rules/relations.md
  - /servoy-rules/rules/valuelists.md
  - /servoy-rules/rules/forms.md
  - /servoy-rules/rules/styles.md
  - /servoy-rules/RULE_TEMPLATE.md (new)
  - /servoy-rules/README.md

**December 31, 2025 - Read Operations Scope Parameter + Module Fix + Formatting Examples**
- Added optional scope parameter to getRelations, listForms, and getValueLists tools
- Scope values: "current" (current context only) or "all" (active solution + modules, DEFAULT)
- Allows users to filter results by context for better granularity
- Backward compatible - default behavior unchanged ("all")
- **CRITICAL FIX**: Changed scope="all" implementation to explicitly iterate through modules
  - Previous: Used `getRelations(true)`, `getForms(null, true)`, `getValueLists(true)` - did NOT include module items
  - Current: Manually loops through `getModulesOfActiveProject()` and collects items from each module
  - This fixes the bug where module relations/forms/valuelists were not appearing in "all" results
- **CRITICAL FIX #2**: Fixed duplication issue where active solution items appeared twice
  - Root cause: `getModulesOfActiveProject()` was returning the active project in the modules array
  - Solution: Added `if (module.equals(activeProject)) continue;` to skip active project when iterating modules
  - Prevents relations/forms/valuelists from appearing twice in results
- **FORMATTING GUIDANCE ADDED**: Added detailed formatting examples to all three rule files
  - Shows AI exactly how to present list results with proper grouping and numbering
  - Includes [REQUIRED], [FORBIDDEN] tags for critical formatting rules
  - Examples show correct vs. wrong formatting to prevent LLM from mis-numbering detail lines
  - Emphasizes: number only main items, indent details, add blank lines between items
- Updated tool descriptions for all three read operations
- Updated knowledgebase-implementation.md with scope parameter documentation
- Updated rule files: relations.md, forms.md, valuelists.md with formatting sections
- Modified handlers: RelationToolHandler, FormToolHandler, ValueListToolHandler

**December 31, 2025 - Read Operations Scope Parameter (Initial)**
- Added optional scope parameter to getRelations, listForms, and getValueLists tools
- Scope values: "current" (current context only) or "all" (active solution + modules, DEFAULT)
- Allows users to filter results by context for better granularity
- Backward compatible - default behavior unchanged ("all")
- Updated tool descriptions for all three read operations
- Updated knowledgebase-implementation.md with scope parameter documentation
- Modified handlers: RelationToolHandler, FormToolHandler, ValueListToolHandler

**December 22, 2025 - Evening Session - Extension Point & Cleanup**
- Implemented proper extension point architecture (IKnowledgeBaseOperations)
- Replaced reflection in ui actions with type-safe extension point API
- Added optional dependency from ui to knowledgebase plugin
- Follows Servoy pattern (matches IExportSolutionWizardProvider)
- Removed broken autoDiscoverRules() static initializer from RulesCache
- RulesCache now clean cache-only implementation (no embedded rules)
- Added Fix 9: Extension point implementation
- Added Fix 10: RulesCache static initializer removal
- Updated all documentation to reflect extension point architecture

**December 22, 2025 - Morning Session - Plugin Split and Early Activation**
- Split monolithic knowledgebase into TWO plugins (core + mcp)
- Eliminated circular dependency between knowledgebase.mcp and ui
- Added IStartup extension to knowledgebase for early activation
- Fixed initialization timing: ONNX and KB now load at startup, not on first tool call
- Updated aibridge to use direct imports (removed reflection)
- Added Fix 7: Plugin split architecture
- Added Fix 8: Early activation with IStartup
- Updated all architecture diagrams to reflect three-plugin system

**December 19, 2025 - Latest Updates**
- Added Fix 5: ClassCastException with IServoyModel interface
- Added Fix 6: Metadata key mismatch ("intent" vs "category")
- Added comprehensive debugging output to KnowledgeBaseManager
- Documented servoy-rules plugin package structure
- Documented npm-based build system for release zip
- Documented MANIFEST.MF requirements for SPM recognition
- Documented package discovery mechanism
- Added debugging guide for package recognition issues

**December 19, 2025 - Document Created**
- Initial version documenting post-split architecture
- Recorded critical fixes: plugin.xml, wst.jsdt.ui removal, networknt.schema removal
- Documented deferred embedding service initialization
- Recorded onnxruntime-extensions launch config requirement

---

## QUICK REFERENCE FOR AI ASSISTANTS

### Most Common Issues and Solutions

1. **UI Actions Using Reflection**
   - Use extension point API, not reflection
   - Import IKnowledgeBaseOperations interface (optional dependency)
   - Get provider via Platform.getExtensionRegistry()
   - Pattern matches IExportSolutionWizardProvider in Servoy codebase

2. **Circular Dependency Errors**
   - Keep knowledgebase (core) and knowledgebase.mcp (tools) separate
   - knowledgebase.mcp can depend on ui
   - ui has OPTIONAL dependency on knowledgebase (for interface only)

3. **Knowledge Bases Not Loading on Solution Activation**
   - Verify IStartup extension exists in knowledgebase/plugin.xml
   - Verify KnowledgeBaseStartup.java exists
   - Check console for "[KnowledgeBase] Plugin starting..." message

4. **RulesCache Static Initializer Issues**
   - RulesCache should NOT have static initializer
   - Rules come ONLY from SPM packages (not embedded in knowledgebase)
   - Loading happens via KnowledgeBaseManager when solution activates

5. **Package Not Recognized by SPM**
   - Check MANIFEST.MF has `Knowledge-Base: true`
   - Check MANIFEST.MF has `Name: servoy-rules.spec` entry with blank line before it
   - Check servoy-rules.spec exists at root of zip
   - Check embeddings.list or rules.list exists

6. **Embeddings Load But No Rules Returned**
   - Verify metadata key is `"intent"` not `"category"`
   - File: `ServoyEmbeddingService.java` (loadEmbeddingsFromReader method)

7. **Bundle Resolution Errors**
   - Verify plugin.xml exists in knowledgebase
   - Verify onnxruntime-extensions bundles in launch config
   - Check for uses constraint violations (wst.jsdt.ui)

8. **Module Relations/Forms/ValueLists Not Appearing in "All" Results**
   - FIXED in December 31, 2025 implementation
   - Read tools now explicitly iterate through all modules
   - Uses `getModulesOfActiveProject()` to collect items from each module
   - Previous `getSolution().getRelations(true)` approach did not include module items

9. **Duplicate Relations/Forms/ValueLists in "All" Results**
   - FIXED in December 31, 2025 implementation
   - Root cause: `getModulesOfActiveProject()` included active project in modules array
   - Solution: Added check `if (module.equals(activeProject)) continue;` to skip duplication
   - Each item now appears only once with correct origin information

10. **Build servoy-rules.zip**
   ```bash
   cd servoy-rules/
   npm install  # First time only
   npm run make_release
   ```

### Key Files to Check When Debugging

- `/com.servoy.eclipse.knowledgebase/plugin.xml` - Extension point definition
- `/com.servoy.eclipse.knowledgebase/schema/knowledgeBaseOperations.exsd` - Extension point schema
- `/com.servoy.eclipse.knowledgebase/src/.../IKnowledgeBaseOperations.java` - Interface with EXTENSION_ID
- `/com.servoy.eclipse.knowledgebase/src/.../ai/RulesCache.java` - No static block!
- `/com.servoy.eclipse.knowledgebase/META-INF/MANIFEST.MF` - Check imports
- `/com.servoy.eclipse.ui/META-INF/MANIFEST.MF` - Optional knowledgebase dependency
- `/com.servoy.eclipse.ui/src/.../actions/LoadKnowledgeBaseAction.java` - Extension point usage
- `/com.servoy.eclipse.aibridge/META-INF/MANIFEST.MF` - Check Require-Bundle
- `/com.servoy.eclipse.knowledgebase/servoy-rules/META-INF/MANIFEST.MF` - Knowledge-Base: true

---

**END OF DOCUMENT**

This document is the authoritative source for understanding the current state of the knowledge base plugin as of December 19, 2025. When continuing work on this project, read this document FIRST to understand what has been done and why.
