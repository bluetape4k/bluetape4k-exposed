# Issue #694 JDBC driver benchmark code review

## 검토 범위

이 문서는 Issue #694의 benchmark-only 변경을 spec, plan, implementation,
raw evidence, 문서/차트, 운영 전달 관점에서 대조한 최종 전 PR 검토 기록입니다.
Production `exposed/jdbc` API, manual, catalog/BOM, workflow, release 파일은
변경하지 않았습니다.

## 수용 기준 매핑

| 기준 | 근거 | 상태 |
| --- | --- | --- |
| 2 driver × 2 row count × 3 pool size matrix | `JdbcDriverBenchmarkMatrix.kt`, `DriverBenchmarkSupportTest.kt` | PASS |
| sequential/parallel JMH contract와 `maxConcurrency=2` | `JdbcDriverKeyEnumerationBenchmark.kt`, `DRIVER_BENCHMARK_MAX_CONCURRENCY` | PASS |
| shared Testcontainers, Hikari lease/statement tracker, setup/teardown guard | `DriverBenchmarkFixture.kt`, `JdbcDriverKeyEnumerationBenchmark.kt` | PASS |
| 6 raw files × 12 entries × 3 primary/auxiliary samples | `postgresql-run-*.json`, `mysql-run-*.json`, `summarize_jmh.py` | PASS |
| raw SHA와 exact run/source/provenance 검증 | `raw-metadata.jsonl`, `summarize_jmh.py`, `capture_jmh_run.py` | PASS |
| EN/KO parity와 링크 대상 존재 | `validate_readme_parity.py`, `readme-parity.json` (`ok=true`) | PASS |
| source-backed semantic ledger와 SVG/PNG pair | `exposed-jdbc-driver-benchmark-issue-694.semantic.json`, chart audits | PASS |
| Kotlin support test, benchmark compile, detekt, H2 regression | Gradle targeted outputs below | PASS |
| lifecycle failure-path fake test | `DriverBenchmarkFixture.kt` cleanup branches | P2 GAP |
| exact PR-head full nightly PostgreSQL/MySQL JDBC evidence | PR dispatch gate | PENDING |

## 독립 관점 결과

| 관점 | P0 | P1 | P2/P3 및 조치 |
| --- | ---: | ---: | --- |
| performance | 0 | 0 | Raw sample count를 정확히 3으로 고정하고, summary/chart throughput shape를 검증했습니다. |
| stability | 0 | 0 | Close 실패 시 active count 복원과 bounded executor shutdown은 구현됐지만 실패 주입 테스트는 P2로 남았습니다. |
| security/provenance | 0 | 0 | connection/credential token denylist, observed driver/image/catalog/Git/runtime pin, post-copy SHA를 검증합니다. |
| operator/Ops | 0 | 0 | Colima/Docker preflight, `TESTCONTAINERS_RYUK_DISABLED=true`, stale report 방지, fail-closed promotion을 문서화했습니다. |
| developer/API | 0 | 0 | matrix 타입은 `internal`, `maxConcurrency`는 단일 상수, benchmark source set/task와 detekt가 통과했습니다. |
| user/caller | 0 | 0 | EN/KO table cell order, heading technical token, raw link와 실제 파일 존재를 parity receipt로 고정했습니다. |

Wave 1에서 제기된 provenance 입력 신뢰, stale report 선택, metadata 선행 발행,
raw sample 수, parity receipt 문제는 모두 수정 후 재실행했습니다. Wave 2의
parity P1은 table cell/heading technical token 비교와 link target 검사로 해소했습니다.
원본 JMH report의 마지막 두 개 LF는 capture helper가 byte-for-byte 보존하므로
6개 raw 파일에 대한 `git diff --check`의 EOF blank-line 경고는 의도된 P3 기록입니다.

## 검증 증거

- `./gradlew :benchmark-exposed-benchmark:test --tests 'io.bluetape4k.exposed.benchmark.jdbc.DriverBenchmarkSupportTest' ...`
  — `SUCCESS: Executed 3 tests`, `BUILD SUCCESSFUL`.
- `./gradlew :benchmark-exposed-benchmark:detekt :benchmark-exposed-benchmark:benchmarkClasses :bluetape4k-exposed-jdbc:detekt ...`
  — `BUILD SUCCESSFUL`.
- `./gradlew :benchmark-exposed-benchmark:benchmarkJdbcKeyEnumerationBenchmark ...`
  — 기존 H2 benchmark `BUILD SUCCESSFUL in 26s`, `34 actionable tasks`.
- `summarize_jmh.py` strict replay — 6 metadata records, 24 summary rows,
  implementation SHA `f325a70fbd2047cdef28be928eeea4675b4b05b6`, catalog ref
  `91f9ea9336b5ea991f5675323a1cf25ccfd6f5ed` byte-identical.
- `validate_readme_parity.py` — `ok=true`, `tableCellsEqual=true`,
  `headingTechnicalTokensEqual=true`, `linkTargetsExist=true`, `linkCount=18`.
- capture smoke — post-copy SHA, metadata lock, resolved driver artifact and catalog ref observed.
- chart regeneration — EN/KO SVG byte-identical; existing-output/symlink guard passes.

## 잔여 위험과 중단 조건

Lifecycle failure-path 주입 테스트와 full exact-head nightly는 아직 완료되지 않았습니다.
따라서 현재 판정은 **PENDING**입니다. PR 생성 후 exact PR head를 다시 읽고
`.github/workflows/nightly-tests.yml`의 PostgreSQL/MySQL JDBC job이 skipped가 아닌
terminal success이며 raw artifact가 비어 있지 않음을 확인해야 합니다. fresh merge
approval 전에는 `gh pr merge`, issue close, branch/worktree cleanup을 수행하지 않습니다.

## DoD Status

- Review P0/P1 gate: **PASS (0/0)**.
- Benchmark/document/raw/chart local evidence: **PASS**.
- Lifecycle failure-path test: **PENDING (P2 gap)**.
- Exact-head full nightly and merge approval: **PENDING**.
