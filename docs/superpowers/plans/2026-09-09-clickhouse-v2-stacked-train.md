# ClickHouse JDBC V2 stacked PR train Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` for inline execution with checkpoints; use `subagent-driven-development` only if bounded native lanes are available. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exposed `1.5.0`의 `exposed/clickhouse` 모듈에 ClickHouse JDBC V2 `0.9.9`의 연결 옵션, 중첩·복합 타입, 명시적 RowBinary 배치 경로, query diagnostics를 기존 API 호환성을 보존한 네 개의 stacked PR로 추가한다.

**Architecture:** `#865`가 immutable options와 redacted connection boundary를 소유하고, `#866`이 그 경계를 재사용하는 명시적 `ColumnType` adapter를 소유한다. `#867`은 connection-scoped beta profile을 preflight에서 선택하는 caller-owned provider와 driver-owned writer를 추가하며, `#868`은 기존 query/배치 경계에서 immutable diagnostics와 동기 non-blocking callback을 수집한다. 각 PR은 부모 PR의 정확한 head를 base로 사용하고 독립된 API·테스트·문서·rollback 증거를 남긴다.

**Tech Stack:** Kotlin/JVM, Gradle, Exposed `1.5.0`, ClickHouse JDBC `0.9.9`, JDK `TimeSource.Monotonic`/`Instant`, JDBC `Connection`/`PreparedStatement`/`ResultSet`, JUnit 5, MockK, bluetape4k assertions·Testcontainers, Python 3 deterministic renderer, CairoSVG `2.9.0`, `gh`.

---

## 계획 상태와 실행 경계

- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준: `develop@ac6a0dc330d29a2812f83dd1a7aaa7db42fd0b26`, Exposed `1.5.0`, ClickHouse JDBC `0.9.9`
- 설계: [`docs/superpowers/specs/2026-09-09-clickhouse-v2-stacked-train-design.md`](../specs/2026-09-09-clickhouse-v2-stacked-train-design.md)
- 설계 리뷰: [`docs/superpowers/reviews/2026-09-09-clickhouse-v2-spec-review.md`](../reviews/2026-09-09-clickhouse-v2-spec-review.md), P0/P1=0인 비독립 inline fallback
- 현재 계획 worktree: `.worktrees/feat/issue-865-clickhouse-v2-options`, 계획 시작 HEAD `06e0b58d585f5ad98f2f4c82a8a1cbdbbc0e3bae`
- canonical `develop`: `ac6a0dc330d29a2812f83dd1a7aaa7db42fd0b26`이며 clean 상태
- 담당 방식: 1인 개발자이므로 계획 승인 후 `$executing-plans`를 leader inline 실행으로 사용한다. 독립 리뷰 lane이 실패하면 독립 리뷰로 표현하지 않고 inline fallback과 비독립성을 receipt에 기록한다.
- 실행하지 않는 항목: merge, auto-merge, tag/release, Full Nightly dispatch, 새 dependency, 중앙 매뉴얼 저장소 수정, #863의 deterministic socket timeout·in-flight remote `KILL QUERY` 보장
- 계획 승인 전 금지: production source, test source, Gradle, README, benchmark, API baseline, PR branch 생성·push의 구현 변경

## 완료·중단 조건

다음 증거가 모두 있을 때만 전체 train을 `DONE`으로 보고한다.

1. 네 PR의 변경 경로가 아래 파일 지도와 일치하고 각 child branch가 부모 PR의 exact head를 가리킨다.
2. #865~#868 targeted RED→GREEN 테스트와 전체 모듈 `test`, `checkKotlinAbi`, `detekt`가 exit code 0이며 JUnit XML failures/errors가 0이다.
3. H2 의미론, ClickHouse wire, RowBinary provider profile, resource lifecycle, diagnostics callback 증거가 서로 섞이지 않는다. Testcontainers lock·image digest·container id·fixture seed가 receipt에 있다.
4. DS-01~DS-09 receipt의 `status`가 `PASS`일 때 `commands`, `expected`, `observed`, `artifacts`, `environment`, `sha256`가 모두 채워져 있고 `SKIPPED`·`PENDING`은 PASS로 집계되지 않는다.
5. #867의 36조합×3 process run raw JSON, SHA256SUMS, EN/KO SVG·PNG, semantic ledger와 분석 문서가 실제 입력과 일치한다. driver private byte bound를 주장하지 않는다.
6. EN/KO README·public KDoc·repo lesson·PR metadata가 동일한 API와 제한을 설명하고, 모든 PR 본문이 `Closes #865`, `Closes #866`, `Closes #867`, `Closes #868`을 각각 사용한다.
7. 최종 6관점 리뷰에서 P0/P1=0이며 hosted CI check, review thread, mergeability와 exact head를 다시 읽었다. merge는 별도 사용자 승인 전에는 수행하지 않는다.

다음 상황은 해당 PR을 중단하고 원인과 미검증 범위를 기록하는 조건이다.

- 기존 `connect`/`chArray`/`queryFlow` descriptor 또는 기본 동작이 사라짐
- typed/raw option, custom header, exception graph에 secret이 남음
- RowBinary setter 이후 fallback·retry·resend, profile 혼합, provider 없는 fail-open이 발생함
- JDBC `Array`/`Struct`가 반환 후 보관되거나 mutable collection이 외부에 노출됨
- callback/sink 예외가 SQL 결과·원래 예외·취소를 대체함
- 컨테이너·lock·benchmark run이 누락되었는데 PASS로 기록하려 함
- independent review가 실패했는데 독립 통과로 표현하려 함

## 파일 소유권 지도

### #865 — 연결·보안·세션 옵션

| 경로 | 책임 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2Options.kt` | public immutable options/auth/proxy/TLS/reuse-strategy와 constructor validation |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2Properties.kt` | V2 allowlist, URL query 검사, precedence, defensive copy, effective `Properties`; internal |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2Redaction.kt` | URL/message/throwable/logger redaction; internal |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseConnectionException.kt` | scrubbed connection wrapper와 sanitized cleanup exception |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabase.kt` | 기존 overload 보존과 options trailing overload 연결 |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2OptionsTest.kt` | key/unit/default, duplicate/unknown/header/auth/redaction matrix |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseValidationTest.kt` | URL/host/port와 exception graph |
| `exposed/clickhouse/src/test/java/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseJavaInteropTest.java` | Java descriptor compile·reflection fixture |
| `api/bluetape4k-exposed-clickhouse.api` | 기존 symbol 불변과 신규 descriptor 추가 |
| `exposed/clickhouse/README.md`, `README.ko.md` | options·precedence·auth/header/TLS·redaction·#863 한계 |

### #866 — 중첩·복합·특수 타입

| 경로 | 책임 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/ArrayColumnType.kt` | 기존 `chArray`와 nullable/nested element adapter |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/CollectionColumnTypes.kt` | Map/Tuple/Nested, JDBC `Array`/`Struct`/`getResultSet` lifecycle |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/SpecialColumnTypes.kt` | JSON, UUID, IPv4/IPv6, Decimal, Enum |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/DateTime64ColumnType.kt` | precision/zone 및 overflow 경계 |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/UnsignedColumnTypes.kt` | UInt64 high-bit/BigInteger 경계 |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseComplexTypesH2Test.kt` | H2 converter 의미론 전용 |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseComplexTypesTest.kt` | ClickHouse DDL·wire round-trip·metadata |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseTypeBoundaryFixtures.kt` | schema, boundary matrix, unique fixture/seed |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/AbstractClickHouseTest.kt` | SAME_THREAD와 ResourceLock |
| `exposed/clickhouse/src/test/resources/junit-platform.properties` | JUnit parallel false |

### #867 — RowBinary 배치 writer

