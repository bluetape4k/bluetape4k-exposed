# R2DBC `transactionManagerRef` 계약 정렬 설계

> Issue #637 · Type A · P1 · milestone `1.13.0`

## 문제

`@EnableExposedR2dbcRepositories`는 `transactionManagerRef`를 공개하지만,
R2DBC 저장소 팩토리와 저장소 구현은 Spring transaction interceptor를 거치지
않고 Exposed의 `suspendTransaction` 경로를 직접 사용한다. 현재 설정 확장도 이
속성을 `R2dbcDatabase` 선택이나 Spring transaction manager bean으로 연결하지
않는다. 따라서 사용자는 다중 데이터베이스 선택이 동작한다고 오해할 수 있고,
설정값은 실행 경로와 분리된 공개 계약으로 남아 있다.

현재 근거:

- `EnableExposedR2dbcRepositories.kt`가 `transactionManagerRef`를 공개한다.
- `ExposedSuspendRepositoryConfigurationExtension.kt`는 repository factory bean을
  등록하지만 해당 속성을 전달하지 않는다.
- `ExposedR2dbcRepositoryFactory`와 `SimpleExposedR2dbcRepository`는 Spring
  transaction interceptor 대신 Exposed `suspendTransaction`을 사용한다.
- `streamAll(database)`와 외부 `suspendTransaction(database)`가 이미 명시적
  데이터베이스 선택 경계를 제공한다.
- `spring-boot/r2dbc/README.md`와 `README.ko.md`는 애플리케이션이
  `R2dbcDatabase`와 리소스 수명주기를 소유한다고 설명한다.

## 목표와 경계

목표는 오해를 제거하면서 기존 소스·바이너리 계약을 보존하는 것이다.

- `transactionManagerRef`의 이름과 기본값은 유지한다.
- 기본값이 아닌 값을 repository 등록 단계에서 즉시 거부한다.
- 해당 속성이 `R2dbcDatabase`를 선택하거나 Spring transaction manager를
  생성한다는 의미를 문서와 KDoc에서 제거한다.
- 다중 데이터베이스 사용법은 `suspendTransaction(database) { ... }` 또는
  `streamAll(database)`로 명시한다.
- `docs/manual/**`의 1.12.1 릴리스 고정 문서와 별도 API 브리지는 변경하지
  않는다.

이번 변경은 Spring transaction manager를 새로 만들거나 R2DBC 연결 소유권을
프레임워크로 이전하지 않는다. 기존 repository 실행·취소·Flow 의미도 바꾸지
않는다.

## 대안

### 대안 A — Spring transaction manager와 `R2dbcDatabase` 브리지 추가

설정 확장이 `transactionManagerRef`를 읽어 각 bean의 데이터베이스를 선택하도록
새 인프라를 추가한다. 다중 데이터베이스 선택을 설정으로 표현할 수 있지만,
애플리케이션 소유 `R2dbcDatabase` 계약과 현재 factory의 직접 Exposed 트랜잭션
경로를 바꾸며 공개 API·수명주기·ABI 범위를 크게 확장한다.

### 대안 B — ABI를 유지하고 비기본값을 조기 거부한다 (선택)

속성은 deprecated 상태로 유지하되, 기본값이 아닌 값은 repository 등록 중
`IllegalArgumentException`으로 거부한다. 실행 경로는 그대로 보존하고, 다중 DB
선택은 이미 지원하는 명시적 API로 유도한다. 잘못된 설정을 애플리케이션 시작
시점에 발견하면서 새로운 리소스 소유권이나 브리지를 도입하지 않는다.

### 대안 C — 속성을 즉시 제거한다

실행 의미에는 맞지만 기존 컴파일·바이너리 사용자에게 즉시 breaking change가
된다. 1.13.0의 범위와 맞지 않고, 사용자가 마이그레이션할 수 있는 deprecation
기간도 제공하지 못하므로 거부한다.

## 실패 모드와 대응

1. 사용자가 custom `transactionManagerRef`를 지정한다. 등록 단계에서 명확한
   오류를 반환하고 `suspendTransaction(database)` 또는 `streamAll(database)`를
   안내한다.
2. 사용자가 기본값을 유지하면서 다중 DB 선택을 기대한다. KDoc와 EN/KO README에
   기본값도 DB 선택을 수행하지 않는다고 명시하고 명시적 선택 예제를 제공한다.
3. Spring Data registrar가 extension의 `postProcess`를 호출하지 않는다. 실제
   registrar 경로를 호출하는 회귀 테스트를 추가해 factory bean 생성 전에
   custom 값을 거부하는지 검증한다.
4. API 호환성 검사에서 annotation default가 달라진다. reflection 테스트로
   `transactionManagerRef`의 기본값 `springTransactionManager`를 고정한다.

## 호환성 및 마이그레이션

기존에 기본값을 사용한 애플리케이션은 변경 없이 등록된다. custom 값을 사용한
애플리케이션은 1.13.0에서 등록 시 실패하므로, 저장소 호출을
`suspendTransaction(targetDatabase) { ... }`로 감싸거나
`streamAll(targetDatabase)`를 사용해야 한다. `transactionManagerRef`는 다음
major 버전에서 제거 여부를 다시 결정할 수 있도록 deprecated 상태로 남긴다.

## 수용 기준

- [x] `transactionManagerRef`의 source/binary ABI와 기본값을 유지한다.
- [x] annotation에 deprecation 및 실제 선택 경계를 설명하는 KDoc을 제공한다.
- [x] custom 값이 registrar/repository 등록 단계에서 `IllegalArgumentException`
      으로 거부된다.
- [x] 기본값은 등록된다.
- [x] EN/KO README가 동일한 동작·마이그레이션·명시적 다중 DB API를 설명한다.
- [x] 기존 `docs/manual/**` 1.12.1 릴리스 문서는 변경하지 않는다.
- [ ] 대상 모듈 전체 회귀, Detekt, API/ABI 및 최종 diff 검증을 통과한다.

## DoD

구현·테스트·문서 diff가 승인 범위에만 포함되고, workflow receipt에 targeted
RED/GREEN, broader verification, component evidence, final completion이
기록되어야 한다. PR 생성·CI·merge는 이 구현 DoD와 별도의 권한 게이트다.

## Writer gate

- `SPW-01`: PASS — Issue #637, 현재 소스 경로, ABI·리소스 소유권·1.12.1 문서
  경계를 고정했다.
- `SPW-02`: PASS — 문제, 대안, 선택, 실패 모드, 호환성, 수용 기준, DoD를 포함했다.
- `SPW-03`: PASS — 한국어 기술 문체와 API·명령어·식별자 보존을 확인했다.
- `SPW-04`: PASS — 현재 R2DBC factory/repository와 README 근거에 대조했다.
- `SPW-05`: PASS — Markdown read-back에서 headings, code spans, 목록 구조를
  확인했다.
