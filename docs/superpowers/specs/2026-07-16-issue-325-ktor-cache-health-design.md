# 이슈 #325 Ktor 캐시 상태 및 메트릭 설계

## 문제

`bluetape4k-exposed-ktor`는 명시적이고 호출자 소유인 데이터베이스 health 및 readiness check를 제공하지만, 캐시 repository 또는 issue #321에서 도입된 transaction-aware snapshot cache에 상응하는 운영 계약이 없습니다. 따라서 Ktor 애플리케이션은 애플리케이션별 endpoint를 직접 만들지 않고서는 캐시 일관성 실패나 제한된 캐시 메트릭을 보고할 수 없습니다.

새로운 surface는 Ktor의 명시적 opt-in 모델을 유지해야 하며, 캐시 키, entity identifier, 값, SQL, URL, exception message, cause 또는 stack trace를 절대 노출해서는 안 됩니다.

## 현재 근거

- `bluetape4kExposedHealthRoutes`는 이미 JDBC 및 R2DBC readiness를 `/readyz/exposed`로 집계하고, 제한된 timeout을 적용하며, `CancellationException`을 다시 throw하고, 유한한 tag를 사용해 Micrometer timer를 생성합니다.
- `CacheHealthReport`는 issue #321에 의해 `develop`에 도입되었으며 아직 stable release에는 포함되지 않았습니다. 현재 `isFlushJobRunning` Boolean은 정상적인 lazy-started worker와 실패했거나 종료된 worker를 구분할 수 없으므로, issue #325는 최초 release 전에 이 계약을 수정합니다.
- `SnapshotCacheFailureBuffer`는 캐시된 값이나 identifier를 노출하지 않고 제한된 counter(`size`, `droppedCount`, `observerFailureCount`)를 제공합니다.
- `SnapshotStoreId.namespace`는 애플리케이션이 이를 정적이고 low-cardinality인 값으로 제어하는 경우에만 허용된 metrics tag로 문서화되어 있습니다. Ktor API는 임의의 cache name을 허용하는 대신 더 제한적인 component-name 계약을 적용합니다.
- Ktor module은 이미 Exposed JDBC 및 R2DBC module에 의존합니다. 기존 `:bluetape4k-exposed-cache` project dependency를 추가해도 external dependency나 새 module은 도입되지 않습니다.

## 선택한 접근 방식

`bluetape4k-exposed-ktor`에 backend-neutral하고 sanitized된 cache readiness contributor contract를 추가하고, 구성된 contributor를 기존 `/readyz/exposed` response에 집계합니다.

### 공개 계약

- `ExposedKtorCacheContributor`는 private constructor를 가진 immutable class입니다. public companion factory는 caller가 kind를 선택하도록 허용하지 않고 kind와 sanitization을 고정합니다.

```kotlin
fun jdbcRepository(
    component: String,
    report: () -> CacheHealthReport,
): ExposedKtorCacheContributor

fun r2dbcRepository(
    component: String,
    report: suspend () -> CacheHealthReport,
): ExposedKtorCacheContributor

fun snapshot(
    component: String,
    buffer: SnapshotCacheFailureBuffer,
): ExposedKtorCacheContributor

fun custom(
    component: String,
    probe: suspend () -> ExposedKtorCacheStatus,
): ExposedKtorCacheContributor
```

- `ExposedKtorCacheStatus`는 유한한 public enum `UP|DOWN`입니다. Custom contributor는 status만 반환하며 metric tag나 measurement field를 생성할 수 없습니다.
- Repository 및 snapshot factory는 내부의 non-serializable sanitized sample을 생성합니다. 이 sample은 사용 가능한 모든 count가 음수가 아닌지 검증하고, repository 전용 및 snapshot 전용 field 조합을 강제합니다. 새로운 Java serialization contract는 도입되지 않습니다.
- `ExposedKtorCacheReadinessConfig(contributors: List<ExposedKtorCacheContributor>)`는 비어 있지 않은 list를 방어적으로 복사하고, 생성 시 name, uniqueness 및 size를 검증합니다.
- 이러한 public declaration이 `CacheHealthReport` 및 `SnapshotCacheFailureBuffer`를 노출하므로 `bluetape4k-exposed-ktor`는 `implementation` dependency가 아닌 `api(project(":bluetape4k-exposed-cache"))`를 추가합니다.