| 경로 | 책임 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryOptions.kt` | beta opt-in과 positive `maxRowsPerFlush` |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseConnectionProvider.kt` | public dual-profile provider |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryPreflight.kt` | SQL/setter/option/capability 판정과 reason code |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryExecutor.kt` | profile 선택, driver writer, close, count, unusable state |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryResult.kt` | immutable counts/path/incomplete flag |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryFallbackEvent.kt` | internal pre-byte fallback event |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryTest.kt` | profile/preflight/fallback/setter/count/lifecycle |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryBenchmarkTest.kt` | 36조합 raw JSON/provenance |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/RowBinaryConnectionProviderFixture.kt` | 독립 profile·close 추적 |
| `docs/benchmarks/clickhouse-v2-rowbinary/` | renderer, six raw JSON, README EN/KO, SHA256SUMS |
| `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.*` | semantic ledger와 EN/KO SVG·PNG |

### #868 — query diagnostics

| 경로 | 책임 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnostics.kt` | immutable diagnostics/event/listener/sink/outcome/cancellation |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsRecorder.kt` | monotonic timer, terminal-once, callback isolation, failure counter; internal |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsRedaction.kt` | SQL/bind/secret redaction; internal |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseExtensions.kt` | diagnostics optional entry point와 기존 behavior |
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryStreaming.kt` | cursor cleanup와 diagnostics lifecycle |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsTest.kt` | ID/clock/order/callback/sink/retry/redaction |
| `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseResourceLifecycleTest.kt` | close/rollback/exception precedence/cancellation |
| `docs/lessons/2026-09-09-clickhouse-v2-*.md` | 구현 후 known gap과 재실행 기록 |

## 공통 TDD·환경 규칙

1. 각 mutation family 직전에 현재 workflow run `20260909T101824Z-d02427ba`의 owner handle과 대상 path를 확인하고 `bluetape-flow.py mutation-check`를 실행한다. 실패 호출은 evidence로 세지 않는다.
2. 각 production 변경은 먼저 실제 assertion을 가진 RED test를 commit하고 selector를 실행한다. compile failure 또는 assertion failure가 관찰되지 않으면 다음 단계로 가지 않는다.
3. 최소 구현 뒤 동일 selector와 `git diff --check`를 실행하고 JUnit XML의 `tests`, `failures`, `errors`, `skipped`를 읽는다. skipped 또는 test count 0은 PASS가 아니다.
4. ClickHouse Testcontainers class에는 `@Execution(ExecutionMode.SAME_THREAD)`와 `@ResourceLock("clickhouse-v2")`를 모두 사용한다. Gradle은 `--no-parallel --max-workers=1 --no-daemon --console=plain`으로 실행한다.
5. commit은 PR 단위 Lore trailer를 사용하고, `component-evidence`·`completion-check`는 모든 required check와 main verification을 읽은 후에만 실행한다.

## Task 0: 기준선·계획 review·train topology를 고정한다

**Files:** `docs/superpowers/plans/2026-09-09-clickhouse-v2-stacked-train.md`, `docs/superpowers/reviews/2026-09-09-clickhouse-v2-plan-review.md`

- [ ] **Step 1: 기준선을 실행한다**

```bash
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-865-clickhouse-v2-options status --short --branch
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-865-clickhouse-v2-options rev-parse HEAD
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed rev-parse develop
./gradlew :bluetape4k-exposed-clickhouse:test --no-daemon --console=plain
git diff --check
```

Expected: feature HEAD `06e0b58d585f5ad98f2f4c82a8a1cbdbbc0e3bae`, canonical develop `ac6a0dc330d29a2812f83dd1a7aaa7db42fd0b26`, baseline test exit 0, diff check clean. Baseline은 신규 DS pass가 아니다.

- [ ] **Step 2: 계획 review를 작성한다**

`docs/superpowers/reviews/2026-09-09-clickhouse-v2-plan-review.md`에서 SPW-01 source/design traceability, SPW-02 API/security/ownership, SPW-03 TDD/verification, SPW-04 stacked exact-head/Closes/rollback, SPW-05 EN/KO/docs/operations를 실제 path와 대조한다. review 방식은 `inline fallback·비독립`으로 명시하고 P0/P1=0일 때만 PASS로 둔다.

- [ ] **Step 3: 계획과 review를 docs-only Lore commit으로 저장한다**

```bash
git add docs/superpowers/plans/2026-09-09-clickhouse-v2-stacked-train.md docs/superpowers/reviews/2026-09-09-clickhouse-v2-plan-review.md
git commit -m "ClickHouse JDBC V2 stacked train 실행 경계를 고정한다" \
  -m "Constraint: 구현은 계획 승인과 exact-head stacked PR gate 뒤에만 시작한다
Rejected: 한 PR에 네 이슈를 합치는 방식 | 부모별 검증·rollback·review 경계가 사라진다
Confidence: high
Scope-risk: moderate
Directive: 각 child branch는 부모의 정확한 head와 자신의 Closes token을 유지한다
Tested: baseline ClickHouse test, git diff --check, plan review SPW-01..05
Not-tested: 신규 API와 benchmark는 계획 승인 후 구현한다"
```

Expected: commit에는 plan/review만 있고 production source·test source·Gradle·README·benchmark output은 없다.

## Task 1: #865 RED — immutable options와 property mapping을 고정한다

**Files:** Create `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2OptionsTest.kt`; modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseValidationTest.kt`

- [x] **Step 1: mapping/auth/header RED 테스트를 작성한다**

테스트는 `connection_timeout=1500`, `socket_timeout=2000`, `connection_request_timeout=3000`, `compress/decompress/client.use_http_compression`, retry, query id, `clickhouse_setting_<name>`의 정확한 값과 milliseconds 단위를 비교한다. `rawProperties` defensive copy, unknown/credential/
`beta.row_binary_for_simple_insert` 거부, timeout negative와 zero-as-driver-default, Basic/AccessToken/BearerToken one-of, token+explicit credential 충돌, case-insensitive `X-ClickHouse-User-Agent`, duplicate header, CR/LF, 인증·routing·hop-by-hop header 거부, URL auth key 거부, scrubbed message/cause/suppressed/logger canary를 각각 독립 assertion으로 둔다.

```kotlin
val options = ClickHouseV2Options(connectionTimeoutMillis = 1_500L, socketOperationTimeoutMillis = 2_000, queryId = "query-865")
options.toEffectiveProperties(user = "default", password = "").getProperty("connection_timeout") shouldBeEqualTo "1500"
assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(rawProperties = mapOf("beta.row_binary_for_simple_insert" to "true")) }
assertFailsWith<IllegalArgumentException> { ClickHouseV2Options(customHeaders = mapOf("Authorization" to "token")) }
```

- [x] **Step 2: RED selector를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseV2OptionsTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: 새 model/mapper 부재 compile failure. 기존 baseline test 성공을 RED 대체 증거로 사용하지 않는다.

## Task 2: #865 GREEN — options/auth/property/redaction을 구현한다

**Files:** Create `ClickHouseV2Options.kt`, `ClickHouseV2Properties.kt`, `ClickHouseV2Redaction.kt` under `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse`

- [x] **Step 1: public model을 구현한다**

`ClickHouseV2Options`는 `connectionTimeoutMillis: Long?`, `socketOperationTimeoutMillis: Int?`, `connectionRequestTimeoutMillis: Long?`, `connectionTtlMillis`, `httpKeepAliveTimeoutMillis`, `connectionPoolEnabled`, `maxOpenConnections`, `connectionReuseStrategy`, `useServerTimeZone`, `compressServerResponse`, `compressClientRequest`, `useHttpCompression`, `lz4UncompressedBufferSize`, `retryOnFailure`, `authentication`, `clientName`, `sessionDbRoles`, `sessionTimezone: ZoneId?`, `queryId`, `logComment`, `serverSettings`, `proxy`, `tls`, `customHeaders`, `rawProperties`를 가진 immutable `AbstractValueObject`로 선언한다. 방어적 collection 복사와 민감정보를 제거한 `buildStringHelper()`를 함께 제공해야 하므로 data class 자동 생성 메서드보다 값 객체 기반 equality·문자열 경계를 우선한다. `ClickHouseV2Authentication`은 `Basic`, `AccessToken(value)`, `BearerToken(value)` one-of sealed type이며, proxy/TLS nested map·list와 raw input은 생성 시 복사한다.

```kotlin
import io.bluetape4k.AbstractValueObject

sealed interface ClickHouseV2Authentication {
    data object Basic : ClickHouseV2Authentication
    data class AccessToken(val value: String) : ClickHouseV2Authentication
    data class BearerToken(val value: String) : ClickHouseV2Authentication
}

class ClickHouseV2Options(
    val connectionTimeoutMillis: Long? = null,
    val socketOperationTimeoutMillis: Int? = null,
    val connectionRequestTimeoutMillis: Long? = null,
    val connectionTtlMillis: Long? = null,
    val httpKeepAliveTimeoutMillis: Long? = null,
    val connectionPoolEnabled: Boolean? = null,
    val maxOpenConnections: Int? = null,
    val connectionReuseStrategy: ClickHouseV2ConnectionReuseStrategy? = null,
    val useServerTimeZone: Boolean? = null,
    val compressServerResponse: Boolean? = null,
    val compressClientRequest: Boolean? = null,
    val useHttpCompression: Boolean? = null,
    val lz4UncompressedBufferSize: Int? = null,
    val retryOnFailure: Int? = null,
    val authentication: ClickHouseV2Authentication = ClickHouseV2Authentication.Basic,
    val clientName: String? = null,
    val sessionDbRoles: List<String> = emptyList(),
    val sessionTimezone: ZoneId? = null,
    val queryId: String? = null,
    val logComment: String? = null,
    val serverSettings: Map<String, String> = emptyMap(),
    val proxy: ClickHouseV2ProxyOptions? = null,
    val tls: ClickHouseV2TlsOptions? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val rawProperties: Map<String, String> = emptyMap(),
) : AbstractValueObject()
```

같은 파일에 `enum class ClickHouseV2ConnectionReuseStrategy { FIFO, LIFO }`, `fun interface ClickHouseV2SecretProvider { fun resolve(): CharArray }`, 다음 두 immutable value를 선언한다.

```kotlin
data class ClickHouseV2ProxyOptions(
    val type: String = "HTTP",
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: ClickHouseV2SecretProvider? = null,
)

data class ClickHouseV2TlsOptions(
    val trustStore: String? = null,
    val keyStoreType: String? = null,
    val sslKeyStore: String? = null,
    val keyStorePassword: ClickHouseV2SecretProvider? = null,
    val sslKeyReference: String? = null,
    val sslRootCertReference: String? = null,
    val sslCertReference: String? = null,
    val sslAuthentication: String? = null,
    val sslSocketSni: String? = null,
)
```

