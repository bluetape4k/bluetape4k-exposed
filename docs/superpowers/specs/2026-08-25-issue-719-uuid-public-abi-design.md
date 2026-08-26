# Issue #719 UUID 공개 ABI 충돌 제거 설계

## 문제와 현재 근거

JDBC와 R2DBC repository에는 Kotlin `kotlin.uuid.Uuid`와 Java
`java.util.UUID`를 위한 공개 특수화가 각각 `Uuid*`와 `UUID*` 이름으로
존재한다. macOS의 case-insensitive filesystem에서는 이 두 JVM class 이름이
충돌해 KGP `checkKotlinAbi` 결과가 플랫폼에 따라 달라진다.

현재 기준은 `origin/develop@1242e5eb990a1f362233dba9542aa6e4d7192730`이며,
실패를 ABI baseline만 갱신하는 방식으로 숨기지 않는다. 영향 범위는
`exposed/jdbc`, `exposed/r2dbc`, 두 모듈의 API baseline, README 영어/한국어,
마이그레이션 안내와 이름 검증 테스트다.

## 목표와 제외 범위

목표는 다음 네 계열의 JVM class 이름을 파일시스템에 안전하게 고정하는
것이다.

- `KotlinUuidJdbcRepository` / `JavaUuidJdbcRepository`
- `KotlinUuidSoftDeletedJdbcRepository` / `JavaUuidSoftDeletedJdbcRepository`
- `KotlinUuidR2dbcRepository` / `JavaUuidR2dbcRepository`
- `KotlinUuidSoftDeletedR2dbcRepository` / `JavaUuidSoftDeletedR2dbcRepository`

기존 1.x 소스의 단계적 이관을 위해 old name은 deprecated source-only
`typealias`로 남긴다. typealias는 JVM class를 만들지 않으므로 이미 컴파일된
consumer는 canonical 이름으로 재컴파일해야 한다. 저장 데이터, 테이블/컬럼,
UUID 직렬화 형식, `UUIDAuditable*`처럼 이 충돌과 무관한 이름은 변경하지
않는다.

## 대안과 결정

### 대안 A — Kotlin 계열만 이름 변경

Java `UUID` 계열은 유지하고 Kotlin 계열을 `KotlinUuid*`로 변경한다. 변경
폭은 작지만 Java 계열의 `UUID` 표기가 filesystem 문제를 계속 노출하며,
네 계열의 naming rule이 일관되지 않다.

### 대안 B — Java 계열만 이름 변경

Kotlin 계열은 유지하고 Java 계열을 `JavaUuid*`로 변경한다. 현재 충돌을
피할 수 있지만 같은 public API 안에 `Uuid`와 `UUID`라는 두 naming convention이
남아 신규 특수화의 규칙을 설명하기 어렵다.

### 대안 C — 두 계열 모두 명시적 접두사로 변경 (채택)

Kotlin 계열은 `KotlinUuid*`, Java 계열은 `JavaUuid*`로 통일한다. 두 class
이름이 case-only 차이를 만들지 않고, JVM 구현 타입과 식별자 출처가 이름에
드러난다. 1.x 이름은 source-only typealias로 제공하되 binary forwarding
class는 만들지 않는다. forwarding class는 같은 충돌을 재도입하기 때문이다.

## API·호환성 계약

각 canonical interface의 generic key type은 기존과 동일하다.

- Kotlin 계열은 `JdbcRepository<kotlin.uuid.Uuid, E>` 또는
  `R2dbcRepository<kotlin.uuid.Uuid, E>`를 유지한다.
- Java 계열은 `JdbcRepository<java.util.UUID, E>` 또는
  `R2dbcRepository<java.util.UUID, E>`를 유지한다.
- 기존 이름은 `@Deprecated`와 `ReplaceWith`가 붙은 source-only typealias다.
- 2.0은 breaking-change major line이므로 binary compatibility는
  canonical 이름 재컴파일을 요구한다.

## 실패 모드와 방어

1. **case-insensitive classpath 충돌 재발** — 네 계열의 `jar tf` 결과와
   `checkKotlinAbi`에 legacy class가 없는지 검사한다.
2. **Kotlin/Java key type이 뒤바뀜** — `javap` descriptor와 API baseline에서
   `kotlin.uuid.Uuid`와 `java.util.UUID`를 각각 확인한다.
3. **소스 migration 안내 누락** — JDBC/R2DBC README 영어/한국어와 별도
   migration 문서에 동일한 8개 old→canonical 매핑을 기록한다.
4. **assertion 계약 이탈** — 이름 검증 테스트는 `io.bluetape4k.assertions`
   matcher만 사용하고 직접 stdout 출력은 사용하지 않는다.
5. **baseline workaround로 문제 은닉** — baseline 갱신 후에도 canonical
   compile, ABI check, jar, javap를 독립적으로 다시 실행한다.

## 수용 기준과 DoD

- 네 모듈 계열에 canonical public interface와 source-only legacy alias가
  존재한다.
- JDBC/R2DBC API baseline의 legacy class descriptor가 제거되고 canonical
  descriptor가 생성된다.
- JDBC 218개(25개 기존 skip), R2DBC 204개(7개 기존 skip) H2 모듈 테스트와
  canonical naming focused test가 통과한다.
- JDBC/R2DBC Detekt와 `git diff --check`가 통과한다.
- `jar tf`에 canonical 8개 class family가 있고 대상 legacy class가 없다.
- `javap`가 Kotlin/Java UUID generic 계약을 확인한다.
- README 영어/한국어와 migration 문서가 binary 재컴파일 요구사항을 설명한다.
- 독립 7-Tier 리뷰가 P0/P1 없이 PASS하고, PR은 `## DoD Status`로 끝난다.
