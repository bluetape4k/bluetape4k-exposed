# Kover Coverage Policy

## Current Status

`bluetape4k-exposed` generates Kover reports in CI/Nightly. Selected modules
exclude benchmark or test-fixture support packages from measurement, but broad
`koverVerify` enforcement is not enabled.

## Policy

Status: report-only transition.

The repository spans Exposed core extensions, JDBC/R2DBC integrations,
serialization modules, Spring Boot auto-configuration, and database-specific
dialects. A single repository threshold would hide module-specific risk.

## Threshold Plan

- Gate pure modules such as core/cache/batch first after baseline measurement.
- Use lower documented bounds for database-specific and Spring Boot integration
  modules.
- Keep generated, benchmark, and test-fixture helper code excluded explicitly.

## CI/Nightly Contract

CI/Nightly upload module coverage artifacts. Add `koverVerify` only when a
module has a measured baseline and a documented threshold.
