# Issue #681 ExposedCursorPage 직렬화 계약 lesson

## Context

`ExposedCursorPage<T, C>`는 #645/#648에서 추가된 public cursor pagination DTO였지만,
`java.io.Serializable`과 명시적인 `serialVersionUID`를 구현하지 않았다. 기존 KDoc와
README는 cursor token의 encode·서명·범위 지정 책임을 호출자에게 둔다는 점만 설명했고,
DTO 객체 자체를 cache/session/message 경계에서 Java serialization할 수 있는지는
정의하지 않았다. 기존 core 테스트도 `hasNext`, `content`, `nextCursor` 불변식만 검증했다.

## Decision or Finding

- DTO 객체는 `Serializable`을 구현하고 `serialVersionUID = 1L`을 명시한다.
- `T`, `C`, 그리고 런타임 `content` 리스트 구현의 직렬화 가능성은 generic 경계로
  강제하지 않고 호출자가 보장한다. 이 선택으로 기존 `Comparable<C>` API 경계를
  바꾸지 않는다.
- DTO 객체 직렬화와 전송용 불투명 cursor token의 encode·서명·만료·범위 지정·decode는
  별도 계약이다. 후자의 책임은 계속 호출자에게 둔다.
- 회귀 테스트는 실제 Java serialization round-trip과
  `ObjectStreamClass.lookup(...).serialVersionUID == 1L`을 함께 확인한다.

## Outcome

현재 구현은 public DTO의 Java serialization 경계와 UID 안정성을 명시하고, EN/KO core
README와 KDoc에서 generic 필드의 책임 및 cursor token 경계를 같은 문장으로 설명한다.
RED 단계에서는 기존 구현이 round-trip에서 `java.io.NotSerializableException`을 내고
UID 조회에서 비직렬화 타입으로 실패했으며, 최소 수정 후 두 회귀가 모두 통과했다.

## Verification

| 검증 | 결과 |
| --- | --- |
| core cursor regression | fresh targeted test: 6개 실행, 0 실패 |
| core full test | `:bluetape4k-exposed-core:test`: 295개 실행, 13개 skipped, 0 실패/오류 |
| JDBC consumer | `EXPOSED_TEST_DB=H2 :bluetape4k-exposed-jdbc:test`: 211개 실행, 23개 skipped, 0 실패/오류 |
| R2DBC consumer | `EXPOSED_TEST_DB=H2 :bluetape4k-exposed-r2dbc:test`: 203개 실행, 7개 skipped, 0 실패/오류 |
| static checks | root `detekt`와 `git diff --check` 통과 |

위 테스트는 `--no-configuration-cache --no-daemon --rerun-tasks`로 순차 실행했다. CI exact-head,
PR review, merge와 Issue close는 이 구현 작업의 별도 외부 경계이며 아직 수행하지 않았다.

## Future Guidance

1. 새 public Kotlin DTO가 cache/session/message 경계에서 Java serialization 대상이면,
   `Serializable`과 명시 UID를 추가하고 실제 payload 타입을 사용한 round-trip 회귀를 함께
   작성한다.
2. DTO가 직렬화 가능하다는 사실을 cursor token의 보안·수명·테넌트 범위 계약으로
   확장해서 해석하지 않는다. 객체 직렬화와 transport token 정책을 문서에서 분리한다.
3. generic bound를 넓혀 호환성을 깨기 전에, 호출자 책임으로 남겨야 하는 런타임 payload와
   타입 시스템으로 강제해야 하는 불변식을 구분한다.

## Writer DoD (SPW-01~05)

- [x] **SPW-01** — live Issue #681, `ExposedCursorPage.kt`, core test, EN/KO README를
  source ledger로 고정하고 CI/PR/merge를 미수행 범위로 기록했다.
- [x] **SPW-02** — Context, Decision or Finding, Outcome, Verification, Future Guidance
  구조로 문제·결정·결과·증거·재발 방지 규칙을 완성했다.
- [x] **SPW-03** — 한국어 technical register를 적용하고 `Serializable`,
  `serialVersionUID`, `Comparable<C>`, `NotSerializableException` 같은 식별자와
  exact error token을 보존했다.
- [x] **SPW-04** — RED/GREEN 결과, 테스트 수, skipped 수, 명령 플래그를 fresh Gradle
  결과와 대조했으며 cursor token 책임과 DTO 직렬화 책임을 분리해 기술했다.
- [x] **SPW-05** — 최종 Markdown read-back으로 heading, 표, code token, 수치, 외부 경계를
  확인했고 Type C workflow evidence에 연결한다.