`trustStore`, `sslKeyStore`, `sslKeyReference`, `sslRootCertReference`, `sslCertReference`는 파일·secret-store reference만 허용하고 private key·trust material 원문을 받지 않는다. `ClickHouseV2SecretProvider`의 반환 배열은 mapping 직후 지우며 `toString`/로그에 포함하지 않는다. 모든 숫자 timeout·TTL·pool limit·retry·LZ4 buffer는 범위를 constructor에서 검증하고 `sessionDbRoles`, proxy allowlist, custom/raw map은 `toList()`/`toMap()`으로 복사한다.

핵심 key/unit 매핑은 다음 표를 테스트 fixture와 동일하게 사용한다.

| typed field | V2 key | 단위·검증 |
|---|---|---|
| `connectionTimeoutMillis` | `connection_timeout` | milliseconds, `> 0` |
| `socketOperationTimeoutMillis` | `socket_timeout` | milliseconds, `>= 0` (`0`은 driver default) |
| `connectionRequestTimeoutMillis` | `connection_request_timeout` | milliseconds, `>= 0` |
| `connectionTtlMillis` | `connection_ttl` | `-1` 또는 `> 0` |
| `httpKeepAliveTimeoutMillis` | `http_keep_alive_timeout` | milliseconds, `> 0` |
| `connectionPoolEnabled` | `connection_pool_enabled` | boolean |
| `maxOpenConnections` | `max_open_connections` | positive integer |
| `compressServerResponse`/`compressClientRequest` | `compress`/`decompress` | boolean |
| `useHttpCompression` | `client.use_http_compression` | boolean |
| `lz4UncompressedBufferSize` | `compression.lz4.uncompressed_buffer_size` | positive bytes |
| `retryOnFailure` | `retry` | non-negative integer |
| `useServerTimeZone` | `use_server_time_zone` | boolean |
| `clientName`/`sessionDbRoles`/`sessionTimezone` | `client_name`/`session_db_roles`/`use_time_zone` | client string, comma-separated non-blank roles, valid IANA zone |
| `queryId`/`logComment` | `query_id`/`clickhouse_setting_log_comment` | non-blank, redacted in logs |

`serverSettings`는 `clickhouse_setting_<name>`로만 내보내고, proxy/TLS는 V2 driver의 proxy·TLS key allowlist로 직렬화한다. typed field와 동일한 raw key가 함께 있으면 typed 우선이 아니라 생성 시 명시적 중복 오류를 낸다. authentication은 `Basic`, `AccessToken`, `BearerToken` one-of를 유지하고 token mode는 default placeholder user/password 외의 명시 credentials와 함께 사용할 수 없다.

- [x] **Step 2: effective properties와 redaction을 구현한다**

V2 `ClientConfigProperties` allowlist와 `clickhouse_setting_<name>`·`http_header_X-ClickHouse-User-Agent` prefix만 raw로 허용한다. RowBinary beta key와 credentials/TLS secret duplicate 및 unknown key는 fail-fast한다. 일반 precedence는 `JDBC URL query > explicit argument 또는 typed option > rawProperties > driver default`이며 새 options overload URL의 `user`, `password`, `access_token`, `bearer_token`, `http_use_basic_auth`는 key만 남긴 scrubbed error로 거부한다. token mode effective map에는 token key와 `http_use_basic_auth=false`만 남긴다. URL query value, password, token, TLS secret, header value, SQL bind와 원본 exception graph를 redaction helper가 제거한다.

테스트와 구현이 공유하는 내부 변환 경계는 `internal fun ClickHouseV2Options.toEffectiveProperties(user: String, password: String, jdbcUrl: String? = null): Properties`로 고정한다. 이 함수는 URL query를 파싱하되 값을 로그·예외에 재사용하지 않고, 반환 `Properties`를 호출자 변경과 드라이버 변경으로부터 보호하는 방어적 복사로 만든다.

- [x] **Step 3: GREEN options와 detekt를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseV2OptionsTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:detekt --no-daemon --console=plain
```

Expected: selector tests와 detekt가 exit 0, failures/errors/skipped=0. `build/reports/clickhouse-v2/ds-02.json`에 실제 key/unit/default와 redaction 결과를 기록한다.

- [x] **Step 4: options boundary commit을 만든다**

```bash
git add exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2*.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2OptionsTest.kt
git commit -m "ClickHouse JDBC V2 옵션의 immutable 보안 경계를 추가한다" \
  -m "Constraint: V2 0.9.9 allowlist와 token/basic one-of를 유지한다
Rejected: raw Properties 전체 전달 | unknown key와 secret 중복을 호출자에게 떠넘긴다
Confidence: high
Scope-risk: moderate
Directive: RowBinary beta key는 #867 provider가 단일 소유한다
Tested: ClickHouseV2OptionsTest, detekt
Not-tested: Database.connect wrapper와 JVM descriptor는 Task 3에서 검증한다"
```

## Task 3: #865 GREEN — connect overload·exception graph·ABI·문서를 구현한다

**Files:** Create `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseConnectionException.kt`; modify `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabase.kt`, `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseValidationTest.kt`; create `exposed/clickhouse/src/test/java/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseJavaInteropTest.java`; modify generated `api/bluetape4k-exposed-clickhouse.api`, `exposed/clickhouse/README.md`, `exposed/clickhouse/README.ko.md`

- [x] **Step 1: overload/Java RED fixture를 작성하고 실행한다**

Java fixture는 `getMethod("connect", String.class, int.class, String.class, String.class, String.class, ClickHouseV2Options.class)`와 `getMethod("connect", String.class, String.class, String.class, ClickHouseV2Options.class)`를 확인하고 return type이 `Database`인지 assertion한다. 기존 Kotlin named/positional call도 그대로 둔다.

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseDatabaseJavaInteropTest' --no-daemon --console=plain
```

Expected: 신규 overload 부재 compile/reflection failure.

- [x] **Step 2: 두 신규 overload와 exception wrapper를 구현한다**

정확한 선언은 다음과 같고 options에는 default를 주지 않는다.

```kotlin
fun connect(host: String = "localhost", port: Int = 8123, database: String = "default", user: String = "default", password: String = "", options: ClickHouseV2Options): Database
fun connect(jdbcUrl: String, user: String = "default", password: String = "", options: ClickHouseV2Options): Database
```

기존 두 descriptor와 `$default` bridge는 유지한다. 새 경로는 effective properties로 DriverManager를 호출하고, 연결/래퍼 생성 실패를 `ClickHouseConnectionException`으로 감싸며 SQLState/vendor code만 복사한다. 원본 cause/suppressed는 연결하지 않는다. raw connection cleanup 실패는 `SanitizedCleanupException(reasonCode, sqlState, vendorCode)`로 변환해 wrapper suppressed에만 둔다.

- [x] **Step 3: ABI·validation·docs GREEN을 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseDatabaseJavaInteropTest' --tests '*ClickHouseDatabaseValidationTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:updateKotlinAbi --no-daemon --console=plain
javap -classpath exposed/clickhouse/build/libs/bluetape4k-exposed-clickhouse-2.1.0.jar io.bluetape4k.exposed.clickhouse.ClickHouseDatabase
git diff --check
```

Expected: Java/validation failures/errors/skipped=0, ABI exit 0, `javap`에 신규 두 descriptor와 기존 descriptor/bridge가 모두 있고 baseline diff는 의도한 symbols뿐이다. README EN/KO는 options 예제·precedence·redaction·#863 한계를 동일하게 설명한다.

- [x] **Step 4: #865 commit과 PR을 생성한다**

```bash
git add exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseConnectionException.kt exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabase.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseValidationTest.kt exposed/clickhouse/src/test/java/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseJavaInteropTest.java api/bluetape4k-exposed-clickhouse.api exposed/clickhouse/README.md exposed/clickhouse/README.ko.md
git commit -m "ClickHouse JDBC V2 연결 옵션을 Exposed API에 연결한다" \
  -m "Constraint: 기존 connect descriptor와 URL precedence를 보존한다
Rejected: Properties overload를 그대로 공개 | auth/raw/header redaction 경계가 분산된다
Confidence: high
Scope-risk: moderate
Directive: PR body는 Closes #865를 사용하고 merge는 별도 승인으로 남긴다
Tested: options, validation, Java interop, checkKotlinAbi, detekt, diff-check
Not-tested: nested type·RowBinary·diagnostics는 부모 exact head 이후에 검증한다"
git push -u origin feat/issue-865-clickhouse-v2-options
gh pr create --repo bluetape4k/bluetape4k-exposed --base develop --head feat/issue-865-clickhouse-v2-options --title "[feat][clickhouse] JDBC V2 연결·보안·세션 옵션을 Exposed API로 노출한다" --body "## 요약
ClickHouse JDBC V2 0.9.9 연결 옵션과 redacted connection boundary를 추가합니다.

## 검증
- ClickHouseV2OptionsTest
- Java interop 및 checkKotlinAbi
- detekt 및 diff-check

