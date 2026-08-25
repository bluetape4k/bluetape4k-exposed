# Issue #724 `spring-boot/r2dbc` assertion 표준화 설계 검토

## 검토 범위와 상태

- 대상: `docs/superpowers/specs/2026-08-25-spring-boot-r2dbc-assertions-design.md`
- 기준: Issue #724 본문, `spring-boot/r2dbc`의 대상 테스트 10개,
  `bluetape4k-assertions` API, `bluetape-kotlin-patterns`, Type A full-feature
  receipt
- 단계: 구현·로컬 검증 완료, PR delivery 대기
- 검토 방식: native review lane의 startup ACK 지연을 liveness 계약에 따라
  기록한 뒤 main-session이 동일한 독립 관점을 재수행했다. native lane을
  성공으로 추정하지 않았다.

## 독립 관점 검토

| 관점 | 확인 내용 | P0 | P1 | P2 | P3 | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 성능 | test-only 직접 dependency와 `check` source scan은 production 경로와 실행 matrix를 늘리지 않는다. 약 22개 Kotlin 파일/128 KB scan은 검증 단계의 bounded 비용이며 Gradle inputs와 deterministic report output으로 up-to-date/cache 실행을 보장한다. | 0 | 0 | 0 | 0 | PASS |
| 안정성 | assertion 표현만 바꾸며 `runSuspendIO`, `runBlocking`, `withTables`, transaction/connection lease 경계를 보존한다. cancellation과 multi-DB 검증을 별도 targeted gate로 둔다. | 0 | 0 | 0 | 0 | PASS |
| 보안 | dependency는 module test source set에만 직접 추가하고, guard 진단은 Gradle `logger`만 사용한다. import·wildcard·fully-qualified 호출과 missing/unreadable source를 fail-closed로 검사한다. production secret·stdout·diagnostic payload 변경이 없다. | 0 | 0 | 0 | 0 | PASS |
| 운영 | guard를 `check`에 연결하고 compile·targeted·full module·detekt·forbidden scan을 순차 검증한다. 실패 시 test-only 변경을 되돌릴 수 있다. | 0 | 0 | 0 | 0 | PASS |
| 개발자/API | `shouldBeEqualTo`, Boolean/null/identity matcher, primitive-array overload, `assertFailsWith`의 의미 대응이 명시되어 있다. Kotlin null-safety와 직접 dependency 계약을 compile gate로 고정한다. | 0 | 0 | 0 | 0 | PASS |
| 사용자/caller | Issue #724의 10개 파일과 완료 조건을 설계·계획·PR DoD로 추적하고, Spring/Exposed 경계 보존을 명시한다. | 0 | 0 | 0 | 0 | PASS |

## Main 통합 판정

- P0: 0
- P1: 0
- P2: 0 (초기 security lane의 두 P2는 guard 호출 범위와 fail-closed 계약을 설계에 반영해 해소)
- P3: 0 (deterministic report output과 Gradle Kotlin DSL 타입 추론을 계획에 고정하고 compile/check에서 확인)
- 통합 판정: **PASS — 계획 단계로 진행 가능**
- 필수 보완: guard 구현을 `logger.error`/`logger.lifecycle`만 사용하도록 고정하고,
  import·wildcard·fully-qualified 호출·missing/unreadable source의 synthetic RED
  probe를 계획에 명시한다. ByteArray 내용 비교·lease 예외 identity·nullable
  smart-cast targeted 테스트도 유지한다.
- 범위 이탈: production source, Spring auto-configuration, Exposed SQL,
  dependency catalog, workflow, README/API 문서는 변경하지 않는다.

성능 lane은 native `code-reviewer` 결과로도 P0/P1/P2/P3 차단 이슈 0건을
확인했다. 안정성·운영·개발자·사용자 관점은 native lane의 bounded fallback
후 main-session이 동일한 source/read-only 기준으로 재수행했으며, 변경 경로는
없다. security lane의 초기 P2 두 건은 설계에 반영했고 amended design 재검토에서
P0/P1/P2/P3 차단 이슈 0건으로 PASS를 확인했다. logger 출력의 source 값 비노출은
구현 task와 T4에서 검증한다.

