# Exposed 1.12.0-SNAPSHOT POM Recovery Checklist

## Authority and scope

- Approved request: repair the malformed `bluetape4k-exposed` SNAPSHOT POM that blocks `bluetape4k-javers` PR #249.
- Workflow: Type C bug fix followed by Type P `routine-snapshot` recovery.
- Repository: `bluetape4k/bluetape4k-exposed`.
- Base branch: `develop`.
- Head branch: `fix/snapshot-pom-versions`.
- Target version: `1.12.0-SNAPSHOT`.
- Latest observed external snapshot: timestamped build `1.12.0-20260716.201738-20` from source SHA `38d13d906ae7d26552f1dec46f22e3e2b541a0ab`.
- Target source authority: the exact merged `develop` SHA containing the POM fix; unknown until merge and therefore publication remains held.
- Consumer scope: `bluetape4k-javers` PR #249 at `8e5a15c4274b0af91e2012a5eaad5cf463e743e8`.
- Stable release, tag, GitHub Release, milestone closeout, BOM train, and unrelated catalog changes: out of scope.

## Topology

- Repository class: stable-capable JVM library currently using its next-line SNAPSHOT.
- Selected flow: routine snapshot.
- Snapshot edge: `bluetape4k-javers` depends on `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.0-SNAPSHOT` and `bluetape4k-exposed-jdbc:1.12.0-SNAPSHOT`.
- Execution order: repair Exposed POM generation -> PR/CI/review -> fresh merge approval -> merge -> publish exact merged `develop` SHA -> verify public snapshot -> rerun Javers PR CI.
- Graph: acyclic for this recovery; no downstream publication is requested.

## Defect evidence

- Public POM: `bluetape4k-exposed-jdbc-1.12.0-20260716.201738-20.pom`.
- Consumer failure: Gradle reports `Required version must not be null` while parsing the public POM.
- Local reproduction on `develop` SHA `76e6278e2e94abc16c8df030d83de97784ed2e93` generates five versionless entries in the JDBC POM.
- The Kotlin stdlib, Kotlin reflect, and kotlinx-coroutines-core entries are valid because the same POM manages them explicitly. The invalid entries are the versionless Spring Boot and Exposed BOM imports.
- Root-cause boundary: publication-facing platform declarations kept using versionless local aliases even though the central catalog already provided the authoritative versioned BOM aliases.

## Bug-fix gates

- [x] C-01: deterministic public and local reproduction collected.
- [x] C-02: surgical scope and regression shape approved; no issue creation was included in the approved scope.
- [x] C-03: the initial publication-POM audit passed 3 tests/10 assertions and reported RED with five versionless entries in the generated JDBC POM; the refined regression suite distinguishes valid managed dependencies from invalid BOM imports.
- [x] C-04: replace publication-facing local BOM aliases with `bt4k.exposed.bom` and `bt4k.spring.boot4.dependencies`, without broad resolved-version rewriting.
- [x] C-05: prove the audit, generated POMs, affected compilation, and proportional broader build are GREEN.
- [x] C-06: update the existing Central POM lesson with the newly discovered catalog-alias failure mode.
- [ ] C-07: create the authorized PR and pass exact-head CI/review.
- [ ] C-08: report exact-head merge readiness.
- [ ] C-09: merge only after fresh approval, then sync and clean up.

## Snapshot publication gates

- [x] PUB-01: routine-snapshot identity, target version, repository, consumer, and current authority are pinned above.
- [x] PUB-02: current workflow, prior snapshot run, public POM, previous POM fix PR #136, and consumer failure were queried live.
- [ ] PUB-03: prove the exact merged candidate state and generated artifact matrix.
- [ ] PUB-04: audit generated BOM/POM metadata, license, signing diagnostics, and publishable-module matrix.
- [ ] PUB-05: immediately before dispatch, pin the merged `develop` SHA and reread `.github/workflows/publish-snapshot.yml` plus live external metadata.
- [ ] PUB-06: dispatch `Publish Snapshot`, monitor the exact run, and independently verify every expected public POM.
- [ ] PUB-07: N/A only after confirming no GitHub Release or milestone closeout belongs to a routine snapshot.
- [ ] PUB-08: verify the Javers consumer resolves and rerun PR #249 CI.
- [ ] PUB-09: N/A only after confirming this recovery already publishes the active next development line.
- [ ] PUB-10: N/A only after confirming no public install documentation changes are required for a same-version snapshot repair.
- [ ] PUB-11: report exact SHAs, workflow URLs, public metadata timestamp, artifact audit, consumer result, and remaining risk.

## Dispatch hold

- Publication is authorized by the approved plan but remains held until the fix PR is merged after fresh approval.
- The snapshot workflow currently declares no `workflow_dispatch` inputs and checks out the default branch.
- Before dispatch, verify the default branch SHA equals the approved merged fix SHA.
- Do not dispatch from the feature branch, a stale `develop` SHA, or before generated POM audits report zero missing versions.

## Local verification evidence

- Publication audit tests: 5 runs, 16 assertions, 0 failures.
- Snapshot POM generation: 35 public POMs generated successfully.
- Structural audit: 10,125 dependency entries checked with zero invalid dependency-management versions.
- Maven consumer-model audit: 35 effective models validated in a single reactor.
- Catalog authority: 28 platform declarations use `bt4k.exposed.bom` or `bt4k.spring.boot4.dependencies`; the duplicate versionless local aliases were removed.
- Proportional build: `./gradlew build -x test -x koverVerify --parallel --no-configuration-cache --no-daemon` passed.
- Static checks: `actionlint`, `xmllint`, Ruby syntax, and `git diff --check` passed.