Closes #865"
pr_865=$(gh pr view feat/issue-865-clickhouse-v2-options --repo bluetape4k/bluetape4k-exposed --json number --jq '.number')
gh pr edit "$pr_865" --repo bluetape4k/bluetape4k-exposed --add-assignee debop --milestone "2.1.0" --add-label documentation --add-label enhancement --add-label feature --add-label test
```

PR 생성 후 `gh pr view --json headRefOid,baseRefName,headRefName,statusCheckRollup,reviews,reviewThreads,mergeable`로 fresh exact-head evidence를 남기고 merge하지 않는다.

실행 결과: PR #869 (`5474d51f30e07cb8198d6be7f71d7ed0970dd361`) 생성 완료.

## Task 4: #866 RED — nullability·nested depth·JDBC lifecycle matrix를 고정한다

**Branch:** `feat/issue-866-clickhouse-v2-types`, #865 PR의 exact head에서 생성, PR base는 `feat/issue-865-clickhouse-v2-options`

**Files:**
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseComplexTypesH2Test.kt`
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseComplexTypesTest.kt`
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/ClickHouseTypeBoundaryFixtures.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/AbstractClickHouseTest.kt`
- Modify: `exposed/clickhouse/src/test/resources/junit-platform.properties`

- [x] **Step 0: #865 exact head에서 child worktree를 만든다**

```bash
git fetch origin develop feat/issue-865-clickhouse-v2-options
parent_865=$(git rev-parse origin/feat/issue-865-clickhouse-v2-options)
git worktree add -b feat/issue-866-clickhouse-v2-types /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-866-clickhouse-v2-types "$parent_865"
test "$(git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-866-clickhouse-v2-types rev-parse HEAD)" = "$parent_865"
```

Expected: child worktree HEAD가 #865 `headRefOid`와 같고, canonical `develop`와 부모/자식 worktree에는 변경이 없다.

- [x] **Step 1: H2 RED matrix를 작성한다**

`ClickHouseComplexTypesH2Test`는 ClickHouse wire semantics 증거가 아니라 converter 의미론 증거다. `Column<List<T?>>`와 `Column<List<T?>?>`를 별도 검사하고, Array/nested Array, Map null key·duplicate key, Tuple arity, Nested ragged row, JSON invalid text/codec exception, UUID/IP family, DateTime64 precision·zone·epoch overflow, Decimal rounding/precision, Enum ordinal 금지, UInt64 high-bit, mutable collection copy, `valueFromDB`/`notNullValueToDB`/`setParameter`/`readObject` nullability를 모두 실제 assertion으로 작성한다.

```kotlin
@Test
fun `Array element nullability와 container nullability는 별도 축이다`() {
    val elements: ColumnType<List<String?>> = ClickHouseArrayColumnType(ClickHouseNullableColumnType(ClickHouseStringColumnType()))
    val container: ColumnType<List<String>?> = ClickHouseNullableColumnType(ClickHouseArrayColumnType(ClickHouseStringColumnType()))
    elements.notNullValueToDB(listOf("a", null)).toList() shouldBeEqualTo listOf("a", null)
    container.valueFromDB(null) shouldBeEqualTo null
}

@Test
fun `빈 배열은 element schema 없이 추측하지 않는다`() {
    assertFailsWith<IllegalArgumentException> {
        ClickHouseArrayColumnType<Any>(null as ColumnType<Any>)
    }
}
```

- [x] **Step 2: H2 RED selector를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesH2Test' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: 신규 builders/lifecycle 부재 compile failure. H2 test 결과를 ClickHouse 증거로 집계하지 않는다.

실행 결과: `ClickHouseComplexTypesH2Test`를 추가하고 selector를 실행했다. `compileTestKotlin`이 `ClickHouseJsonCodec`, nullable-array builder, UUID/IP/Decimal/Enum adapter 부재의 unresolved reference로 실패했다. 이는 구현 전 RED 증거이며 H2/ClickHouse wire 결과로 집계하지 않는다.

- [ ] **Step 3: ClickHouse wire RED round-trip을 작성한다**

`ClickHouseComplexTypesTest`는 클래스별 고유 table/schema와 seed를 사용해 DDL→insert→select→metadata를 실행하고 `finally`에서 정리한다. Array(Nullable), nested Array, Map, Tuple, Nested, JSON, UUID, IPv4/IPv6, Date/DateTime64, Decimal, Enum, UInt64를 각각 insert/select한다. `ResultSetMetaData`의 type name, JDBC type, nullability, precision/scale, nested signature을 별도 assertion으로 둔다. JDBC `Array`/`Struct`는 읽은 뒤 즉시 close한다.

```kotlin
@Test
fun `ClickHouse complex type round trip은 순서 null metadata를 보존한다`() {
    transaction(db) {
        ComplexTypesTable.insert { row ->
            row[nullableArray] = listOf("a", null, "c")
            row[nestedArray] = listOf(listOf(1, 2), emptyList())
            row[tuple] = listOf("kr", 7)
        }
        val selected = ComplexTypesTable.selectAll().single()
        selected[nullableArray] shouldBeEqualTo listOf("a", null, "c")
        selected[nestedArray] shouldBeEqualTo listOf(listOf(1, 2), emptyList())
        selected[tuple] shouldBeEqualTo listOf("kr", 7)
    }
}
```

## Task 5: #866 GREEN — collection·special·temporal adapter와 metadata를 구현한다

**Files:**
- Modify: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/ArrayColumnType.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/CollectionColumnTypes.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/SpecialColumnTypes.kt`
- Modify: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/DateTime64ColumnType.kt`
- Modify: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types/UnsignedColumnTypes.kt`

- [ ] **Step 1: Array/Map/Tuple/Nested adapter를 구현한다**

기존 `fun <T: Any> Table.chArray(name: String, innerType: ColumnType<T>): Column<List<T>>`의 source/JVM surface는 유지한다. 새 nullable element/container overload는 별도 이름으로 선언하고 `Column<List<T?>>`와 `Column<List<T?>?>`를 혼동하지 않는다.

```kotlin
fun <T : Any> Table.chArray(name: String, innerType: ColumnType<T>): Column<List<T>> =
    registerColumn(name, ClickHouseArrayColumnType(innerType))

fun <T : Any> Table.chArrayNullableElements(name: String, innerType: ColumnType<T>): Column<List<T?>> =
    registerColumn(name, ClickHouseNullableArrayColumnType(innerType, nullableContainer = false))

fun <T : Any> Table.chNullableArray(name: String, innerType: ColumnType<T>): Column<List<T?>?> =
    registerColumn(name, ClickHouseNullableArrayColumnType(innerType, nullableContainer = true))

fun <K : Any, V> Table.chMap(name: String, keyType: ColumnType<K>, valueType: ColumnType<V>): Column<Map<K, V>>
fun Table.chTuple(name: String, elements: List<ColumnType<*>>): Column<List<Any?>>
fun Table.chNested(name: String, elements: List<ColumnType<*>>): Column<List<List<Any?>>>
```

`ClickHouseNullableArrayColumnType<T : Any>(inner: ColumnType<T>, nullableContainer: Boolean)`를 새 내부 adapter로 정의해 element nullability와 container nullability를 각각 표현한다. 기존 `ClickHouseArrayColumnType<T : Any>`의 constructor와 descriptor는 바꾸지 않는다.

각 adapter가 `valueFromDB`, `notNullValueToDB`, `setParameter`, `readObject`에서 같은 matrix를 적용한다. Map key는 non-null·unique, Tuple arity는 고정, Nested 모든 column 길이는 동일해야 한다. JDBC `Array`/`Struct`/`ResultSet`은 converter가 읽은 직후 `finally`에서 close하고 반환 collection은 immutable copy다. unsupported type을 String으로 fallback하지 않는다.

- [ ] **Step 2: JSON/network/decimal/enum/temporal/UInt64를 구현한다**

```kotlin
fun Table.chJson(name: String): Column<String>
fun <T> Table.chJson(name: String, codec: ClickHouseJsonCodec<T>): Column<T>
fun Table.chUuid(name: String): Column<UUID>
fun Table.chIpv4(name: String): Column<Inet4Address>
fun Table.chIpv6(name: String): Column<Inet6Address>
fun Table.chDecimal(name: String, precision: Int, scale: Int): Column<BigDecimal>
fun <E : Enum<E>> Table.chEnum(name: String, values: Map<E, String>): Column<E>
```

JSON raw mode는 유효 JSON text만 허용하고 codec은 caller 소유다. Enum은 ordinal을 사용하지 않고 선언된 name/alias만 wire에 쓴다. Decimal은 declared precision/scale과 `RoundingMode.UNNECESSARY`를 적용한다. DateTime64는 precision과 zone이 없으면 실패하며, UInt64는 signed `Long` high-bit 축소를 금지하고 `BigInteger` 범위를 검증한다. production serializer dependency는 추가하지 않는다.

경계 matrix는 다음 입력·wire·실패 규칙을 코드와 receipt에 그대로 반영한다.

| 타입 | 허용 입력·wire | 반드시 실패하는 경계 |
|---|---|---|
| Array/Nullable/Nested | `List`, Java array, JDBC `Array`; element/container nullability와 depth를 보존 | null key, ragged nested row, 빈 schema 추론, unsupported String literal |
| Map/Tuple | `Map<K,V>`와 고정 arity tuple; JDBC `Struct`/`ResultSet`은 즉시 close | null/중복 key, tuple arity 불일치, mutable 결과 노출 |
| JSON | 유효 JSON text 또는 caller codec의 typed value | malformed text, codec 예외를 문자열로 숨김 |
| UUID/IP | `UUID`, `Inet4Address`, `Inet6Address`와 ClickHouse 해당 wire | IPv4/IPv6 family 혼동, malformed address |
| Date/DateTime64 | UTC `Instant`와 선언 precision/zone | precision `0..9` 밖, epoch overflow, zone 누락 |
| Decimal | 선언 precision/scale에 맞는 `BigDecimal` | rounding이 필요한 값, precision/scale 초과 |
| Enum | 명시한 name/alias ↔ enum 값 | ordinal·미등록 alias |
| UInt64 | `BigInteger` `0..2^64-1` | signed `Long` high-bit 축소, 음수·범위 초과 |

- [ ] **Step 3: H2와 ClickHouse GREEN을 순차 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesH2Test' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: 두 명령 exit 0, 각 XML failures/errors/skipped=0, H2와 ClickHouse receipt가 별도 생성된다. ClickHouse receipt에 image tag/digest, container id, fixture name, seed, start/end time을 기록한다. container/lock failure는 `PENDING`이다.

- [ ] **Step 4: #866 ABI·문서를 확인한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:detekt --no-daemon --console=plain
git diff --check
```

