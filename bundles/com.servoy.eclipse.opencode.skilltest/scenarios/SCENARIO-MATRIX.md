# Servoy AI skill-test scenario matrix (prompt library)

This is the single, human-readable source of truth for the SVY-21659 golden
baseline prompt library: the committed set of **prompts a tester copy-pastes
straight into Servoy AI** to produce real goldens for the private
`Servoy/servoy-ai-skilltest-baselines` repo. There is no JSON here on purpose — a
JSON file is not copy-pasteable, so every prompt lives as a fenced code block you
can copy verbatim.

The library has three parts:

1. **Solution setups** — a small set of reusable starting solutions, each with a
   self-contained setup prompt that builds it from an empty solution. Run a setup
   prompt once, save the result, and reuse it across every scenario that needs
   pre-existing code to enhance or fix.
2. **The scenario matrix** — twenty scenarios spanning the full skill surface:
   build, **enhance**, **fix** (described problem AND stacktrace), review,
   security-review (both polarities), unit-test, **form-test**, E2E, modernise and
   optimise. Each carries its starting state (`empty` or a setup id), a recommended
   verification tier and a one-line rationale, followed by its full copy-ready
   prompt.
3. **Input artifacts** — the HTML mockups and setup-spec markdown the prompts
   reference, under [`inputs/`](inputs/).

See the bundle [`README.md`](../README.md) → "The scenario matrix (prompt
library)" for the full record → export → import → review → commit procedure.

---

## 1. Solution setups

A **Solution Setup** is a named reusable starting solution plus the self-contained
prompt that builds it from an empty solution. The tester runs a setup prompt
**once** against a fresh `empty` solution, lets the agent finish, then **saves**
the resulting solution (exports it, or commits it to a folder/git location).
Scenarios that need pre-existing code to enhance or fix point their
`precondition.source` at that saved solution and set `precondition.solution` to
the setup's solution name. **One setup is reused by several scenarios**, so the
pre-built shape is authored and reviewed once.

| id | purpose | shape summary | scenarios that use it |
|---|---|---|---|
| `setup-crm-basic` | A small CRM to enhance / fix / form-test against. | Solution `aiCrmBasic`, bound to `db:/udm`. A CSS-position form `companies` bound to `db:/udm/companies` (fields for company name, email, website) and a CSS-position form `orders` bound to `db:/udm/orders` (fields for order id, order date, order total and the paid flag, plus a form function `formatAmount(amount)`). A global scope `reports` with `getOrderTotalsByCompany(companyIds)` (deliberately written as an unguarded query-in-a-loop so the fix/optimize scenarios have real N+1 code and a real crash) and a scope `pricing` with `calculateLineTotal(quantity, unitPrice, discountPercent)`. A custom-values valuelist `orderPaidStatus` (Open = 0, Paid = 1) on the paid field. One i18n key `crm.orders.title`. | `svyfixstacktrace-order-totals`, `svycreateformtest-orders`, `enhance-order-total-field` |
| `setup-auth-login` | A small solution with authentication wired but **broken on purpose**, for the described-problem fix scenario. | Solution `aiAuthLogin`, bound to `db:/example_data`. Configured as AUTHENTICATOR (type 3) with an authenticator **module** `aiAuthLogin_auth` whose `globals.js` holds a correct `onSolutionOpen(arg, queryParams)` that validates credentials and calls `security.login()`. The planted defect: the module's `solution_settings.obj` **does NOT set `onSolutionOpenMethodID`** to that function's UUID, so the runtime never calls it and login silently fails. A main-solution form `home` bound to `db:/example_data/customers` so there is something to land on after login. | `fix-authenticator-not-called` |
| `setup-review-defects` | A form with two deliberate runtime defects, for the review-only scenario. | Solution `aiReviewDefects`, referencing `servoy_resources`. One CSS-position form `customerCard` bound to `db:/example_data/customers`: a label plus a `bootstrapcomponents-textbox` for company name (`companyname`), contact name (`contactname`) and phone, and a `Save` button. The two planted defects: the phone field binds to `phone_number`, which does not exist (the column is `phone`), and the button's `onActionMethodID` is a UUID that no method has (the form has no `.js`). Everything else is valid, so the review has exactly these two things to find. Committed in the baselines repo under `solutions/aiReviewDefects`. | `reviewer-defect-find` |

