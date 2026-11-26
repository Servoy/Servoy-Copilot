# Solution setup: `setup-crm-basic`

An input artifact for the SVY-21659 scenario matrix. This file describes, in
prose, the shape the `setup-crm-basic` setup prompt should produce, so a tester
can verify the saved solution before reusing it across the enhance / fix /
form-test scenarios. The full setup prompt is at the bottom and is also carried
(verbatim) in [`../../SCENARIO-MATRIX.md`](../../SCENARIO-MATRIX.md).

## Identity

- **Setup id:** `setup-crm-basic`
- **Solution name:** `aiCrmBasic`
- **Database:** bound to `db:/udm`
- **Purpose:** a small, realistic CRM to **enhance**, **fix** and **form-test**
  against. One saved setup is reused by several scenarios.
- **Scenarios that start from it:** `svyfixstacktrace-order-totals`,
  `svycreateformtest-orders`, `enhance-order-total-field`.

## Expected shape

### Forms (CSS-position)

- **`companies`** — bound to `db:/udm/companies`, with text fields bound to
  `company_name`, `company_email` and `company_url`.
- **`orders`** — bound to `db:/udm/orders`, with fields bound to `order_id`,
  `order_date`, `order_total` and `is_paid` (the paid flag uses the
  `orderPaidStatus` value list). The form has a form function
  `formatAmount(amount)` that returns the amount as a two-decimal string and
  `"0.00"` for a missing or non-numeric amount; `svycreateformtest-orders` tests
  it.

All data lives in `udm`: `companies` -> `orders` on `company_id`, and
`orders` -> `order_items` on `order_id` (`extended_price` per line), which the
`enhance-order-total-field` scenario computes from. `udm.orders` has no status
column.

### Global scopes

- **`reports`** with `getOrderTotalsByCompany(companyIds)` — given an array of
  `company_id` values, returns a plain object mapping each `company_id` to the
  summed `order_total` of that company's orders. It is **written the naive way on
  purpose**: a loop over the ids running one query per id inside the loop (an
  intentional N+1), so the fix/optimize scenarios have real code to repair. This
  method also reads `companyIds.length` without guarding the array, which is the
  bug behind the `svyfixstacktrace-order-totals` stacktrace.
- **`pricing`** with `calculateLineTotal(quantity, unitPrice, discountPercent)` —
  returns `quantity * unitPrice` with the discount applied, rounded to two
  decimals, and returns `0` for any missing or negative input.

### Value list

- **`orderPaidStatus`** — a custom-values value list with the display values
  `Open` and `Paid` for the real values `0` and `1`, attached to the orders
  form's `is_paid` field.

### i18n

- One key **`crm.orders.title`** with the English value `Orders`.

## Verification checklist (for the tester)

- [ ] Solution `aiCrmBasic` exists and uses only `db:/udm` (no `example_data`).
- [ ] Forms `companies` and `orders` exist, are CSS-position, and are bound to the
      right datasources with the described fields on real columns.
- [ ] The `orders` form has `formatAmount(amount)` with the two-decimal and
      missing-amount behaviour.
- [ ] Scope `reports` has `getOrderTotalsByCompany(companyIds)` written as an
      unguarded query-in-a-loop (the deliberate N+1 and the missing guard are
      present, not already fixed).
- [ ] Scope `pricing` has `calculateLineTotal(quantity, unitPrice,
      discountPercent)` with the rounding and the missing/negative-input guard.
- [ ] Value list `orderPaidStatus` maps `Open`/`Paid` to `0`/`1` and is on the
      `is_paid` field.
- [ ] i18n key `crm.orders.title` = `Orders`.

## Full setup prompt

Paste this into the Servoy AI import/record wizard against a fresh `empty`
solution, let the agent finish, verify against the checklist above, then **save**
the solution and note its location.

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
