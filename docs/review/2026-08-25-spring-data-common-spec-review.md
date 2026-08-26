# Spring Data 공통 모듈 설계 7-Tier 리뷰

## 검토 범위와 기준

- 대상: `docs/superpowers/specs/2026-08-25-spring-data-common-design.md`
- 근거: live issue #729, `spring-boot/jdbc`·`spring-boot/r2dbc` 현재 source와
  Gradle dependency graph, `settings.gradle.kts`, production ABI inventory,
  manual manifest
- 실행 방식: 1인 개발자 lane에서 여섯 관점을 독립적으로 순차 검토한 뒤
  main-session이 중복·severity·수용 기준을 통합했다.
- 범위: 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자

## 관점별 결과

| Priority | Lens | Evidence | Required edit | Rerun |
|---|---|---|---|---|
| P2 | Performance | mapping context와 PartTree/sort 변환은 기존 hot path를 이동할 뿐 새 round trip이나 retry를 추가하지 않는다. | 구현 계획에서 기존 JDBC/R2DBC query/sort 회귀 테스트를 유지하고 별도 benchmark는 범위 밖임을 명시한다. | performance |
| P2 | Stability | 두 adapter가 common mapping context를 등록할 때 `@ConditionalOnMissingBean`과 단독/동시 context 검증이 필요하다. transaction/cancellation semantics는 변경 범위 밖이다. | 계획에 단독 JDBC, 단독 R2DBC, combined context test와 auto-config ordering 제거 검증을 고정한다. | stability |
| P2 | Security | common 이동은 raw SQL 권한이나 입력 경계를 확대하지 않는다. 새 reflection/classpath scan을 추가하지 않으며 query/annotation 값 logging도 추가하지 않는다. | 계획에 source guard와 기존 sanitized logging 패턴 확인을 추가한다. | security |
| P2 | Operator/Ops | 새 publishable module은 BOM, ABI, manual inventory, release train과 함께 등록되어야 한다. database migration이나 runtime rollback은 없다. | 계획에 settings → publication/BOM → ABI → manual validator 순서와 실패 시 branch rollback을 명시한다. | operator |
| P1 | Developer/API | mapping interface/class의 generic signature를 단순 Kotlin typealias로 보존할 수 없고, facade가 실제로 JDBC artifact에 남아야 기존 ABI가 유지된다. | 명세에 facade 소유권 표와 typealias 금지, descriptor fallback을 추가했다. 계획은 각 symbol별 bridge/기존 구현 유지 여부를 먼저 검증한다. | developer/API |
| P2 | User/caller | R2DBC-only 사용자가 기존 `jdbc.annotation.*` import를 계속 사용할 수 없으므로 migration 예제가 필요하다. | 명세에 common import before/after와 JDBC legacy facade 범위를 추가했다. README/manual task에 동일 예제를 요구한다. | user/caller |

## 통합 판정

초기 P1은 legacy facade 소유권과 R2DBC migration 경계가 모호했던 점이다.
명세를 다음처럼 보정했다.

1. 기존 JDBC facade는 JDBC artifact에 남겨 기존 JDBC-only binary consumer의
   class loader와 ABI를 보존한다.
2. canonical API는 common package이며 R2DBC production source는 common만
   import한다.
3. R2DBC application의 legacy JDBC annotation import는 common annotation으로
   migration한다. README/manual에 before/after를 포함한다.
4. mapping interface/class는 상속 bridge가 descriptor를 보장하지 못하면
   기존 JDBC public symbol을 유지하고 common helper로만 위임한다.

이 보정은 모듈 분리 목표를 바꾸지 않고 구현 가능한 compatibility 경계를
고정한다. 현재 통합 결과는 P0=0, P1=0이며 P2는 계획과 검증 명령에 반영한다.

## SPW writer gate

- SPW-01 목적·독자·범위: PASS — 설계 리뷰 독자와 검토 범위를 명시했다.
- SPW-02 구조·문체: PASS — 관점별 표와 통합 판정을 분리했다.
- SPW-03 사실성·추적성: PASS — source/dependency/ABI/manual 근거와 수정
  지점을 연결했다.
- SPW-04 한국어 기술 문체: PASS — 코드 토큰과 한국어 설명을 구분했다.
- SPW-05 read-back: PASS — 최종 Markdown을 다시 읽고 headings/table/code
  token을 확인했다.

## Step DoD

`Step 2-R: PASS` — 여섯 관점과 main-session 통합 리뷰를 완료했고 latest
integrated table의 P0=0/P1=0을 확인했다. P2는 계획 단계의 검증 항목으로
추적한다.