Expected: 기존 `chArray` descriptor 불변, 신규 symbols만 baseline에 추가, detekt/diff check exit 0. README EN/KO 타입 matrix와 unsupported 정책이 동일하다.

- [ ] **Step 5: #866 commit과 PR을 생성한다**

```bash
git add exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/types exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/AbstractClickHouseTest.kt exposed/clickhouse/src/test/resources/junit-platform.properties exposed/clickhouse/README.md exposed/clickhouse/README.ko.md api/bluetape4k-exposed-clickhouse.api
git commit -m "ClickHouse JDBC V2 복합 타입의 nullability와 wire 경계를 고정한다" \
  -m "Constraint: 기존 chArray API와 H2/ClickHouse 증거를 분리한다
Rejected: 지원하지 않는 서버 타입을 String으로 변환 | 데이터 손실과 metadata 오해를 만든다
Confidence: high
Scope-risk: broad
Directive: JDBC Array/Struct는 반환 직후 닫고 mutable collection을 노출하지 않는다
Tested: ClickHouseComplexTypesH2Test, ClickHouseComplexTypesTest, ABI, detekt
Not-tested: RowBinary setter capability는 Task 6~8에서 검증한다"
git push -u origin feat/issue-866-clickhouse-v2-types
gh pr create --repo bluetape4k/bluetape4k-exposed --base feat/issue-865-clickhouse-v2-options --head feat/issue-866-clickhouse-v2-types --title "[feat][clickhouse] JDBC V2 중첩·복합·특수 타입 매핑을 Exposed DSL로 지원한다" --body "## 요약
Array nullable/nested, Map/Tuple/Nested, JSON·UUID·IP·DateTime·Decimal·Enum·UInt64 adapter와 metadata round-trip을 추가합니다.

## 검증
- ClickHouseComplexTypesH2Test
- ClickHouseComplexTypesTest
- ABI, detekt, EN/KO 문서 parity

Closes #866"
pr_866=$(gh pr view feat/issue-866-clickhouse-v2-types --repo bluetape4k/bluetape4k-exposed --json number --jq '.number')
gh pr edit "$pr_866" --repo bluetape4k/bluetape4k-exposed --add-assignee debop --milestone "2.1.0" --add-label documentation --add-label enhancement --add-label feature --add-label test
```

PR base/head를 `gh pr view`로 read-back하고 #865 exact head가 history에 포함되는지 확인한다. merge하지 않는다.

## Task 6: #867 RED — profile·preflight·fallback·count 계약을 고정한다

**Branch:** `feat/issue-867-clickhouse-v2-rowbinary`, #866 exact head에서 생성, PR base는 `feat/issue-866-clickhouse-v2-types`

**Files:**
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryTest.kt`
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/RowBinaryConnectionProviderFixture.kt`
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryBenchmarkTest.kt`

- [ ] **Step 0: #866 exact head에서 child worktree를 만든다**

```bash
git fetch origin feat/issue-866-clickhouse-v2-types
parent_866=$(git rev-parse origin/feat/issue-866-clickhouse-v2-types)
git worktree add -b feat/issue-867-clickhouse-v2-rowbinary /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-867-clickhouse-v2-rowbinary "$parent_866"
test "$(git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-867-clickhouse-v2-rowbinary rev-parse HEAD)" = "$parent_866"
```

Expected: child worktree HEAD가 #866 `headRefOid`와 같고, #865 변경이 history에 포함되며 다른 worktree는 dirty하지 않다.

- [ ] **Step 1: provider/options RED API test를 작성한다**

```kotlin
@Test
fun `provider는 true false profile을 독립 생성하고 검증한다`() {
    val provider = RowBinaryConnectionProviderFixture()
    provider.open(true).use { provider.assertProfile(it, true) }
    provider.open(false).use { provider.assertProfile(it, false) }
    provider.assertNoProfileReuse()
}

@Test
fun `provider가 없으면 unsupported configuration으로 fail closed 한다`() {
    assertFailsWith<UnsupportedConfiguration> {
        ClickHouseRowBinaryExecutor(null, ClickHouseRowBinaryOptions(enabled = true))
            .executeBatch("INSERT INTO t VALUES (?)", listOf(row(1)))
    }
}
```

`ClickHouseRowBinaryOptions`는 `enabled=false`, 양수 `maxRowsPerFlush`, beta property raw override 금지를 고정한다. provider/DataSource/pool은 caller 소유이고 executor는 빌린 connection/statement/result만 닫는다.

- [ ] **Step 2: preflight/fallback RED matrix를 작성한다**

단순 single-values eligible, `INSERT SELECT`, 여러 values group, values function, unsupported nested setter, invalid option, capability unknown, provider absent, setter failure after statement, first-byte 이후 failure, empty input, partial update counts를 각각 작성한다.

```kotlin
@Test
fun `unsupported SQL은 statement 생성 전에 beta disabled profile을 선택한다`() {
    val provider = RowBinaryConnectionProviderFixture()
    val result = executor(provider).executeBatch("INSERT INTO t SELECT ?", listOf(row(1)))
    result.path shouldBeEqualTo ClickHouseBatchPath.JDBC_FALLBACK
    provider.openedProfiles shouldBeEqualTo listOf(false)
    provider.fallbackEvents.single().beforeFirstByte shouldBeTrue()
}

@Test
fun `setter 이후 실패는 원래 예외와 unusable 상태를 보존하고 fallback하지 않는다`() {
    val original = SQLException("setter-marker")
    val provider = RowBinaryConnectionProviderFixture(setterFailure = original)
    val failure = assertFailsWith<SQLException> { executor(provider).executeBatch(simpleInsert, listOf(row(1))) }
    failure.shouldBeSameInstanceAs(original)
    provider.assertClosedExactlyOnce()
    provider.fallbackEvents shouldBeEmpty()
}
```

- [ ] **Step 3: RED selector를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: options/provider/executor/preflight 미정의 compile RED.

## Task 7: #867 GREEN — driver-owned executor와 result lifecycle을 구현한다

**Files:**
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryOptions.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseConnectionProvider.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryPreflight.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryResult.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryFallbackEvent.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryExecutor.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/RowBinaryConnectionProviderFixture.kt`

- [ ] **Step 1: public API와 preflight를 구현한다**

```kotlin
data class ClickHouseRowBinaryOptions(
    val enabled: Boolean = false,
    val maxRowsPerFlush: Int = 1024,
)

fun interface ClickHouseConnectionProvider {
    fun open(rowBinaryEnabled: Boolean): Connection
}

data class ClickHouseRowBinaryResult(
    val updateCounts: List<Int>,
    val acceptedCount: Int,
    val path: ClickHouseBatchPath,
    val acceptedCountMayBeIncomplete: Boolean,
)

enum class ClickHouseBatchPath { ROW_BINARY, JDBC_FALLBACK }

class UnsupportedConfiguration(
    val reasonCode: String,
    message: String,
) : IllegalStateException(message)

fun interface ClickHouseRowBinaryRow {
    fun bind(statement: PreparedStatement)
}

class ClickHouseRowBinaryExecutor(
    private val provider: ClickHouseConnectionProvider?,
    private val options: ClickHouseRowBinaryOptions,
) {
    fun executeBatch(sql: String, rows: Iterable<ClickHouseRowBinaryRow>): ClickHouseRowBinaryResult
}
```

`ClickHouseRowBinaryPreflight`는 connection/statement를 만들기 전에 SQL이 단순 `INSERT ... VALUES (?, ...)`, 단일 values group, supported column/setter, valid options인지 판정한다. eligible이면 provider `open(true)`, unsupported/capability unknown이면 `open(false)`를 호출한다. provider가 실제 profile을 검증하지 못하면 고정 `UnsupportedConfiguration`으로 fail-closed한다. beta property는 connection-scoped이며 기존 `Database`/pool properties를 mutate하지 않는다.

- [ ] **Step 2: operation lifecycle과 fallback state machine을 구현한다**

executor는 provider가 반환한 operation-scoped connection에서 statement를 만들고 rows를 `maxRowsPerFlush` 행 이하로 flush한다. provider/DataSource/pool/ambient Exposed transaction은 닫지 않으며 `commit()`·`rollback()`을 호출하지 않는다.

```kotlin
connection.use { connection ->
    connection.prepareStatement(sql).use { statement ->
        rows.chunked(options.maxRowsPerFlush).forEach { chunk ->
            chunk.forEach { row ->
                row.bind(statement)
                statement.addBatch()
            }
            updateCounts += statement.executeBatch().asList()
        }
    }
}
```

