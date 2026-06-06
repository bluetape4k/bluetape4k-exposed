# Issue #255 StarRocks module lesson

## Context

Issue #255 added a locally testable StarRocks Exposed module after the OLAP
local-testability research in #227 selected StarRocks as the strongest next
candidate.

## Decision

Keep the first module deliberately narrow: native Connector/J connectivity,
Exposed dialect registration, explicit database bootstrap, metadata discovery,
simple StarRocks table DDL, insert/select smoke tests, and CI/Nightly visibility.
Do not claim MySQL/PostgreSQL/Trino/ClickHouse parity.

## Outcome

The module passed after adding a StarRocks capacity readiness probe. A plain
listening-port wait and `SELECT 1` were not enough because table creation could
still fail with `Cluster has no available capacity` while the all-in-one image
was finishing backend startup.

## Verification Evidence

- `./gradlew projects --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:dependencyInsight --dependency starrocks-connector-j --configuration runtimeClasspath --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:compileKotlin --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:cleanTest :bluetape4k-exposed-starrocks:test --no-build-cache --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:koverXmlReport --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-starrocks:compileTestKotlin --no-configuration-cache --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `git diff --check`

## Future Guard

For StarRocks or similar OLAP all-in-one containers, readiness should prove a
minimal table create/drop path, not only TCP readiness or `SELECT 1`. Keep the
first dialect surface small until backend-specific DDL behavior is proven by
container tests.
