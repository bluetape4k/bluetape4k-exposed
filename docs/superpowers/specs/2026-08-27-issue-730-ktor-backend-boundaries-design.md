# Issue #730 Ktor backend-selective artifact 경계 설계

## 상태와 범위

- 대상 이슈: [#730](https://github.com/bluetape4k/bluetape4k-exposed/issues/730)
- 기준 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop` (`c5e9d499d9c1baeb6f92a531345d184c16febc27`)
- 작업 branch: `refactor/issue-730-ktor-boundaries`
- 작업 worktree: `.worktrees/refactor/issue-730-ktor-boundaries`
- 승인 상태: 아키텍처와 공개 계약을 사용자에게 승인받음
- 이 명세의 범위: Ktor 연동 artifact의 backend 선택 경계, 호환 aggregator, 테스트·문서·catalog/CI 전환
- 제외 범위: database/pool/dispatcher/registry 생성·종료, Ktor core 일반 기능의 재설계, PR·merge·release

## 문제

현재 `ktor/exposed` 하나의 artifact가 JDBC, R2DBC, cache, Micrometer와 그에
필요한 Ktor 기능을 모두 API 의존성으로 노출한다. JDBC만 사용하는 호출자도
R2DBC와 cache 경계를 함께 받으며, R2DBC만 사용하는 호출자도 JDBC artifact를
끌어온다. 이 구조는 선택적 소비자 classpath와 책임 경계를 검증하기 어렵게
만든다.

동시에 기존 `bluetape4k-exposed-ktor`를 바로 제거하면 다음 공개 계약이
깨진다.

- `io.bluetape4k.exposed.ktor` 패키지의 installer/config/transaction/route/API
- 통합 `StatusPagesConfig.bluetape4kExposedErrors()` 호출
- 기존 BOM, README/manual 예제와 Ktor demo
- 현재 JVM descriptor를 확인하는 `ExposedKtorAbiCompatibilityTest`

목표는 backend별 artifact를 독립적으로 사용할 수 있게 하면서도 기존
aggregator 소비자의 source와 binary 호환을 유지하는 것이다.

## 현재 근거

다음 파일과 검증 결과를 기준으로 설계했다.

| 근거 | 현재 사실 |
|---|---|
| `ktor/exposed/build.gradle.kts` | 하나의 모듈이 Ktor core, cache, JDBC, R2DBC, coroutine, Micrometer를 `api`로 노출한다. |
| `settings.gradle.kts` | `ktor/exposed`만 `:bluetape4k-exposed-ktor`로 등록한다. |
| `Bluetape4kExposedKtorConfig.kt` | 하나의 config가 nullable JDBC/R2DBC database와 공통 health/status/metrics 옵션을 함께 받는다. |
| `ExposedKtorTransactions.kt` | JDBC와 R2DBC transaction helper가 같은 패키지에 있다. |
| `ExposedKtorHealthRoutes.kt` | JDBC/R2DBC probe와 cache readiness를 하나의 route API가 조합한다. |
| `ExposedKtorStatusPages.kt` | Exposed JDBC, `SQLException`, `R2dbcException`을 한 extension이 매핑한다. |
| `ExposedKtorCacheReadiness.kt` | cache contributor와 report adapter가 Ktor 모듈에 포함되어 있다. |
| `examples/ktor-exposed-demo` | legacy aggregator를 직접 의존한다. |
| `docs/manual/manifest.yaml` | 현재 Ktor manual entry가 하나이며 source/test 경로도 `ktor/exposed` 하나다. |
| baseline | `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-ktor:test ...` 결과 63개 테스트 PASS. |

기존 installer는 resource를 생성하거나 닫지 않는다. database, pool,
dispatcher, repository, `MeterRegistry`, authentication과 shutdown은
호출자가 소유한다. 이 계약은 분할 후에도 변경하지 않는다.

## 선택한 구조

### 모듈과 책임

| 디렉터리 | Gradle module | 소유 책임 | 금지되는 backend 의존성 |
|---|---|---|---|
| `ktor/core` | `:bluetape4k-exposed-ktor-core` | Exposed 비의존 Ktor route/probe 조합, 공통 metrics, 공통 오류 응답과 예외 | `exposed-jdbc`, `exposed-r2dbc`, `bluetape4k-exposed-cache` |
| `ktor/jdbc` | `:bluetape4k-exposed-ktor-jdbc` | JDBC transaction helper, JDBC readiness probe, JDBC SQL/Exposed 오류 매핑 | R2DBC와 cache artifact |
| `ktor/r2dbc` | `:bluetape4k-exposed-ktor-r2dbc` | R2DBC transaction helper, R2DBC readiness probe와 오류 매핑 | JDBC와 cache artifact |
| `ktor/cache` | `:bluetape4k-exposed-ktor-cache` | `ExposedKtorCacheContributor`, cache readiness/metrics adapter | JDBC와 R2DBC Ktor adapter |
| `ktor/exposed` | `:bluetape4k-exposed-ktor` | 기존 통합 config/installer/route/error extension과 호환 forwarding | 선택 모듈을 `api`로 재노출하는 것 외의 새 backend 로직 |

각 선택 모듈은 `bluetape4k-exposed-ktor-core`를 `api`로 사용한다. JDBC와
R2DBC adapter는 필요한 JetBrains Exposed backend만 직접 의존한다. cache
adapter는 `:bluetape4k-exposed-cache`만 직접 의존하며 cache 모듈 자체의
JDBC/R2DBC Ktor artifact 의존성을 만들지 않는다.

### Core probe 계약

core는 backend 타입을 알지 못하므로 다음 backend-neutral 계약을 제공한다.

```kotlin
interface ExposedKtorReadinessProbe {
    val component: String

    suspend fun probe(timeout: Duration): String
}
```

core route는 `List<ExposedKtorReadinessProbe>`를 받아 health/readiness
response, 경로 검증, timeout 경계, `UP`/`DOWN`/`timeout` sanitization과
metrics 태그를 책임진다. component는 안정적인 `[a-z][a-z0-9_.-]{0,62}`
이름만 허용하며 tenant, key, URL, SQL, namespace, secret을 인코딩할 수
없다. 선택 adapter는 다음 factory를 제공한다.

- `exposedKtorJdbcReadinessProbe(...)`
- `exposedKtorR2dbcReadinessProbe(...)`
- `ExposedKtorCacheReadinessConfig`를 probe 계약으로 변환하는 cache adapter

새 선택형 route의 probe는 순차적으로 실행하고 각 probe에 route의
`readinessProbeTimeout`을 전달한다. legacy 통합 route는 기존 JDBC/R2DBC
probe와 cache phase의 세부 budget 및 response key를 그대로 유지한다. 이
두 계약을 섞어 기존 timeout 의미를 바꾸지 않는다.

### 오류 매핑

core는 cancellation 재전파, `ExposedKtorTransactionException`,
`ExposedKtorReadinessTimeoutException`과 공통 API error payload만 등록한다.
JDBC module은 `ExposedSQLException`·`SQLException`, R2DBC module은
`R2dbcException`을 각각 등록한다. 통합 aggregator의
`bluetape4kExposedErrors()`는 세 extension을 한 번에 조합해 기존 호출을
보존한다. 예외 message, cause, SQL, URL, credential은 response나 metric
tag에 노출하지 않는다.

### 호환 aggregator

`bluetape4k-exposed-ktor`는 제거하지 않는다. 다음을 유지한다.

- `Bluetape4kExposedKtorConfig`의 생성자와 기본값
- `Application.installBluetape4kExposedKtor` 두 overload의 JVM descriptor
- 통합 `Route.bluetape4kExposedHealthRoutes` overload
- `ApplicationCall.exposedJdbcTransaction`/`exposedR2dbcTransaction`
- `ExposedKtorCacheContributor`와 `ExposedKtorCacheReadinessConfig`의 binary 이름
- `StatusPagesConfig.bluetape4kExposedErrors()`와 공통 예외 binary 이름

구현은 새 module의 기능을 호출하는 얇은 forwarding으로 바꾼다. combined
config/installer와 통합 extension에는 `@Deprecated(level = WARNING)`와
선택 module migration KDoc을 추가하되, `ERROR` 또는 `HIDDEN`으로 바꾸지
않는다. 제거 시점은 별도 major migration 결정 없이는 정하지 않는다.

## 데이터 흐름

```text
선택 소비자
  -> ktor-core route + (jdbc | r2dbc | cache) probe
  -> shared health/readiness response + metrics

legacy 소비자
  -> ktor aggregator config/installer
  -> jdbc/r2dbc/cache child adapter
  -> 기존 package/JVM descriptor와 response semantics
```

설치 helper는 resource를 만들거나 닫지 않는다. JDBC block은 호출자가 준
blocking dispatcher에서만 실행하고, R2DBC block은 `suspendTransaction`에서
실행한다. route가 이미 설치된 `StatusPages`를 덮어쓰지 않는 기존 검사를
유지한다.

## 실패 모드와 대응

1. **선택 모듈 classpath에 다른 backend가 새어 나옴**  
   각 child module의 compile/runtime resolved artifact를 검사하는
   `checkKtor*DependencyBoundary` task와 consumer compile fixture를 둔다.
   forbidden artifact가 있으면 build를 실패시킨다.
2. **legacy JVM descriptor 또는 default argument bridge가 사라짐**  
   기존 ABI compatibility test를 aggregator 대상으로 유지하고, 두
   installer overload와 route overload의 descriptor 및 Kotlin `$default`
   bridge를 확인한다.
3. **partial readiness에서 timeout/error 정보가 노출됨**  
   core가 허용된 component와 유한 상태만 직렬화하고, child adapter가
   backend exception을 공통 sanitized outcome으로 변환한다. SQL/cause와
   cache key를 response/log tag에 넣지 않는다.
4. **old aggregator와 child 구현의 timeout semantics가 달라짐**  
   legacy route는 기존 통합 구현의 phase budget을 회귀 테스트로 고정하고,
   new child route는 별도 probe contract test로 검증한다.
5. **BOM/manual/example만 일부 전환됨**  
   settings, BOM 자동 constraint, manifest, EN/KO manual, README, Ktor demo,
   CI path와 test job을 한 변경에서 점검한다. 생성 manifest는 export task로
   다시 만든다.

## 테스트와 수용 기준

### 테스트

- core: probe 순서, timeout, cancellation, 중복/위험 component, response
  sanitization, metrics outcome.
- JDBC child: H2 transaction/readiness/error mapping과 blocking dispatcher
  검증.
- R2DBC child: H2 transaction/readiness/error mapping과 cancellation 검증.
- cache child: contributor snapshot/failed report/metric redaction 검증.
- aggregator: 기존 63개 Ktor 테스트, ABI compatibility, cache readiness,
  driver timeout, README parity를 그대로 통과.
- consumer boundary: JDBC-only, R2DBC-only, cache-only consumer가 각각
  필요한 API를 compile하고 금지 artifact가 resolved classpath에 없는지 확인.
- 통합 DB 검증은 H2 후 PostgreSQL, MySQL 순서로 순차 실행한다. Docker 기반
  검증은 healthy Colima를 재시작하지 않고 현재 환경을 사용한다.

### 수용 기준

1. 다섯 Gradle module이 settings에 등록되고 BOM에 자동 constraint로 포함된다.
2. JDBC-only/R2DBC-only/cache-only consumer가 다른 backend Ktor artifact 없이
   필요한 API를 사용한다.
3. legacy aggregator의 기존 source·binary ABI와 응답·caller-owned lifecycle
   의미가 유지된다.
4. metrics 이름·backend/operation/outcome 태그와 오류 redaction이 변하지
   않는다.
5. EN/KO manual·README·manifest·example·CI path/job가 새 모듈 경계를
   설명하고 서로 일치한다.
6. core/child/aggregator 테스트와 선택 DB matrix가 통과하며
   `git diff --check`와 dependency boundary 검사가 PASS한다.

## 문서와 migration

새 manual entry는 `bluetape4k-exposed-ktor-core`, `...-jdbc`, `...-r2dbc`,
`...-cache`에 대해 EN/KO 한 쌍씩 추가한다. 기존 Ktor manual은 legacy
aggregator compatibility 페이지로 남기고 다음 선택 규칙을 명시한다.

```kotlin
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

모든 dependency 예시는 `bluetape4k-dependencies` BOM을 사용하고 individual
Bluetape version은 쓰지 않는다. Ktor demo는 child module 조합을 주 예제로
사용하며, 별도 compile fixture는 legacy aggregator를 계속 확인한다.

## DoD

- [ ] 승인된 모듈 경계와 public probe/error contract가 구현되었다.
- [ ] 선택 module의 compile/runtime dependency boundary가 자동 검사된다.
- [ ] legacy aggregator ABI, source 사용법, timeout/error semantics가 회귀
  테스트로 증명된다.
- [ ] core/JDBC/R2DBC/cache/aggregator와 example의 targeted tests가 PASS한다.
- [ ] H2 및 해당되는 PostgreSQL/MySQL 검증과 `git diff --check`가 PASS한다.
- [ ] BOM, manifest, EN/KO manual, README, example, CI path/job가 parity를
  이룬다.
- [ ] P0/P1 review finding이 없고, 공개 API·KDoc·migration 문서가
  `bluetape4k-kotlin-patterns`와 Korean writer gate를 통과한다.
- [ ] 이 worktree의 변경만 구현하며 PR·merge·release는 수행하지 않는다.

## 작성 게이트 (SPW)

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 이슈 URL, 기준 ref, worktree, 대상 독자(호출자·유지보수자), source ledger와 미지원 범위를 명시했다. |
| SPW-02 | PASS | 문제, 제약, 구조, 계약, 흐름, 실패 모드, 호환성, 테스트, 수용 기준과 DoD를 포함했다. |
| SPW-03 | PASS | Korean technical register와 `artifact`, `aggregator`, `readiness`, `caller-owned` 용어를 문맥별로 일관되게 사용했다. |
| SPW-04 | PASS | 현재 Gradle/source/test/manual 경로와 baseline 63 tests를 기준으로 주장과 migration 범위를 대조했다. |
| SPW-05 | PASS | Markdown을 다시 읽어 표·코드 fence·목록·링크를 확인했으며 미해결 기술 placeholder가 없다. |
