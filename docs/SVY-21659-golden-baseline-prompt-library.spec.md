# Spec: SVY-21659 — Build a real golden baseline library (in-repo prompt library + solution setups + docs)

## 1. Goal

Close the skill-test coverage gap left by SVY-21366 with the part that lives **in this
repo and can be built by a code agent**: a committed, human-readable **prompt library**
that proves the `skill4servoy` skills are actually good at **building, enhancing and
fixing** things in a real Servoy solution — end to end — not merely "invoke a skill once".

Concretely the deliverable is:

- a small set of reusable **Solution Setups** — named starting solutions, each with a
  self-contained **setup prompt** that builds its forms, tables, columns, methods, i18n
  etc. from an empty solution. The tester runs a setup prompt once, saves the resulting
  solution, and reuses it across every scenario that needs pre-existing code to enhance or
  fix.
- a **scenario matrix** of exact prompts — the human-readable `SCENARIO-MATRIX.md` doc a
  tester copy-pastes straight into Servoy AI — each annotated with its recommended
  verification tier and its starting state (`empty` or a setup id). The matrix spans the full skill
  surface: build, **enhance**, **fix** (described problem AND stacktrace), review,
  security-review (both polarities), unit-test, **form-test**, E2E, modernise and optimise.
- the committed **input artifacts** the prompts reference — HTML UI mockups and the
  per-setup / per-scenario **setup specs in markdown**.
- the **docs** that tie the matrix to the existing record → export → import wizard
  procedure, now including the setup → save → reuse flow.

There is **no production Java helper** in this deliverable (the reporter does not need
folder scaffolding). The only code touched is a single minimal **plainjunit guard test**
that parses the committed `SCENARIO-MATRIX.md` and fails on drift.

The real `export.json` / `baseline.json` goldens are still authored by a human tester
(feeding each prompt into the import wizard against a live LLM, then human-reviewing the
drafted assertions) and committed to the private `Servoy/servoy-ai-skilltest-baselines`
repo (a local checkout of it). This ticket produces the inputs that make that human work
fast, repeatable and consistent — not the goldens themselves.

## 2. Background

- **The engine is done (SVY-21366), the coverage is not.** The replay runner, the three
  verification tiers (`expect.persists` structural / `verify.acceptanceCriteria` LLM-judge
  / `verify.jsunit`), the transcript comparator, the import wizard with its AI
  baseline-authoring agent, the headless CI runner and the nightly pipeline all exist in
  `bundles/com.servoy.eclipse.opencode.skilltest`. The only in-repo fixture is the
  hand-written placeholder `create-valuelist-basic`.
- **Real goldens live in a separate private repo.** `Jenkinsfile.skilltest` clones
  `Servoy/servoy-ai-skilltest-baselines` and points the run at
  `-Dskilltest.baselines.dir=…/baselines-repo/baselines`; the in-repo
  `tests/.../baselines/` holds only the loader/parser placeholder. The goldens live in the
  private `Servoy/servoy-ai-skilltest-baselines` repo (a local checkout of it). It already
  holds four reviewed baselines that are the shape templates for this matrix:
  - `form-with-button-and-label` — `empty`, `expect.persists: []`, acceptance-criteria
    judge only.
  - `create-customerdetail-form-with-data-bindings` — `git` source, `expect.persists`
    (form + two bound textboxes) **+** `verify.jsunit` (injected name-tolerant script).
  - `customer-order-summary-with-i18n` — `empty` + inputs, `expect.persists`
    (form + scope method) **+** `verify.jsunit`.
  - `building-order_tabs-tab-panel-form-in-servoy` — `empty` + `inputs` (HTML mockup),
    minimal `expect.persists` (form-exists) + judge-authoritative
    `verify.acceptanceCriteria`.
- **Why these prompts, this way.** The matrix is driven by what the `skill4servoy` pack
  (its `.opencode-shippable/` tree) actually
  enforces: eight agents (`Orchestrator`, `Developer`, `Reviewer`, `Security-Reviewer`,
  `JSUnit-Tester`, `E2E-Tester`, `Database-Researcher`, `Researcher`, plus the read-only
  `Judge`) and ten `servoy-*` skills. **The matrix deliberately spans build, ENHANCE, FIX,
  and the non-build review/test families** so it exercises the skills the way a real user
  does: not only creating from scratch, but modifying existing structure and diagnosing and
  repairing broken code. The trimmed create-* prompts exercise the `servoy-developer` build
  rails from a cold solution; the enhance/fix/form-test prompts run against a **pre-built
  Solution Setup**, so they test whether the skills can read existing forms, tables and
  methods and change them correctly; the data-layer prompts *tempt* a specific failure the
  skills prevent — an N+1 query-in-a-loop (D4/D5), a discarded `saveData()` (D1); and the
  remaining prompts drive each non-build family the pack ships: the `servoy-reviewer`
  finding a planted defect, the `servoy-security-reviewer` in **both** polarities (a clean
  run that must yield no finding AND a rawSQL-injection run that must be flagged CRITICAL),
  the `servoy-unittester` (both a scope method and an EXISTING form's function via
  `svyCreateFormTest`), the `servoy-e2etester`, `svyModernize`/`svyOptimize` refactoring
  existing code without changing behaviour, and `svyFixStacktrace` repairing the bug behind
  a pasted Servoy stacktrace.
- **Starting state is no longer always empty.** Pure-create and refactor-in-one-run
  scenarios still start from `precondition.source: empty` (the runner creates a fresh
  solution via the MCP `createSolution` tool, auto-provisioning the default web packages —
  bootstrapcomponents, 12grid, fontawesome, servoyextra). Enhance / fix / form-test /
  fix-stacktrace scenarios instead start from a **saved Solution Setup** (§3.1): the tester
  runs the setup prompt once against an empty solution, saves the result, and points those
  scenarios' `precondition.source` (`folder` or `git`) at it.
- **`baseline.json` schema** (from `Baseline`/`BaselineLoader`): `id`, `title`, `active`,
  `export`, `promptOverride`, `agent`, `model`, `maxAttempts`, `timeoutSeconds`,
  `precondition` ({`solution`, `source:{type[,location,ref]}`, optional `inputs[]`,
  optional `cleanProjects`, `delete`/`restore`}), `compare` ({`ordered`, `strict`,
  `forbidden`, `argOverrides`}), `expect.persists[]`
  ({`kind`, `name`, `scope`, `exists`, `props`, `children`}), `verify.jsunit`
  ({`scripts[]`, `existingScripts[]`, `scope`, `method`, `timeoutSeconds`,
  `warmupTimeoutSeconds`, `required`}) and `verify.acceptanceCriteria`
  ({`criteria[]`, `judgeModel`, `required`}). A `null` `judgeModel` reuses the builder's
  model in a separate judge session.

