# Issue #794 mixed Spring Data MappingContext 교훈

## 맥락

JDBC와 R2DBC Spring Boot auto-configuration이 같은 `exposedMappingContext`
bean 이름을 사용하지만, JDBC 설정은 deprecated JDBC facade를 반환하고
R2DBC 설정은 common `ExposedMappingContext` 타입을 조건으로 검사하고 있었다.
두 설정을 함께 로드하면 이름 조건 때문에 R2DBC 설정은 물러나고, common 타입
기반 주입은 bean을 찾지 못했다.

## 원인과 결정

- RED 회귀에서 두 auto-configuration을 함께 로드한 context의 common mapping
  context 수가 `0`임을 확인했다.
- JDBC auto-configuration의 기존 public
  `exposedMappingContext(): jdbc.mapping.ExposedMappingContext` 반환 descriptor를
  common 타입으로 직접 바꾸면 legacy ABI가 깨질 수 있다.
- 기존 public 메서드는 유지하되 `@Bean` 등록을 제거하고, 같은 bean 이름으로
  private common-returning `@Bean`을 등록했다. `@ConditionalOnMissingBean`에는
  common 타입과 기존 bean 이름을 함께 지정해 사용자 bean 및 다른
  auto-configuration과의 back-off semantics를 보존했다.
- mixed context 회귀는 JDBC 테스트 모듈에서 실제 JDBC/R2DBC auto-configuration
  클래스를 함께 로드해 검증하며, R2DBC 의존성은 test scope에만 둔다.

## 결과

mixed JDBC/R2DBC context가 정확히 하나의 common mapping context bean을
생성하고, deprecated JDBC facade class와 기존 public ABI는 JDBC artifact에
남는다. JDBC repository의 common mapping context 주입도 그대로 동작한다.

## 검증

- production 수정 전 mixed `ApplicationContextRunner` 회귀: common bean
  `0`으로 실패(RED).
- 수정 후 mixed context 회귀: `5 passing`.
- JDBC 전체 모듈 테스트: `261 passing`, failures/errors `0`.
- JDBC `detekt`, JDBC `checkKotlinAbi`, root `checkProductionAbi`: 통과.
- R2DBC source legacy JDBC import guard: `0 matches`.
- R2DBC dependency boundary: `BUILD SUCCESSFUL`.
- `git diff --check`: 통과.

## 다음 변경을 위한 guard

Spring Boot auto-configuration의 public `@Bean` 메서드 반환 타입을 common
타입으로 교체하기 전에 generated ABI를 확인한다. legacy descriptor를 유지해야
하면 bean registration method와 compatibility method를 분리하고, 명시적인
bean 이름·type 조건과 mixed context 회귀를 함께 검증한다.
