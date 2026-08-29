# Issue #763 TenantContext 기반 Ktor transaction bridge 실행 계획

## 목표와 완료 조건

`bluetape4k-projects`의 `KtorTenantContext`에 바인딩된 `TenantId`를
application-owned database resolver에 전달하고, 선택된 `Database` 또는
`R2dbcDatabase`를 기존 Ktor transaction helper로 실행하는 두 개의 선택형
artifact를 `develop`에 추가한다. 기존 `ktor/core`, `ktor/jdbc`,
`ktor/r2dbc`, `ktor/exposed`의 public API와 dependency graph는 변경하지
않는다.

완료 조건은 다음과 같다.

- `:bluetape4k-exposed-ktor-tenant-jdbc`와
  `:bluetape4k-exposed-ktor-tenant-r2dbc`가 컴파일·테스트·ABI·publication
  metadata를 통과한다.
- context 누락은 resolver/transaction 전에 `MissingTenantContextException`으로
  실패하고, resolver 예외는 동일 인스턴스로 전달된다.
- tenant A/B의 실제 H2 JDBC·R2DBC database가 교차 없이 선택되고, 동시 call과
  cancellation 뒤에도 call attribute가 오염되지 않는다.
- 기존 helper의 dispatcher, Micrometer timer, exception mapping,
  `CancellationException` 재전파를 새 adapter가 그대로 사용한다.
- settings, module inventory, manual manifest, CI/nightly, Kover, production ABI,
  dependency allowlist와 README/KDoc이 모두 등록된다.
- `bluetape4k-dependencies#213` alias handoff가 OPEN인 동안에는 root `bt4k`
  version catalog의 BOM version authority에 기반한 direct
  `io.github.bluetape4k:bluetape4k-tenant`와
  `io.github.bluetape4k:bluetape4k-ktor-tenant` 좌표를 임시 compile/test 증거로만
  사용한다. 현재 확인한 timestamped `2.0.0-SNAPSHOT` POM/JAR의 존재·checksum·
  metadata parity를 각 downstream gate 직전에
  `ruby scripts/verification/validate_issue_763_tenant_snapshot.rb`로
  재검증한다. exact timestamp/build와 metadata·POM·JAR SHA-256은
  `scripts/verification/issue-763-tenant-snapshot.json`에 고정하며,
  mismatch/404/drift이면 PR/publication/release/merge를 hold한다. `#213`
  handoff 또는 fresh owner decision 전까지 구현 상태는 provisional이며,
  stable `2.0.0` publication은 범위 밖이다.

## 작업 순서

### 1. 모듈 경계와 빌드 등록

소유 파일:

- `settings.gradle.kts`
- `ktor/tenant-jdbc/build.gradle.kts`
- `ktor/tenant-r2dbc/build.gradle.kts`
- `build.gradle.kts` (production ABI 수와 Ktor boundary 선택 목록)
- `scripts/verification/issue-763-tenant-snapshot.json`
- `scripts/verification/validate_issue_763_tenant_snapshot.rb`
- `api/bluetape4k-exposed-ktor-tenant-jdbc.api`
- `api/bluetape4k-exposed-ktor-tenant-r2dbc.api`

작업:

1. 두 디렉터리와 Gradle mapped module을 등록한다.
2. 각 module은 backend helper project와 upstream
   `bluetape4k-tenant`, `bluetape4k-ktor-tenant`를 모두 `api`로 선언한다.
   `TenantId`와 `KtorTenantContext`가 public signature에 직접 노출되므로
   generated POM/Gradle metadata에서도 두 좌표를 직접 확인한다. JDBC module은
   `kotlinx-coroutines-core`와 H2 test dependency를, R2DBC module은 H2 R2DBC
   test dependency를 추가한다. 각 dependency는 root version catalog/BOM을
   사용하고 새 버전을 하드코딩하지 않는다.
3. production ABI inventory를 42개에서 44개로 갱신하고 새 public API dump를
   `updateProductionAbiBaseline`으로 생성한다. 기존 API dump는 수정하지 않는다.
4. module compile task로 좌표·Kotlin signature·JVM target을 먼저 확인한다.

검증:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
./gradlew :bluetape4k-exposed-ktor-tenant-jdbc:compileKotlin \
  :bluetape4k-exposed-ktor-tenant-r2dbc:compileKotlin \
  --refresh-dependencies --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