## 3. Design

The single source of truth is one committed **human-readable markdown** file,
`bundles/com.servoy.eclipse.opencode.skilltest/scenarios/SCENARIO-MATRIX.md` — the prompt
library a tester copy-pastes straight into Servoy AI. There is **no JSON** in this
deliverable: a JSON file is not copy-pasteable, so the prompts live as fenced code blocks in
the matrix doc instead. The matrix doc's content **is** the §3.1 (solution setups) + §3.2
(scenario matrix) content of this spec — the implementation copies it verbatim into
`SCENARIO-MATRIX.md`; it is not generated from any other file. Its structure:

- a short intro;
- the **solution-setups** section: each setup is a `##`/`###` heading with its shape summary
  and its full setup prompt in a fenced ``` code block (this is §3.1);
- the **scenario matrix table** (id | scenario | skills | starting state | tier | rationale),
  then one `###` section per scenario carrying its full prompt in a fenced code block, ready
  to copy — each setup-backed scenario naming the setup it starts from (this is §3.2);
- the input artifacts (HTML mockups + setup-spec markdown) live under
  `bundles/com.servoy.eclipse.opencode.skilltest/scenarios/inputs/...` (§3.3) and are
  referenced from the matrix doc by relative path.

### 3.1 Solution setups

A **Solution Setup** is a named reusable starting solution plus the self-contained prompt
that builds it from an empty solution. The tester runs a setup prompt **once** against a
fresh `empty` solution, lets the agent finish, then **saves** the resulting solution
(exports it, or commits it to a folder/git location). Scenarios that need pre-existing code
to enhance or fix point their `precondition.source` at that saved solution and set
`precondition.solution` to the setup's solution name. **One setup is reused by several
scenarios**, so the pre-built shape is authored and reviewed once.

Keep the shapes realistic but minimal. Three setups cover every non-empty scenario in this
matrix:

| id | purpose | shape summary | scenarios that use it |
|---|---|---|---|
| `setup-crm-basic` | A small CRM to enhance / fix / form-test against. | Solution `aiCrmBasic`, bound to `db:/udm`. A CSS-position form `companies` bound to `db:/udm/companies` (fields for company name, email, website) and a CSS-position form `orders` bound to `db:/udm/orders` (fields for order id, order date, order total and the paid flag, plus a form function `formatAmount(amount)`). A global scope `reports` with `getOrderTotalsByCompany(companyIds)` (deliberately written as an unguarded query-in-a-loop so the fix/optimize scenarios have real N+1 code and a real crash) and a scope `pricing` with `calculateLineTotal(quantity, unitPrice, discountPercent)`. A custom-values valuelist `orderPaidStatus` (Open = 0, Paid = 1) on the paid field. One i18n key `crm.orders.title`. | `svyfixstacktrace-order-totals`, `svycreateformtest-orders`, `enhance-order-total-field` |
| `setup-auth-login` | A small solution with authentication wired but **broken on purpose**, for the described-problem fix scenario. | Solution `aiAuthLogin`, bound to `db:/example_data`. Configured as AUTHENTICATOR (type 3) with an authenticator **module** `aiAuthLogin_auth` whose `globals.js` holds a correct `onSolutionOpen(arg, queryParams)` that validates credentials and calls `security.login()`. The planted defect: the module's `solution_settings.obj` **does NOT set `onSolutionOpenMethodID`** to that function's UUID, so the runtime never calls it and login silently fails. A main-solution form `home` bound to `db:/example_data/customers` so there is something to land on after login. | `fix-authenticator-not-called` |
| `setup-review-defects` | A form with two deliberate runtime defects, for the review-only scenario. | Solution `aiReviewDefects`, referencing `servoy_resources`. One CSS-position form `customerCard` bound to `db:/example_data/customers`: a label plus a `bootstrapcomponents-textbox` for company name (`companyname`), contact name (`contactname`) and phone, and a `Save` button. The two planted defects: the phone field binds to `phone_number`, which does not exist (the column is `phone`), and the button's `onActionMethodID` is a UUID that no method has (the form has no `.js`). Everything else is valid, so the review has exactly these two things to find. Committed in the baselines repo under `solutions/aiReviewDefects`. | `reviewer-defect-find` |

> **Why these two.** `setup-crm-basic` is the workhorse: a couple of data-bound forms, two
> scope methods (one intentionally N+1), a valuelist and an i18n key give the
> enhance/fix/form-test scenarios genuine existing structure to read and change.
> `setup-auth-login` isolates one realistic, described (not stacktrace) defect — an
> authenticator function that exists but is never wired as the module's `onSolutionOpen`
> handler, which per the authentication guide is exactly the "function exists but is never
> called by the runtime" trap — so the fix scenario tests diagnosis, not just editing.

The **full setup prompts** (self-contained, same tone as the task prompts — the tester
pastes each into the import/record wizard against a fresh `empty` solution, saves the
result, then reuses it):

#### `setup-crm-basic`
```
Build a small CRM starting solution in the active (empty) solution, against the udm database
only (do not use example_data). Create two CSS-position forms: a form named "companies" bound
to db:/udm/companies with text fields bound to company_name, company_email and company_url;
and a form named "orders" bound to db:/udm/orders with fields bound to order_id, order_date,
order_total and is_paid. On the orders form add a form function formatAmount(amount) that
returns the amount as a string with exactly two decimals, and returns "0.00" for a missing or
non-numeric amount. Create a custom-values value list named "orderPaidStatus" with the display
values Open and Paid for the real values 0 and 1, and attach it to the is_paid field. Add one
i18n key crm.orders.title with the English value "Orders". Then create two global scopes. In
a scope named "reports", add a method getOrderTotalsByCompany(companyIds): given an array of
company_id values it returns a plain object mapping each company_id to the summed
order_total of that company's orders (db:/udm/orders, matched on its company_id column).
Write it the naive way on purpose: loop over companyIds.length, run one query per id inside
the loop, and do NOT guard a missing or empty companyIds array (this is intentional; later
tasks fix and optimize it). In a scope named "pricing", add a method
calculateLineTotal(quantity, unitPrice, discountPercent) that returns quantity * unitPrice
with the discount applied, rounded to two decimals, and returns 0 for any missing or
negative input. If any detail is unspecified, choose sensible real values and continue
without asking. Do not write any tests. Try to do this in a single run without stopping to
ask questions.
```