Contributor name은 lowercase ASCII pattern `[a-z][a-z0-9_-]{0,62}`와 일치해야 하고, 구성된 name은 byte-for-byte 기준으로 unique해야 하며, cache-readiness config 및 route마다 최대 16개의 contributor만 설치할 수 있습니다. Component name은 운영 label이지 tenant, key, namespace, URL, endpoint 또는 기타 data-bearing identifier가 아닙니다. 검증된 name은 caller가 제어할 수 있는 유일한 metric tag 값입니다.

Caller가 제공하는 모든 probe는 side-effect-free이며 bounded되어야 합니다. JDBC report supplier는 repository의 기존 in-memory atomic consistency state를 O(1)로 읽는 작업만 수행할 수 있으며, database, cache, network, file 또는 기타 blocking I/O를 수행해서는 안 됩니다. R2DBC report supplier에도 동일한 in-memory-only 규칙이 적용되며, suspend하는 경우 non-blocking이고 coroutine cancellation에 cooperative해야 합니다. Custom probe도 마찬가지로 non-blocking이고 cancellation-cooperative해야 합니다. Snapshot sampling은 bounded local read이며 backend I/O를 수행하지 않습니다. Blocking 또는 backend 작업이 필요한 caller는 별도의 operational surface에서 dispatcher offload 및 backend-native timeout을 직접 소유해야 합니다. 이를 readiness supplier에 배치하는 것은 지원되지 않습니다. Library는 caller code를 격리하기 위한 dispatcher, executor, scope 또는 worker를 생성하지 않습니다.

### Source 및 JVM 호환성

기존 `Bluetape4kExposedKtorConfig` primary constructor, `Application.installBluetape4kExposedKtor(config)` 및 eight-parameter `Route.bluetape4kExposedHealthRoutes(...)` declaration은 정확한 JVM descriptor와 기존 `$default` method를 유지합니다. 이들은 cache contributor가 없는 새로운 internal aggregate implementation에 위임합니다.

Cache support는 새로운 overload를 사용하며, 기존 public declaration에 default parameter를 추가하지 않습니다.

```kotlin
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)

fun Route.bluetape4kExposedHealthRoutes(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    healthPath: String = Bluetape4kExposedKtorConfig.DEFAULT_HEALTH_PATH,
    readinessPath: String = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PATH,
    readinessProbeTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PROBE_TIMEOUT,
    jdbcQueryTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_JDBC_QUERY_TIMEOUT,
    meterRegistry: MeterRegistry? = null,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)
```

새 overload parameter에는 default가 없으므로 overload ambiguity를 방지합니다. Cache-only installation은 database argument에 null을 공급하고 비어 있지 않은 cache config를 전달합니다. Installer overload는 `installHealthRoutes`를 따르며, validation은 적어도 하나의 구성된 database 또는 cache contributor를 허용합니다. English 및 Korean README example은 JDBC supplier, R2DBC suspend supplier, snapshot buffer, custom status probe 및 cache-only form에 대해 compile-check되어야 합니다.

### Readiness 집계