## 계획 검토 입력

구현 계획은 직접 dependency, fail-closed import/wildcard/fully-qualified guard,
identity/content/nullable assertion 경계, 순차 DB 검증, 7-Tier와 PR DoD를
각 task와 acceptance traceability로 연결한다. 계획 단계에서도 P0/P1=0이며,
Gradle Kotlin DSL 타입 추론은 compile/check에서 확인할 P3 watch로 남긴다.

### 계획 six-lens 통합

| 관점 | 계획 검토 결과 | P0 | P1 | P2 | P3 | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 성능 | targeted→multi-DB→full 순차 실행, `--no-parallel`, bounded source scan, deterministic report output | 0 | 0 | 0 | 0 | PASS |
| 안정성 | assertion-only diff, coroutine/transaction 경계별 acceptance traceability, skipped 원인 기록 | 0 | 0 | 0 | 0 | PASS |
| 보안 | fail-closed root/read/scan 예외, logger-only·source 값 비노출, direct test dependency 범위 | 0 | 0 | 0 | 0 | PASS |
| 운영 | rollback/rerun 지점, context-mode 명령, Type A receipt와 exact changed paths | 0 | 0 | 0 | 0 | PASS |
| 개발자/API | 10개 파일별 matcher 매핑, identity/content/null 의미와 direct compile gate | 0 | 0 | 0 | 0 | PASS |
| 사용자/caller | Issue #724→acceptance→review/lesson→PR DoD 추적성과 merge 보류 | 0 | 0 | 0 | 0 | PASS |

계획 검토 통합 판정: **PASS — 구현 단계로 진행 가능**. native performance
lane은 P0/P1/P2=0, P3 권고를 보고했으며 deterministic output을 계획에 반영해
P3도 해소했다. 나머지 관점은 main-session fallback으로 재검토했고 변경 경로는
없다. 계획 writer gate(SPW-01..05, KO-01..07)는 read-back과 terminology audit로
확인한다.

## SPW writer gate

- [x] SPW-01: Issue #724와 source anchors를 대상으로 하는 Kotlin test maintainer용 문서임을 명시했다.
- [x] SPW-02: 문제·근거·경계·대안·계약·실패 모드·롤백·DoD·승인 근거 구조를 갖췄다.
- [x] SPW-03: `audit-korean-terms.mjs` 통과; KO-01..KO-07을 확인했다.
- [x] SPW-04: `gh issue view 724` live body와 대상 파일/import scan을 다시 대조했다.
- [x] SPW-05: 문서 read-back, `git diff --check`, placeholder/미완료 marker scan을 계획 gate에서 재실행한다.

## Delivery gate

Type A receipt의 main lane에 구현·검증·review·lesson evidence를 연결한 뒤 Lore
commit과 exact push를 수행한다. PR은 `develop`을 base로 하고 `Closes #724`,
assignee `debop`, milestone `2.0.0`, `test/refactoring/tech-debt` labels를
설정하며 마지막 section은 `## DoD Status`로 끝낸다. PR 생성 후에는 exact head,
body, metadata, checks를 다시 읽고 merge 없이 다음 Issue #725로 이동한다.

## 구현 후 7-Tier 재검토

구현 변경은 `spring-boot/r2dbc/build.gradle.kts`와 Issue #724가 지정한 테스트
10개로 제한했다. production source, coroutine/transaction 경계,
Spring auto-configuration, dependency catalog, workflow, README/API는 변경하지
않았다. `bluetape4k-assertions`를 test source set에 직접 선언했고, 모듈 `check`에
legacy assertion guard를 연결했다. guard는 `logger.error`/`logger.lifecycle`만
사용하며 source 값이나 사용자 입력을 출력하지 않는다.

