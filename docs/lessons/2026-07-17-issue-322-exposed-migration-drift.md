# Issue #322 Exposed Migration Drift Lessons

## Context

Exposed 1.3.1 exposes two related but different migration surfaces. The Gradle
plugin generates a file from build-time JDBC metadata, while the JDBC and
R2DBC `MigrationUtils` APIs compare live database metadata inside their
matching transaction types. Treating those surfaces as interchangeable made
the old README example easy to misuse and left schema drift without a focused
regression lane.

## Decisions and Findings

### Fixed filenames prove reproducibility, not application versioning

The repository demos keep fixed `V1` filenames because CI deletes only those
two known fixtures, regenerates them with `--rerun --no-build-cache`, and
requires a clean bounded migration-directory status. This is a deterministic
repository fixture contract.

Applications must do the opposite: select a new immutable versioned filename,
check that it does not already exist, and never overwrite a migration that may
have been applied. The plugin's timestamp default is convenient, but it is not
a reproducibility proof because every invocation can choose a different path.

### Build-time generation is JDBC even for an R2DBC application

The Exposed Gradle plugin reads schema metadata through JDBC. An R2DBC
application that uses the plugin therefore still owns a build-time JDBC URL and
driver. Live R2DBC comparison remains separate and must run through the R2DBC
`MigrationUtils` API and suspending transaction.

### Generated SQL must cross an exact execution boundary

The regression executes only the single additive nullable
`VARCHAR(255)` statement produced for a synthetic table. The validator matches
the whole statement, exact table and column tokens, and the complete approved
tail. Quoted identifiers are matched as tokens rather than stripped: stripping
quotes first can turn an identifier with leading or trailing whitespace into
the expected name while the database still targets a different object.

Type-change output is characterized but never passed to the additive executor.
This keeps the regression useful without converting an experimental upstream
API into a production migration runner.

### Empty output has a deliberately narrow meaning

An empty `MigrationUtils` result means only that this Exposed comparison did
not detect a difference for the supplied table model and current dialect. It
does not prove data compatibility, rollout safety, complete schema equality,
or the absence of objects that Exposed does not model.

### Failure evidence needs two independent statuses

CI captures the Gradle pipeline result with `PIPESTATUS[0]` and separately
tracks evidence staging. Evidence assembly stays in a guarded non-errexit
section so a later redaction, copy, or report failure cannot replace the
original Gradle exit. The final selection result prefers a nonzero Gradle
status, then the evidence status. Raw logs remain runner-temporary; only
sanitized summaries, status, and stream-free JUnit XML may be uploaded.

## Outcome

- Normal module tests exclude the `migration-drift` tag.
- Dedicated JDBC and R2DBC tasks are live-only, non-cacheable, serialized by
  the repository Test mutex, and restricted to `H2`, `POSTGRESQL`, or
  `MYSQL_V8`.
- Pull requests prove fixed demo generation and independent H2 JDBC/R2DBC
  drift behavior.
- Sunday/manual full Nightly proves PostgreSQL and MySQL 8 selections
  sequentially without retries.
- English and Korean README guidance separates application and contributor
  scenarios, while the stable 1.11 manual remains unchanged until the 1.12
  release owner promotes it against an exact release ref.

## Verification

- Validator regression observed RED for quoted identifiers with embedded
  boundary whitespace, then GREEN after exact-token matching.
- Invalid `EXPOSED_TEST_DB=TYPO` changed from a successful dry run to a
  fail-fast configuration error.
- JDBC/R2DBC H2 additive convergence, H2 type-change characterization, and
  real coroutine-cancellation cleanup pass.
- Fixed JDBC/R2DBC V1 regeneration leaves both migration directories clean.
- README parity self-test and live parity check, `actionlint`, Detekt, stable
  manual no-diff, and `git diff --check` pass.

## Future Guidance

- Keep production migration orchestration outside startup and request paths.
- Add new executable statement shapes only with a whole-statement negative
  matrix and a dialect-specific live proof.
- Keep real-database selections sequential and no-retry so the evidence shows
  the first actual migration failure.
- Promote README material into the stable manual only after an exact 1.12
  release ref and commit are available.
