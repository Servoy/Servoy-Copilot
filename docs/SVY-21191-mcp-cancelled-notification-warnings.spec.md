# Spec: SVY-21191 â MCP endpoints showing a lot of warnings in the log

## 1. Goal

Eliminate the repeated `WARN` messages from `McpStreamableServerSession` about
missing `notifications/cancelled` handlers that flood the console when an MCP
client is actively connected.

## 2. Background

### 2.1 The warning

When opencode (or any MCP client) cancels in-flight requests, it sends a
`notifications/cancelled` JSON-RPC notification per the MCP protocol spec. The
Spring AI MCP SDK logs a warning for each unhandled notification:

```
WARN io.modelcontextprotocol.spec.McpStreamableServerSession - No handler registered for notification method: JSONRPCNotification[jsonrpc=2.0, method=notifications/cancelled, params={requestId=50, reason=AbortError: The operation was aborted.}]
```

During a large coding session this produces hundreds of warnings, obscuring
useful output.

### 2.2 Root cause

`McpServerFactory.createSyncServer()` builds the `McpSyncServer` without
registering a handler for `notifications/cancelled`. The warning is emitted
in `McpStreamableServerSession.accept(JSONRPCNotification)`: it looks up the
notification method in its `notificationHandlers` map, and if no entry is
found it calls `logger.warn("No handler registered for notification method: {}", ...)`.

The `notificationHandlers` map is populated by
`McpAsyncServer.prepareNotificationHandlers()`, which only registers handlers
for `notifications/initialized` and `notifications/roots/list_changed`. There
is no public API on `McpServer.SyncSpecification` (SDK 1.1.2) to register
additional notification handlers — the builder only exposes `rootsChangeHandler()`
for notification-style callbacks.

The `notificationHandlers` map is passed by reference into
`DefaultMcpStreamableServerSessionFactory` as a package-private field at
construction time, and from there into each `McpStreamableServerSession`.

### 2.3 MCP protocol: `notifications/cancelled`

Per the MCP specification, `notifications/cancelled` is sent by the client to
inform the server that a previously-issued request is no longer needed. The
`params` object contains:

- `requestId` â the ID of the request being cancelled
- `reason` (optional) â human-readable reason for cancellation

The server is not required to act on this notification but should acknowledge it
silently (i.e. not log a warning).

### 2.4 Where the server is built

- **Plugin:** `com.servoy.eclipse.developer.mcp`
- **Factory:** `com.servoy.eclipse.developer.mcp.McpServerFactory`
- **Builder call:** lines 80â86 in `createSyncServer(Object, HttpServletStreamableServerTransportProvider, Collection<String>)`

## 3. Design

### 3.1 Register a no-op notification handler for `notifications/cancelled`

After `McpSyncServer` is built, use reflection to obtain the
`DefaultMcpStreamableServerSessionFactory` that was installed on the
`HttpServletStreamableServerTransportProvider` by `McpAsyncServer`, and add
a no-op entry to its `notificationHandlers` map:

```java
handlers.put("notifications/cancelled", (exchange, params) -> Mono.empty());
```

This is safe because:
- The field name `notificationHandlers` is part of the public constructor
  signature of `DefaultMcpStreamableServerSessionFactory`, making it a
  stable API surface unlikely to change without a major version bump.
- The map is mutable (`HashMap`) and is shared by reference with every
  session created afterwards.
- Failures are caught and logged as a warning rather than crashing the
  server startup.

### 3.2 Why not use the builder?

`McpServer.SyncSpecification` (SDK 1.1.2) does not expose a generic
`notificationHandler(String, handler)` method. The only notification hook is
`rootsChangeHandler()`. Upgrading to SDK 2.0.0 was evaluated but it contains
breaking `McpSchema` changes unrelated to this fix, and PR #985 ("Reduce
logging levels") in 2.0.0 does not address this specific warning either.

## 4. Implementation plan

1. **Modify `McpServerFactory.createSyncServer()`** — in
   `com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/McpServerFactory.java`,
   store the built `McpSyncServer` in a local variable, then call a new private
   helper `registerCancelledNotificationHandler(transportProvider)` before returning.

2. **Add `registerCancelledNotificationHandler()`** — private helper that uses
   reflection to reach `HttpServletStreamableServerTransportProvider.sessionFactory`
   (a `DefaultMcpStreamableServerSessionFactory`), then puts a no-op
   `McpNotificationHandler` for `"notifications/cancelled"` into its
   `notificationHandlers` map. Failures are caught and logged at WARN level.

3. **Test** — start a Servoy Developer instance, connect opencode, perform
   operations that trigger cancellations (e.g. abort a long-running tool call),
   and verify no `notifications/cancelled` warnings appear in the console.

## 5. Acceptance criteria

- [ ] No `WARN` log messages about `notifications/cancelled` appear in the
      Eclipse console during normal opencode usage.
- [ ] The MCP server continues to function normally (tool calls still work).
- [ ] No regression in handling other notifications (e.g. `notifications/initialized`).
- [ ] The fix does not break the server builder for any of the 12 registered
      MCP server endpoints.

## 6. Out of scope

- Actually cancelling in-progress tool executions when `notifications/cancelled`
  is received (future enhancement).
- Handling other unregistered notification methods (address if observed).
- Changes to the MCP client (opencode) side.
- Changing the logging level of the Spring AI MCP SDK globally.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should we also handle `notifications/initialized` and `notifications/roots/list_changed` proactively? | Dev | open - add if warnings are observed |
| Should a future ticket implement actual request cancellation? | PM | open - low priority enhancement |
| When SDK exposes a public notification handler API, migrate away from reflection? | Dev | open - track on SDK upgrade |