> **Why these setups.** `setup-crm-basic` is the workhorse: a couple of data-bound
> forms, two scope methods (one intentionally N+1), a valuelist and an i18n key
> give the enhance/fix/form-test scenarios genuine existing structure to read and
> change. `setup-auth-login` isolates one realistic, described (not stacktrace)
> defect — an authenticator function that exists but is never wired as the
> module's `onSolutionOpen` handler, which per the authentication guide is exactly
> the "function exists but is never called by the runtime" trap — so the fix
> scenario tests diagnosis, not just editing.
> `setup-review-defects` is the odd one out: it is committed, not built by a prompt, so the
> review scenario always sees the same two planted defects.

Each setup also has a committed **setup spec** markdown file describing its shape
in prose (for the human tester):
[`inputs/setups/setup-crm-basic.md`](inputs/setups/setup-crm-basic.md) and
[`inputs/setups/setup-auth-login.md`](inputs/setups/setup-auth-login.md).

#### 1b. `aiSkilltest_empty` (committed empty solution + resources project)

Scenarios that touch the `udm` database cannot start from the runner's generated
`empty` solution, because its resources project only knows `example_data`. They
start from a committed, otherwise empty solution instead:

- Solution `aiSkilltest_empty`, referencing the resources project `servoy_resources`.
  Both live in the baselines repo under `solutions/`, together with the `.dbi`
  schemas of the `example_data` and `udm` servers (no `security.sec`, no
  `ng_web_packages`: the runner plants the web packages itself).
- A baseline selects it with `precondition.solution = "aiSkilltest_empty"` and a
  `git` source pointing at the baselines repo itself
  (`https://github.com/Servoy/servoy-ai-skilltest-baselines`, `ref: master`). The runner
  clones it and imports the solution plus `servoy_resources`.

Scenarios that start from it: `create-form-css-position`, `create-valuelist-db`,
`create-relation`, `datalayer-open-order-totals`, `datalayer-bulk-archive-save`,
`security-tenant-query-clean`, `security-rawsql-injection`,
`orchestrator-form-plus-unittest` and `optimize-query-in-loop` - every scenario whose prompt uses
a database. Database-free scenarios still start from `empty`.

### setup-crm-basic

Starting solution: `aiCrmBasic`. Spec:
[`inputs/setups/setup-crm-basic.md`](inputs/setups/setup-crm-basic.md).

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

### setup-auth-login

Starting solution: `aiAuthLogin`. Spec:
[`inputs/setups/setup-auth-login.md`](inputs/setups/setup-auth-login.md).

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

---

### setup-review-defects

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
## 2. The scenario matrix

Twenty scenarios, deliberately spanning **build, enhance, fix, review,
security-review (both polarities), unit-test, form-test, E2E, modernise and
optimise** — the full skill surface the pack ships, including all three valuelist
types (`CUSTOM_VALUES`, `TABLE_VALUES`, `GLOBAL_METHOD`). The "starting state"
column names `empty` (the runner creates a fresh solution), `aiSkilltest_empty`
(the committed empty solution with its resources project, §1b) or a Solution Setup
id from §1 (the tester points the precondition at the saved setup solution).

**For an `empty` scenario that must act on EXISTING code, the prompt builds that
code first, in the same run, then invokes the skill on it** — this keeps the
pure-create and refactor-in-one-run baselines reproducible from a cold solution.
**For a setup-backed scenario, the pre-existing code already lives in the saved
setup solution**, so the prompt reads and changes it directly. For the review-only
and security-review scenarios the closing instruction is to **perform the review
and report findings, not to fix** — the Reviewer / Security-Reviewer finding is
what the baseline grades.

Tiers are *recommendations* the tester confirms at record time, following the
`skilltest-authoring` rule: deterministic artifact → `persists`; visual/layout
fidelity or a review/fix verdict → acceptance-criteria judge; behaviour that must
run → JSUnit.

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

---

## 3. Scenario prompts

Each heading below carries the full, copy-ready prompt for one scenario, in the
same order as the matrix table. Setup-backed scenarios open by naming the saved
setup solution as their starting point.

### create-form-css-position

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

```
Create a new CSS-position form named "productDetail" in the active solution, bound to the
data source db:/example_data/products. Place two text fields on it: one bound to
dataprovider "productname", one bound to "unitprice". If any detail is unspecified, choose
a sensible default and continue without asking. Do not write any tests. Try to do this in a
single run without stopping to ask questions.
```