fallback은 첫 setter/byte/row 전송 전 한 번만 허용하며, provider가 실제 beta-disabled profile을 선택한 경우에만 internal `ClickHouseRowBinaryFallbackEvent(reasonCode, beforeFirstByte=true)`를 만든다. 첫 setter 이후 실패하면 writer를 unusable로 만들고 원래 예외를 던지며 fallback/retry/resend하지 않는다. fixed beta-enabled Database에서 unsupported SQL이면 일반 JDBC로 몰래 변경하지 않고 `UnsupportedConfiguration`을 던지며 fallback event를 만들지 않는다. `SUCCESS_NO_INFO`·`EXECUTE_FAILED`를 포함한 update count 배열을 그대로 보존하고, accepted count가 불완전할 수 있는 경우 flag를 true로 둔다. terminal 상태 후 writer 재사용은 고정 예외로 거부한다.

- [ ] **Step 3: GREEN RowBinary test를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: simple/complex/unsupported/invalid-option/setter-failure/partial-count/unusable assertion과 dual-profile assertion이 모두 통과한다. JUnit XML과 `build/reports/clickhouse-v2/ds-04.json`에 fallback reason code와 profile sequence를 기록한다.

- [ ] **Step 4: 실제 ClickHouse profile fixture를 검증한다**

`RowBinaryConnectionProviderFixture`는 true/false마다 독립 JDBC URL/Properties를 만들고, connection profile을 읽을 수 없으면 반환 전에 `UnsupportedConfiguration`을 던진다. ClickHouse Testcontainers에서 narrow/complex/default/nullable insert와 일반 JDBC fallback insert를 실행한다. profile 혼합·pool property mutation·ambient transaction connection 전달을 검사한다.

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: image tag/digest, container id, fixture seed와 server row count를 receipt에 기록한다. profile 검증 불가 또는 container/lock 실패는 `PENDING`이다.

## Task 8: #867 benchmark·chart·분석 문서를 구현하고 검증한다

**Files:**
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryBenchmarkTest.kt`
- Create: `docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py`
- Create: `docs/benchmarks/clickhouse-v2-rowbinary/README.md`
- Create: `docs/benchmarks/clickhouse-v2-rowbinary/README.ko.md`
- Create: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json`
- Create: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg`
- Create: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg`
- Create: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.png`
- Create: `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png`
- Create: `docs/benchmarks/clickhouse-v2-rowbinary/SHA256SUMS`

- [ ] **Step 1: 36조합 benchmark guard를 구현한다**

`ClickHouseRowBinaryBenchmarkTest`는 `-PclickhouseV2Benchmark=true`일 때만 실행한다. `rowCount={10_000,100_000,1_000_000}` × `maxRowsPerFlush={256,1_024,4_096}` × `rowShape={narrow,wide}` × `path={rowbinary,jdbc-fallback}`를 정확히 순회한다. 각 조합은 warmup 2회와 독립 측정 5회를 수행하고 raw score, median, first byte, accepted count, peak heap/RSS를 기록한다. path별 JSON은 18조합과 모든 raw score를 포함하고 provenance에 JDK·OS·CPU architecture·implementation SHA/dirty flag·catalog/driver version·Docker image tag/digest·JVM flags·Gradle task/arguments·input seed·row width를 기록한다.

- [ ] **Step 2: 세 process run을 실행한다**

```bash
for run in 1 2 3; do
  ./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryBenchmarkTest' -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" --no-parallel --max-workers=1 --no-daemon --console=plain || exit 1
