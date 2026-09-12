# bluetape4k-exposed-tenant-jdbc

English | [한국어](./README.ko.md)

Framework-neutral tenant resource lifecycle support for JetBrains Exposed JDBC.
`TenantJdbcResourceRegistry<K>` creates one `DataSource`/Exposed `Database`
pair for each configured tenant, exposes exact-match lookup, and owns cleanup after a factory hands a `DataSource` to the registry. The registry is an opt-in resource owner; it is not an authentication, authorization, readiness, or request-drain component.

## Purpose

Use this module when an application needs a fixed, tenant-keyed set of JDBC resources without coupling the resource registry to Spring, Ktor, HikariCP, Micrometer, or Reactor. The registry preserves the materialized tenant order for creation and reverse-order cleanup, and returns the same immutable resource entry from `resourceFor`, `databaseFor`, and `dataSourceFor`.

`Database.connect(dataSource)` configures and registers the Exposed `Database`; it does not prove that the database is reachable or ready. Run application-owned health checks separately.

## Dependency

Declare the central BOM once. The registry artifact intentionally omits an individual version so that the BOM controls its compatible Exposed version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-tenant-jdbc")
}
```

The artifact is introduced on the `2.1.0-SNAPSHOT` development line. Do not expect it in the stable `2.0.0` BOM until that line publishes it.

## Create and lookup

The application supplies the finite, bounded tenant set and the
`DataSource` factory/disposer. `createHikariDataSource` below is an application-owned function; HikariCP and its configuration remain outside this module.

```kotlin
import io.github.bluetape4k.exposed.tenant.jdbc.TenantJdbcResourceRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

enum class TenantId { ACME, GLOBEX }

val registry = TenantJdbcResourceRegistry.create(
    tenants = TenantId.entries,
    dataSourceFactory = ::createHikariDataSource,
    disposeDataSource = { _, dataSource -> dataSource.close() },
)

fun databaseFor(
    requestedTenantId: TenantId,
    allowedTenantIds: Set<TenantId>,
): Database {
    check(requestedTenantId in allowedTenantIds) {
        "Forbidden tenant access."
    }
    return registry.databaseFor(requestedTenantId)
}

val database = databaseFor(requestedTenantId, allowedTenantIds)
transaction(db = database) {
    performTenantWork()
}
```

Authorize the requested tenant before calling `resourceFor`, `databaseFor`, or
`dataSourceFor`. An authenticated identity does not automatically authorize every tenant in the registry, and an unknown tenant never falls back to a default resource. Lookup is an exact map lookup, not a lease: a returned resource may become unusable after shutdown begins.

Always pass the selected `Database` explicitly as `transaction(db = database)`
for tenant work. A parameterless `transaction {}` selects Exposed's global
`primaryDatabase` and can bypass the tenant routing checked by the registry.

An unknown tenant throws `UnknownTenantJdbcResourceException` with the fixed message `Unknown tenant JDBC resource.`. A lookup after close throws the fixed state `IllegalStateException` before it checks whether the tenant is known.

`K` must have stable `equals`/`hashCode` values for the lifetime of the registry. Prefer immutable value types, enums, or strings. The registry materializes the input before it acquires any resource; null and duplicate keys fail before the factory is called.

## Shutdown order

The application owns the shutdown boundary and must use this order:

1. Stop accepting new requests and prevent new tenant work from starting.
2. Drain active transactions and other work that already obtained a registry resource.
3. Call `registry.close()` exactly once from the application shutdown path.

The first `close()` changes the registry to a closed state before cleanup, so new lookup calls fail fast. For each resource, the registry first calls Exposed's `TransactionManager.closeAndUnregister(database)` and then invokes the caller's `disposeDataSource` callback. Resources are cleaned in reverse creation order. Concurrent close callers wait for the owner and observe the same completed result; repeated cleanup is not attempted. A disposer must not close another registry synchronously in a cycle.

## Failure semantics

- `tenants` must be finite and bounded by application configuration. The registry materializes the iterable and does not impose an arbitrary hard limit; the caller owns configuration-size and memory limits.
- A null key fails with `IllegalArgumentException("Tenant key must not be null.")`. A duplicate key fails with `IllegalArgumentException("Duplicate tenant key.")`. These checks happen before any factory invocation and do not call a key's
  `toString()`.
- A null factory result fails with
  `IllegalArgumentException("dataSourceFactory returned null.")`.
- Reusing the same `DataSource` object for two tenants fails with
  `IllegalArgumentException("Tenant JDBC DataSource is reused.")`. The second reference is not disposed; the already-owned resources are cleaned once in reverse order. Different proxy objects that share one underlying pool are not detectable and are a caller contract violation.
- If `Database.connect` or a later assembly step fails after handoff, the current and previously owned resources are cleaned in reverse order. The original failure remains primary unless a fatal cleanup failure must be promoted; other non-fatal cleanup failures are retained as `suppressed`
  exceptions.
- A fatal factory or assembly failure is rethrown after every resource already owned by the registry has been given a best-effort cleanup attempt.
- `close()` continues best-effort cleanup after a non-fatal disposer or unregister failure. The first cleanup failure is retained and subsequent
  `close()` calls report the same aggregate without retrying. Fatal failures (`VirtualMachineError`, `ThreadDeath`, or `LinkageError`) still allow the owned resources to be attempted, then are rethrown to the cleanup owner. A concurrent or later caller observes the fixed fatal-state
  `IllegalStateException` instead of receiving the raw fatal error.
- When a registry with a non-fatal close failure remains reachable, its raw callback exception graph, including messages and stack traces, remains in memory so later callers can receive the same failure. The fatal error is returned only to the cleanup owner and is not stored in registry state. The provider does not log or redact raw failures. Sanitize them at the application boundary before using them in logs, metrics, or HTTP responses.

## Caller responsibilities

The application or adapter owns:

- authentication and authorization, including checking the requested tenant against the authenticated principal before lookup;
- a finite, bounded tenant configuration with immutable, stable keys;
- pool selection, credentials, `DataSource` creation, and cleanup of resources acquired before the factory returns;
- the synchronous disposer callback after the registry takes ownership;
- database readiness/health checks, callback timeout and retry policy, and shutdown watchdogs;
- transaction admission, request draining, and dispatcher/thread lifecycle;
- redacted logs and metrics that do not expose tenant identifiers, credentials, or raw callback failures; and
- supervisor or process restart handling after a fatal cleanup failure.

After a factory returns a `DataSource`, the registry owns its cleanup. The caller must not close that same `DataSource` independently.

## Unsupported behavior

This artifact does not provide:

- tenant header parsing, string-to-tenant conversion, authentication, or authorization;
- a default tenant, fallback database, global registry, or implicit database selection;
- Spring Boot, Ktor, HikariCP, Micrometer, Reactor, or R2DBC integrations;
- readiness/health probes, retries, timeouts, metrics, logging, credentials, or a graceful-drain/lease coordinator;
- caller-injected `Database`, `setupConnection`, custom `DatabaseConfig`, or custom transaction-manager wiring;
- Java SAM overloads or a builder API; or
- detection of shared underlying pools hidden behind different proxy objects.

These concerns stay at the application or framework adapter boundary.
