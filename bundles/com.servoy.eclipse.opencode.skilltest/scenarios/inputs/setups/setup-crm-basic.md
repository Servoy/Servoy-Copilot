# Solution setup: `setup-crm-basic`

An input artifact for the SVY-21659 scenario matrix. This file describes, in
prose, the shape the `setup-crm-basic` setup prompt should produce, so a tester
can verify the saved solution before reusing it across the enhance / fix /
form-test scenarios. The full setup prompt is at the bottom and is also carried
(verbatim) in [`../../SCENARIO-MATRIX.md`](../../SCENARIO-MATRIX.md).

## Identity

- **Setup id:** `setup-crm-basic`
- **Solution name:** `aiCrmBasic`
- **Database:** bound to `db:/example_data`
- **Purpose:** a small, realistic CRM to **enhance**, **fix** and **form-test**
  against. One saved setup is reused by several scenarios.
- **Scenarios that start from it:** `svyfixstacktrace-order-totals`,
  `svycreateformtest-orders`, `enhance-order-total-field`.

## Expected shape

### Forms (CSS-position)

- **`customers`** — bound to `db:/example_data/customers`, with text fields for
  the company name, the contact name and the phone number. The agent inspects the
  `customers` table for the real column names and binds to those.
- **`orders`** — bound to `db:/example_data/orders`, with fields for the order id,
  the status, the order date and the order total. The agent inspects the `orders`
  table for the real column names.

### Global scopes

- **`reports`** with `getOrderTotalsByCustomer(customerIds)` — given an array of
  customer id values, returns a plain object mapping each customer id to the
  summed total of that customer's orders. It is **written the naive way on
  purpose**: a loop over the ids running one query per id inside the loop (an
  intentional N+1), so the fix/optimize scenarios have real code to repair. This
  method also dereferences `customerIds` without guarding it, which is the bug
  behind the `svyfixstacktrace-order-totals` stacktrace.
- **`pricing`** with `calculateLineTotal(quantity, unitPrice, discountPercent)` —
  returns `quantity * unitPrice` with the discount applied, rounded to two
  decimals, and returns `0` for any missing or negative input.

### Value list

- **`orderStatus`** — a custom-values value list with the values `open`,
  `archived`, `cancelled`.

### i18n

- One key **`crm.orders.title`** with the English value `Orders`.

## Verification checklist (for the tester)

- [ ] Solution `aiCrmBasic` exists and is bound to `db:/example_data`.
- [ ] Forms `customers` and `orders` exist, are CSS-position, and are bound to the
      right datasources with the described fields on real columns.
- [ ] Scope `reports` has `getOrderTotalsByCustomer(customerIds)` written as a
      query-in-a-loop (the deliberate N+1 is present, not already optimized).
- [ ] Scope `pricing` has `calculateLineTotal(quantity, unitPrice,
      discountPercent)` with the rounding and the missing/negative-input guard.
- [ ] Value list `orderStatus` has exactly `open`, `archived`, `cancelled`.
- [ ] i18n key `crm.orders.title` = `Orders`.

## Full setup prompt

Paste this into the Servoy AI import/record wizard against a fresh `empty`
solution, let the agent finish, verify against the checklist above, then **save**
the solution and note its location.

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
