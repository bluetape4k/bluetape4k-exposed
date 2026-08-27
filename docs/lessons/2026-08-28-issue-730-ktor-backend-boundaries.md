# Issue #730 Ktor backend 선택 경계 구현 lesson

## 맥락

기존 `bluetape4k-exposed-ktor`는 JDBC, R2DBC, cache와 Ktor route·error·metric을
한 artifact에 함께 노출했다. 이번 변경에서는 backend-neutral core와 JDBC/R2DBC/cache
adapter를 별도 artifact로 만들고, 기존 aggregator 소비자의 binary/source 계약은
그대로 두는 것이 목표였다.

## 결정

- `ktor/core`는 cooperative probe marker, 순차 shared monotonic deadline, 고정
  error catalog, core metric을 소유한다.
- `ktor/jdbc`는 caller dispatcher의 `runInterruptible`과 statement timeout을,
  `ktor/r2dbc`는 `suspendTransaction`을 소유한다.
- `ktor/cache`는 O(1)·non-blocking·caller-owned 상태 supplier만 받아 core probe로
  변환한다.
- legacy aggregator는 deprecated migration surface로 남기고, 기존 JDBC→R2DBC→cache
  phase·response key·예외/constructor 계약을 보존한다. health route의 JDBC/R2DBC
  probe와 독립 phase budget은 기존 구현을 유지하고, transaction/status/error 표면만
  child/core로 forwarding한다. 호환 경계의 동작을 child 내부 구현으로 바꾸면
  cache supplier가 중복 실행되거나 phase budget이 달라질 수 있으므로 characterization을
  우선한다.
- child transaction exception은 public raw-cause constructor를 열지 않고 no-arg
  core exception에 내부 `initCause`만 적용한다. legacy wrapper는 기존 `(Throwable)`
  constructor와 cause chain을 유지한다.

## 결과

선택 consumer는 필요한 child만 조합할 수 있고, legacy consumer는 기존 package와
응답 계약을 계속 사용할 수 있다. BOM EN/KO 관리 표에도 child와 compatibility
aggregator를 함께 기록했으며, example은 BOM을 사용하면서 core/JDBC/R2DBC/cache를
직접 조합한다. CI는 4개 child test, aggregator test, dependency boundary, production
ABI, manual inventory를 같은 Ktor job에서 검증하도록 갱신했다.

## 검증

| 영역 | 결과 |
|---|---|
| child/legacy contract | core 7, JDBC 5, R2DBC 5, cache 3, legacy 63 테스트 PASS |
| example | 32 테스트 PASS |
| legacy matrix | H2 63, PostgreSQL 63, MySQL_V8 63 테스트 PASS |
| ABI | child `checkKotlinAbi` 4개와 production `42/42` inventory PASS |
| dependency/publication | `checkKtorDependencyBoundary`, `validate_ktor_consumer.rb` 4개 선택 consumer, child POM/metadata 생성 PASS |
| static/docs | 6개 detekt, YAML parse, manual inventory/validation, `git diff --check` PASS |

## 놓친 점과 수정

1. Kotlin JUnit test 함수의 마지막 expression이 assertion 결과가 되면 반환 타입이
   `Unit`이 아니어서 테스트가 discovery되지 않았다. contract test는 마지막에
   명시적으로 `Unit`을 반환한다.
2. `@Deprecated`를 KDoc 뒤에 두면 기존 source contract가 KDoc을 선언 직전으로
   인식하지 못했다. deprecation annotation은 KDoc 앞에 두고 KDoc과 선언을 붙였다.
3. core exception을 `cause: Throwable?` 기본 인자로 선언하면 JVM에 public
   `(Throwable)` constructor가 생긴다. 설계의 no-arg ABI를 지키기 위해 no-arg
   constructor와 내부 `initCause` 조합으로 바꿨다.
4. legacy readiness route를 child JDBC probe로 forwarding하면 child의
   `runInterruptible`·remaining-budget 경계가 기존 독립 JDBC phase와 달라져
   real smoke가 statement 진입 전에 종료될 수 있었다. legacy JDBC/R2DBC probe는
   기존 구현을 유지하고 child adapter는 선택 consumer와 transaction/status/error
   표면에 사용한다. cache contributor도 동일 supplier를 한 번만 읽도록 고정한다.
5. 첫 detekt 실행에서는 Dokka serialization 초기화가 일시적으로 실패했고,
   `./gradlew help` 후 재실행으로 해소했다. Docker 검증은 healthy Colima와
   `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` export를 확인한
   뒤 실행했다.

## 다음 변경을 위한 guard

- 새 child public API는 `.api` baseline, `javap -p -s`, `checkKotlinAbi`와 함께
  검토한다. 특히 exception constructor와 JVM owner를 별도 assertion으로 고정한다.
- backend module을 추가하거나 옮길 때 `settings.gradle.kts`, BOM/inventory, POM,
  Gradle metadata, CI path filter, EN/KO manual manifest를 한 변경에서 갱신한다.
- legacy facade를 바꿀 때는 source characterization, phase order, response key,
  metric name, constructor/cause chain을 먼저 실행하고 child forwarding이 중복
  실행을 만들지 않는지 확인한다.
- probe contract는 marker·component allowlist·shared deadline·cancellation
  precedence·resource ownership을 함께 테스트한다.

## DoD Status

- [x] 구현 source gate: `PASS (P0=0, P1=0)`
- [x] child/legacy/backend matrix와 ABI 근거를 기록했다.
- [x] EN/KO manual·README와 example/CI 경계를 기록했다.
- [x] local verification과 hosted/release 미실행 범위를 분리했다.
- [x] SPW-01~SPW-05 및 한국어 naturalness 검토를 완료했다. audit의 남은
  `snapshot` 2건은 README 예제 추출용 `example:snapshot:start/end` marker다.