#### `setup-auth-login`
```
Build a small authenticated starting solution in the active (empty) solution, bound to the
example_data database. Configure stateless authentication of the AUTHENTICATOR kind: create
a separate authenticator module, add it as a dependency of the main solution, and in that
module create a globals.js with an onSolutionOpen(arg, queryParams) function that reads
queryParams.username and queryParams.password, validates them against the example_data user
store the idiomatic way (fetch by username, verify a PBKDF2 hash) and calls security.login()
on success, returning an errorMessage object on failure. IMPORTANT — leave the module's
onSolutionOpen event handler UNSET: create the function but do NOT set the module's
onSolutionOpenMethodID to its UUID, so the function exists but the runtime never calls it on
login. Also create a main-solution CSS-position form named "home" bound to
db:/example_data/customers showing the company name, so there is a landing form after login.
If any detail is unspecified, choose sensible real values and continue without asking. Do
not write any tests. Try to do this in a single run without stopping to ask questions.
```

#### `setup-review-defects`

Starting solution: `aiReviewDefects`. Unlike the other two setups this one is not built by a
prompt: it is committed as-is in the baselines repo under `solutions/aiReviewDefects`, so the
planted defects are byte-identical on every run. A baseline selects it with
`precondition.solution = "aiReviewDefects"` and a `git` source pointing at the baselines repo
(`https://github.com/Servoy/servoy-ai-skilltest-baselines`, `ref: master`).

```
The solution aiReviewDefects holds one CSS-position form, customerCard, bound to
db:/example_data/customers. It has a labelled bootstrapcomponents-textbox for company name
(companyname), contact name (contactname) and phone, plus a Save button. Two defects are planted
on purpose: the phone field binds to the dataprovider phone_number, which does not exist in the
customers table (the real column is phone), and the Save button's onActionMethodID is a UUID that
no method in the solution has. The form has no .js file. Everything else is valid.
```
Each setup also has a committed **setup spec** markdown file describing its shape in prose
(for the human tester), referenced from the matrix doc by relative path — see §3.3.

#### `aiSkilltest_empty` (committed empty solution + resources project)

Scenarios that touch the `udm` database cannot start from the runner's generated `empty`
solution, because its resources project only knows `example_data`. They start from a
committed, otherwise empty solution instead:

- Solution `aiSkilltest_empty`, referencing the resources project `servoy_resources`. Both
  live in the baselines repo under `solutions/`, together with the `.dbi` schemas of the
  `example_data` and `udm` servers (no `security.sec`, no `ng_web_packages`: the runner
  plants the web packages itself).
- A baseline selects it with `precondition.solution = "aiSkilltest_empty"` and a `git`
  source pointing at the baselines repo itself
  (`https://github.com/Servoy/servoy-ai-skilltest-baselines`, `ref: master`). The runner
  clones it and imports the solution plus `servoy_resources`.

Scenarios that start from it: `create-form-css-position`, `create-valuelist-db`,
`create-relation`, `datalayer-open-order-totals`, `datalayer-bulk-archive-save`,
`security-tenant-query-clean`, `security-rawsql-injection`,
`orchestrator-form-plus-unittest` and `optimize-query-in-loop` - every scenario whose prompt uses
a database. Database-free scenarios still start from `empty`.

### 3.2 The scenario matrix (the prompt library)

Nineteen scenarios, deliberately spanning **build, enhance, fix, review, security-review
(both polarities), unit-test, form-test, E2E, modernise and optimise** — the full skill
surface the pack ships, including all three valuelist types (`CUSTOM_VALUES`,
`TABLE_VALUES`, `GLOBAL_METHOD`). The "starting state" column names `empty` (the runner
creates a fresh solution), `aiSkilltest_empty` (the committed empty solution with its
resources project, §3.1) or a Solution Setup id from §3.1 (the tester points the
precondition at the saved setup solution).

**For an `empty` scenario that must act on EXISTING code, the prompt builds that code first,
in the same run, then invokes the skill on it** — this keeps the pure-create and
refactor-in-one-run baselines reproducible from a cold solution. **For a setup-backed
scenario, the pre-existing code already lives in the saved setup solution**, so the prompt
reads and changes it directly — this is what tests the skills' enhance/fix ability on real
existing structure. For the review-only and security-review scenarios the closing
instruction is to **perform the review and report findings, not to fix** — the Reviewer /
Security-Reviewer finding is what the baseline grades. For the fix/enhance scenarios the
closing is the usual "single run, iterate as needed, don't stop to ask".

Tiers are *recommendations* the tester confirms at record time; the rationale follows the
`skilltest-authoring` rule — deterministic artifact → `persists`; visual/layout fidelity or a
review/fix verdict → acceptance-criteria judge; behaviour that must run → JSUnit.