### create-form-responsive

Starting state: `empty`.

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

### create-form-order-tabs-from-mockup

Starting state: `empty`. Input mockup:
[`inputs/specs/03_order_tabs.html`](inputs/specs/03_order_tabs.html).

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

### create-valuelist-custom

Starting state: `empty`.

```
Create a custom-values value list named "priority" in the active solution with the fixed
custom values low, medium and high. If any detail is unspecified, choose a sensible default
and continue without asking. Do not write any tests. Try to do this in a single run without
stopping to ask questions.
```

### create-valuelist-db

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

```
In the active solution, against the example_data database, create a database (table-based)
value list named "customerNames" based on the db:/example_data/customers table. It must show
the company name as the display value and the customer id as the real/return value. Do NOT
assume the column names — inspect the customers table for the real company-name and id column
names and bind the display and real dataproviders to those exact columns. If any detail is
unspecified, choose a sensible default and continue without asking. Do not write any tests. Try
to do this in a single run without stopping to ask questions.
```

### create-valuelist-global

Starting state: `empty`.

```
In the active solution, create a global scope method that returns the contents for a value
list — a small dataset (or array) of display/real pairs, for example a few order statuses
(e.g. "Open"/"open", "Shipped"/"shipped", "Cancelled"/"cancelled"). Then create a value list
named "orderStatusVl" of the global-method type, bound to that method so its contents come
from the method. If any detail is unspecified, choose a sensible default and continue without
asking. Do not write any tests — the scope method is part of the value list artifact, not a
test. Try to do this in a single run without stopping to ask questions.
```

### create-relation

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

```
In the active solution, create a relation named "companies_to_orders" from
db:/udm/companies to db:/udm/orders. The relation joins each company to their orders, but do
NOT assume the key column names — inspect the udm schema and find the real primary key on
companies and the matching foreign key on orders, then build the
relation on that exact column pair. Use the live schema as the source of truth for the column
names. If any detail is unspecified, choose a sensible default and continue without asking. Do
not write any tests. Try to do this in a single run without stopping to ask questions.
```

### datalayer-open-order-totals

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

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

### datalayer-bulk-archive-save

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

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

### security-tenant-query-clean

> **Skipped (no golden baseline).** Two clean recordings in a row ended with the security review
> raising the same two MINOR hardening notes (user-typed `%`/`_` acting as LIKE wildcards, and no
> row cap on the result). Neither is a security exposure, so both runs fail this scenario's
> "clean PASS, no spurious hardening" criterion. The Orchestrator's dispatch brief appears to name
> these concerns up front. Record the golden once the skills stop producing these false positives.

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

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

### security-rawsql-injection

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

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

### reviewer-defect-find

Starting state: `setup-review-defects` (solution `aiReviewDefects`).

```
In the active solution, the CSS-position form "customerCard" is meant to show a customer's
company name, contact name and phone, each in a labelled text field, plus a "Save" button that
saves the record. Review this form and report your findings, with a severity for each. Do NOT
fix anything — the review report is the deliverable. If any detail is unspecified, choose
sensible real values and continue without asking. Do not write any tests. Try to do this in a
single run without stopping to ask questions.
```

### orchestrator-form-plus-unittest

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

```
In the active solution, against the example_data database, create a global scope named
"pricing" with a method calculateLineTotal(quantity, unitPrice, discountPercent) that returns
quantity * unitPrice with discountPercent applied, rounded to two decimals, and returns 0 for
any missing or negative input. Then write a JSUnit test scope that exercises this method with
a normal case, a zero/negative-input case and a discount case, and make sure the test passes.
If any detail is unspecified, choose a sensible default and continue without asking. Do this
in a single run without stopping to ask questions.
```

### svycreateformtest-orders

Starting state: `setup-crm-basic` (solution `aiCrmBasic`).

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

### enhance-order-total-field

Starting state: `setup-crm-basic` (solution `aiCrmBasic`).

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

### svyfixstacktrace-order-totals

Starting state: `setup-crm-basic` (solution `aiCrmBasic`).

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

### fix-authenticator-not-called

Starting state: `setup-auth-login` (solution `aiAuthLogin`).

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

### e2e-form-cypress-test

Starting state: `empty`.

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

### modernize-method-syntax

Starting state: `empty`.

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

### optimize-query-in-loop

Starting state: `aiSkilltest_empty` (committed solution, see §1b).

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
