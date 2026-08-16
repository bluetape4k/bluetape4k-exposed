# Issue #643 R2DBC 코루틴 QBE/FluentQuery 설계 검토

## 검토 범위

- 대상 설계: `docs/superpowers/specs/2026-08-16-issue-643-r2dbc-coroutine-fluentquery-design.md`
- 기준 커밋: `e2996664b3624df651f2ad8b85a6745179cd1613`
- 대상 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 선택한 대안: 코루틴 네이티브 QBE/FluentQuery API만 제공
- 제외 범위: `ReactiveQueryByExampleExecutor`, Reactor facade, 코루틴·Reactor 이중 facade

## 최초 6관점 검토 결과

| 우선순위 | 관점 | 발견 사항 | 설계 반영 |
| --- | --- | --- | --- |
| P1 | 성능 | `exists()`가 전체 row를 선택할 위험 | `table.id`만 `LIMIT 1`로 조회하도록 고정 |
| P2 | 성능 | page가 불필요한 count를 항상 실행할 위험 | unpaged·마지막 short page는 total을 추론하고 필요한 경우만 count |
| P2 | 성능 | property/projection reflection 반복 | repository 수명 캐시와 direct 생성자 경로 캐시 규칙 추가 |
| P1 | 안정성 | Exposed 1.4.0의 실제 outer/nested transaction 의미와 다른 단일 재사용 표현 | `useNestedTransactions` on/off 동작과 1.4.0 cleanup 제약을 분리 |
| P1 | 안정성 | 같은 logical transaction을 동일 snapshot으로 오해할 위험 | `READ COMMITTED`의 statement별 snapshot 차이를 명시 |
| P2 | 안정성 | mutable probe가 lazy 실행 전에 바뀔 수 있음 | canonical detached `R2dbcExampleSnapshot`을 진입점에서 생성 |
| P1 | 보안 | database 선택과 자원 소유자가 불명확 | Issue #637의 caller-owned outer transaction 계약을 명시 |
| P1 | 보안 | getter/transformer 전에 구조 검증이 부족 | 구조·property·matcher를 먼저 검증하고 값을 한 번만 평가 |
| P1 | 보안 | reflection/projection cause graph로 값이 노출될 위험 | cause/suppressed graph를 제거한 안전한 `MappingException`으로 고정 |
| P2 | 보안 | 취소 시 top-level과 outer cleanup 책임이 불명확 | Exposed top-level cleanup과 caller-owned outer transaction 경계를 분리 |
| P1 | 운영 | DB·transaction 소유권과 취소 후 pool 재사용 증거 부족 | top-level/default outer cleanup 및 pool 재사용 테스트 추가 |
| P1 | 운영 | page의 snapshot 보장이 과도함 | same logical transaction만 보장하고 isolation 선택은 caller 책임 |
| P2 | 운영 | 관측 정보의 cardinality·민감 정보 경계 부족 | 저카디널리티 분류와 금지 값을 명시하고 새 registry를 배제 |
| P1 | 개발자 API | base interface 확장이 외부 직접 구현체 ABI를 깨뜨림 | base는 유지하고 opt-in child interface를 추가 |
| P1 | 개발자 API | 공개 4인자 생성자로 domain/projection metadata가 부족 | factory internal collaborator와 direct first-probe 고정 경로를 분리 |
| P2 | 개발자 API | domain partial projection 의미가 모호함 | `asType` 뒤 projection 입력의 exact-set만 허용하고 domain partial은 거부 |
| P1 | 사용자 | `one()`과 fluent limit의 우선순위가 불명확 | limit을 무시하고 최대 2건으로 cardinality를 검사 |
| P1 | 사용자 | Flow 생성·수집의 transaction 경계가 불명확 | 생성은 무동작, 수집 시 transaction 선택, 외부 transaction 예제 추가 |
| P1 | 사용자 | opt-in migration과 projection 실패 형태가 불명확 | 부모 interface 변경, 잘못된 사용 예, 오류 taxonomy를 명시 |

## 통합 결정

1. Issue #643은 코루틴 네이티브 계약 하나만 제공하며 Reactor 타입은 공개 API와 구현에서 제외한다.
2. 기존 `ExposedR2dbcRepository`에는 새 abstract 메서드를 추가하지 않는다. `ExposedR2dbcQueryByExampleRepository`가 opt-in 하위 계약을 제공한다.
3. repository는 `R2dbcDatabase`를 소유하거나 주입받지 않는다. 다중 DB caller는 Issue #637과 같이 outer `suspendTransaction(database)` 안에서 terminal을 호출하거나 Flow를 수집한다.
4. Exposed 1.4.0은 outer transaction에서 `useNestedTransactions=false`이면 outer 객체를 그대로 반환하고, `true`이면 same-connection wrapper/savepoint를 만든다. 1.4.0 nested 경로는 wrapper close/savepoint release를 보장하지 않으므로 QBE는 `true` 조합을 SQL 전에 거부한다.
5. `Example`과 mutable probe는 plan에 보관하지 않고 검증된 detached snapshot만 보관한다.
6. `exists()`는 ID-only `LIMIT 1`, page는 조건부 count, property/projection metadata는 repository 수명 캐시를 사용한다.
7. outer transaction context별 terminal lease로 같은 connection의 병렬 terminal을 SQL 전에 거부하고 성공·예외·취소 모두 `finally`에서 해제한다.
8. QBE mapping 오류는 type/property만 남기고 cause graph와 값은 제거한다. QBE 자체 로그와 metric tag에도 probe·row·bind·ID·raw SQL·접속 정보를 남기지 않으며, 기존 Exposed logger는 caller-controlled 경계로 분리한다.