| Tier | 관점 | 판정 | 근거 |
| --- | --- | --- | --- |
| T1 | 요구사항·Issue 추적성 | PASS | Issue #724의 10개 대상 파일, 직접 dependency, `!!` 제거, guard, PR DoD를 spec/plan/review와 diff에 대조했다. |
| T2 | API·Kotlin pattern·null-safety | PASS | `shouldBeEqualTo`, Boolean/null/identity matcher와 primitive `ByteArray` 동등성 overload를 의미별로 적용했다. ABI resource는 `shouldNotBeNull()`로 smart-cast하며 새 `!!`는 없다. `compileTestKotlin`과 detekt가 성공했다. |
| T3 | coroutine·transaction·lifecycle | PASS | `runSuspendIO`, `runBlocking`, `withTables`, Flow 수집, lease 예외·cancellation identity, multi-DB parameterization을 변경하지 않았고 대상·Multi-DB·전체 테스트가 통과했다. |
| T4 | security·diagnostic·logger-only guard | PASS | import/wildcard/fully-qualified/unqualified legacy call을 fail-closed로 검사하고 missing root, empty inventory, read/scan/write 예외를 `GradleException`으로 전파하도록 구현했다. report는 위치·rule만 기록하며 `println`/`System.out`/`System.err`는 0건이다. |
| T5 | performance·test cost | PASS | guard는 sorted Kotlin inventory와 declared inputs/outputs를 사용하고 configuration cache를 재사용한다. targeted 22개, Multi-DB 3개, 전체 315개 테스트를 `--no-parallel`로 순차 실행했다. |
| T6 | 운영·rollback·재현성 | PASS | context-mode Gradle 명령, deterministic report, `git diff --check`, target forbidden scan, XML 집계, Type A receipt evidence를 남겼다. 변경은 test-only라 revert 지점이 명확하다. |
| T7 | delivery·문서·증거 무결성 | PASS (로컬) | spec/plan/review/lesson을 한국어로 read-back하고 SPW-01..05·KO-01..07을 확인했다. Lore commit·exact push·Korean PR은 다음 delivery gate에서 수행하며 hosted checks/review와 merge는 아직 보류한다. |

### 최종 통합 판정

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 통합 판정: **PASS — 로컬 구현·검증 완료, PR delivery 가능**
- 남은 외부 gate: exact commit/push, PR metadata/body read-back, hosted checks와 GitHub
  review. merge 및 auto-merge는 별도 승인이 없으므로 수행하지 않는다.

### 최신 검증 증거

| 검증 | 결과 |
| --- | --- |
| `checkSpringBootR2dbcAssertionStyle` | PASS, Kotlin test source 22개 검사, configuration cache 저장·재사용 확인 |
| `compileTestKotlin` | PASS, 기존 `R2dbcBindValueSnapshotterTest` unchecked cast warning만 존재 |
| targeted tests | PASS, 22 tests / failures 0 / errors 0 / skipped 0 |
| Multi-DB test | PASS, 3 tests / failures 0 / errors 0 / skipped 0 |
| full module test | PASS, 315 tests / failures 0 / errors 0 / skipped 8 |
| detekt | PASS |
| 대상 10개 forbidden scan | legacy import/call 0, `!!` 0, `println`·`System.out/err` 0 |
| `git diff --check` | PASS |

### 문서 writer gate

- [x] SPW-01: Issue #724와 source anchor, 대상 독자를 명시했다.
- [x] SPW-02: 문제·근거·경계·대안·계약·실패·rollback·DoD 구조를 유지했다.
- [x] SPW-03: `audit-korean-terms.mjs` 결과 `findings=0`, KO-01..KO-07 확인.
- [x] SPW-04: live Issue와 구현 diff/검증 결과를 read-back 대조했다.
- [x] SPW-05: placeholder·미완료 marker scan과 `git diff --check`를 통과했다.
