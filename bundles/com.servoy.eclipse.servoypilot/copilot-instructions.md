# CODING RULES - MANDATORY FOR ALL AI ASSISTANTS

## PART A: WORKFLOW RULES

### RULE 0: BE BRIEF AND CONCISE
Keep responses short unless detail is explicitly requested.

---

### ⚠️ RULE 1 [CRITICAL — HIGHEST PRIORITY]: STOP WHEN RESULTS ARE UNCERTAIN

If you perform any operation (file create/edit/delete, terminal command) and **cannot verify the result** — **STOP IMMEDIATELY**.

- State what you did, what you expected, and what you cannot confirm.
- Recommend a corrective action (e.g. "Please refresh Eclipse workspace") and **wait for confirmation**.
- **DO NOT** retry, workaround, or attempt alternatives on your own.

Triggers requiring immediate STOP:
- Terminal echoes command but does not execute it
- File created/deleted via terminal but `list_dir` still shows old state
- Eclipse workspace does not reflect file system changes
- Tool reports success but subsequent verification shows no change

---

### RULE 2: NEVER MODIFY FILES WITHOUT EXPRESS PERMISSION

- Do NOT create, update, delete, or rename ANY file unless explicitly requested.
- Provide ALL analysis, plans, and reports in **chat only**.
- Always describe EXACTLY what you will change and wait for explicit approval:
  `"yes" / "go ahead" / "implement it" / "make the change" / "proceed"`

**Explicit triggers to act:** "Create a document for..." / "Save this to a file..." / "Implement it" / "Make the change" / "Proceed with..."

If unsure: **DEFAULT TO CHAT, ASK FIRST.**

---

### RULE 3: PROPER WORKFLOW

1. Analyze → explain in chat
2. Propose solution → describe changes in chat
3. Ask permission → wait for explicit approval
4. Make changes only after approval

---

## PART B: JAVA CODING STANDARDS

| # | Rule | Required |
|---|------|----------|
| 1 | **Positive conditionals** — happy path flows naturally inside `if` blocks; all error cases converge to single return at method end | REQUIRED |
| 2 | **Direct imports** — no fully qualified class names in code | REQUIRED |
| 3 | **No unnecessary `else`** — drop `else` when `if`-block returns/throws | REQUIRED |
| 4 | **No nested `try-catch`** — single handler per method | REQUIRED |
| 5 | **`try-with-resources`** — for all `AutoCloseable` (streams, connections, etc.) | REQUIRED |
| 6 | **Pattern matching `instanceof`** — `if (obj instanceof String str)` (Java 16+) | REQUIRED |
| 7 | **Minimal variable scope** — declare variables as late and as locally as possible | REQUIRED |
| 8 | **No magic numbers/strings** — use named constants | REQUIRED |
| 9 | **`StringBuilder` in loops** — never use `+` for string concatenation in loops | REQUIRED |
| 10 | **Composition over inheritance** — favor interfaces and composition | GUIDELINE |

---

## SUMMARY CHECKLIST

**Workflow:**
- [ ] Asked permission before modifying files?
- [ ] Waited for explicit approval?
- [ ] Analysis provided in chat (not in files)?
- [ ] Response brief and concise?
- [ ] If result uncertain — stopped and informed user?

**Java:**
- [ ] Positive conditionals, happy path inside `if`-blocks
- [ ] Single error return at method end
- [ ] Direct imports only
- [ ] No unnecessary `else`
- [ ] No nested `try-catch`
- [ ] `try-with-resources` for `AutoCloseable`
- [ ] Pattern matching `instanceof`
- [ ] Minimal variable scope
- [ ] No magic numbers/strings
- [ ] `StringBuilder` in loops
