# Issue #726 DDD Spring Modulith UUID v7 전환 lesson

## 배경

DDD Spring Modulith 예제의 OrderId와 OrderAcceptedEvent가
java.util.UUID.randomUUID()를 직접 호출하고 있었다. bluetape4k ecosystem
사용과 ID generator 직접 dependency 선언, aggregate/event serialization 회귀
검증이 Issue #726의 범위였다.

## 결정

- aggregate와 event ID를 Uuid.V7.nextId().toString()으로 생성했다.
- order-·event- prefix와 canonical UUID 문자열을 유지했다. Base62인
  nextIdAsString()은 JSON·저장 계약을 바꾸므로 사용하지 않았다.
- UUID v7 전체 UUID의 lexical 순서를 보장한다고 가정하지 않고, 48-bit Unix
  timestamp field의 비감소와 version 7, 전체 값 유일성을 테스트했다.
- module build에 bt4k.bluetape4k.idgenerators를 직접 선언했다. 이 요구가
  Type B fast-track 신규 dependency 금지 경계를 넘으므로 Type B 영수증을
  취소하고 Type A run으로 전환했다.

## 결과와 검증

- 변경 파일은 module build 1개, production domain 1개, application test 1개,
  domain test 1개, 설계·계획·review·lesson 문서다.
- RED 단계에서 기존 UUID v4로 focused domain test가 2 tests/2 failures였다.
- compileTestKotlin: BUILD SUCCESSFUL.
- OrderDomainTest: 2 tests pass.
- 전체 module test: DddSpringModulithDemoApplicationTest 10개와 OrderDomainTest
  2개, 합계 12 tests, failures 0, errors 0, skipped 0.
- detekt: NO-SOURCE, BUILD SUCCESSFUL. 대상 module에 Detekt source가 없음을
  검증 범위로 기록했다.
- static scan: production UUID.randomUUID 0건, Uuid.V7.nextId 2건, 대상
  assertion/console legacy pattern 0건.
- git diff --check PASS, 설계·계획·review terminology audit findings 0.
- 7-Tier와 Kotlin checklist에서 P0/P1/P2/P3 모두 0이다.

## 놓친 점과 복구

첫 focused compile에서 Kotlin callable reference UUID::v7Timestamp가 Java
UUID의 member/extension 충돌로 실패했다. extension 이름을 uuidV7Timestamp로
바꾸는 것만으로 해결되지 않아 callable reference를 lambda 호출로 바꿨고,
compile과 targeted test를 다시 실행해 PASS를 확인했다. 다음에는 Java/Kotlin
표준 타입과 이름이 겹칠 수 있는 extension은 callable reference보다 명시적
lambda 호출을 우선 검토한다.

## 재발 방지

1. 이슈가 직접 dependency 선언을 요구하면 Type B boundary를 먼저 확인하고
   필요한 경우 Type A receipt를 새로 시작한다.
2. UUID v7 테스트는 version·prefix·uniqueness와 timestamp monotonicity를
   분리하고 random tail에 대한 과도한 lexical 보장을 쓰지 않는다.
3. domain helper 변경 뒤 compileTestKotlin을 먼저 실행하고 focused behavior,
   전체 module test, static scan, Detekt task surface를 순서대로 확인한다.
4. event serializer를 바꾸지 않는 refactor도 JSON prefix와 opaque payload
   회귀를 기존 application test에서 계속 고정한다.

## SPW-01~05

- SPW-01 PASS: issue, source, branch, base SHA, commands, exact results를 고정했다.
- SPW-02 PASS: context, decision, outcome, surprise, verification, future guard를 포함했다.
- SPW-03 PASS: 한국어 기술 문체와 code/API/command token을 보존했다.
- SPW-04 PASS: review, test XML counts, static scan, Detekt 결과와 대조했다.
- SPW-05 PASS: 최종 Markdown read-back과 terminology audit을 완료했다.
