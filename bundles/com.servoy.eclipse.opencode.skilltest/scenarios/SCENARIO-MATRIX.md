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
| `setup-crm-basic` | A small CRM to enhance / fix / form-test against. | Solution `aiCrmBasic`, bound to `db:/example_data`. A CSS-position form `customers` bound to `db:/example_data/customers` (fields for company name, contact name, phone) and a CSS-position form `orders` bound to `db:/example_data/orders` (fields for order id, status, order date, a total field). A global scope `reports` with `getOrderTotalsByCustomer(customerIds)` (deliberately written as a query-in-a-loop so the fix/optimize scenarios have real N+1 code) and a scope `pricing` with `calculateLineTotal(quantity, unitPrice, discountPercent)`. A custom-values valuelist `orderStatus` (open, archived, cancelled). One i18n key `crm.orders.title`. | `svyfixstacktrace-order-totals`, `svycreateformtest-orders`, `enhance-order-total-field` |
| `setup-auth-login` | A small solution with authentication wired but **broken on purpose**, for the described-problem fix scenario. | Solution `aiAuthLogin`, bound to `db:/example_data`. Configured as AUTHENTICATOR (type 3) with an authenticator **module** `aiAuthLogin_auth` whose `globals.js` holds a correct `onSolutionOpen(arg, queryParams)` that validates credentials and calls `security.login()`. The planted defect: the module's `solution_settings.obj` **does NOT set `onSolutionOpenMethodID`** to that function's UUID, so the runtime never calls it and login silently fails. A main-solution form `home` bound to `db:/example_data/customers` so there is something to land on after login. | `fix-authenticator-not-called` |

> **Why these two.** `setup-crm-basic` is the workhorse: a couple of data-bound
> forms, two scope methods (one intentionally N+1), a valuelist and an i18n key
> give the enhance/fix/form-test scenarios genuine existing structure to read and
> change. `setup-auth-login` isolates one realistic, described (not stacktrace)
> defect — an authenticator function that exists but is never wired as the
> module's `onSolutionOpen` handler, which per the authentication guide is exactly
> the "function exists but is never called by the runtime" trap — so the fix
> scenario tests diagnosis, not just editing.

Each setup also has a committed **setup spec** markdown file describing its shape
in prose (for the human tester):
[`inputs/setups/setup-crm-basic.md`](inputs/setups/setup-crm-basic.md) and
[`inputs/setups/setup-auth-login.md`](inputs/setups/setup-auth-login.md).

### setup-crm-basic

Starting solution: `aiCrmBasic`. Spec:
[`inputs/setups/setup-crm-basic.md`](inputs/setups/setup-crm-basic.md).

