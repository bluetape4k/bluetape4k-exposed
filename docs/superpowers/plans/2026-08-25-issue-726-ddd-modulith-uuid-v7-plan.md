# Issue #726 DDD Spring Modulith UUID v7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or the repository's inline execution lane to apply each checked step. Steps use checkbox syntax for tracking.

**Goal:** DDD Spring Modulith 예제의 aggregate/event ID를 직접 선언한 bluetape4k UUID v7 generator로 생성하고 기존 이벤트 계약을 회귀 테스트로 고정한다.

**Architecture:** 기존 OrderId와 OrderAcceptedEvent의 문자열 경계를 유지하고 ID 생성 구현만 Uuid.V7.nextId().toString()으로 교체한다. Spring context가 필요 없는 domain 테스트에서 ID 속성을 검증하고, 기존 application test에서 Spring Modulith 직렬화·opaque payload 계약을 검증한다.

**Tech Stack:** Kotlin/JVM, Spring Boot 4, Spring Modulith, Exposed, JUnit 5, bluetape4k-assertions, bluetape4k-idgenerators, Gradle version catalog.

---

## 기준과 변경 파일

- 기준 branch: refactor/ddd-modulith-uuid-v7, base origin/develop (1242e5eb990a1f362233dba9542aa6e4d7192730)
- Issue: #726
- 설계: docs/superpowers/specs/2026-08-25-issue-726-ddd-modulith-uuid-v7-design.md
- 변경: examples/ddd-spring-modulith-demo/build.gradle.kts, .../orders/OrderDomain.kt, .../DddSpringModulithDemoApplicationTest.kt, 새 .../orders/OrderDomainTest.kt
- 검토/lesson: docs/review/2026-08-25-issue-726-ddd-modulith-uuid-v7-review.md, docs/lessons/2026-08-25-issue-726-ddd-modulith-uuid-v7.md
- 변경하지 않음: serializer production code, event class, listener/repository, README, schema, workflow, 다른 examples

## Task 1: RED — domain/event 회귀 테스트를 먼저 추가한다

**Files:**

- Create: examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomainTest.kt
- Modify: examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt

- [ ] OrderDomainTest에 Order.accept와 OrderId.newId의 실패 가능한 계약을 작성한다. UUID.fromString, UUID.version, UUID v7 timestamp field, Set.size, prefix를 shouldBeEqualTo·shouldBeTrue로 검증하고 raw JUnit assertion은 사용하지 않는다.
- [ ] 반복 생성한 64개 order에서 aggregate UUID와 event UUID를 각각 파싱해 version 7, 전체 유일성, timestamp 비감소를 확인한다. timestamp 추출은 (uuid.mostSignificantBits ushr 16) and 0xFFFFFFFFFFFFL로 고정해 UUID v7의 random tail에 대해 엄격한 lexical monotonicity를 주장하지 않는다.
- [ ] 기존 serialization test에 aggregateId=order-와 eventId=event- 접두사 포함 검증을 추가해 JSON prefix 보존을 먼저 고정한다.
- [ ] RED 실행: :examples-ddd-spring-modulith-demo:compileTestKotlin을 실행해 Uuid 기반 production 구현 전 테스트가 실패하거나 새 symbols가 없음을 확인하고 raw 결과를 기록한다. 실패가 예상과 다르면 구현으로 진행하지 않고 테스트 계약을 고친다.

## Task 2: GREEN — production helper와 직접 dependency를 적용한다

**Files:**

- Modify: examples/ddd-spring-modulith-demo/build.gradle.kts
- Modify: examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomain.kt

- [ ] build dependency에 implementation(bt4k.bluetape4k.idgenerators)를 추가하고 다른 version/artifact는 선언하지 않는다.
- [ ] OrderDomain.kt에서 java.util.UUID random import를 제거하고 io.bluetape4k.idgenerators.uuid.Uuid를 import한다.
- [ ] OrderId.newId()를 OrderId("order-" + Uuid.V7.nextId())로 바꾼다.
- [ ] Order.accept()의 event ID를 eventId = "event-" + Uuid.V7.nextId()로 바꾼다. event fields, acceptedAt, recordDomainEvent, exception과 DDD boundary는 수정하지 않는다.
- [ ] GREEN 실행: :examples-ddd-spring-modulith-demo:compileTestKotlin과 ...OrderDomainTest targeted test를 실행해 새 테스트가 PASS하는지 확인한다.

## Task 3: 기존 application serialization 계약을 검증한다

**Files:**

- Test: examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt

- [ ] focused Spring test를 실행해 aggregateId, eventId, occurredAt JSON fields, opaque payload, secret/type metadata 비노출과 prefix assertions가 동시에 PASS하는지 확인한다.
- [ ] 테스트 결과에서 failure/error/skipped를 기록하고 기존 Modulith boundary verifier가 함께 PASS했는지 확인한다.

## Task 4: 비례 검증과 7-Tier review를 수행한다

- [ ] 정적 scan으로 examples/ddd-spring-modulith-demo/src/main의 UUID.randomUUID가 0건이고 Uuid.V7.nextId가 2건임을 확인한다. println, System.out, System.err 신규 사용과 raw assertion import를 함께 검색한다.
- [ ] 순차 실행: :examples-ddd-spring-modulith-demo:test --no-parallel (Testcontainers가 없더라도 모듈 전체 결과를 확인한다).
- [ ] :examples-ddd-spring-modulith-demo:detekt task 존재 여부를 먼저 확인하고, 있으면 실행한다. 없으면 task listing과 root static scope를 N/A 근거로 남긴다.
- [ ] git diff --check, Kotlin pattern checklist, dependency/catalog scan을 실행한다. README·locale parity와 module registration은 source/docs 변경이 없다는 근거로 N/A를 기록한다.
- [ ] T1 Performance, T2 Stability, T3 Security, T4 Operator/Ops, T5 Developer/API, T6 User/Caller, T7 Integration을 현재 diff와 fresh output에 대조한다. P0/P1은 0이어야 하며 P2/P3는 수정하거나 명시적으로 N/A 처리한다.

## Task 5: delivery artifact와 PR을 만든다

- [ ] docs/review/...-review.md에 scope, source basis, six-lens/T7 findings, SPW-01~05, Kotlin checklist, command evidence, P0/P1 convergence를 기록한다.
- [ ] docs/lessons/...-lesson.md에 Type B→Type A 재분류 이유, UUID v7 timestamp monotonicity 해석, 검증 결과, miss와 future guard를 기록한다.
- [ ] 두 artifact를 read-back하고 Korean naturalness/terminology audit을 수행한다. 기술 token·명령·수치·불확실성을 변경하지 않는다.
- [ ] Lore 형식의 한국어 커밋을 만들고 exact head를 origin/refactor/ddd-modulith-uuid-v7에 push한다.
- [ ] Issue #726 metadata를 재확인한 뒤 Korean PR을 develop 대상으로 만들고 Closes #726, assignee debop, milestone 2.0.0, labels test, refactor, tech-debt를 적용한다. PR body 마지막은 ## DoD Status로 둔다.
- [ ] gh pr view --json와 gh pr checks로 exact head, body, metadata, check 상태를 다시 읽는다. hosted pending/review/merge는 PENDING으로 남기고 gh pr merge와 auto-merge는 실행하지 않는다.

## 요구사항 추적

| Issue #726 요구사항 | 계획 증거 |
|---|---|
| bluetape4k Uuid.V7 사용 | Task 2 source/build, Task 4 static scan |
| 직접 ID generator dependency | Task 2 build, Type A classification/lesson |
| aggregate/event prefix·serialization 보존 | Task 1/3 tests, Task 4 review |
| monotonic/version/uniqueness | Task 1 OrderDomainTest |
| bluetape4k-assertions 활용 | Task 1 tests, existing application test |
| DDD boundary·7-Tier | Task 3 boundary verifier, Task 4 T1~T7 |
| commit/PR 후 다음 이슈 | Task 5 exact push/PR; merge held, then #727 live read |

## 롤백과 재실행

커밋 전 실패는 위 네 source/test 파일만 되돌리고 설계·계획을 보존한다. 컴파일 실패는 실제 source line을 고친 뒤 RED/GREEN부터 재실행한다. 커밋 후 회귀는 기존 PR branch를 유지하고 후속 fix commit으로 수정한다. 직접 dependency가 catalog resolution을 깨뜨리면 bt4k.bluetape4k.idgenerators alias와 BOM source를 재확인하되 새 version을 임의로 추가하지 않는다.

## SPW-01~05 계획 gate

- SPW-01 PASS: 독자, issue, 기준 SHA, 정확한 파일·명령·기술 token을 고정했다.
- SPW-02 PASS: dependency 순서, RED/GREEN, 테스트, rollback, docs/PR gate를 포함했다.
- SPW-03 PASS: 한국어 기술 문체와 code/command token 보존 규칙을 적용한다.
- SPW-04 PASS: spec→plan→source/test→review/lesson traceability 표를 제공한다.
- SPW-05 PASS: 구현 전 plan read-back과 미완성 표시/type consistency 점검을 완료했다.
