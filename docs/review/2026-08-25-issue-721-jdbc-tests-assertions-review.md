# Issue #721 `jdbc-tests` assertion 구현 7-Tier 검토

## 검토 범위와 상태

- Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/721
- 기준 ref: `origin/develop@1242e5eb990a1f362233dba9542aa6e4d7192730`
- 대상: `exposed/jdbc-tests/build.gradle.kts`,
  `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`,
  Issue #721 설계·계획·lesson 산출물
- 구현 상태: source mutation·module-local 검증 완료, commit/PR delivery pending
- workflow: `20260825T041335Z-a4e6d514` (Type A), topology receipt sequence 73 이후 증거 갱신 중

## 독립 렌즈 결과

| 렌즈 | provenance | P0 | P1 | P2 | P3 | 판정 |
|---|---|---:|---:|---:|---:|---|
| performance | `plan-performance-replacement-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 1 watch | PASS |
| stability | `plan-stability-replacement-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| security | `plan-security-replacement-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| ops/reliability | `plan-ops-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| API/ABI | `plan-api-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| user/maintainer | `plan-user-agent/gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |

모든 렌즈에서 P0/P1/P2 blocker는 0건이다. performance의 P3 watch는
`TestDB` 선택 dialect가 H2 helper case를 다시 실행하는 고정 비용이며, 다른
렌즈의 P3 지적은 최종 계획에서 해소되었다.

## 7-Tier 판정

| Tier | 검토 내용 | 판정 | 근거와 구현 후 증거 |
|---|---|---|---|
| 1. 요구사항·범위 | `bluetape4k-assertions` direct `api`, migration fixture 정규화, module-local raw assertion 금지, #721 단일 모듈 | PASS | Gradle/test 두 파일과 Type A 설계·계획·review·lesson만 변경하고 production migration logic은 보존했다 |
| 2. 구조·의존성 | catalog alias direct edge와 published API surface | PASS | `apiElements`, POM, module JSON, ABI baseline digest, isolated consumer `compileKotlin` 모두 PASS |
| 3. 동작·의미 | equality/identity/boolean/exception matcher와 migration diagnostic 보존 | PASS | matcher별 compile과 H2/PostgreSQL/MySQL_V8 migration count, primary/suppressed identity 검증 |
| 4. 보안·경계 | fixed roots, canonical containment, symlink 차단, alias/wildcard/continuation/comment/semicolon fail-closed | PASS | raw baseline RED, alias·dotted spacing/backtick·semicolon·block-comment negative probes, GREEN source inventory |
| 5. 성능·자원 | production path 불변, serial DB 실행, retry·cache 경계 | PASS | no-cache/no-config-cache/no-parallel/max-workers=1, `maxAttempts=1`, migration cleanup 경계 유지 |
| 6. 운영·검증 | H2, PostgreSQL, MySQL_V8 순차 실행과 XML count·Docker lifecycle | PASS | H2 7/0/0/0, PostgreSQL/MySQL_V8 각 8/0/0/0, Colima/Docker preflight와 container cleanup PASS |
| 7. 유지보수·전달 | Kotlin checklist, Korean review/lesson, Lore commit, Korean PR DoD | PENDING | SPW/KO checklist와 lesson PASS; Lore commit·PR body/metadata/hosted CI는 delivery 단계에서 확인한다 |

## `$bluetape-kotlin-patterns`와 문서 checklist

- KT-01..KT-11: intent-specific matcher, null/equality/identity 의미, 예외
  message·suppressed 관계, 테스트 격리와 cleanup 계약을 구현·검증했다.
- [x] **SPW-01** Issue URL, 기준 ref, source anchor, GNO/live metadata를 source ledger에 고정했다.
- [x] **SPW-02** spec·plan·review·lesson·PR body의 산출물과 DoD 경계를 고정했다.
- [x] **SPW-03** 한국어 용어 audit 대상과 판정 절차를 고정했다.
- [x] **SPW-04** 각 주장에 source path, Gradle task, expected output 또는 receipt를 연결했다.
- [x] **SPW-05** Markdown read-back, diff 검사, 미완성 표식 검사를 계획에 고정했다.
- [x] **KO-01** evidence가 없는 구현·CI·merge 상태를 PASS로 쓰지 않는다.
- [x] **KO-02** 빈 주장 대신 path, count, exit status, lifecycle 범위를 기록한다.
- [x] **KO-03** 번역투를 줄이고 한국어 reader-facing 문장으로 작성한다.
- [x] **KO-04** 기술 register와 bluetape4k API 용어를 원문 그대로 보존한다.
- [x] **KO-05** 과도한 voice를 제거하고 검증 가능한 사실 중심으로 쓴다.
- [x] **KO-06** review, lesson, PR body와 사용자 문서 표면을 전수 점검한다.
- [x] **KO-07** 문맥 용어 audit 후 source 사실을 바꾸지 않았음을 확인한다.

## 변경·비변경 경계

변경 대상은 `exposed/jdbc-tests/build.gradle.kts`,
`JdbcMigrationDriftTest.kt`, 그리고 Issue #721 설계·계획·review·lesson
문서다. migration production logic, `withLogs = false`, primary/suppressed
예외 identity, cleanup/drop, transaction semantics는 변경하지 않는다.

## Gate 결론

구현·module 검증 기준의 7-Tier gate는 Tier 1–6 **PASS**, Tier 7은
delivery pending이다. source mutation, Gradle 검증, isolated consumer,
ABI/publication metadata, dialect XML evidence와 lesson을 기록했다. 다음 단계는
Lore commit, 원격 push, Korean PR 생성 및 live metadata/DoD·hosted CI 확인이다.
PR이 열려도 merge는 별도 승인 없이는 수행하지 않는다.