실패 시 새 module 등록·dependency 좌표·upstream `2.0.0-SNAPSHOT` resolution을
고치고 다음 작업으로 진행하지 않는다. upstream 좌표가 없거나 metadata/checksum
parity가 예상과 다르면 implementation을 `PENDING`으로 hold하고 검증 결과를
남긴다.

검증:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
```

### 2. JDBC adapter TDD

소유 디렉터리:

- `ktor/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/ktor/tenant/jdbc/`
- `ktor/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/ktor/tenant/jdbc/`
- `ktor/tenant-jdbc/README.md`, `ktor/tenant-jdbc/README.ko.md`

작업:

1. `ExposedTenantJdbcTransactionTest`에 먼저 RED 테스트를 작성한다. 테스트는
   Ktor `testApplication` route에서 `KtorTenantContext.bindTenant`를 호출하고,
   (a) missing context resolver 호출 횟수 0, (b) resolver 예외 동일 인스턴스,
   (c) 두 H2 database marker의 A/B routing, (d) 동시 call isolation, (e)
   transaction block의 예외 wrapping·error timer와 cancellation 재전파·timer
   outcome을 증명한다.
2. 최소 구현으로 다음 public extension을 추가한다.

   ```kotlin
   suspend fun <T> ApplicationCall.exposedTenantJdbcTransaction(
       databaseResolver: (TenantId) -> Database,
       blockingDispatcher: CoroutineDispatcher,
       meterRegistry: MeterRegistry? = null,
       block: JdbcTransaction.() -> T,
   ): T
   ```

   구현 순서는 `KtorTenantContext.requireCurrent(this)` → resolver →
   `this.exposedJdbcTransaction(db, blockingDispatcher, meterRegistry, block)`로
   고정한다. resolver에는 `try/catch`, fallback, cache, log, retry를 넣지
   않으며 database/pool/dispatcher를 닫지 않는다.
3. RED를 관찰한 뒤 GREEN을 확인하고, receiver shadowing·불필요한 abstraction이
   없는지 Kotlin pattern checklist로 정리한다.
4. 한국어 KDoc과 module README에 `KtorTenantContext.bindTenant` →
   exact-match `map.getValue(tenantId)` → transaction 호출의 실행 가능한 예제,
   `tenant_resolution_failed`를 application `StatusPages`에서 매핑하는 예제,
   resolver 입력 검증·인증/인가·resource lifecycle 책임과 기존 helper 복귀
   방법을 설명한다.

검증:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
./gradlew :bluetape4k-exposed-ktor-tenant-jdbc:test \
  --tests '*ExposedTenantJdbcTransactionTest' \
  --refresh-dependencies --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

### 3. R2DBC adapter TDD

소유 디렉터리:

- `ktor/tenant-r2dbc/src/main/kotlin/io/bluetape4k/exposed/ktor/tenant/r2dbc/`
- `ktor/tenant-r2dbc/src/test/kotlin/io/bluetape4k/exposed/ktor/tenant/r2dbc/`
- `ktor/tenant-r2dbc/README.md`, `ktor/tenant-r2dbc/README.ko.md`

작업:

1. `ExposedTenantR2dbcTransactionTest`에 JDBC와 같은 RED/GREEN 계약을
   `R2dbcDatabase`와 `suspendTransaction` fixture로 작성한다. suspend resolver가
   아닌 `(TenantId) -> R2dbcDatabase` 계약을 유지하고, R2DBC coroutine-native
   cancellation을 검증한다.
2. 다음 public extension을 기존 helper에 얇게 위임한다.

   ```kotlin
   suspend fun <T> ApplicationCall.exposedTenantR2dbcTransaction(
       databaseResolver: (TenantId) -> R2dbcDatabase,
       meterRegistry: MeterRegistry? = null,
       block: suspend R2dbcTransaction.() -> T,
   ): T
   ```

   resolver 호출 전에는 transaction/metric이 시작되지 않으며, 이후의 성공·
   오류·취소 처리는 `this.exposedR2dbcTransaction`에 위임한다.
3. coroutine test와 H2 R2DBC marker 조회가 green인지 확인하고, blocking
   dispatcher나 ThreadLocal/ScopedValue 전파를 추가하지 않는다.

검증:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
./gradlew :bluetape4k-exposed-ktor-tenant-r2dbc:test \
  --tests '*ExposedTenantR2dbcTransactionTest' \
  --refresh-dependencies --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

### 4. 사용자 문서와 module inventory

소유 파일:

- `docs/manual/en/modules/bluetape4k-exposed-ktor-tenant-jdbc.md`
- `docs/manual/ko/modules/bluetape4k-exposed-ktor-tenant-jdbc.md`
- `docs/manual/en/modules/bluetape4k-exposed-ktor-tenant-r2dbc.md`
- `docs/manual/ko/modules/bluetape4k-exposed-ktor-tenant-r2dbc.md`
- `docs/manual/manifest.yaml`
- `README.md`, `README.ko.md`

작업:

1. EN/KO README와 manual에 dependency coordinate, `TenantId` binding,
   resolver 예제, missing/unknown tenant 오류, caller-owned database/pool/
   dispatcher/registry lifecycle, 인증·인가와 입력 검증의 caller 책임을
   기록한다.
2. JDBC 문서에는 `runInterruptible` dispatcher 경계를, R2DBC 문서에는
   coroutine-native `suspendTransaction` 경계를 명시한다.
3. manifest에 두 `develop-only` library entry를 source/test path와 함께
   추가하고 `exportManualModuleInventory` 결과와 일치시킨다.
4. 문서에는 raw tenant 값·credential·SQL을 metric/log에 넣지 않는 운영
   관측 계약과 `2.0.0-SNAPSHOT` metadata hold 조건을 반영한다. resolver는
   O(1) non-blocking immutable/thread-safe lookup이라는 계약을 예제와 KDoc에
   고정한다.

검증:

```bash
git diff --check
./gradlew exportManualModuleInventory
ruby scripts/manual/validate_manuals.rb \
  build/manual/module-inventory.json docs/manual/manifest.yaml