- 기존 database-only behavior는 기존 public declaration 및 JVM descriptor를 보존하여 source 및 binary-compatible 상태로 유지됩니다.
- `installHealthRoutes`는 유일한 installation switch로 유지됩니다. 최소 하나의 database 또는 cache contributor가 필요합니다.
- `/healthz/exposed`는 liveness response로 유지되며 cache probe를 실행하지 않습니다.
- `/readyz/exposed`는 구성된 cache probe를 installation order에 따라 sequential하게 실행합니다. 이를 통해 unstructured concurrency를 피하고, simultaneous backend pressure를 방지하며, deterministic metric emission을 유지합니다.
- 기존 `readinessProbeTimeout`은 각 JDBC 및 R2DBC probe별 timeout으로 유지되며, 하나의 cache contributor마다 새로 적용되는 timeout이 아니라 공유되는 단일 cache-phase deadline이기도 합니다. 모든 cache probe는 monotonic-time budget의 남은 시간만 전달받습니다.
- 일반적인 `DOWN` 또는 error는 phase를 short-circuit하지 않습니다. budget이 남아 있는 동안 이후 contributor도 계속 실행됩니다. Deadline이 만료되면 active contributor 및 모든 remaining contributor에 대해 probe를 호출하지 않고 installation order에 따라 HTTP `timeout` detail을 전달합니다.
- 지원되는 non-blocking 및 cancellation-cooperative probe의 경우 cache phase는 구성된 cache contributor 수와 무관하게 기존 database readiness 작업에 최대 하나의 `readinessProbeTimeout` interval만 추가합니다. Library가 이를 종료할 수 있는 thread 또는 process boundary를 소유하지 않으므로, 지원되지 않는 blocking 또는 cancellation-insensitive JDBC, R2DBC 또는 custom supplier는 이 bound를 초과할 수 있습니다.
- Response detail은 `cache.<component> -> UP|DOWN|timeout`만 사용하며, measurement 또는 failure metadata는 반환하지 않습니다.
- Required cache contributor 중 하나라도 `DOWN`이거나 timeout되면 aggregate response는 `503 Service Unavailable`이 됩니다.
- 호출된 모든 probe는 HTTP detail, timer 또는 gauge가 update되기 전에 정확히 하나의 internal sealed terminal result를 생성합니다. 실제로 timeout된 invocation은 하나의 `timeout` result와 timer를 생성합니다. Shared budget이 소진되어 skip된 contributor는 synthetic HTTP `timeout` detail과 `NaN` gauge를 받지만 probe-duration timer는 생성하지 않습니다. 현재 request context가 inactive인 동안 cancellation signal이 감지되면 active invocation에 대해 정확히 하나의 `cancelled` timer를 생성하고 이를 rethrow하며 HTTP failure detail은 생성하지 않습니다. 현재 request context가 active인 동안 supplier가 `CancellationException`을 throw하면 sanitized ordinary `error`가 됩니다. 해당 message와 cause는 폐기되고, budget이 남아 있는 동안 이후 contributor가 계속 실행되며 request cancellation으로 rethrow되지 않습니다. 하나의 invocation에 대해 두 개의 outcome을 기록하는 code path는 없으며, fatal JVM `Error` 값은 cache `DOWN`으로 변환되지 않습니다.

`R = readinessProbeTimeout`, `J_effective`는 현재 whole-second/minimum-one-second conversion 이후의 JDBC statement timeout이며, configured JDBC, R2DBC 및 cache probe에 대한 indicator variable을 사용할 때 conservative supported planning budget은 다음과 같습니다.

`T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + routing/dispatcher overhead`.

Additive JDBC term은 coroutine deadline 직전에 시작된 blocking query가 이후 driver timeout을 소비하는 경우를 포함합니다. JDBC driver timeout enforcement, dispatcher saturation 및 unsupported blocking/cancellation-insensitive supplier는 여전히 caller/backend constraint이므로, operator는 이 공식을 process-kill guarantee로 취급하지 말고 deployment margin을 추가해야 합니다. README example은 orchestrator `timeoutSeconds`를 반올림한 budget 및 margin보다 크게 설정하고, `periodSeconds`를 `timeoutSeconds`보다 크게 유지하며, 일시적인 eviction을 방지하기 위해 `failureThreshold >= 3`을 사용합니다.

### 기본 제공 상태 매핑

`CacheHealthReport`에 대해 다음을 적용합니다.

