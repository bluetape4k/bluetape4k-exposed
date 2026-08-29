# Issue #763 TenantContext 기반 Ktor transaction bridge 설계

## 상태와 범위

- 대상 이슈: [#763](https://github.com/bluetape4k/bluetape4k-exposed/issues/763)
- 기준 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop` (`c32c0cc00438478801d8f110735fd3f4374a745e`)
- 작업 branch: `feat/issue-763-tenant-context-ktor`
- 작업 worktree: `.worktrees/feat-issue-763-tenant-context-ktor`
- 승인 근거: 현재 사용자 요청 `#763 작업하자`와 이전 대화의 `승인`; issue 본문의
  dependency/ABI 설계 게이트를 구현 전 결정하는 범위
- 이 명세의 범위: caller-owned tenant resolver를 기존 Ktor JDBC·R2DBC
  transaction helper에 연결하는 선택형 adapter, 테스트, 문서, 모듈 등록과
  dependency guard
- 제외 범위: PR/merge/release, header·인증·인가, tenant provisioning/migration,
  기본 tenant, 전역 DB 자동 선택, Spring Boot auto-configuration, pool·dispatcher·
  registry 생성·종료

## 문제와 현재 근거

`bluetape4k-projects`의 공개 `2.0.0-SNAPSHOT`에는
`io.github.bluetape4k:bluetape4k-tenant`와
`io.github.bluetape4k:bluetape4k-ktor-tenant`가 추가되었습니다. Ktor adapter는
`ApplicationCall.attributes`에 one-call/one-tenant binding을 저장하고
`KtorTenantContext.requireCurrent(call)`에서 binding이 없으면
`MissingTenantContextException`을 던집니다. tenant 식별자 검증과 요청 인증은
호출자 책임입니다.

현재 이 저장소의
`ktor/jdbc/.../ExposedKtorTransactions.kt`와
`ktor/r2dbc/.../ExposedKtorTransactions.kt`는 각각 caller-owned
`Database`/`R2dbcDatabase`를 받아 dispatcher, metrics, exception mapping,
`CancellationException` 재전파를 구현합니다. 그러나 call tenant를 database
resolver에 전달하는 공통 진입점은 없습니다.

upstream PR #1566과 `2.0.0-SNAPSHOT` handoff PR #1568은 merge되었고 공개
`2.0.0-SNAPSHOT` metadata와 jar/POM은 확인했습니다. 다만 live upstream issue #1562와
`bluetape4k-dependencies#213`은 아직 OPEN이며 현재 immutable catalog
`df64293753a9491b337852a158f89d4a93a1734a`에는 tenant alias가 없습니다. 구현은
central BOM version authority(`bt4kVersion("bluetape4k-bom")`)를 사용하고,
catalog alias가 생기면 별도 후속 동기화로 전환합니다. alias가 없다는 사실은
최종 PR handoff의 잔여 의존성 상태로 기록합니다.

## 선택지와 결정

| 선택지 | 장점 | 위험/비용 | 결정 |
|---|---|---|---|
| 기존 `ktor/jdbc`·`ktor/r2dbc`에 overload 추가 | 파일 수와 호출 단계가 적음 | 기존 모든 소비자에게 tenant API가 전이되어 opt-in과 ABI 경계가 흐려짐 | 거부 |
| 하나의 `ktor/tenant`에 JDBC·R2DBC를 함께 포함 | artifact 수가 적음 | JDBC만 쓰는 소비자도 R2DBC classpath를 받으며 선택적 의존성 검증이 약해짐 | 거부 |
| `ktor/tenant-jdbc`와 `ktor/tenant-r2dbc`를 backend별 분리 | tenant·backend 의존성을 모두 opt-in으로 유지하고 각 classpath를 최소화 | 모듈·CI·문서 등록이 두 배가 됨 | **채택** |

두 adapter는 구현 중복을 새 공통 계층으로 추출하지 않고 각 기존 helper에
직접 위임합니다. transaction lifecycle의 단일 소유자는 기존 helper이며,
tenant adapter는 context 조회와 resolver 호출만 책임집니다.

## 공개 모듈과 API 계약

### 모듈 경계

| 디렉터리 | Gradle module | 공개 artifact | `api` 의존성 |
|---|---|---|---|
| `ktor/tenant-jdbc` | `:bluetape4k-exposed-ktor-tenant-jdbc` | `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc` | `bluetape4k-ktor-tenant`, `:bluetape4k-exposed-ktor-jdbc` |
| `ktor/tenant-r2dbc` | `:bluetape4k-exposed-ktor-tenant-r2dbc` | `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc` | `bluetape4k-ktor-tenant`, `:bluetape4k-exposed-ktor-r2dbc` |

각 모듈은 `bluetape4k-tenant`가 제공하는 `TenantId`를 public signature에
노출하므로 upstream Ktor tenant artifact를 `api`로 선언합니다. JDBC adapter는
JDBC transaction helper만, R2DBC adapter는 R2DBC transaction helper만
전이합니다. `ktor/core`와 기존 `ktor/jdbc`·`ktor/r2dbc`의 dependency/API는
변경하지 않습니다.

### 정확한 함수 시그니처

JDBC 모듈:

```kotlin
package io.bluetape4k.exposed.ktor.tenant.jdbc

import io.bluetape4k.tenant.TenantId
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

suspend fun <T> ApplicationCall.exposedTenantJdbcTransaction(
    databaseResolver: (TenantId) -> Database,
    blockingDispatcher: CoroutineDispatcher,
    meterRegistry: MeterRegistry? = null,
    block: JdbcTransaction.() -> T,
): T
```

R2DBC 모듈:

```kotlin
package io.bluetape4k.exposed.ktor.tenant.r2dbc

import io.bluetape4k.tenant.TenantId
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

suspend fun <T> ApplicationCall.exposedTenantR2dbcTransaction(
    databaseResolver: (TenantId) -> R2dbcDatabase,
    meterRegistry: MeterRegistry? = null,
    block: suspend R2dbcTransaction.() -> T,
): T
```

두 함수의 실행 순서는 동일합니다.

1. `KtorTenantContext.requireCurrent(this)`로 현재 call의 `TenantId`를 조회합니다.
2. resolver에 식별자를 전달해 database를 반환받습니다.
3. 해당 database와 기존 explicit helper를 호출합니다.

따라서 context가 없으면 resolver와 transaction이 시작되기 전에
`MissingTenantContextException`이 발생합니다. resolver가 알 수 없는 tenant나
database를 거부하면 그 예외를 그대로 호출자에게 전달합니다. adapter는
resolver를 cache하거나 database/pool을 닫지 않습니다. `Database`와
`R2dbcDatabase`, dispatcher, `MeterRegistry`의 생성·종료는 application이
소유합니다.

기존 `ApplicationCall.exposedJdbcTransaction(db, ...)`와
`ApplicationCall.exposedR2dbcTransaction(db, ...)`는 그대로 유지합니다. 새
함수는 overload가 아니라 이름이 분리된 확장 함수이므로 JVM descriptor 충돌을
피하고 기존 source/binary compatibility를 보장합니다.

## 오류·취소·관측 의미

- `MissingTenantContextException`: resolver 호출 전 fail-fast; default database로
  우회하지 않음
- resolver 예외: adapter가 감싸지 않고 그대로 전달; 기존 transaction metric은
  시작되지 않음
- transaction 성공/일반 오류/`Error`/`CancellationException`: 기존 helper가
  정한 outcome metric과 예외 mapping을 그대로 사용
- JDBC blocking: 기존 `runInterruptible(blockingDispatcher)` 경계를 유지하고
  event loop에서 직접 blocking하지 않음
- R2DBC: 기존 `suspendTransaction`과 cooperative cancellation 경계를 유지
- call attribute는 upstream `KtorTenantContext`가 소유하므로 다른 call의
  tenant를 읽거나 dispatcher hop을 통해 암묵적으로 전파하지 않음

adapter 자체에는 새 metric name/tag, retry, fallback, logging, background scope를
추가하지 않습니다. resolver와 transaction block 내부에서 발생한 예외의 진단과
HTTP 응답 매핑은 기존 application/StatusPages 조합이 담당합니다.

운영 애플리케이션은 다음 최소 관측 계약을 조합해야 합니다. context 누락은
`tenant_context_missing`, resolver 거부·실패는 `tenant_resolution_failed`로
분류하고, transaction의 성공·일반 오류·취소는 기존 helper의
`bluetape4k.exposed.ktor.core.transaction` timer outcome을 사용합니다. metric과
log에는 raw `TenantId`, header, URL, SQL, credential을 넣지 않고 고정된 분류와
요청 correlation 값만 사용합니다. HTTP 상태와 경보 임계값은 인증·인가 및
운영 정책을 아는 애플리케이션이 결정하며, 1차 대응 주체도 애플리케이션
운영자입니다. adapter는 이 분류를 강제하거나 별도 로그를 남기지 않습니다.

## 테스트 전략과 수용 기준 매핑

각 새 모듈은 `bluetape4k-assertions`와 JUnit 5의 backtick 테스트 이름을
사용하고, 실제 H2 database를 분리해 다음을 증명합니다.

| 수용 기준 | 검증 방식 |
|---|---|
| missing context fail-fast | binding 없는 `ApplicationCall`에서 resolver 호출 횟수가 0이고 `MissingTenantContextException`이 발생하는 테스트 |
| tenant A/B routing | 두 H2 JDBC database와 두 R2DBC database를 resolver map으로 연결하고 각 transaction에서 서로 다른 marker를 읽는 테스트 |
| resolver/transaction 예외 | resolver 예외는 동일 인스턴스로 전파하고 transaction 예외는 기존 `ExposedKtorTransactionException` mapping을 사용하는 테스트 |
| metrics/cancellation 유지 | 기존 helper의 targeted tests를 새 모듈에서 재사용하고, tenant wrapper가 cancellation을 삼키지 않는 suspend test를 추가 |
| call isolation | 두 `ApplicationCall`에 서로 다른 tenant를 binding하고 순차·dispatcher hop 뒤 resolver 인자를 비교하는 테스트 |
| 기존 API compatibility | 기존 JDBC/R2DBC module test suite와 ABI/publication metadata 검증 |
| dependency 경계 | `checkKtorDependencyBoundary`, generated POM/Gradle metadata, `ktor/core` tenant grep guard |
| 문서와 예제 | 한국어 KDoc과 `README.md`/`README.ko.md`, 두 module manual의 좌표·resolver·책임 경계 read-back |

실제 database/pool은 테스트가 생성한 범위에서만 사용하고 production adapter가
resource를 소유하지 않는다는 점을 fixture와 code review에서 확인합니다.
Testcontainers 기반 PostgreSQL/MySQL 경로는 이번 adapter가 driver routing을
추가하지 않으므로 새로 만들지 않고 기존 Ktor nightly matrix에 모듈을 등록해
순차 실행합니다.

## 등록·문서·운영 영향

- `settings.gradle.kts`에 두 mapped module을 등록합니다.
- `ktor/tenant-jdbc`와 `ktor/tenant-r2dbc` 각각에 English/Korean README,
  `src/test/resources/junit-platform.properties`, `logback-test.xml`을 둡니다.
- `docs/manual/manifest.yaml`과 English/Korean module manual을 추가합니다.
- `.github/workflows/ci.yml`와 `nightly-tests.yml`의 Ktor path filter, test job,
  Kover artifact, coverage `needs`에 두 module을 포함합니다.
- `scripts/verification/ktor-dependency-allowlist.json` 및
  `ktor_dependency_allowlist_test.rb`에 backend별 허용 좌표와 upstream tenant
  좌표를 등록합니다.
- `exposed/bom`은 자동으로 모든 publishable subproject constraint를 수집하므로
  별도 수동 constraint를 추가하지 않고 generated BOM을 검증합니다.
- 기존 `ktor/exposed` compatibility aggregator에는 tenant adapter를 추가하지
  않습니다. tenant 사용자는 두 opt-in artifact를 직접 선택합니다.
- public KDoc/README는 tenant 식별자 검증·인증·인가와 database resolver가
  application 책임임을 명시합니다.

## 롤백과 호환성

새 모듈은 기존 artifact와 독립적이므로 rollback은 두 module 디렉터리, settings/
CI/manual 등록, allowlist 변경을 한 commit 단위로 제거하면 됩니다. 기존
`ktor/core`, `ktor/jdbc`, `ktor/r2dbc`, `ktor/exposed` API와 publication 좌표는
변경하지 않으므로 기존 소비자는 dependency graph 변경 없이 유지됩니다.

catalog alias handoff가 완료되기 전에는 central BOM version으로 upstream
coordinates를 직접 선언합니다. 해당 임시 경계는 `bluetape4k-dependencies#213`
완료 후 alias 전환을 위한 후속 정리 대상으로 남깁니다. 이 상태에서는 공개
`2.0.0-SNAPSHOT`이 이동하면 dependency resolution을 다시 검증해야 하며, stable
`2.0.0` publication은 이 작업의 DoD가 아닙니다.

rollback은 producer와 consumer를 분리해 수행합니다. producer가 아직 publish하지
않았다면 새 모듈 디렉터리와 settings/CI/manual/allowlist 등록을 한 commit에서
되돌립니다. 이미 consumer가 `2.0.0-SNAPSHOT`을 채택한 뒤에는 해당 consumer가
tenant adapter dependency를 제거하고 기존 `exposedJdbcTransaction(db, ...)` 또는
`exposedR2dbcTransaction(db, ...)`로 복귀한 뒤, 마지막으로 검증된 producer
version을 고정합니다. CI와 release 전에는 timestamped metadata와 POM/JAR
resolution을 다시 확인하고, 좌표가 없거나 checksum/metadata가 예상과 다르면
배포·후속 전환을 hold합니다.

## 설계 DoD

- [x] upstream API와 Exposed Ktor helper의 책임 경계를 source/POM/metadata로
  확인함
- [x] overload, 단일 통합 module, backend별 두 adapter의 dependency/ABI를
  비교하고 backend별 분리를 선택함
- [x] missing context, resolver failure, transaction/cancellation, call isolation,
  compatibility와 registration chain을 명시함
- [x] 테스트·문서·CI·BOM/allowlist·rollback 검증을 계획함
- [ ] `bluetape4k-dependencies#213` tenant alias handoff 완료 — 외부 선행조건,
  현재 OPEN
- [x] `SPW-01`~`SPW-05` self-review와 Korean technical naturalness 검토 완료
