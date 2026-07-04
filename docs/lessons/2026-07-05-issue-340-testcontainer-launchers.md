# Issue 340 Testcontainer Launcher Cleanup

## What Changed

BigQuery and StarRocks test container setup was moved behind launcher-style fixtures:

- `BigQueryEmulator.Launcher.endpoint` centralizes local-vs-container endpoint discovery.
- `StarRocksTestServer.Launcher.starRocks` centralizes container startup, mapped ports, credentials, JDBC URLs, and shutdown registration.

## What To Repeat

- Keep raw `GenericContainer` creation behind a narrow launcher helper.
- Expose endpoint, mapped ports, credentials, and JDBC URLs through typed fixture properties.
- Register container shutdown with `ShutdownQueue`.
- Verify host/port mapping before database readiness checks.
- Run Testcontainers-backed BigQuery and StarRocks tests serially.

## Evidence

- BigQuery and StarRocks test compilation passed.
- Serial targeted tests passed with BigQuery 46 passing and StarRocks 21 passing.