done
```

Expected: `rowbinary-run-1.json`, `rowbinary-run-2.json`, `rowbinary-run-3.json`, `jdbc-fallback-run-1.json`, `jdbc-fallback-run-2.json`, `jdbc-fallback-run-3.json`이 모두 생성되고 각 JSON의 18조합·warmup=2·measurement=5·finite score가 확인된다. 누락 run, non-finite score, provenance mismatch, driver 전체 buffering이면 ds-05는 `PENDING`이다.

- [ ] **Step 3: deterministic renderer와 PNG를 생성한다**

```bash
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale en --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale ko --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
/Users/debop/.local/bin/cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.png
/Users/debop/.local/bin/cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png
sha256sum docs/benchmarks/clickhouse-v2-rowbinary/*.json docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.* > docs/benchmarks/clickhouse-v2-rowbinary/SHA256SUMS
```

Expected: renderer는 output overwrite/symlink을 거부하고 semantic ledger와 raw input을 비교한다. SVG/PNG EN/KO hash가 SHA256SUMS에 있으며 분석 README는 lower-is-better, input row cap, private driver buffer byte bound 미보장, provenance 차이를 양언어로 설명한다.

- [ ] **Step 4: #867 commit과 PR을 생성한다**

```bash
git add exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinary*.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinary*.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/RowBinaryConnectionProviderFixture.kt docs/benchmarks/clickhouse-v2-rowbinary docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.* exposed/clickhouse/README.md exposed/clickhouse/README.ko.md
git commit -m "ClickHouse JDBC V2 RowBinary를 profile 선택과 행 상한으로 격리한다" \
  -m "Constraint: beta writer는 connection-scoped이며 전송 후 fallback/resend를 금지한다
Rejected: setter 뒤 일반 JDBC로 교체 | V2 statement 구현과 private buffer 경계를 위반한다
Confidence: high
Scope-risk: broad
Directive: accepted count가 불완전할 수 있음을 보존하고 재사용·자동 재전송하지 않는다
Tested: RowBinaryTest, three-run 36-combination benchmark, chart renderer, SHA256SUMS
Not-tested: remote cancellation completion은 #863 범위다"
git push -u origin feat/issue-867-clickhouse-v2-rowbinary
gh pr create --repo bluetape4k/bluetape4k-exposed --base feat/issue-866-clickhouse-v2-types --head feat/issue-867-clickhouse-v2-rowbinary --title "[feat][clickhouse] JDBC V2 RowBinary 배치 writer를 안전하게 활성화·검증한다" --body "## 요약
beta RowBinary를 dual-profile provider와 fail-closed preflight로 선택하고 일반 JDBC fallback·partial count·memory benchmark를 추가합니다.

## 검증
- ClickHouseRowBinaryTest
- 36조합 x 3 process run raw benchmark
- deterministic EN/KO chart와 SHA256SUMS

Closes #867"
pr_867=$(gh pr view feat/issue-867-clickhouse-v2-rowbinary --repo bluetape4k/bluetape4k-exposed --json number --jq '.number')
gh pr edit "$pr_867" --repo bluetape4k/bluetape4k-exposed --add-assignee debop --milestone "2.1.0" --add-label documentation --add-label enhancement --add-label feature --add-label test
```

PR base는 #866 exact head, head는 현재 commit인지 `gh pr view`로 확인한다. merge하지 않는다.

## Task 9: #868 RED — diagnostics model·callback·terminal lifecycle을 고정한다

**Branch:** `feat/issue-868-clickhouse-v2-diagnostics`, #867 exact head에서 생성, PR base는 `feat/issue-867-clickhouse-v2-rowbinary`

**Files:**
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsTest.kt`
- Create: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseResourceLifecycleTest.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`

- [ ] **Step 0: #867 exact head에서 child worktree를 만든다**

```bash
git fetch origin feat/issue-867-clickhouse-v2-rowbinary
parent_867=$(git rev-parse origin/feat/issue-867-clickhouse-v2-rowbinary)
git worktree add -b feat/issue-868-clickhouse-v2-diagnostics /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-868-clickhouse-v2-diagnostics "$parent_867"
test "$(git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-868-clickhouse-v2-diagnostics rev-parse HEAD)" = "$parent_867"
```

Expected: child worktree HEAD가 #867 `headRefOid`와 같고, #865·#866·#867 변경이 history에 포함되며 다른 worktree는 dirty하지 않다.

- [ ] **Step 1: diagnostics RED test를 작성한다**

```kotlin
@Test
fun `query lifecycle은 Started부터 terminal까지 순서와 terminal once를 지킨다`() = runSuspendIO {
    val events = CopyOnWriteArrayList<ClickHouseQueryEvent>()
    val diagnosticsConfig = ClickHouseQueryDiagnosticsConfig(listener = ClickHouseQueryListener { events += it })
    queryList(db, diagnostics = diagnosticsConfig) { listOf(1, 2, 3) }
    events.map { it.kind } shouldBeEqualTo listOf(Started, RequestPrepared, ResponseReceived, Completed)
    events.count { it.isTerminal } shouldBeEqualTo 1
}

@Test
fun `listener 예외는 callbackFailure로 격리하고 원래 취소를 보존한다`() = runSuspendIO {
    val original = CancellationException("cancel-marker")
    val observed = CopyOnWriteArrayList<ClickHouseQueryDiagnostics>()
    val diagnosticsConfig = ClickHouseQueryDiagnosticsConfig(
        listener = ClickHouseQueryListener { throw IllegalStateException("listener-secret") },
        sink = ClickHouseQueryDiagnosticsSink { observed += it },
    )
    val failure = assertFailsWith<CancellationException> {
        queryFlow(db, diagnostics = diagnosticsConfig, query = { Numbers.selectAll() }, mapper = { throw original }).collect()
    }
    failure.shouldBeSameInstanceAs(original)
    observed.single().callbackFailure?.sanitizedMessage shouldNotContain "listener-secret"
}
```

추가 RED cases는 caller/generated UUID uniqueness, monotonic elapsed non-negative, UTC instants, null vendor/row metadata, listener thread/order, sink exactly once after cleanup, bounded `query_listener_failures_total`, SQL·bind·token·password·header redaction, queryList retry와 queryFlow `maxAttempts=1`, local cancellation no retry, RowBinary fallback conversion을 포함한다.

- [ ] **Step 2: diagnostics RED selector를 실행한다**

```bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryDiagnosticsTest' --tests '*ClickHouseResourceLifecycleTest' --no-parallel --max-workers=1 --no-daemon --console=plain
```

Expected: diagnostics config/model 부재 compile RED.

## Task 10: #868 GREEN — diagnostics lifecycle·redaction·재시도 경계를 구현한다

**Files:**
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnostics.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsRecorder.kt`
- Create: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsRedaction.kt`
- Modify: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseExtensions.kt`
- Modify: `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryStreaming.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsTest.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseResourceLifecycleTest.kt`
- Modify: `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`

- [ ] **Step 1: immutable public model과 새 overload를 구현한다**

기존 `queryList` 두 descriptor와 기존 `queryFlow` 두 descriptor는 변경하지 않고, `diagnostics`를 필수로 받는 별도 overload를 추가한다. 새 overload는 기존 dispatcher default를 재사용하며 기존 호출의 source·binary compatibility를 보존한다.

~~~kotlin
enum class ClickHouseQueryEventKind { Started, RequestPrepared, ResponseReceived, Completed, Failed, Cancelled }
enum class ClickHouseQueryCancellationState { LocalCancellation, CancelRequested, RemoteTerminationObserved, Unknown }

sealed interface ClickHouseQueryOutcome {
    data object Success : ClickHouseQueryOutcome
    data class Failure(val sqlState: String?, val vendorCode: Int?) : ClickHouseQueryOutcome
    data class Cancelled(val state: ClickHouseQueryCancellationState) : ClickHouseQueryOutcome
}

data class ClickHouseCallbackFailure(
    val exceptionType: String,
    val reasonCode: String,
    val sanitizedMessage: String?,
)

data class ClickHouseQueryDiagnostics(
    val queryId: String,
    val logComment: String?,
    val clientName: String?,
    val sessionSettings: Map<String, String>,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val elapsed: Duration?,
    val returnedRows: Long?,
    val vendorCode: Int?,
    val outcome: ClickHouseQueryOutcome?,
    val callbackFailure: ClickHouseCallbackFailure?,
)

data class ClickHouseQueryEvent(
    val kind: ClickHouseQueryEventKind,
    val diagnostics: ClickHouseQueryDiagnostics,
    val isTerminal: Boolean,
)

fun interface ClickHouseQueryListener { fun onEvent(event: ClickHouseQueryEvent) }
fun interface ClickHouseQueryDiagnosticsSink { fun accept(diagnostics: ClickHouseQueryDiagnostics) }

data class ClickHouseQueryDiagnosticsConfig(
    val listener: ClickHouseQueryListener? = null,
    val sink: ClickHouseQueryDiagnosticsSink? = null,
)
~~~

추가 overload의 형태는 아래로 고정한다. `queryFlow`에는 현재 존재하는 `Query`+mapper와 `Iterable` 두 경계 각각에 동일한 diagnostics overload를 둔다.

~~~kotlin
suspend fun <T> queryList(
    db: Database,
    diagnostics: ClickHouseQueryDiagnosticsConfig,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: JdbcTransaction.() -> Iterable<T>,
): List<T>

fun <T> queryFlow(
    db: Database,
    diagnostics: ClickHouseQueryDiagnosticsConfig,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    query: JdbcTransaction.() -> Query,
    mapper: (ResultRow) -> T,
): Flow<T>
~~~

- [ ] **Step 2: recorder·callback isolation·terminal once를 구현한다**

`ClickHouseQueryDiagnosticsRecorder`는 `TimeSource.Monotonic.markNow()`로 elapsed를 계산하고 wall-clock은 `Instant.now(Clock.systemUTC())`로 저장한다. caller query id가 없을 때만 요청 범위 UUID를 생성하고, `sessionSettings`와 options 값은 immutable copy로 보관한다. 이벤트는 `Started → RequestPrepared → ResponseReceived → terminal` 순서이며 terminal 이벤트는 cursor/statement/transaction 정리 이후 한 번만 발행한다.

listener는 query dispatcher에서 동기로 호출하되 blocking I/O와 재진입 query를 수행하지 않는 계약을 KDoc에 적는다. listener/sink 예외는 `ClickHouseCallbackFailure`로 type·reason code·redacted message만 저장하고 SQL 결과, 원본 `Throwable`, `CancellationException`을 대체하지 않는다. bounded counter는 `query_listener_failures_total` 하나로 누적하며 query id·SQL·bind·header를 label로 사용하지 않는다. sink는 terminal event와 cleanup이 완료된 뒤 최종 diagnostics를 정확히 한 번 받는다.

`ClickHouseQueryDiagnosticsRedaction`은 SQL text, bind value, token, password, custom header, `rawProperties`, throwable message/cause/suppressed/stack trace를 canary 값 없이 제거한다. JDBC vendor code, returned rows, elapsed가 실제 제공되지 않으면 `null`로 남긴다. `LocalCancellation`과 `Unknown`만 이번 train의 Flow 결과에 사용하고 `RemoteTerminationObserved`는 주장하지 않는다. `queryList`는 기존 transaction retry를 유지하되 local cancellation과 callback failure를 재시도하지 않고, `queryFlow`는 `maxAttempts=1`과 기존 producer cleanup을 유지한다. #867의 internal fallback event는 `ClickHouseQueryEvent(kind = RequestPrepared, ...)`의 제한된 reason code로만 변환한다.

선택적 metrics adapter가 소비할 수 있는 이름은 `query_started_total`, `query_completed_total`, `query_failed_total`, `query_cancelled_total`, `query_listener_failures_total`, `query_duration_ms`, `query_rows`로 고정한다. label은 `outcome`, `transport`, `database`처럼 bounded 값만 허용하고 query id·SQL·bind·사용자 header는 payload나 label에 넣지 않는다.

- [ ] **Step 3: diagnostics와 resource lifecycle GREEN을 실행한다**

~~~bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryDiagnosticsTest' --tests '*ClickHouseResourceLifecycleTest' --tests '*ClickHouseQueryLifecycleTest' --no-parallel --max-workers=1 --no-daemon --console=plain
~~~

Expected: ID uniqueness, monotonic elapsed, UTC instant, null metadata, event order/thread, terminal-once, callback/sink failure isolation, redaction, success/failure/cancel outcome, queryList retry·queryFlow no-retry, RowBinary fallback conversion이 모두 통과한다. JUnit XML의 failures/errors/skipped=0을 확인하고 `build/reports/clickhouse-v2/ds-06.json`, `ds-07.json`에 event trace·cleanup order·counter를 기록한다. callback 또는 sink 테스트가 실패하면 원래 SQL/취소 결과를 바꾸지 않은 채 해당 PR을 `PENDING`으로 둔다.

- [ ] **Step 4: diagnostics ABI·문서·lesson을 확인한다**

~~~bash
./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:detekt --no-daemon --console=plain
git diff --check
~~~

`api/bluetape4k-exposed-clickhouse.api`에서 기존 queryList/queryFlow symbol이 사라지지 않고 새 diagnostics overload만 추가됐는지 확인한다. `exposed/clickhouse/README.md`와 `README.ko.md`에 listener/sink 계약, callback thread, bounded metrics, cancellation state, no remote termination claim, #863 후속 범위를 같은 예제로 기록한다. `docs/lessons/2026-09-09-clickhouse-v2-diagnostics.md`에는 실제 실패·재실행·미검증 범위를 Korean으로 남기고 writer receipt를 추가한다.

- [ ] **Step 5: #868 commit과 PR을 생성한다**

~~~bash
git add exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnostics*.kt exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseExtensions.kt exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryStreaming.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryDiagnosticsTest.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseResourceLifecycleTest.kt exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt exposed/clickhouse/README.md exposed/clickhouse/README.ko.md docs/lessons/2026-09-09-clickhouse-v2-diagnostics.md api/bluetape4k-exposed-clickhouse.api
git commit -m "ClickHouse JDBC V2 query diagnostics의 lifecycle 경계를 고정한다" \
  -m "Constraint: callback과 sink는 cleanup 뒤 한 번 호출되고 원래 결과·취소를 보존한다
Rejected: SQL과 bind를 callback payload/metric label로 노출 | secret과 cardinality 경계를 동시에 깨뜨린다
Confidence: high
Scope-risk: moderate
Directive: RemoteTerminationObserved는 #863 fixture 증거 없이는 주장하지 않는다
Tested: diagnostics, resource lifecycle, query lifecycle, ABI, detekt
Not-tested: 최종 hosted CI와 mergeability는 PR 생성 후 fresh read-back한다"
git push -u origin feat/issue-868-clickhouse-v2-diagnostics
gh pr create --repo bluetape4k/bluetape4k-exposed --base feat/issue-867-clickhouse-v2-rowbinary --head feat/issue-868-clickhouse-v2-diagnostics --title "[feat][clickhouse] JDBC query diagnostics와 취소·자원 lifecycle을 고정한다" --body "## 요약\nimmutable query diagnostics, 동기 non-blocking listener/sink, redaction, terminal-once와 재시도·취소 경계를 추가합니다.\n\n## 검증\n- ClickHouseQueryDiagnosticsTest\n- ClickHouseResourceLifecycleTest 및 기존 query lifecycle 회귀\n- ABI, detekt, EN/KO 문서 parity\n\nCloses #868"
pr_868=$(gh pr view feat/issue-868-clickhouse-v2-diagnostics --repo bluetape4k/bluetape4k-exposed --json number --jq '.number')
gh pr edit "$pr_868" --repo bluetape4k/bluetape4k-exposed --add-assignee debop --milestone "2.1.0" --add-label documentation --add-label enhancement --add-label feature --add-label test
~~~

PR base는 #867 exact head이고 head는 현재 commit인지 `gh pr view`로 확인한다. 모든 PR 본문은 `Closes` token이 정확히 하나씩 포함되어야 하며 merge는 수행하지 않는다.

## Task 11: 네 PR의 통합 검증·receipt·7-Tier review를 수행한다

**Files:** `build/reports/clickhouse-v2/`, `docs/superpowers/reviews/2026-09-09-clickhouse-v2-final-review.md`, `docs/superpowers/reviews/2026-09-09-clickhouse-v2-writer-review.md`, `docs/lessons/2026-09-09-clickhouse-v2-verification.md`

- [ ] **Step 1: exact-head stacked topology와 GitHub metadata를 다시 읽는다**

~~~bash
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-865-clickhouse-v2-options log -1 --format='%H %s'
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-866-clickhouse-v2-types log -1 --format='%H %s'
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-867-clickhouse-v2-rowbinary log -1 --format='%H %s'
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/feat/issue-868-clickhouse-v2-diagnostics log -1 --format='%H %s'
gh pr view 865 --repo bluetape4k/bluetape4k-exposed --json number,baseRefName,headRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,reviewThreads,mergeable
gh pr view 866 --repo bluetape4k/bluetape4k-exposed --json number,baseRefName,headRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,reviewThreads,mergeable
gh pr view 867 --repo bluetape4k/bluetape4k-exposed --json number,baseRefName,headRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,reviewThreads,mergeable
gh pr view 868 --repo bluetape4k/bluetape4k-exposed --json number,baseRefName,headRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,reviewThreads,mergeable
~~~

Expected: #865 base `develop`, #866 base #865 branch, #867 base #866 branch, #868 base #867 branch이며 각 `headRefOid`가 local exact head와 일치한다. assignee `debop`, milestone `2.1.0`, 기존 labels와 Korean body, `Closes #865`~`Closes #868`, unresolved thread 0, mergeability와 required checks를 receipt에 기록한다. head mismatch·missing check·unresolved thread·merge conflict는 해당 train을 `PENDING`으로 멈춘다.

- [ ] **Step 2: DS-01~DS-04·DS-06~DS-09를 순차 검증한다**

~~~bash
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseV2OptionsTest' --tests '*ClickHouseDatabaseValidationTest' --tests '*ClickHouseDatabaseJavaInteropTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesH2Test' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryDiagnosticsTest' --tests '*ClickHouseResourceLifecycleTest' --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test --no-parallel --max-workers=1 --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:detekt --no-daemon --console=plain
./gradlew :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:checkKotlinAbi :bluetape4k-exposed-clickhouse:detekt --no-parallel --max-workers=1 --no-daemon --console=plain
~~~

각 selector는 exit 0이며 모든 JUnit XML에서 `failures=0`, `errors=0`, `skipped=0`, `tests>0`이어야 한다. DS-01 ABI/Kotlin/Java descriptor, DS-02 options mapping/redaction, DS-03 H2와 ClickHouse wire, DS-04 profile/preflight/fallback, DS-06 diagnostics, DS-07 resource lifecycle, DS-08 docs/terminology/metadata, DS-09 full module·ABI·detekt·review를 각각 `build/reports/clickhouse-v2/ds-*.json`으로 기록한다. Testcontainers에는 image tag/digest·container id·fixture seed가 있고, lock/container failure는 PASS가 아닌 `PENDING`이다.

- [ ] **Step 3: DS-05 benchmark·chart provenance를 별도 검증한다**

~~~bash
for run in 1 2 3; do
  ./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryBenchmarkTest' -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" --no-parallel --max-workers=1 --no-daemon --console=plain || exit 1
done
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale en --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale ko --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
/Users/debop/.local/bin/cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.png
/Users/debop/.local/bin/cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png
sha256sum docs/benchmarks/clickhouse-v2-rowbinary/*.json docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.* > docs/benchmarks/clickhouse-v2-rowbinary/SHA256SUMS
~~~

각 path/run JSON에 18조합, warmup=2, measurement=5, 모든 finite raw score, input seed와 환경 provenance가 있고 6개 JSON·4개 chart hash가 `SHA256SUMS`에 있어야 한다. missing run, non-finite score, provenance mismatch, private driver full buffering, renderer ledger mismatch가 있으면 DS-05와 전체 train을 `PENDING`으로 둔다. 분석 README는 lower-is-better, Exposed input-row cap, private driver byte/heap bound 미보장, path 차이를 실제 수치와 함께 설명한다.

- [ ] **Step 4: final 7-Tier review와 inline fallback receipt를 작성한다**

`docs/superpowers/reviews/2026-09-09-clickhouse-v2-final-review.md`에 API/ABI, Kotlin/Exposed idiom, integration/transactions, security/redaction, performance/resource, tests/CI, docs/operations의 7 tier를 각각 path·command·observed evidence와 대조한다. 독립 reviewer lane이 사용 불가하거나 실패하면 `inline fallback·비독립`을 명시하고 P0/P1을 PASS로 세지 않는다. P0/P1=0, known gap은 #863 follow-up issue로 연결, `SKIPPED`·`PENDING`·receipt missing은 0점으로 기록한다. `docs/superpowers/reviews/2026-09-09-clickhouse-v2-writer-review.md`에는 EN/KO README·KDoc·benchmark 분석·중앙 매뉴얼 traceability를 writer 관점에서 대조하고 같은 비독립 경계를 기록한다. `build/reports/clickhouse-v2/central-manual-traceability.json`에는 중앙 매뉴얼 경로·landing 존재 여부·이 train에서의 N/A 또는 PASS 근거를 남긴다. `docs/lessons/2026-09-09-clickhouse-v2-verification.md`에는 rerun command, failed attempt, rollback point와 residual risk를 Korean으로 남긴다.

- [ ] **Step 5: completion evidence와 stop condition을 확인한다**

~~~bash
git diff --check
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json docs/superpowers/specs/2026-09-09-clickhouse-v2-stacked-train-design.md docs/superpowers/plans/2026-09-09-clickhouse-v2-stacked-train.md docs/superpowers/reviews/2026-09-09-clickhouse-v2-plan-review.md docs/superpowers/reviews/2026-09-09-clickhouse-v2-writer-review.md docs/superpowers/reviews/2026-09-09-clickhouse-v2-final-review.md docs/lessons/2026-09-09-clickhouse-v2-verification.md
git status --short --branch
~~~

계획 receipt의 DS-01~DS-09 상태가 모두 `PASS`이고 변경된 각 worktree가 clean이며, GitHub hosted CI·review threads·mergeability를 fresh read-back하고 exact head를 확인했을 때만 `MERGE_READY`로 표시한다. merge·branch deletion·worktree cleanup·tag/release는 이 계획의 stop condition 이후에도 별도 사용자 승인 없이는 실행하지 않는다. 하나라도 부족하면 `PENDING`으로 남기고 부족한 증거와 재실행 command를 기록한다.

## Task 12: 계획 자체를 검토하고 승인 게이트에서 멈춘다

- [ ] 설계 문서의 DS-01~DS-09와 이 계획의 task·file·command·expected evidence가 일대일로 추적된다.
- [ ] 각 production 변경 앞에 실제 assertion을 가진 RED selector가 있고, GREEN 뒤에 같은 selector·ABI·detekt·diff check가 있다.
- [ ] 기존 public descriptor, `chArray`, `queryList`, `queryFlow`, 기본 JDBC 동작과 transaction/cleanup ownership이 보존된다.
- [ ] auth/raw/header/exception/callback redaction, profile isolation, setter-after-byte no-fallback, partial count, driver private buffer limitation이 구현·테스트·문서에서 동일하다.
- [ ] H2 의미론, ClickHouse wire, RowBinary benchmark, diagnostics lifecycle의 증거가 서로 다른 receipt로 분리된다.
- [ ] 모든 PR command가 Korean body와 정확한 `Closes #865`~`Closes #868`, semantic base/head, Lore commit을 사용한다.
- [ ] 금지된 placeholder 표현이나 미정 API 이름이 없고, 실제 파일 경로·Gradle selector·artifact 경로가 존재한다.

이 task의 끝은 **계획 승인 대기**다. 승인 뒤에만 `$executing-plans`를 leader inline으로 실행하고, 각 child PR 생성 후 fresh CI/read-back을 수행한다. merge와 cleanup은 별도 승인을 다시 받는다.