| id | scenario | skill(s) exercised | starting state | recommended tier | rationale |
|---|---|---|---|---|---|
| `create-form-css-position` | Create a CSS-position form bound to a datasource with two bound text fields | servoy-developer (form build, data binding) | `aiSkilltest_empty` | `expect.persists` | Deterministic: form `dataSource` + `useCssPosition` + two components bound to named dataproviders look identical every valid run. |
| `create-form-responsive` | Create a fully responsive (flex/grid) form with a header row and a two-column body | servoy-developer (responsive layout) | empty | `acceptanceCriteria` (judge) | Responsive div/row/column nesting + styleClasses legitimately vary run-to-run; structural equality is brittle, so a judge grades layout intent. Keep only a minimal form-exists `persists`. |
| `create-form-order-tabs-from-mockup` | Build an order-tabs tab-panel form matching a committed HTML mockup | servoy-developer (responsive layout, UI fidelity) | empty | `acceptanceCriteria` (judge) | UI-fidelity build from an input mockup (`inputs/specs/03_order_tabs.html`): the judge grades the built form matches the mockup's tab/panel structure. Keep a minimal form-exists `persists`. |
| `create-valuelist-custom` | Create a custom/fixed-values valuelist (`CUSTOM_VALUES`) | servoy-developer (valuelist) | empty | `expect.persists` | Deterministic: the fixed custom values are known up front; a persist assertion nails them exactly. |
| `create-valuelist-db` | Create a database/table-based valuelist (`TABLE_VALUES`) bound to a table's display + real columns | servoy-developer (valuelist) | `aiSkilltest_empty` | `expect.persists` | Deterministic: the valuelist's datasource plus its display and real dataprovider binding are fixed once the real columns are found, so a persist assertion nails them. |
| `create-valuelist-global` | Create a global-method-based valuelist (`GLOBAL_METHOD`) bound to a scope method that returns the contents | servoy-developer (valuelist) | empty | `expect.persists` (+ optional `verify.jsunit`) | Deterministic: the valuelist type and its bound method, plus the method's existence, are fixed (persists). A jsunit tier is optional since the real list content is produced by the method at runtime. |
| `create-relation` | Create a relation between two udm tables (companies, orders) — the task requires inspecting the schema for the real FK columns (delegates to Database-Researcher) | servoy-developer (relation), servoy-database (Database-Researcher) | `aiSkilltest_empty` | `expect.persists` | Deterministic: relation name, primary/foreign datasource and the key pair are fixed. The prompt withholds the column names so the real FK must be found from the live schema, exercising the bounded read-only schema delegation. |
| `datalayer-open-order-totals` | Build a scope method that sums open-order totals for many customers in ONE query (tempts N+1) | servoy-developer (data-layer rails D4/D5), servoy-database | `aiSkilltest_empty` | `expect.persists` + `verify.jsunit` | The scope method is a deterministic artifact (persists); only running it proves it returns correct totals, guards the empty-input case, and uses a single query (jsunit). The prompt tempts a query-in-a-loop the rails must prevent. |
| `datalayer-bulk-archive-save` | Build a scope method that bulk-updates records and must honour the `saveData()` result (tempts discarded save) | servoy-developer (data-layer rail D1) | `aiSkilltest_empty` | `verify.jsunit` | Behaviour-only: the defect (silently discarded `databaseManager.saveData()` Boolean → silent data loss) is invisible structurally; only a JSUnit run that asserts the records actually persisted catches it. Folds in the D4 unbounded-read concern — the load is scoped by the cutoff date, not an unbounded table scan. |
| `security-tenant-query-clean` **(skipped)** | Build a QBSelect-based search over a tenant table from user input, then run a security review — a correct run yields NO finding | servoy-developer (build), servoy-security-reviewer (false-positive filter) | `aiSkilltest_empty` | `acceptanceCriteria` (judge) | The judge grades that the feature was built AND that the security review returned a clean PASS with no spurious hardening: QBSelect is parameterized by construction, NGClient auto-sanitizes display, the tenant filter is auto-injected — a correct run neither concatenates raw SQL nor adds a manual tenant `WHERE`. This is the false-positive polarity. |
| `security-rawsql-injection` | Build a `ws_*` endpoint that concatenates user input into a rawSQL string, then run a security review — the review MUST flag it CRITICAL | servoy-developer (build), servoy-security-reviewer (true-positive detection) | `aiSkilltest_empty` | `acceptanceCriteria` (judge) | The true-positive polarity: the prompt deliberately builds an injectable source→sink path (request param → `+` string concat → `plugins.rawSQL.executeSQL`) with no auth gate. The judge grades that the review names the SQL-injection (and the missing `ws_*` authz) as CRITICAL. No fix is applied; the verdict is the artifact. |
| `reviewer-defect-find` | Review a committed form that carries two planted runtime defects (a dangling `dataProviderID`, an `onActionMethodID` wired to a non-existent handler); the review must FIND both | servoy-reviewer | `setup-review-defects` | `acceptanceCriteria` (judge) | Review-only (no fix): the defects are pre-built in the committed `aiReviewDefects` setup, identical on every run, and are the kind the `.frm` grep passes (U5/U7) are built to catch. The prompt describes the intended form but does not name the defects. The judge grades that the review NAMES the planted defect; structure can't express "the reviewer noticed". |
| `orchestrator-form-plus-unittest` | Build a scope method and write a passing unit test for it (exercises the build → unit-test hand-off) | servoy-orchestrator, servoy-developer, servoy-unittester | `aiSkilltest_empty` | `expect.persists` + `verify.jsunit` | Deterministic method (persists) plus a behaviour check; verifies the orchestrator correctly hands off to the unit tester and the produced method is testable. Prefer the injected name-tolerant script as the authoritative behaviour gate. |
| `svycreateformtest-orders` | Generate a JSUnit form test for the EXISTING `orders` form in the setup solution | servoy-unittester (svyCreateFormTest), servoy-developer | `setup-crm-basic` | `verify.jsunit` | Form-test against existing structure: the `orders` form already exists in the setup, so the scenario tests that the skill builds the `orders_test` extending form in the `*_test` module and a passing `test_*` function that calls the form's own `formatAmount` function — not that it creates a form. Behaviour runs, so JSUnit is authoritative. |
| `enhance-order-total-field` | Enhance the EXISTING `orders` form — add a computed order-total field and a refresh button that recomputes it | servoy-developer (form enhancement, data-layer) | `setup-crm-basic` | `expect.persists` + `verify.jsunit` | The flagship ENHANCE scenario: it modifies an existing form (adds a field + a button wired to a handler + the computing method) rather than creating from scratch. The added structure persists; jsunit runs the compute function to prove the total (sum of `udm.order_items.extended_price` for the order) is correct. Tests reading and extending existing structure. |
| `svyfixstacktrace-order-totals` | Fix the bug behind a pasted Servoy stacktrace in the EXISTING `reports.getOrderTotalsByCompany` method | svyFixStacktrace (servoy-developer, servoy-database) | `setup-crm-basic` | `verify.jsunit` + `acceptanceCriteria` (judge) | The setup's `getOrderTotalsByCompany` throws on an empty/missing `companyIds` array (the naive loop dereferences it). The prompt pastes a representative Servoy stacktrace for that failure. jsunit asserts the error no longer reproduces (empty input returns an empty object, normal input returns correct totals); the judge grades that the root cause was named. |
| `fix-authenticator-not-called` | Fix a DESCRIBED problem — "the authenticator is not being called on login" — in the setup solution | svyFixStacktrace family / servoy-developer (diagnosis), servoy-platform (auth) | `setup-auth-login` | `acceptanceCriteria` (judge) | Described-problem diagnosis+fix (not a stacktrace, not a ticket): the setup wired an authenticator whose `onSolutionOpen` exists but is never set as the module's `onSolutionOpenMethodID`, so login silently fails. The judge grades that the diagnosis named the unwired handler as the root cause AND the fix sets `onSolutionOpenMethodID` to the function's UUID. The fix is a structural-file change the judge is best placed to confirm against the described symptom. |
| `e2e-form-cypress-test` | Build a small form, then ask for a Cypress E2E spec that drives it | servoy-developer (build), servoy-e2etester | empty | `acceptanceCriteria` (judge) | Build-then-test: the prompt builds a simple interactive form, then asks the E2E-Tester for a `.cy.ts` spec exercising the feature end to end. The judge grades that an E2E spec was produced for the built form, using the project's selector helpers (no raw selectors, no `cy.wait(ms)`); the spec file is the artifact, not a persisted solution object. |
| `modernize-method-syntax` | Build a scope method using `var`, a function expression and a magic HTTP status number, then modernize it — behaviour unchanged | servoy-developer (svyModernize rails) | empty | `expect.persists` + `acceptanceCriteria` (judge) | Refactor, same behaviour: the prompt first writes an intentionally dated method, then modernizes it to `const`/arrow and `plugins.http.HTTP_STATUS.*`. The method persists; the judge grades that behaviour is unchanged AND the modern syntax replaced every dated construct (no `var`, no magic status number). |
| `optimize-query-in-loop` | Build a scope method with a query inside a loop over records, then optimize it to a single guarded `isin()` query — behaviour unchanged | servoy-developer (svyOptimize rails D4/D5) | `aiSkilltest_empty` | `expect.persists` + `verify.jsunit` + `acceptanceCriteria` (judge) | Performance, same results: the prompt first writes the N+1 version, then optimizes it. The method persists; jsunit asserts identical results (ideally with a query-count assertion proving a single query); the judge grades that no query-in-loop remains and the `isin()` has an empty-array guard. |