```
Build a small CRM starting solution in the active (empty) solution, bound to the
example_data database. Create two CSS-position forms: a form named "customers" bound to
db:/example_data/customers with text fields for the company name, the contact name and the
phone number (inspect the customers table for the real column names and bind to those); and
a form named "orders" bound to db:/example_data/orders with fields for the order id, the
status, the order date and the order total (again inspect the orders table for the real
column names). Create a custom-values value list named "orderStatus" with the values open,
archived and cancelled. Add one i18n key crm.orders.title with the English value "Orders".
Then create two global scopes. In a scope named "reports", add a method
getOrderTotalsByCustomer(customerIds): given an array of customer id values it returns a
plain object mapping each customer id to the summed total of that customer's orders — write
it the naive way, looping over the ids and running one query per id inside the loop (this
is intentional; later tasks will optimize it). In a scope named "pricing", add a method
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

## 2. The scenario matrix

Twenty scenarios, deliberately spanning **build, enhance, fix, review,
security-review (both polarities), unit-test, form-test, E2E, modernise and
optimise** — the full skill surface the pack ships, including all three valuelist
types (`CUSTOM_VALUES`, `TABLE_VALUES`, `GLOBAL_METHOD`). The "starting state"
column names either `empty` (the runner creates a fresh solution) or a Solution
Setup id from §1 (the tester points the precondition at the saved setup solution).

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
| `create-form-css-position` | Create a CSS-position form bound to a datasource with two bound text fields | servoy-developer (form build, data binding) | empty | `expect.persists` | Deterministic: form `dataSource` + `useCssPosition` + two components bound to named dataproviders look identical every valid run. |
| `create-form-responsive` | Create a fully responsive (flex/grid) form with a header row and a two-column body | servoy-developer (responsive layout) | empty | `acceptanceCriteria` (judge) | Responsive div/row/column nesting + styleClasses legitimately vary run-to-run; structural equality is brittle, so a judge grades layout intent. Keep only a minimal form-exists `persists`. |
| `create-form-order-tabs-from-mockup` | Build an order-tabs tab-panel form matching a committed HTML mockup | servoy-developer (responsive layout, UI fidelity) | empty | `acceptanceCriteria` (judge) | UI-fidelity build from an input mockup (`inputs/specs/03_order_tabs.html`): the judge grades the built form matches the mockup's tab/panel structure. Keep a minimal form-exists `persists`. |
| `create-valuelist-custom` | Create a custom/fixed-values valuelist (`CUSTOM_VALUES`) | servoy-developer (valuelist) | empty | `expect.persists` | Deterministic: the fixed custom values are known up front; a persist assertion nails them exactly. |
| `create-valuelist-db` | Create a database/table-based valuelist (`TABLE_VALUES`) bound to a table's display + real columns | servoy-developer (valuelist) | empty | `expect.persists` | Deterministic: the valuelist's datasource plus its display and real dataprovider binding are fixed once the real columns are found, so a persist assertion nails them. |
| `create-valuelist-global` | Create a global-method-based valuelist (`GLOBAL_METHOD`) bound to a scope method that returns the contents | servoy-developer (valuelist) | empty | `expect.persists` (+ optional `verify.jsunit`) | Deterministic: the valuelist type and its bound method, plus the method's existence, are fixed (persists). A jsunit tier is optional since the real list content is produced by the method at runtime. |
| `create-relation` | Create a relation between two example_data tables — the task requires inspecting the schema for the real FK columns (delegates to Database-Researcher) | servoy-developer (relation), servoy-database (Database-Researcher) | empty | `expect.persists` | Deterministic: relation name, primary/foreign datasource and the key pair are fixed. The prompt withholds the column names so the real FK must be found from the live schema, exercising the bounded read-only schema delegation. |
| `datalayer-open-order-totals` | Build a scope method that sums open-order totals for many customers in ONE query (tempts N+1) | servoy-developer (data-layer rails D4/D5), servoy-database | empty | `expect.persists` + `verify.jsunit` | The scope method is a deterministic artifact (persists); only running it proves it returns correct totals, guards the empty-input case, and uses a single query (jsunit). The prompt tempts a query-in-a-loop the rails must prevent. |
| `datalayer-bulk-archive-save` | Build a scope method that bulk-updates records and must honour the `saveData()` result (tempts discarded save) | servoy-developer (data-layer rail D1) | empty | `verify.jsunit` | Behaviour-only: the defect (silently discarded `databaseManager.saveData()` Boolean → silent data loss) is invisible structurally; only a JSUnit run that asserts the records actually persisted catches it. Folds in the D4 unbounded-read concern — the load is scoped by the cutoff date, not an unbounded table scan. |
| `security-tenant-query-clean` | Build a QBSelect-based search over a tenant table from user input, then run a security review — a correct run yields NO finding | servoy-developer (build), servoy-security-reviewer (false-positive filter) | empty | `acceptanceCriteria` (judge) | The judge grades that the feature was built AND that the security review returned a clean PASS with no spurious hardening: QBSelect is parameterized by construction, NGClient auto-sanitizes display, the tenant filter is auto-injected — a correct run neither concatenates raw SQL nor adds a manual tenant `WHERE`. This is the false-positive polarity. |
| `security-rawsql-injection` | Build a `ws_*` endpoint that concatenates user input into a rawSQL string, then run a security review — the review MUST flag it CRITICAL | servoy-developer (build), servoy-security-reviewer (true-positive detection) | empty | `acceptanceCriteria` (judge) | The true-positive polarity: the prompt deliberately builds an injectable source→sink path (request param → `+` string concat → `plugins.rawSQL.executeSQL`) with no auth gate. The judge grades that the review names the SQL-injection (and the missing `ws_*` authz) as CRITICAL. No fix is applied; the verdict is the artifact. |
| `reviewer-defect-find` | Build a form/method with a deliberate reviewer-catchable defect (a dangling `dataProviderID`, an `onActionMethodID` wired to a non-existent handler), then ask for a review that must FIND it | servoy-reviewer | empty | `acceptanceCriteria` (judge) | Review-only (no fix): the prompt plants a defect the `.frm` grep passes (U5/U7) are built to catch, then asks for a review. The judge grades that the review NAMES the planted defect; structure can't express "the reviewer noticed". |
| `orchestrator-form-plus-unittest` | Build a scope method and write a passing unit test for it (exercises the build → unit-test hand-off) | servoy-orchestrator, servoy-developer, servoy-unittester | empty | `expect.persists` + `verify.jsunit` | Deterministic method (persists) plus a behaviour check; verifies the orchestrator correctly hands off to the unit tester and the produced method is testable. Prefer the injected name-tolerant script as the authoritative behaviour gate. |
| `svycreateformtest-orders` | Generate a JSUnit form test for the EXISTING `orders` form in the setup solution | servoy-unittester (svyCreateFormTest), servoy-developer | `setup-crm-basic` | `verify.jsunit` | Form-test against existing structure: the `orders` form already exists in the setup, so the scenario tests that the skill builds the `orders_test` extending form in the `*_test` module and a passing `test_*` function that calls the form's own function — not that it creates a form. Behaviour runs, so JSUnit is authoritative. |
| `enhance-order-total-field` | Enhance the EXISTING `orders` form — add a computed order-total field and a refresh button that recomputes it | servoy-developer (form enhancement, data-layer) | `setup-crm-basic` | `expect.persists` + `verify.jsunit` | The flagship ENHANCE scenario: it modifies an existing form (adds a field + a button wired to a handler + the computing method) rather than creating from scratch. The added structure persists; jsunit runs the compute function to prove the total is correct. Tests reading and extending existing structure. |
| `svyfixstacktrace-order-totals` | Fix the bug behind a pasted Servoy stacktrace in the EXISTING `reports.getOrderTotalsByCustomer` method | svyFixStacktrace (servoy-developer, servoy-database) | `setup-crm-basic` | `verify.jsunit` + `acceptanceCriteria` (judge) | The setup's `getOrderTotalsByCustomer` throws on an empty/missing `customerIds` array (the naive loop dereferences it). The prompt pastes a representative Servoy stacktrace for that failure. jsunit asserts the error no longer reproduces (empty input returns an empty object, normal input returns correct totals); the judge grades that the root cause was named. |
| `fix-authenticator-not-called` | Fix a DESCRIBED problem — "the authenticator is not being called on login" — in the setup solution | svyFixStacktrace family / servoy-developer (diagnosis), servoy-platform (auth) | `setup-auth-login` | `acceptanceCriteria` (judge) | Described-problem diagnosis+fix (not a stacktrace, not a ticket): the setup wired an authenticator whose `onSolutionOpen` exists but is never set as the module's `onSolutionOpenMethodID`, so login silently fails. The judge grades that the diagnosis named the unwired handler as the root cause AND the fix sets `onSolutionOpenMethodID` to the function's UUID. The fix is a structural-file change the judge is best placed to confirm against the described symptom. |
| `e2e-form-cypress-test` | Build a small form, then ask for a Cypress E2E spec that drives it | servoy-developer (build), servoy-e2etester | empty | `acceptanceCriteria` (judge) | Build-then-test: the prompt builds a simple interactive form, then asks the E2E-Tester for a `.cy.ts` spec exercising the feature end to end. The judge grades that an E2E spec was produced for the built form, using the project's selector helpers (no raw selectors, no `cy.wait(ms)`); the spec file is the artifact, not a persisted solution object. |
| `modernize-method-syntax` | Build a scope method using `var`, a function expression and a magic HTTP status number, then modernize it — behaviour unchanged | servoy-developer (svyModernize rails) | empty | `expect.persists` + `acceptanceCriteria` (judge) | Refactor, same behaviour: the prompt first writes an intentionally dated method, then modernizes it to `const`/arrow and `plugins.http.HTTP_STATUS.*`. The method persists; the judge grades that behaviour is unchanged AND the modern syntax replaced every dated construct (no `var`, no magic status number). |
| `optimize-query-in-loop` | Build a scope method with a query inside a loop over records, then optimize it to a single guarded `isin()` query — behaviour unchanged | servoy-developer (svyOptimize rails D4/D5) | empty | `expect.persists` + `verify.jsunit` + `acceptanceCriteria` (judge) | Performance, same results: the prompt first writes the N+1 version, then optimizes it. The method persists; jsunit asserts identical results (ideally with a query-count assertion proving a single query); the judge grades that no query-in-loop remains and the `isin()` has an empty-array guard. |

---

## 3. Scenario prompts

Each heading below carries the full, copy-ready prompt for one scenario, in the
same order as the matrix table. Setup-backed scenarios open by naming the saved
setup solution as their starting point.

### create-form-css-position

Starting state: `empty`.

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

Starting state: `empty`.

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

Starting state: `empty`.

```
In the active solution, create a relation named "customers_to_orders" from
db:/example_data/customers to db:/example_data/orders. The relation joins each customer to
their orders, but do NOT assume the key column names — inspect the example_data schema and
find the real primary key on customers and the matching foreign key on orders, then build the
relation on that exact column pair. Use the live schema as the source of truth for the column
names. If any detail is unspecified, choose a sensible default and continue without asking. Do
not write any tests. Try to do this in a single run without stopping to ask questions.
```

### datalayer-open-order-totals

Starting state: `empty`.

```
In the active solution, against the example_data database, create a global scope named
"orders" with a method getOpenOrderTotalsByCustomer(customerIds). Given an array of customer
id values it returns a plain object mapping each customer id to the summed total of that
customer's orders whose status is "open". Requirements: it MUST run a single query for all
customers at once (never a query inside a loop over the ids), it MUST guard against an empty
or missing input array by returning an empty object, and it MUST use a query builder or
foundset rather than rawSQL. Inspect the orders table to find the real total, status and
customer-id columns and use them. If any detail is unspecified, choose sensible real values
and continue without asking. Do not write any tests yourself. Try to do this in a single run
without stopping to ask questions.
```

### datalayer-bulk-archive-save

Starting state: `empty`.

```
In the active solution, against the example_data database, create a global scope named
"maintenance" with a method archiveOldOrders(cutoffDate). It loads every order whose order
date is before cutoffDate, sets each one's status to "archived", and saves the changes. It
must treat the save result as authoritative: if the save does not succeed it must report the
failure to the caller (return false or throw) rather than silently continuing, so no record
is left half-updated. Inspect the orders table for the real date and status column names and
use them. If any detail is unspecified, choose sensible real values and continue without
asking. Do not write any tests yourself. Try to do this in a single run without stopping to
ask questions.
```

### security-tenant-query-clean

Starting state: `empty`.

```
In the active solution, against the example_data database, create a global scope named
"search" with a method findCustomersByName(nameFragment) that returns the customers whose
companyname contains the given text, using a query builder (QBSelect). The nameFragment comes
from end-user input. Build it the normal, idiomatic Servoy way. Also create a CSS-position
form named "customerSearch" bound to db:/example_data/customers with a text field for the
search term, a search button whose onAction runs the query, and a label that displays the
matched company name. Inspect the customers table for the real companyname column and use it.
Once it is built, perform a security review of the code you just wrote and report your
findings — do not change the code in response to the review. If any detail is unspecified,
choose sensible real values and continue without asking. Do not write any tests. Try to do
this in a single run without stopping to ask questions.
```

### security-rawsql-injection

Starting state: `empty`.

```
In the active solution, against the example_data database, create a global scope named "api"
with a REST endpoint method ws_read(customerName) that looks up customers by company name and
returns them as JSON. Build the lookup by assembling a SQL string from the incoming
customerName value and running it with plugins.rawSQL.executeSQL against the example_data
server (the endpoint should have no permission or authentication check — build it as a plain
public ws_* method). Inspect the customers table for the real companyname and id columns and
use them in the SQL. Once it is built, perform a security review of the code you just wrote
and report your findings — classify each with a severity, and do not change the code in
response to the review. If any detail is unspecified, choose sensible real values and continue
without asking. Do not write any tests. Try to do this in a single run without stopping to ask
questions.
```

### reviewer-defect-find

Starting state: `empty`.

```
In the active solution, bound to db:/example_data/customers, build a CSS-position form named
"customerCard" that shows the customer's company name, contact name and phone, each in a
labelled text field, plus a "Save" button. Then, once the form exists, perform a code review
of the form you just built against that description and report your findings — list anything
that would not work at runtime (a field bound to a non-existent dataprovider, a button whose
onAction points at a handler that does not exist, a missing label), with a severity for each.
Do NOT fix anything — the review report is the deliverable. Inspect the customers table for the
real column names. If any detail is unspecified, choose sensible real values and continue
without asking. Do not write any tests. Try to do this in a single run without stopping to ask
questions.
```

### orchestrator-form-plus-unittest

Starting state: `empty`.

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
form named "orders" bound to db:/example_data/orders with its own form functions. Write a
JSUnit form test for the EXISTING "orders" form: create the orders_test extending form in the
solution's *_test module (extending the production "orders" form, headless-safe), and a
test_* function that exercises one of the orders form's own functions and asserts the result.
Do not re-create the orders form and do not re-implement its functions inside the test — call
the real form function. Make sure the test passes. If any detail is unspecified, choose a
sensible default and continue without asking. Do this in a single run without stopping to ask
questions.
```