## 기각하거나 정규화한 제안

- repository별 `R2dbcDatabase` 주입: Issue #637의 caller-owned database 계약과 충돌하므로 기각했다.
- 기존 base interface 직접 확장: 외부 direct implementor의 binary/source 호환성을 깨뜨리므로 opt-in 하위 interface로 정규화했다.
- 모든 outer 호출이 nested wrapper를 만든다는 표현과 동일 snapshot 보장: Exposed 1.4.0의 on/off 분기 및 일반적인 `READ COMMITTED` 의미와 달라 기각했다.
- Reactor 표준 facade 병행: 이번 범위의 단일 coroutine transaction·cancellation 계약을 훼손하므로 기각했다.

## 재검토 중 추가 발견과 폐쇄

| 우선순위 | 관점 | 발견 사항 | 최종 처리 |
| --- | --- | --- | --- |
| P1 | 사용자 | transformer empty와 raw null `INCLUDE`의 JDBC parity 충돌 | non-null은 제외하고 raw null `INCLUDE`는 `IS NULL` 유지 |
| P1 | 사용자 | projection/Flow 예제가 suspend 문맥 없이 작성됨 | `suspend fun`, outer transaction, 기대 예외를 포함한 예제로 수정 |
| P1 | 운영 | QBE 로그 금지 범위가 Exposed 자체 logger까지 과도하게 포함 | QBE redaction과 caller-controlled Exposed logger 경계를 분리 |
| P1 | 보안·안정성 | array/collection/custom transformer bind 값의 불변성 부족 | immutable whitelist, defensive deep copy, 복제 불가 custom 값 거부 |
| P1 | 보안 | `CancellationException`과 `Error`가 sanitization에 wrapping될 수 있음 | 두 타입은 wrapping 대상에서 제외하고 원래 객체 전파 |
| P1 | 보안 | 사용자 property token의 control 문자·길이 제한 누락 | JDBC와 같은 separator 제거 및 128자 제한 적용 |
| P1 | 안정성 | `useNestedTransactions` on/off 의미와 nested cleanup 보장이 부정확 | `false` outer 객체 재사용, `true` SQL 전 fail-fast로 고정 |
| P1 | 안정성 | terminal execution lease의 취소·예외 해제 규칙 누락 | 성공·예외·취소 모두 `finally` 해제 |

## 재검토 상태

| 관점 | P0 | P1 | 상태 |
| --- | ---: | ---: | --- |
| 성능 | 0 | 0 | PASS |
| 안정성 | 0 | 0 | PASS — 2차 보완 재검토 |
| 보안 | 0 | 0 | PASS — 2차 보완 재검토 |
| 운영 | 0 | 0 | PASS — 2차 보완 재검토 |
| 개발자 API | 0 | 0 | PASS |
| 사용자 | 0 | 0 | PASS — 2차 보완 재검토 |

## 문서 품질 게이트

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| SPW-01 독자·목적·근거 고정 | PASS | Issue #643, Spring Data/Exposed 1.4.0, repo source와 비지원 범위 고정 |
| SPW-02 artifact 계약 충족 | PASS | 문제·대안·경계·API·실패·호환성·인수 조건·DoD 포함 |
| SPW-03 한국어 기술 문체 | PASS | 식별자를 보존하고 엔지니어 대상의 직접적인 한국어로 재독 |
| SPW-04 기술 의미·추적성 | PASS | 최초 및 추가 P1을 최종 설계 처리와 연결 |
| SPW-05 최종 read-back | PASS | 제목·표·목록·코드 펜스·범위·미실행 항목 확인 |

한국어 자연스러움 체크는 `KO-01` 사실·식별자 보존, `KO-02` 근거 없는 강조 제거, `KO-03` 번역투 제거, `KO-04` 용어·서술어 일치, `KO-05` 불필요한 비유 배제, `KO-06` 본문·표·링크·코드 전체 확인을 모두 통과했다.

## 최종 판정

- 6관점 독립 재검토 결과: P0 0건, P1 0건
- 설계 검토 상태: `DONE`
- Issue #643 전체 상태: `PENDING` — 사용자 설계 승인 뒤 구현 계획·계획 검토·구현을 진행한다.
- 알려진 제약: Exposed 1.4.0 outer transaction에서 `useNestedTransactions=true`인 QBE 호출은 SQL 전에 실패한다.

## DoD Status

- [x] 코루틴 전용 대안 선택과 Reactor 제외 범위를 기록했다.
- [x] 최초 6관점 검토 결과를 설계에 반영했다.
- [x] 영향받은 6관점을 재검토해 P0/P1 0건을 확인했다.
- [x] 문서 품질 게이트와 Markdown 검증을 통과했다.
- [x] 설계·검토 문서를 커밋했다.
- [ ] 사용자에게 작성된 설계 승인을 요청한다.
- 설계 검토 상태: `DONE`
- 전체 상태: `PENDING`
- 미실행 범위: 구현 계획, production code, 구현 테스트, PR, CI