**The full prompt text for each scenario** (these ARE the deliverable — each is a
self-contained prompt a tester pastes into the import/record wizard; the closing
"single run, don't stop to ask" instruction matches the tone of the existing
`building-order_tabs` and `customer-order-summary` prompts). Setup-backed scenarios open by
naming the saved setup solution as their starting point:

#### `create-form-css-position`
```
Create a new CSS-position form named "productDetail" in the active solution, bound to the
data source db:/example_data/products. Place two text fields on it: one bound to
dataprovider "productname", one bound to "unitprice". If any detail is unspecified, choose
a sensible default and continue without asking. Do not write any tests. Try to do this in a
single run without stopping to ask questions.
```

#### `create-form-responsive`
```
Create a new fully responsive form named "dashboardHome" in the active solution (use a
responsive/flex layout, not an absolute CSS-position layout). The form needs a header row
across the top containing a title label, and below it a body split into two equal columns,
each holding a labelled placeholder panel. The screen contains no database data, so it needs
no datasource or data binding — static UI only. Make it look clean at both wide and narrow
screen widths. If any detail is unspecified, choose a sensible default and continue without
asking. Do not write any tests. Try to do this in a single run without stopping to ask
questions.
```

#### `create-form-order-tabs-from-mockup`
```
Build a responsive form named "orderTabs" in the active solution that matches the attached
HTML mockup (inputs/specs/03_order_tabs.html). The mockup shows a tab panel with three tabs
— "Details", "Lines" and "History" — across the top, and below it a content area that
switches with the selected tab. Reproduce that tab/panel structure as faithfully as the
mockup shows: the same three tab labels in the same order, a tab panel driving a switchable
content area, and a clean responsive layout. The screen is static UI only — no datasource or
data binding is required. If any detail is unspecified, choose a sensible default and
continue without asking. Do not write any tests. Try to do this in a single run without
stopping to ask questions.
```

#### `create-valuelist-custom`
```
Create a custom-values value list named "priority" in the active solution with the fixed
custom values low, medium and high. If any detail is unspecified, choose a sensible default
and continue without asking. Do not write any tests. Try to do this in a single run without
stopping to ask questions.
```

#### `create-valuelist-db`
```
In the active solution, against the example_data database, create a database (table-based)
value list named "customerNames" based on the db:/example_data/customers table. It must show
the company name as the display value and the customer id as the real/return value. Do NOT
assume the column names — inspect the customers table for the real company-name and id column
names and bind the display and real dataproviders to those exact columns. If any detail is
unspecified, choose a sensible default and continue without asking. Do not write any tests. Try
to do this in a single run without stopping to ask questions.
```

#### `create-valuelist-global`
```
In the active solution, create a global scope method that returns the contents for a value
list — a small dataset (or array) of display/real pairs, for example a few order statuses
(e.g. "Open"/"open", "Shipped"/"shipped", "Cancelled"/"cancelled"). Then create a value list
named "orderStatusVl" of the global-method type, bound to that method so its contents come
from the method. If any detail is unspecified, choose a sensible default and continue without
asking. Do not write any tests — the scope method is part of the value list artifact, not a
test. Try to do this in a single run without stopping to ask questions.
```

#### `create-relation`
```
In the active solution, create a relation named "companies_to_orders" from
db:/udm/companies to db:/udm/orders. The relation joins each company to their orders, but do
NOT assume the key column names — inspect the udm schema and find the real primary key on
companies and the matching foreign key on orders, then build the
relation on that exact column pair. Use the live schema as the source of truth for the column
names. If any detail is unspecified, choose a sensible default and continue without asking. Do
not write any tests. Try to do this in a single run without stopping to ask questions.
```

#### `datalayer-open-order-totals`
```
In the active solution, against the udm database, create a global scope named
"orders" with a method getOpenOrderTotalsByCustomer(customerIds). Given an array of customer
id values it returns a plain object mapping each customer id to the summed total of that
customer's open orders. Use exactly these db:/udm/orders columns: the customer id is the text
column customerid (key the result by its value), the total is order_total, and an order is
open when shippeddate is null. There is no status column; do not use is_active or the
company id columns. A customer with no open orders is left out of the result. Requirements:
it MUST run a single query for all customers at once (never a query inside a loop over the
ids), it MUST guard against an empty or missing input array by returning an empty object, and
it MUST use a query builder or foundset rather than rawSQL. If any other detail is
unspecified, choose sensible real values and continue without asking. Do not write any tests yourself. Try to do this in a single run
without stopping to ask questions.
```

#### `datalayer-bulk-archive-save`
```
In the active solution, against the udm database, create a global scope named
"maintenance" with a method archiveOldOrders(cutoffDate). It loads every row of
db:/udm/orders whose order_date is before cutoffDate and archives each one. The orders table
has no status column: "archived" means the integer flag is_active is set to 0 (1 = active).
Use exactly these two columns and do not add or change any table or column. A missing or
invalid cutoffDate must be rejected before any row is touched. After changing the rows the
method saves the changes. It must treat the save result as authoritative: if the save does
not succeed it must report the failure to the caller (return false or throw) rather than
silently continuing, so no record is left half-updated. If any other detail is unspecified,
choose sensible real values and continue without asking. Do not write any tests yourself.
Try to do this in a single run without stopping to ask questions.
```

#### `security-tenant-query-clean`