### enhance-order-total-field

Starting state: `setup-crm-basic` (solution `aiCrmBasic`).

```
This solution is the saved "aiCrmBasic" starting solution: it already contains a CSS-position
form named "orders" bound to db:/example_data/orders. Enhance the EXISTING "orders" form —
do not create a new form. Add a read-only "order total" field that shows the computed total
for the currently selected order, and add a "Refresh total" button whose onAction recomputes
and updates that field. Put the computation in a form function the button calls (and that a
test could call), reading the order's real line/amount columns — inspect the orders table (and
any related order-lines table) for the real column names. Keep the rest of the form intact. If
any detail is unspecified, choose sensible real values and continue without asking. Do not
write any tests. Try to do this in a single run, iterating as needed, without stopping to ask
questions.
```

### svyfixstacktrace-order-totals

Starting state: `setup-crm-basic` (solution `aiCrmBasic`).

```
This solution is the saved "aiCrmBasic" starting solution. Running
scopes.reports.getOrderTotalsByCustomer with an empty or missing customerIds array throws at
runtime. Here is a representative stacktrace:

TypeError: Cannot read property "length" from undefined
	at reports.getOrderTotalsByCustomer (reports.js:14)
	at <anonymous> (command line:1)

Find the bug behind this error and fix it: the method must guard an empty or missing
customerIds array by returning an empty object without querying, and it must still return the
correct per-customer totals for a normal input. Name the root cause in your summary. Inspect
the orders table for the real customer-id and total columns if you need them. If any detail is
unspecified, choose sensible real values and continue without asking. Do not write any tests
yourself. Try to do this in a single run, iterating as needed, without stopping to ask
questions.
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

Starting state: `empty`.

```
In the active solution, against the example_data database, create a global scope named "reports"
with a method getOrderCountsByCustomer(customerIds). Given an array of customer id values it
returns a plain object mapping each customer id to the number of that customer's orders. First
write it the naive way — loop over the ids and run one query per id inside the loop. Once it
returns correct counts, optimize it so it runs a SINGLE query for all ids at once using an
isin() condition, guarding the empty/missing input array so it returns an empty object without
querying. The results for the same inputs must be identical to the naive version. Inspect the
orders table for the real customer-id column and use it. If any detail is unspecified, choose
sensible real values and continue without asking. Do not write any tests yourself. Try to do
this in a single run without stopping to ask questions.
```