```

### 5. CI, dependency boundary, ABI와 BOM 확인

소유 파일:

- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`
- `scripts/verification/ktor-dependency-allowlist.json`
- `build.gradle.kts`

작업:

1. Ktor path filter, H2 selective test job, Kover XML task와 nightly 동일 job에
   두 module을 추가한다. coverage/status `needs`는 기존 job 이름을 유지하되
   새 테스트가 같은 Ktor job에서 실행되도록 한다.
2. dependency allowlist에 module별 local helper와 upstream
   `io.github.bluetape4k:bluetape4k-ktor-tenant`, transitive
   `io.github.bluetape4k:bluetape4k-tenant` 좌표를 추가한다. `ktor/core` source와
   publication metadata에 tenant 좌표가 나타나지 않는 guard를 둔다.
3. `checkKtorDependencyBoundary` selective path/module map을 6개로 확장하고
   generated POM/Gradle metadata 및 external consumer fixture를 확인한다.
4. production ABI 수/CI 고정값을 44/44로 맞추고 `exposed/bom` 자동 constraint가
   두 publishable module을 포함하는지 generated BOM으로 확인한다.

검증:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
./gradlew checkKtorDependencyBoundary checkProductionAbi \
  :bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication \
  --refresh-dependencies --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
ruby scripts/verification/ktor_dependency_allowlist_test.rb
```

### 6. 통합 검증, 리뷰와 handoff

작업:

1. 두 새 module 테스트와 기존 `ktor/jdbc`, `ktor/r2dbc`, `ktor/exposed` 테스트를
   순차 실행한다. H2를 먼저 확인하고, 기존 nightly Testcontainers 경로는 CI
   등록만 검증한다.
2. `detekt`, targeted compile/test, production ABI, manual manifest, dependency
   boundary, `git diff --check`를 모두 실행하고 JUnit에서 `skipped=0`을
   확인한다.
3. 승인된 spec/plan과 diff를 대조하는 verifier를 수행하고 performance/
   stability scan에서 resolver allocation, blocking 경계, resource ownership,
   cancellation, concurrent call 오염을 재확인한다.
4. 여섯 관점의 독립 code review와 main-session 통합 review를 수행한다. P0/P1은
   수정 후 영향 lane을 다시 실행하고 P2/P3는 수정 또는 후속 issue 근거를
   남긴다. integrated review와 lesson은 `bluetape-writer` SPW-01~05를
   통과시킨다.
5. Lore commit으로 spec/plan, 구현, 문서·등록과 review artifact를 의도별로
   커밋한다. PR 생성/merge/release는 이 작업의 stop condition 밖이며 별도
   권한 게이트로 남긴다.

대표 검증 명령:

```bash
ruby scripts/verification/validate_issue_763_tenant_snapshot.rb
./gradlew :bluetape4k-exposed-ktor-tenant-jdbc:test \
  :bluetape4k-exposed-ktor-tenant-r2dbc:test \
  :bluetape4k-exposed-ktor-jdbc:test \
  :bluetape4k-exposed-ktor-r2dbc:test \
  :bluetape4k-exposed-ktor:test \
  checkKtorDependencyBoundary checkProductionAbi exportManualModuleInventory \
  detekt --refresh-dependencies --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