- 최초 stable release 전에 모호한 `isFlushJobRunning` Boolean을 유한한 `CacheWorkerState`인 `NOT_APPLICABLE`, `IDLE`, `RUNNING`, `DRAINING`, `FAILED` 또는 `STOPPED`로 교체합니다. Serializable shape는 의도적으로 호환되지 않으므로 새로운 fixed `serialVersionUID`를 할당하고 명시적으로 테스트합니다. 기존 UID를 유지하면 non-null state가 null인 legacy stream이 발생할 위험이 있습니다. Repository implementation은 하나의 authoritative atomic lifecycle state를 소유하며, health probe는 이를 observe할 뿐 worker를 시작, 재시작, 종료하거나 그 밖의 방식으로 소유하지 않습니다.
- `READ_ONLY` 및 `WRITE_THROUGH`는 `NOT_APPLICABLE`을 report하며 supplier 자체가 실패하지 않는 한 `UP`입니다.
- 새로 생성된 lazy `WRITE_BEHIND` repository는 `IDLE`을 report합니다. 마지막 flush error가 없으면 `IDLE` 및 `RUNNING`은 `UP`입니다.
- 최초의 accepted work는 one-way compare-and-set `IDLE -> RUNNING`을 시도합니다. Admission이 close와 race하는 경우 `DRAINING`, `FAILED` 또는 `STOPPED`를 절대 overwrite하지 않습니다. R2DBC admission 및 queue-depth accounting은 fast consumer가 producer가 acceptance를 기록하기 전에 decrement하지 않도록 linearize됩니다. Rejected 또는 cancelled send는 phantom depth를 남기지 않으며, terminal classification은 late increment와 race하지 않습니다. Expected `close()`는 channel이 close되기 전에 `IDLE|RUNNING -> DRAINING`으로 transition합니다. Confirmed normal drain completion은 `DRAINING -> STOPPED`로 transition하며, `STOPPED` 이후의 expected scope cancellation은 이를 변경하지 않습니다. Uncaught terminal failure 또는 draining 전이나 도중의 cancellation은 `FAILED`로 transition합니다. 30-second production close wait가 만료되면 `close()`는 scope를 cancel하기 전에 `FAILED`를 설정하며, 반환된 repository를 indefinite하게 `DRAINING` 상태로 남기지 않습니다. Late completion은 `FAILED`를 `STOPPED`로 overwrite할 수 없습니다. Tests는 public timeout을 변경하지 않고 실제 deadline-expiry path를 실행하기 위해 module-internal wait-duration seam을 사용하며, thread interruption도 별도로 다룹니다.
- `DRAINING`, `FAILED`, `STOPPED` 또는 last flush error는 `WRITE_BEHIND`를 `DOWN`으로 만듭니다. 이후 성공적인 flush는 recoverable flush error를 clear하지만, terminal failure와 expected close는 서로 구별됩니다.
- Queue depth는 measurement이며 자동 failure threshold가 아닙니다.

`SnapshotCacheFailureBuffer`에 대해 다음을 적용합니다.

- Retained, dropped 및 observer-failure count는 measurement이며 그 자체로 application readiness를 fail시키지 않습니다. Snapshot-cache mutation은 best-effort이고 database correctness가 authoritative로 유지되며, 후자의 두 counter는 reset semantics가 없는 cumulative 값이기 때문입니다.
- Built-in snapshot contributor는 buffer state를 sanitized read-only snapshot으로 한 번 읽을 수 있으면 `UP`입니다. 해당 snapshot operation이 ordinary exception을 throw하는 경우에만 `DOWN`입니다. Windowed threshold 또는 acknowledgement policy가 필요한 application은 이를 custom contributor를 통해 표현합니다.
- Probe는 buffer state를 한 번 읽으며 이를 drain, acknowledge, reset 또는 mutate하지 않습니다. Concurrent offer/drain operation은 다음 sample을 변경할 수 있지만 shared deadline을 초과하여 readiness를 block할 수는 없습니다.

Custom contributor의 경우 caller가 safe state mapping을 소유하지만 response field 또는 metric tag key를 추가할 수는 없습니다.

### 메트릭

기존 Ktor Micrometer naming 및 outcome vocabulary를 재사용합니다.

- `bluetape4k.exposed.ktor.cache.readiness`: 유한한 `component`, `kind`, `operation=readiness` 및 `outcome=success|error|timeout|cancelled` tag를 사용하는 probe duration입니다.
- Outcome mapping은 고정됩니다. 반환된 `UP -> success`; 반환된 `DOWN`, repository `DRAINING|FAILED|STOPPED`, 기록된 flush error 또는 ordinary supplier/snapshot/custom exception `-> error`; active shared-deadline expiry `-> timeout`; parent cancellation `-> cancelled`입니다. Skip된 contributor는 timer를 기록하지 않습니다.
- 네 개의 gauge는 `component` 및 `kind` tag만 사용합니다.
  - `bluetape4k.exposed.ktor.cache.queue.depth`, base unit `entries`: 아직 flushed된 것으로 observe되지 않은 accepted write-behind entry;
  - `bluetape4k.exposed.ktor.cache.snapshot.pending`, base unit `events`: 현재 retained된 snapshot failure event;
  - `bluetape4k.exposed.ktor.cache.snapshot.dropped`, base unit `events`: bounded buffer가 drop한 cumulative event;
  - `bluetape4k.exposed.ktor.cache.snapshot.observer.failures`, base unit `events`: observer callback failure의 cumulative event.
  각 meter description은 `NaN`이 zero가 아니라 unavailable을 의미한다고 명시합니다. Measurement value는 절대 tag가 되지 않습니다.
