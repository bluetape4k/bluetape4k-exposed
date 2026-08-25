# Issue #726 설계·계획 검토

## 검토 범위와 근거

- 설계: docs/superpowers/specs/2026-08-25-issue-726-ddd-modulith-uuid-v7-design.md
- 계획: docs/superpowers/plans/2026-08-25-issue-726-ddd-modulith-uuid-v7-plan.md
- 기준: origin/develop, 1242e5eb990a1f362233dba9542aa6e4d7192730
- source anchors: examples/ddd-spring-modulith-demo/.../OrderDomain.kt,
  .../DddSpringModulithDemoApplication.kt,
  .../DddSpringModulithDemoApplicationTest.kt,
  .../build.gradle.kts
- 적용 기준: bluetape-workflow Type A, bluetape-kotlin-patterns, bluetape-writer

직접 ID generator dependency 선언은 Type B fast-track의 신규 dependency 금지
경계를 넘으므로 Type B 영수증을 취소하고 Type A 영수증을 새로 만들었다. 현재
세션의 native subagent를 사용하지 않고 main session에서 각 렌즈를 분리해 읽는
fallback으로 검토했다. 이 선택은 독립 모델 증거가 아니며 최종 receipt에 남긴다.

## Six-perspective 설계·계획 검토

| 렌즈 | 확인 내용 | 조치 | 결과 |
|---|---|---|---|
| T1 Performance | ID 생성은 aggregate/event 생성 시점에만 실행되고 DB round trip·직렬화 필드 수·재시도를 늘리지 않는다. | UUID v7 timestamp field 검사만 수행하고 benchmark는 범위 밖 N/A로 기록한다. | PASS |
| T2 Stability | Spring context, Modulith publication, listener, repository 흐름을 보존한다. UUID.randomUUID만 helper 호출로 바뀐다. | domain 단위 테스트와 module 전체 테스트를 순차 실행한다. | PASS |
| T3 Security | UUIDv7 시간 부분은 식별자 특성이며 인증/비밀 토큰이 아니다. event payload allowlist와 secret 비노출 계약은 기존 테스트가 담당한다. | serializer production code를 수정하지 않고 opaque payload assertions를 유지한다. | PASS |
| T4 Operator/Ops | 운영 출력·logger 경로는 이슈 범위가 아니며 직접 print 변경도 없다. | println/System.out/System.err 신규 사용 scan을 final review에 포함한다. | PASS |
| T5 Developer/API | Uuid.V7.nextId().toString()은 canonical UUID를 보존하고 직접 dependency 선언으로 import와 build 계약을 맞춘다. value class/event/listener API는 불변이다. | nextIdAsString()과 새 wrapper를 대안에서 제외한다. | PASS |
| T6 User/Caller | order-·event- prefix, aggregateId/eventId/occurredAt JSON 필드, Spring Modulith boundary verifier를 보존한다. | 기존 application test에 prefix assertion을 추가한다. | PASS |
| T7 Integration | spec→plan→source/test→review/lesson→PR traceability가 있고 README/schema/workflow 변경은 없다. | fresh command, diff check, PR exact-head read-back을 순서에 고정한다. | PASS |

## 계획 검토

- 요구사항 매핑: UUID helper, 직접 dependency, prefix/serialization,
  monotonic/version/uniqueness, bluetape assertions, DDD boundary, 7-Tier,
  commit/PR 후 다음 이슈가 각각 Task 1~5에 연결된다.
- 실행 순서: RED test → GREEN source/build → application serialization →
  proportional verification → review/lesson/PR 순서로 downstream evidence를
  앞당기지 않는다.
- 위험 해석: UUID v7 전체 UUID의 엄격한 lexical monotonicity가 아니라 48-bit
  Unix timestamp의 비감소를 검증한다. 이 제한을 설계·계획·테스트에 반복해
  잘못된 보장을 막았다.
- 의존성: version catalog의 bt4k.bluetape4k.idgenerators alias만 직접
  선언하고 version/artifact 증설은 하지 않는다.
- N/A: README locale parity, schema, workflow/catalog registration,
  Testcontainers, benchmark는 source diff에 해당하지 않는 근거를 final review에
  기록한다. N/A는 실행 생략을 뜻하지 않는다.

## Writer gate

- SPW-01 PASS: artifact 종류, 독자, 한국어 범위, 기준 SHA, source paths,
  issue/branch, exact API와 미확정 사항을 고정했다.
- SPW-02 PASS: spec은 경계·대안·실패 모드·호환성·검증·DoD를, plan은 파일·순서·
  명령·증거·rollback·PR gate를 포함한다.
- SPW-03 PASS: Korean technical register와 동일 용어를 사용하고 code token,
  command, URL, SHA를 보존했다. 자연스러움 checklist를 read-back에 적용했다.
- SPW-04 PASS: source anchors와 spec→plan traceability를 대조했고 UUID v7
  monotonicity claim을 timestamp field로 한정했다.
- SPW-05 PASS: 두 artifact와 이 검토 문서를 read-back했으며 미완성 표시,
  contradictory scope, missing acceptance mapping이 없다.

## Gate verdict

설계·계획 검토에서 P0=0, P1=0이다. 구현 전 gate를 PASS로 닫고, 다음 단계는
계획에 정의한 RED test 추가와 실제 compile evidence 수집이다. 이 문서는 구현,
최종 7-Tier, PR hosted checks 또는 merge 승인으로 해석하지 않는다.
