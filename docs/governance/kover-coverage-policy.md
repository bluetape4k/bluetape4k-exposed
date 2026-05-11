# Kover Coverage Policy

## Current Status

`bluetape4k-exposed` generates Kover reports in CI/Nightly. Selected modules
exclude benchmark or test-fixture support packages from measurement, but broad
coverage threshold enforcement is not enabled.

## Policy

Status: report-only transition.

The repository spans Exposed core extensions, JDBC/R2DBC integrations,
serialization modules, Spring Boot auto-configuration, and database-specific
dialects. A single repository threshold would hide module-specific risk.

## Threshold Plan

- Treat Kover as a trend signal, not a build gate.
- Use CI/Nightly reports to identify coverage regressions.
- Open a focused issue when a module needs coverage repair; do not introduce a
  failing threshold as the default enforcement mechanism.
- Keep generated, benchmark, and test-fixture helper code excluded explicitly.

## CI/Nightly Contract

CI/Nightly upload module coverage artifacts for visibility. They must not fail
solely because a module is below a fixed coverage percentage unless a future
issue explicitly reintroduces that gate.