- `CacheWriteMode`는 response-internal finite value이며 metric tag가 아닙니다. Cache key, namespace, URL, SQL, exception type 및 message는 tag로 사용할 수 없습니다.

Meter, immutable tag set, direct timer reference 및 stable thread-safe gauge holder는 route 설치 시 한 번 생성하고 모든 request에서 재사용합니다. Request path에서는 `MeterRegistry.find`, meter builder, tag construction 또는 meter registration을 수행하지 않습니다. Contributor마다 네 개의 gauge는 atomic reference 뒤에 하나의 immutable sample을 보관합니다. 적용되지 않는 field, 아직 성공하지 않은 field 또는 error/timeout result에 속한 field는 stale value가 아닌 `NaN`을 publish합니다. Monotonic generation은 각 contributor에 대해 probe가 시작되거나 synthetic budget-exhausted timeout을 받을 때만 claim됩니다. 해당 contributor에 대해 가장 최근에 claim된 generation만 publish할 수 있으므로, 오래된 attempt의 late completion 또는 cancellation은 새로운 sample을 overwrite할 수 없습니다. 해당 contributor에 도달하기 전에 새로운 request가 cancellation되더라도 이전 in-flight result를 suppress하지 않습니다. Parent cancellation은 해당 generation이 여전히 newest인 경우에만 active contributor의 gauge를 `NaN`으로 설정합니다. 아직 invoke되지 않은 contributor는 마지막 completed sample을 유지합니다. 성공적인 probe는 하나의 sanitized snapshot에서 네 field를 모두 publish합니다. Timer는 네 개의 유한한 outcome meter ID를 사용합니다. Upper bound는 contributor당 8개의 Micrometer meter ID이며, 16-contributor limit에서는 route installation당 128개의 cache meter ID입니다. Repeated 또는 concurrent request는 더 많은 meter를 register할 수 없습니다. Export된 backend time-series count는 registry 및 distribution-configuration에 따라 달라집니다. 하나의 timer meter가 count, sum, maximum, histogram bucket 또는 percentile로 확장될 수 있기 때문입니다.

Library-owned route installation은 하나의 installation-only `ReentrantLock` critical section에서 preflight와 registration을 serialize합니다. 이 lock은 request path에서 절대 사용되지 않으며 operation 이후 registry reference를 보유하지 않습니다. Cache meter를 하나라도 register하기 전에 installation은 동일한 `component` 및 `kind` identity를 가진 기존 library meter name을 reject합니다. 여기에는 호환되지 않는 meter type 또는 추가 tag가 있는 identity도 포함됩니다. 현재 attempt가 생성한 meter만 추적하며, 이후 registration이 실패하면 이를 제거한 뒤 registry exception을 cause로 보유하지 않는 stable sanitized error를 throw합니다. Concurrent identical library installation은 정확히 하나의 winner만 허용하며, loser는 meter를 추가하거나 winner의 state holder에 gauge를 bind하지 않습니다. 서로 다른 identity를 가진 여러 route installation은 각각 최대 128개의 meter ID를 추가하므로 application-wide Micrometer ID bound는 `128 * cache-route-installation-count`입니다. Export된 backend time-series는 여전히 registry/configuration에 따라 달라집니다. Documentation은 application/registry마다 하나의 Exposed readiness route를 사용할 것을 권장합니다. Library는 global registry를 추가하거나 route 간에 state holder를 암묵적으로 공유하지 않습니다. Caller는 library installation API 외부에서 이러한 library-owned identity를 concurrent하게 mutate해서는 안 됩니다.

하나의 readiness request 내부에는 sequential execution이 적용됩니다. Caller는 route에 대한 authentication, request concurrency 및 rate limiting을 소유하며, simultaneous request는 동일한 backend를 concurrent하게 probe할 수 있습니다. Shared gauge state는 exception이나 meter growth 없이 이러한 update를 처리할 수 있어야 합니다. 또한 library는 application lifecycle 작업이 될 수 있는 cross-request mutex, queue 또는 rate limiter를 생성하지 않습니다.

