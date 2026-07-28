# Issue 340 Testcontainer Launcher 리뷰

## 범위

- Issue: #340 `test: centralize BigQuery and StarRocks containers behind launchers`
- Branch: `test/issue-340-testcontainer-launchers`
- Review type: Type B 6-R lite, Tier 4 code correctness + Tier 5 test/evidence

## 근거

- BigQuery와 StarRocks의 baseline `compileTestKotlin`: `BUILD SUCCESSFUL`.
- `git diff --check`: clean.
- targeted scan: raw `GenericContainer` setup은 `BigQueryEmulator.Launcher`와 `StarRocksTestServer.Launcher` 뒤로 격리되어 있습니다.
- serial targeted test: `./gradlew --no-parallel :bluetape4k-exposed-bigquery:test :bluetape4k-exposed-starrocks:test`가 통과했고, BigQuery 46개와 StarRocks 21개 test가 통과했습니다.

## 발견 사항

| Severity | Finding | Evidence | Status |
|---|---|---|---|
| P0 | 없음 | fixture extraction과 targeted test 검토 | PASS |
| P1 | 없음 | launcher helper는 endpoint/credentials/port를 노출하고 singleton lifecycle을 보존합니다 | PASS |
| P2 | BigQuery helper는 module-local test fixture로 유지 | 현재 dependency boundary 안에는 shared bluetape4k BigQuery emulator server가 없습니다 | 허용된 예외 |

## 판정

P0/P1 = 0. 최종 검증 뒤 PR 생성 가능한 상태입니다.
