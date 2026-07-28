# WIP - bluetape4k-exposed

Snapshot: 2026-06-02 KST
범위: 1.10.0 release train 이후 version alignment.
열린 항목: 6 issues.

## 현재 방향

`1.10.0` stable line은 publish되었고 `bluetape4k-dependencies` `1.2.0`에서 소비되었습니다.
이제 개발은 `1.11.0`으로 이동하며, workflow가 주입하는 snapshot publication을 위해
`snapshotVersion=`은 비워 둡니다.

## 활성 대기열

| 우선순위 | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#225](https://github.com/bluetape4k/bluetape4k-exposed/issues/225) feat: add Spring Boot Actuator health indicators for Exposed cache consistency | 1.10.0 | 이 branch에서 JDBC/R2DBC Caffeine repository 대상으로 구현했습니다. |
| P1 | [#226](https://github.com/bluetape4k/bluetape4k-exposed/issues/226) test: add migration generation smoke coverage for Exposed Gradle plugin demo modules | 1.10.0 | 이 branch에서 weekly/PR workflow로 구현했습니다. |
| P1 | [#228](https://github.com/bluetape4k/bluetape4k-exposed/issues/228) feat: add BigQuery query job options and dry-run validation | 1.10.0 | 이 branch에서 credential-free dry-run coverage와 함께 구현했습니다. |
| P1 | [#229](https://github.com/bluetape4k/bluetape4k-exposed/issues/229) feat: expose Trino JDBC performance options and pushdown smoke coverage | 1.10.0 | 이 branch에서 typed JDBC option과 EXPLAIN guidance로 구현했습니다. |
| P1 | [#230](https://github.com/bluetape4k/bluetape4k-exposed/issues/230) feat: add database-specific scenario examples for exposed modules | 1.10.0 | 이 branch에서 BigQuery dry-run example과 기존 ClickHouse example matrix로 구현했습니다. |
| P1 | [#231](https://github.com/bluetape4k/bluetape4k-exposed/issues/231) [epic] 1.10.0 database stability, analytics, and examples | 1.10.0 | 모든 child issue가 닫힌 뒤 close합니다. |

## 열린 PR

이 branch 이전에는 없습니다.

## 갱신 메모

- 2026-06-02 KST에 `gh`로 검증했습니다.
- `bluetape4k-*` issue와 이를 해결하는 PR의 milestone을 일치시킵니다.