Application은 계속해서 `MeterRegistry`와 그 lifecycle을 소유합니다. Library는 meter-maintenance job을 시작하지 않으며 caller가 application 및 registry를 close한 이후 어떠한 lifecycle 작업도 유지하지 않습니다.

## 거부된 대안

### 전용 캐시 엔드포인트

`/readyz/exposed/cache`를 추가하면 cache output을 격리할 수 있지만, operator가 여러 readiness endpoint를 조합해야 하며 database readiness와 cache readiness가 서로 불일치할 수 있습니다. 기존 endpoint에 집계하면 하나의 운영 의사결정 지점을 유지할 수 있습니다.
### 백엔드별 repository parameters

Caffeine, Redisson, Lettuce 또는 snapshot-store 구현을 직접 허용하면 Ktor 모듈이 선택적 backend 모듈에 결합되고, dependency graph가 확장되며, backend capability 차이가 public API의 일부가 됩니다. 정제된 contributor 경계를 유지하면 모듈을 backend-neutral하게 유지할 수 있습니다.

### 호출자 제공 상세 정보 맵 및 태그 맵

임의의 map은 유연하지만 redaction이나 cardinality 제한을 강제할 수 없습니다. Typed finite field는 의도적으로 확장성이 낮으며 더 안전합니다.

## 실패 모드와 완화책

1. **Probe timeout or hung backend**: 지원되는 probe에 shared monotonic cache-phase deadline을 적용하고, active invocation에 대해 timeout timer 하나만 기록하며, 건너뛴 contributor에는 timer 없이 synthetic HTTP timeout detail을 제공하고, exception을 노출하지 않습니다. Blocking 또는 cancellation-insensitive supplier code는 library-owned dispatcher에 숨기지 않고 contract에 따라 거부하며, deadline을 초과하거나 deadline 이후에도 실행될 수 있습니다.
2. **Probe throws an ordinary exception**: `Exception`만 catch하고, `DOWN`을 반환하며, `error`를 emit하고, HTTP 및 metric boundary에서 message/cause/stack detail을 폐기합니다. 임의의 `Throwable`을 catch하거나 보존하지 않습니다. fatal JVM error는 전파됩니다.
3. **Coroutine cancellation**: parent/request cancellation, cache-phase timeout, 그리고 request context가 active인 동안 supplier가 throw한 `CancellationException`을 구분합니다. inactive request context에 대해서만 정확히 하나의 `cancelled` outcome을 emit하고 HTTP failure detail 없이 rethrow하며, active-context supplier signal은 sanitized `error`로 매핑하고 계속 진행합니다.
4. **Duplicate or unsafe component names**: routes가 설치되기 전에 configuration을 거부하며 raw name을 echo하지 않습니다. Validation error에는 list index, input length, stable reason code만 포함하고, duplicate error에는 position만 식별하며, message와 cause 어느 쪽에도 거부된 값이 포함되지 않습니다.
5. **Metric cardinality growth**: route installation당 contributor를 16개로 제한하고, exact lowercase ASCII contract로 component name을 검증하며, registry identity collision을 거부하고, meter를 한 번만 register하며, 유한한 library-owned tag key와 value만 허용합니다.
6. **Write-behind lifecycle ambiguity**: `IDLE`, `RUNNING`, `DRAINING`, `FAILED`, `STOPPED`를 명시적으로 노출하며, fresh idle repository를 healthy로 처리하되 probe가 worker를 시작하도록 하지는 않습니다.
7. **Historical snapshot failures**: cumulative dropped/observer counter를 measurement로만 유지하여, 하나의 recovered event가 restart 전까지 readiness를 `DOWN`으로 유지하지 않도록 합니다.

## 호환성과 소유권