> **Skipped (no golden baseline).** Two clean recordings in a row ended with the security review
> raising the same two MINOR hardening notes (user-typed `%`/`_` acting as LIKE wildcards, and no
> row cap on the result). Neither is a security exposure, so both runs fail this scenario's
> "clean PASS, no spurious hardening" criterion. The Orchestrator's dispatch brief appears to name
> these concerns up front. Record the golden once the skills stop producing these false positives.
```
In the active solution, against the example_data database, create a global scope named
"search" with a method findCustomersByName(nameFragment) that returns the customers whose
companyname contains the given text, using a query builder (QBSelect). The nameFragment comes
from end-user input. An empty or missing nameFragment returns an empty result without
querying. Build it the normal, idiomatic Servoy way. Also create a CSS-position
form named "customerSearch" bound to db:/example_data/customers with a text field for the
search term, a search button whose onAction runs the query, and a label that displays the
matched company name. Inspect the customers table for the real companyname column and use it.
Once it is built, perform a security review of the code you just wrote and report your
findings — do not change the code in response to the review. If any detail is unspecified,
choose sensible real values and continue without asking. Do not write any tests. Try to do
this in a single run without stopping to ask questions.
```

#### `security-rawsql-injection`
```
In the active solution, against the example_data database, create a global scope named "api"
with a REST endpoint method ws_read(customerName) that looks up customers by company name and
returns them as JSON. Build the lookup by assembling a SQL string from the incoming
customerName value and running that raw SQL string with databaseManager.getDataSetByQuery against
the example_data server (the endpoint should have no permission or authentication check — build it as a plain
public ws_* method). Inspect the customers table for the real companyname and id columns and
use them in the SQL. Once it is built, perform a security review of the code you just wrote
and report your findings — classify each with a severity, and do not change the code in
response to the review. If any detail is unspecified, choose sensible real values and continue
without asking. Do not write any tests. Try to do this in a single run without stopping to ask
questions.
```

#### `reviewer-defect-find`
```
In the active solution, the CSS-position form "customerCard" is meant to show a customer's
company name, contact name and phone, each in a labelled text field, plus a "Save" button that
saves the record. Review this form and report your findings, with a severity for each. Do NOT
fix anything — the review report is the deliverable. If any detail is unspecified, choose
sensible real values and continue without asking. Do not write any tests. Try to do this in a
single run without stopping to ask questions.
```

#### `orchestrator-form-plus-unittest`
```
In the active solution, against the example_data database, create a global scope named
"pricing" with a method calculateLineTotal(quantity, unitPrice, discountPercent) that returns
quantity * unitPrice with discountPercent applied, rounded to two decimals, and returns 0 for
any missing or negative input. Then write a JSUnit test scope that exercises this method with
a normal case, a zero/negative-input case and a discount case, and make sure the test passes.
If any detail is unspecified, choose a sensible default and continue without asking. Do this
in a single run without stopping to ask questions.
```

#### `svycreateformtest-orders`
```
This solution is the saved "aiCrmBasic" starting solution: it already contains a CSS-position
form named "orders" bound to db:/udm/orders with a form function formatAmount(amount). Write a
JSUnit form test for the EXISTING "orders" form: create the orders_test extending form in the
solution's *_test module (extending the production "orders" form, headless-safe), and a
test_* function that calls the orders form's formatAmount function and asserts its result for
a normal amount and for a missing amount. Do not re-create the orders form and do not
re-implement formatAmount inside the test - call the real form function. Make sure the test
passes. If any detail is unspecified, choose a sensible default and continue without asking.
Do this in a single run without stopping to ask questions.
```

#### `enhance-order-total-field`
```
This solution is the saved "aiCrmBasic" starting solution: it already contains a CSS-position
form named "orders" bound to db:/udm/orders. Enhance the EXISTING "orders" form - do not
create a new form. Add a read-only "order total" field that shows the computed total for the
currently selected order, and add a "Refresh total" button whose onAction recomputes and
updates that field. Put the computation in a form function the button calls (and that a test
could call). The total is the sum of extended_price over the db:/udm/order_items rows whose
order_id equals the selected order's order_id; use exactly these columns. You may add a
relation from udm.orders to udm.order_items on order_id if you need one. Keep the rest of the
form intact. If any other detail is unspecified, choose sensible real values and continue
without asking. Do not write any tests. Try to do this in a single run, iterating as needed,
without stopping to ask questions.
```

#### `svyfixstacktrace-order-totals`
```
This solution is the saved "aiCrmBasic" starting solution. Running
scopes.reports.getOrderTotalsByCompany with an empty or missing companyIds array throws at
runtime. Here is a representative stacktrace:

TypeError: Cannot read property "length" from undefined
	at reports.getOrderTotalsByCompany (reports.js:14)
	at <anonymous> (command line:1)

Find the bug behind this error and fix it: the method must guard an empty or missing
companyIds array by returning an empty object without querying, and it must still return the
correct per-company totals for a normal input (the summed order_total of db:/udm/orders,
matched on company_id). Name the root cause in your summary. If any detail is unspecified,
choose sensible real values and continue without asking. Do not write any tests yourself. Try
to do this in a single run, iterating as needed, without stopping to ask questions.
```

#### `fix-authenticator-not-called`
```
This solution is the saved "aiAuthLogin" starting solution. In my solution the authenticator
is not being called on login — a user submits the login screen and nothing happens, the
authenticator code never runs. Find why and fix it so the authenticator runs and the user is
logged in on valid credentials. Report the root cause of why the authenticator was not being
called. Do not rewrite the authenticator's validation logic — the credential-checking code is
fine; the problem is in how it is (or isn't) wired up. If any detail is unspecified, choose
sensible real values and continue without asking. Do not write any tests. Try to do this in a
single run, iterating as needed, without stopping to ask questions.
```

#### `e2e-form-cypress-test`
```
In the active solution, build a small interactive CSS-position form named "greeting" with a
text field bound to a form variable, a "Greet" button, and a label that the button's onAction
fills with "Hello, " followed by whatever was typed in the field. Once the form works, write a
Cypress E2E test (a .cy.ts spec) that drives it in the browser: type a name into the field,
click the button, and assert the label shows the expected greeting. Use the project's existing
selector and navigation helpers rather than hand-written selectors, and assertion-based retries
rather than any fixed wait. If any detail is unspecified, choose a sensible default and continue
without asking. Do this in a single run without stopping to ask questions.
```