## 위험·완화·재실행 지점

| Risk | Signal | Mitigation / rerun |
|---|---|---|
| upstream `2.0.0-SNAPSHOT` metadata 또는 direct coordinate 이동 | dependency resolution 404, POM/JAR/checksum/metadata mismatch | `validate_issue_763_tenant_snapshot.rb`로 중앙 metadata와 timestamped POM/JAR를 각 gate에서 재확인하고 alias handoff 전환/구현을 `PENDING`으로 hold; Step 1 재실행 |
| tenant context 전역 오염 | 동시 call에서 resolver 인자가 뒤섞임 | call별 `KtorTenantContext.bindTenant`와 concurrent H2 routing 테스트를 실패시킨 뒤 Step 2/3 재실행 |
| JDBC event-loop blocking | thread-name이 caller thread로 관측됨 | 기존 helper의 `runInterruptible` 호출을 직접 위임하고 dispatcher-isolation 테스트 재실행 |
| cancellation 삼킴 또는 timer 누락 | `CancellationException`이 mapping되거나 outcome timer가 없음 | adapter에 catch/log를 추가하지 않고 기존 helper 위임을 복원; targeted cancellation/metric 테스트 재실행 |
| consumer classpath 확장 | boundary/POM metadata에 R2DBC 또는 tenant 좌표 누락·추가 | backend별 module allowlist와 generated metadata를 비교하고 settings/allowlist task 재실행 |
| 이미 채택된 `2.0.0-SNAPSHOT` consumer의 rollback 실패 | 새 artifact 제거 후 기존 helper 복귀가 컴파일되지 않음 | consumer dependency를 제거하고 마지막 검증 producer version을 고정; producer/consumer rollback 절차를 재검증 |

## 파일 소유권과 병렬화

JDBC adapter와 테스트는 `ktor/tenant-jdbc/**`, R2DBC adapter와 테스트는
`ktor/tenant-r2dbc/**`로 분리할 수 있다. settings/build/CI/manifest/README/
ABI 파일은 공유 영역이므로 leader가 순차 통합한다. 두 adapter 구현 전에
공유 module 등록과 dependency boundary의 이름을 확정하며, 어느 lane도
unapproved commit·PR·external mutation을 수행하지 않는다.

## 롤백

publish 전에는 새 module 디렉터리와 등록/문서/allowlist를 한 commit 단위로
제거한다. publish 후 consumer가 이미 의존하면 consumer에서 tenant adapter를
제거하고 기존 explicit helper로 복귀한 뒤 마지막 검증 producer version을
고정한다. alias handoff가 완료되면 direct dependency를 catalog alias로 바꾸는
별도 후속 작업을 만들고, 변경 전후 POM/metadata/boundary를 다시 검증한다.

## 계획 DoD

- [x] 승인된 설계 명세의 두 backend별 module 결정을 task 순서에 반영함
- [x] 모든 acceptance criterion에 파일·테스트·검증 명령을 연결함
- [x] settings/ABI/BOM/CI/nightly/allowlist/manual inventory 등록을 포함함
- [x] 성공·실패·동시성·취소·lifecycle·backend capability 테스트를 명시함
- [x] upstream `2.0.0-SNAPSHOT` hold, rollback, compatibility와 stop condition을 명시함
- [x] `bluetape-writer` SPW-01~05 검토와 여섯 관점 plan review를 수행할 위치를 지정함