- JDBC/R2DBC database만 configure하는 기존 caller는 동일한 route와 response shape을 유지합니다.
- Cache support는 configured contributor를 통한 opt-in이며, library는 repository, cache, dispatcher, scope 또는 registry를 생성하지 않습니다.
- Spring Boot 또는 Actuator type은 Ktor module에 들어오지 않습니다. Kotlin API change는 `spring-boot/jdbc`와 `spring-boot/r2dbc`에서 조정됩니다. 자동으로 발견되는 `exposedJdbcCacheHealthIndicator`와 `exposedR2dbcCacheHealthIndicator`는 `NOT_APPLICABLE|IDLE|RUNNING`을 `Status.UP`으로, `DRAINING|STOPPED`를 `Status.OUT_OF_SERVICE`로, `FAILED` 또는 모든 `lastFlushError`를 `Status.DOWN`으로 매핑합니다. Non-null flush error는 `Health.down(error)`에 전달되는 throwable로 유지하며, 해당 error가 없는 `FAILED` state는 `Health.down()`을 사용합니다. Actuator는 `repositoryCount`와 report별 `mode`, `queueDepth`, `lastFlushError` message detail을 유지하고, `flushJobRunning`만 finite `workerState`로 대체하며, 기존 management-endpoint disclosure policy는 더 엄격한 Ktor redaction boundary와 별도로 유지합니다. Tests와 bilingual Spring README는 해당 exact status와 detail을 assert합니다. Ktor documentation은 해당 module을 link하고 automatic Actuator discovery와 explicit Ktor contributor installation을 대조합니다.
- Cache repository lifecycle behavior는 변경되지 않으며, 이전에 release되지 않은 health report만 observable state를 구분합니다.
- Snapshot/develop consumer는 `report.isFlushJobRunning` check를 `report.workerState == CacheWorkerState.RUNNING` 또는 적절한 finite-state mapping으로 대체합니다. Released database-only Ktor caller는 migration이 필요하지 않습니다.
- README safe-deployment example은 지원되는 두 가지 shape을 다룹니다: ingress/network policy로 보호되는 installer-owned root route, 그리고 `installHealthRoutes = false`와 caller-owned `authenticate("ops")` 내부에 중첩된 direct route overload입니다. Helper 자체는 authentication을 제공하지 않으며, route를 public Internet에 직접 노출해서는 안 되고, caller는 두 번째 unprotected route를 실수로 설치해서는 안 됩니다.
- Library는 Ktor boundary에서 contributor exception detail을 폐기하며 message, cause 또는 stack trace를 log하지 않습니다. Caller가 safe custom-probe logging과 backend telemetry를 소유하고, repository worker log는 repository-owned로 유지됩니다.

## 검증