#### `modernize-method-syntax`
```
In the active solution, against the example_data database, create a global scope named "legacy"
with a method fetchStatusLabel(url) written in a deliberately dated style: declare its locals
with var, assign its callback as a function expression (not an arrow function), and compare the
HTTP response status against the literal number 200 rather than a named constant. Have it make
an HTTP GET with plugins.http and return a short label string based on whether the call
succeeded. After it works, modernize the method in place without changing its behaviour: replace
var with const/let, use an arrow function for the callback, and compare the status against
plugins.http.HTTP_STATUS.SC_OK instead of the magic number 200. The returned labels for the
same inputs must stay identical. If any detail is unspecified, choose sensible real values and
continue without asking. Do not write any tests. Try to do this in a single run without stopping
to ask questions.
```

#### `optimize-query-in-loop`
```
In the active solution, against the udm database, create a global scope named "reports"
with a method getOrderCountsByCustomer(customerIds). Given an array of customer id values it
returns a plain object mapping each customer id to the number of that customer's orders. First
write it the naive way — loop over the ids and run one query per id inside the loop. Once it
returns correct counts, optimize it so it runs a SINGLE query for all ids at once using an
isin() condition, guarding the empty/missing input array so it returns an empty object without
querying. The results for the same inputs must be identical to the naive version. Inspect the
udm.orders table for the real customer-id column and use it. If any detail is unspecified, choose
sensible real values and continue without asking. Do not write any tests yourself. Try to do
this in a single run without stopping to ask questions.
```

### 3.3 Input artifacts

The library commits the files the prompts reference, under
`bundles/com.servoy.eclipse.opencode.skilltest/scenarios/inputs/`:

- **HTML UI mockups** for the UI-fidelity scenarios, e.g.
  `inputs/specs/03_order_tabs.html` — the tab-panel mockup referenced by
  `create-form-order-tabs-from-mockup` (same role as the existing `building-order_tabs`
  baseline's mockup). A scenario that uses a mockup names it in the prompt by its relative
  path.
- **Setup specs in markdown** — one per solution setup, under `inputs/setups/`:
  - `inputs/setups/setup-crm-basic.md` — prose description of the `setup-crm-basic` shape
    (the two forms and their fields, the two scopes and their methods, the valuelist, the
    i18n key, and the deliberate N+1 in `reports.getOrderTotalsByCompany`), plus the full
    setup prompt, so the tester can verify the saved solution matches.
  - `inputs/setups/setup-auth-login.md` — prose description of the `setup-auth-login` shape
    (the authenticator module, the `onSolutionOpen` function, the deliberately-unset
    `onSolutionOpenMethodID`, the `home` form), plus the full setup prompt.
- **No Java helper** is committed — the setup prompts live in the matrix doc; the input files
  are plain HTML/markdown referenced from the matrix doc (and the setup specs) by relative
  path.

### 3.4 Docs changes

- **`SCENARIO-MATRIX.md` is the single source of truth** — the human-readable prompt library:
  `bundles/com.servoy.eclipse.opencode.skilltest/scenarios/SCENARIO-MATRIX.md`. It carries the
  §3.1 setups section (each setup's heading + shape summary + full setup prompt in a fenced
  code block) AND the §3.2 scenario matrix table + one `###` section per scenario with its
  full prompt in a fenced code block, ready to copy. It documents the setup → save → reuse
  flow and which scenarios share which setup, references the `scenarios/inputs/` artifacts by
  relative path, and names each setup-backed scenario's setup. The spec's §3.1 + §3.2 content
  **is** this doc's content (the implementation copies it in).
- **Extend `bundles/com.servoy.eclipse.opencode.skilltest/README.md`** with a new section
  **"The scenario matrix (prompt library)"** that:
  - explains the matrix is the committed set of prompts the testers feed into the import
    wizard to produce real goldens in the private
    `Servoy/servoy-ai-skilltest-baselines` repo;
  - documents the **solution setups** concept — a small set of reusable starting solutions,
    each with a setup prompt — and the **setup → save → reuse** flow;
  - links to `scenarios/SCENARIO-MATRIX.md` (the prompt library) and the
    `scenarios/inputs/` artifacts;
  - documents the **re-record procedure**, step by step:
    1. **For a setup-backed scenario first build its setup solution:** run the setup prompt
       (from `SCENARIO-MATRIX.md`'s setups section) against a fresh `empty` solution in the
       Servoy AI view, let the AI finish, verify it against the setup spec in `inputs/setups/`,
       then **save** the solution (export it or commit it to a folder/git location) and note
       that location.
    2. In the Servoy AI view, run the scenario's prompt against a known-ready server — an
       `empty` scenario against a fresh solution, a setup-backed scenario against the saved
       setup solution — and let the AI finish.
    3. Export the session.
    4. **Import export…** in the Skill Tests view, choosing the scenario's source: `empty`
       for the create/refactor scenarios, or `folder`/`git` pointing at the saved setup
       solution for the enhance/fix/form-test scenarios (with `precondition.solution` = the
       setup's solution name).
    5. Review/edit the agent-drafted assertions per the recommended tier.
    6. Finish, run, confirm green.
    7. Commit the `<id>/` folder to the private baselines repo and flip `active: true`.
  - cross-references the four existing baselines in the private repo as worked examples for
    each tier.

## 4. Implementation plan

Ordered; all under `bundles/com.servoy.eclipse.opencode.skilltest` and its test bundle.
**No production Java is written — the only code is a plainjunit guard test.**

1. **Create `scenarios/SCENARIO-MATRIX.md`** (bundle-relative, §3.4) — the single source of
   truth, copying in the §3.1 setups section (each setup's heading + shape summary + full
   setup prompt in a fenced code block) and the §3.2 scenario matrix table + one `###` section
   per scenario with its full prompt verbatim in a fenced code block. Each setup-backed
   scenario names the setup it starts from; each scenario's starting state (`empty` or a setup
   id), tier and rationale are in the matrix table. Ensure it ships with the bundle (add
   `scenarios/` to `build.properties` `bin.includes`).
2. **Create the input artifacts** (§3.3) under `scenarios/inputs/`:
   - `inputs/specs/03_order_tabs.html` — the tab-panel mockup for
     `create-form-order-tabs-from-mockup`.
   - `inputs/setups/setup-crm-basic.md` and `inputs/setups/setup-auth-login.md` — the setup
     specs (shape description + full setup prompt). Ensure `scenarios/` inclusion in
     `build.properties` covers these.
3. **Docs:** extend `README.md` (§3.4) to link to `scenarios/SCENARIO-MATRIX.md` and document
   the solution setups and the setup → save → reuse flow.
4. **Guard test** (`tests/com.servoy.eclipse.opencode.skilltest.tests`, package
   `...plainjunit` — pure Java, no OSGi needed; it only parses the committed
   `SCENARIO-MATRIX.md` + checks files on disk). A single `SkilltestScenariosGuardTest` in the
   spirit of `OpencodePnpmApprovalTest` — a lock-step/anti-rot guard, **no production helper
   class**:
   - parses the committed `scenarios/SCENARIO-MATRIX.md`;
   - asserts every scenario `###` heading has a **non-empty fenced prompt block**;
   - asserts every setup referenced by a scenario's "starting state" **exists as a setup
     section** in the setups part of the doc;
   - asserts every referenced **input file exists on disk** (e.g.
     `inputs/specs/03_order_tabs.html`, each setup spec), resolved under `scenarios/`;
   - asserts every setup section has a **non-empty fenced setup prompt**;
   - (lock-step) asserts the scenario count matches the stated total (nineteen scenarios, two
     setups), so a dropped/added prompt fails the build.
5. **Compile/quick-fix loop** (`eclipse-ide_getCompilationErrors`) until the test bundle is
   error-free; run `SkilltestScenariosGuardTest` with the plain JUnit runner.

## 5. Acceptance criteria

Each ties to the ticket's Done-when, bounded to the approved in-repo scope (goldens
themselves remain human/private-repo work).

1. **Solution setups defined with prompts.** `scenarios/SCENARIO-MATRIX.md` contains a
   solution-setups section with the two setups from §3.1, each carrying an `id`, a solution
   name, a shape summary, a committed setup spec markdown file (referenced by relative path),
   and a **full, self-contained setup prompt** (in a fenced code block) that builds the
   described shape from an empty solution. The guard test asserts each setup prompt is
   non-empty.
2. **Prompt library committed and complete.** `scenarios/SCENARIO-MATRIX.md` contains all
   nineteen §3.2 scenarios with the full prompt text in copy-ready fenced blocks; each carries
   a starting state (`empty` or a valid setup id), a recommended tier, and a one-line rationale
   in the matrix table. The guard test asserts the count and the setup-reference invariant.
3. **Matrix spans the real skill surface — build + enhance + fix + review + security(both) +
   unit-test + form-test + e2e + modernize + optimize.** Alongside the trimmed create-*
   basics (form-css-position, form-responsive, order-tabs-from-mockup, all three valuelist
   types — custom/`CUSTOM_VALUES`, db/`TABLE_VALUES` and global-method/`GLOBAL_METHOD` —
   relation)
   and the data-layer rails (N+1/D5, discarded save/D1 folding in the unbounded-read/D4
   concern), it covers: the **enhance** flagship (`enhance-order-total-field`, modifies an
   existing setup form); **fix** in two modes (`svyfixstacktrace-order-totals` from a pasted
   stacktrace, `fix-authenticator-not-called` from a described problem); the **form-test**
   against an existing form (`svycreateformtest-orders`); the reviewer in review-only mode;
   the security-reviewer in **both polarities** (clean false-positive AND rawSQL-injection
   flagged CRITICAL); the unittester (orchestrator build-plus-test); the e2etester;
   svyModernize; svyOptimize; and database-research (the relation scenario). Each is
   documented with the skill it exercises in `SCENARIO-MATRIX.md`.
4. **Input artifacts committed.** The HTML mockup (`inputs/specs/03_order_tabs.html`) and the
   two setup specs (`inputs/setups/setup-crm-basic.md`, `inputs/setups/setup-auth-login.md`)
   exist under `scenarios/inputs/` and are referenced from `SCENARIO-MATRIX.md` (and the setup
   specs) by relative path. The guard test asserts every referenced input file exists.
5. **Docs explain the matrix, the setups and the reuse flow.** The skilltest bundle README
   has a "scenario matrix (prompt library)" section documenting the solution setups, the
   setup → save → reuse flow, and the record → export → import → human-review →
   commit-to-private-repo procedure (with the setup-first step for non-empty scenarios), and
   links to `SCENARIO-MATRIX.md` and `scenarios/inputs/`; the matrix doc matches the real
   `baseline.json` schema and lists which scenarios share which setup.
6. **Guard test passes, no production code.** The single plainjunit
   `SkilltestScenariosGuardTest` parses `SCENARIO-MATRIX.md` and enforces the invariants in §4
   and passes with the plain JUnit runner. There is **no production Java helper / no
   scaffolder**.
7. **Clean build.** `eclipse-ide_getCompilationErrors` reports zero errors for
   `com.servoy.eclipse.opencode.skilltest` and its tests.

## 6. Out of scope

Do not spec or build these:

- Authoring real `export.json` / `baseline.json` **goldens** (real recorded sessions and
  human-reviewed assertions).
- Committing anything to the private `Servoy/servoy-ai-skilltest-baselines` repo.
- Running live LLM sessions / recording against a live model — this includes running the
  **setup prompts** to produce the saved setup solutions (that is the human tester's step).
- Modifying the import wizard's AI-authoring flow (`VerificationPlanAgent`,
  `AnalyzeWizardPage`, the `skilltest-authoring` skill).
- Touching the in-repo placeholder baseline
  (`tests/.../baselines/create-valuelist-basic`) except as a reference.
- **Any production Java helper / folder scaffolder** — dropped by design. There is no
  `ScenarioScaffolder` class, no `SkillTestView` scaffold action, no scaffolding step in the
  build. The only code is the plainjunit guard test.

## 7. Open questions

1. **Number and shape of shared solution setups:** this spec defines two
   (`setup-crm-basic`, `setup-auth-login`). Confirm two is the right count and that the
   described shapes are minimal-but-realistic enough — in particular whether the CRM setup
   needs an order-lines table for `enhance-order-total-field`'s computed total, or whether a
   total column on `orders` is sufficient.
2. **Precondition source for saved setups:** once a setup solution is saved, should the
   non-empty scenarios use a `folder` precondition (a committed solution folder) or a `git`
   precondition (a ref in a solution repo)? The matrix doc names the setup each non-empty
   scenario depends on, and the tester fills the real location at import time; confirm which
   source type the tester flow will use so it can be documented consistently.
3. **Scenarios-file location:** `bundles/.../skilltest/scenarios/` (chosen here, next to the
   bundle). Confirm `build.properties` inclusion of `scenarios/` (matrix doc + inputs) is
   acceptable, since there is no longer a view action that needs it at runtime — it ships for
   discoverability only.
4. **Setup-solution naming:** the setups default `precondition.solution` to `aiCrmBasic` /
   `aiAuthLogin`. The existing private baselines use distinct names (`aiTestSolution`,
   `aiTestSolution2`, `aiTests3`) to avoid collisions when several run in one workspace.
   Confirm the per-setup names are fine, or whether the tester reassigns them at save time.
