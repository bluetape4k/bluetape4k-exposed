# Issue #724 `spring-boot/r2dbc` assertion 표준화 설계 검토

## 검토 범위와 상태

- 대상: `docs/superpowers/specs/2026-08-25-spring-boot-r2dbc-assertions-design.md`
- 기준: Issue #724 본문, `spring-boot/r2dbc`의 대상 테스트 10개,
  `bluetape4k-assertions` API, `bluetape-kotlin-patterns`, Type A full-feature
  receipt
- 단계: 구현 전 설계 검토
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

## 다음 gate

설계 검토가 PASS이므로 Type A 계획 문서를 작성한다. 계획 검토와 7-Tier
review가 다시 PASS가 될 때까지 Kotlin/build 파일을 수정하지 않는다.