- 기존 8개의 Ktor test를 baseline으로 보존합니다.
- Fresh-idle, running, draining, recoverable-error, failed, stopped repository state와 normal close, drain failure, close-timeout cancellation, late-completion race, unexpected cancellation, snapshot measurement 및 historical failure 이후 recovery를 위한 RED/GREEN test를 추가합니다. timeout, ordinary exception redaction, cancellation propagation, duplicate/unsafe name, 16-contributor limit도 추가합니다.
- Cache phase가 하나의 timeout budget 안에 유지되고, exhausted-budget contributor가 invoke되지 않으며, ordinary error가 이후 contributor로 계속 진행되고, result가 installation order로 유지됨을 입증합니다.
- 하나의 active cache timeout이 `timeout` 하나와 `cancelled` outcome 0개를 emit하고, skipped contributor는 timer를 emit하지 않으며 `NaN` gauge를 노출하고, parent cancellation은 `cancelled` 하나를 emit하고 rethrow하며 HTTP failure detail을 emit하지 않고, request가 active인 동안 secret-bearing supplier-thrown `CancellationException`이 later contributor를 중단하지 않고 sanitized `error` 하나가 됨을 입증합니다. Fatal JVM error가 propagate되는지 검증합니다.
- Documented bounded JDBC/R2DBC repository supplier와 non-blocking cancellation-cooperative custom probe를 실행합니다. Intentionally blocking JDBC/custom 및 cancellation-insensitive R2DBC/custom test double을 사용하여 unsupported supplier가 coroutine deadline을 초과하거나 그 이후까지 실행될 수 있지만, library가 compensating thread 또는 scope를 생성하지 않는다는 점을 문서화합니다.
- Response body에 secret, exception message, SQL, URL, cache key 또는 namespace가 포함되지 않음을 검증합니다.
- Malicious component name이 거부되고 KDoc/README가 component name에 tenant, key, namespace, URL 및 endpoint material을 포함하지 못하도록 금지함을 검증합니다. Exception message와 cause에는 raw value, control character 또는 secret-bearing substring이 포함되지 않아야 합니다.
- Timer tag와 measurement meter가 허용된 유한 field만 사용하고, exact meter name/base unit/description을 사용하며, `CacheWriteMode`가 tag가 아니고, repeated 및 concurrent probe가 meter count를 일정하게 유지하며, per-contributor generation ordering이 contributor에 도달하지 않은 newer request와 newer success 이후의 older cancellation을 처리하고, thread-safe gauge update가 throw하지 않으며, error/timeout gauge가 `NaN`이 되고, cancellation이 active newest contributor generation의 gauge만 clear하며, route installation당 cache meter ID가 최대 128개인지 검증합니다. Exported backend time-series는 registry/configuration dependent로 보고합니다.
- Duplicate meter identity installation이 meter가 하나라도 추가되기 전에 fail하고, Nth registration 이후의 filter/registry failure가 current attempt의 meter만 제거하며, concurrent identical install이 정확히 하나의 winner를 생성하고, distinct route installation이 application-wide meter-ID formula를 따르며, 어떤 route도 다른 route의 state holder에 gauge를 조용히 bind하지 않음을 검증합니다.
- Producer와 drainer가 concurrent하게 실행되는 동안 snapshot sampling이 read-only이고 bounded인지 검증합니다.
- JDBC, R2DBC 및 cache contributor를 configure한 all-backend planning budget을 검증합니다. 여기에는 constrained blocking dispatcher와, JDBC query가 `R` 근처에서 시작한 뒤 `J_effective`를 소비하는 controllable JDBC statement/DataSource fixture, deployment margin을 포함한 orchestrator timeout example이 포함됩니다. Deterministic virtual-time orchestration을 위해 internal time/probe seam을 사용하고, 명시적인 executor/DataSource cleanup과 함께 하나의 bounded real-time smoke test를 유지합니다.
- `javap` 또는 동등한 compiled-consumer compatibility check를 사용하여 기존 config, installer, route 및 `$default` JVM descriptor를 보존합니다.
- `:bluetape4k-exposed-cache`, `:bluetape4k-exposed-jdbc-caffeine`, `:bluetape4k-exposed-r2dbc-caffeine`, `:bluetape4k-exposed-spring-boot-jdbc`, `:bluetape4k-exposed-spring-boot-r2dbc`, `:bluetape4k-exposed-ktor`를 compile 및 test한 다음 Kotlin diagnostics와 `git diff --check`를 실행합니다.
- Ktor authentication test를 추가하여 unauthenticated denial이 readiness detail 또는 contributor invocation 없이 expected 401/403을 반환하고, authenticated access가 contributor를 정확히 한 번 invoke한 뒤 readiness를 반환함을 입증합니다.
- Repository `DOWN`, cache timeout, snapshot cumulative counter, `NaN` gauge, invalid contributor configuration 및 unsupported custom probe를 위한 bilingual runbook row를 추가합니다. Runbook은 caller-owned log, backend telemetry, worker state, queue depth 및 fixed meter를 확인하도록 안내하고, dropped/observer counter가 cumulative이며 readiness failure로 취급하지 말고 rate/increase로 query해야 한다고 설명합니다.
- README parity review는 양 locale에서 heading, compile-checked code, API name, supported supplier constraint, route/status example, meter name/tag, registry-installation limit, runbook row, security warning, orchestrator timing 및 Actuator link를 다룹니다. Factual parity가 통과한 후 identifier를 번역하거나 semantic을 변경하지 않는 natural Korean technical-prose review를 수행합니다.

## 인수 조건

- Ktor application이 기존 Exposed readiness route에 cache repository 및 snapshot-cache readiness를 추가할 수 있습니다.
- Timeout과 `DOWN` behavior가 deterministic하고 test-covered입니다.
- HTTP detail과 Micrometer tag가 sanitized되고 bounded입니다.
- Feature가 명시적인 opt-in이며 caller-owned로 유지됩니다.
- 기존 database-only Ktor behavior가 compatible하게 유지됩니다.

## 완료 조건

- Spec과 implementation plan이 performance, stability, security, Ops, developer/API, user/caller 및 integration review를 통과하고 P0=0/P1=0입니다.
- 모든 targeted test, diagnostics, documentation parity check 및 final scoped review가 통과합니다.
- Issue-linked PR이 `develop`을 target하고 issue metadata를 mirror하며, merge approval 요청 전에 exact head에서 green CI에 도달합니다.
