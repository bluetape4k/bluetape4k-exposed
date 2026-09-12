# ClickHouse JDBC V2 stacked PR train 설계

## 상태와 승인 범위

- 대상 저장소: `bluetape4k/bluetape4k-exposed`
- 대상 모듈: `exposed/clickhouse`
- 이슈 순서: [#865](https://github.com/bluetape4k/bluetape4k-exposed/issues/865) → [#866](https://github.com/bluetape4k/bluetape4k-exposed/issues/866) → [#867](https://github.com/bluetape4k/bluetape4k-exposed/issues/867) → [#868](https://github.com/bluetape4k/bluetape4k-exposed/issues/868)
- 기준: `develop@ac6a0dc330d29a2812f83dd1a7aaa7db42fd0b26`, Exposed `1.5.0`, ClickHouse JDBC `0.9.9`
- 브랜치 train: `feat/issue-865-clickhouse-v2-options` → `feat/issue-866-clickhouse-v2-types` → `feat/issue-867-clickhouse-v2-rowbinary` → `feat/issue-868-clickhouse-v2-diagnostics`
- 사용자는 위 train의 대안과 순서를 2026-09-09 승인했다. 최초 설계 초안의 P1 처분을 반영한 이 수정본은 재검토·재승인 전까지 구현 완료를 의미하지 않는다.
- PR은 각각 부모 PR의 정확한 head를 base로 삼고, 본문에는 `Closes #865`와 같은 자동 종료 토큰을 사용한다. 머지는 별도 승인이다.

이번 train은 ClickHouse JDBC V2의 연결 옵션, 타입 매핑, RowBinary 배치 경로, 쿼리 진단을 Exposed DSL과 기존 확장 API에 단계적으로 노출한다. 기존 연결 overload와 기본 동작을 보존하고, 결정적 socket timeout·in-flight remote `KILL QUERY` 완료 보장은 후속 이슈 #863의 범위로 남긴다. 새 HTTP client/pool 의존성, async insert, 전체 OTel/Micrometer 연동, 다른 DB 모듈 변경은 범위 밖이다.

## 현재 근거

- `ClickHouseDatabase.kt`에는 host/port 기반과 JDBC URL 기반의 두 `connect` overload가 있고, 현재 `Properties`에는 사용자와 비밀번호만 설정된다.
- `ClickHouseArrayColumnType`는 배열 내부 요소를 non-null generic으로 모델링하며 Map/Tuple/Nested/JSON 및 특수 타입 DSL은 아직 없다.
- ClickHouse JDBC V2 `0.9.9`의 [ClientConfigProperties](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/client-v2/src/main/java/com/clickhouse/client/api/ClientConfigProperties.java)는 TLS, bearer token, timeout, compression, proxy, retry, client name, log comment, session setting prefix와 pool 옵션을 제공한다.
- 현재 query 확장은 결과 수집과 스트리밍을 제공하지만 query id, 응답 메타데이터, lifecycle callback을 공개하지 않는다.
- 기준 worktree에서 `./gradlew :bluetape4k-exposed-clickhouse:test --no-daemon --console=plain`이 성공했다. 결과는 기존 동작의 기준선이며 신규 수용 기준의 통과로 해석하지 않는다.

## 설계 선택과 대안

### 채택안: immutable options + 명시적 타입 adapter + capability/fallback + 독립 진단 모델

각 PR이 소유하는 계약을 분리하되, 앞 단계의 공개 모델을 뒤 단계가 재사용한다.

1. #865는 연결 생성과 드라이버 옵션의 단일 입력 모델을 제공한다.
2. #866은 그 연결에서 전달할 수 있는 ClickHouse 값을 명시적 Exposed `ColumnType` adapter로 확장한다.
3. #867은 #866 타입 정보와 setter capability를 사용해 RowBinary 적격성을 판정하고, 부적격 입력을 기존 JDBC writer로 보낸다.
4. #868은 연결·statement·result lifecycle에서 수집한 진단을 immutable 값과 callback으로 전달한다.

다음 대안은 채택하지 않는다.

- raw `Properties`만 노출하면 드라이버 호환성은 높지만 타입 안전성, 허용 목록, secret redaction 계약을 호출자마다 반복해야 한다.
- 모든 드라이버 속성과 서버 타입을 완전한 DSL로 만들면 서버 버전과 JDBC 구현에 강하게 결합되고 staged train의 검증 범위를 초과한다.
- 새 HTTP client/pool을 도입하면 현재 JDBC 경로의 자원 소유권과 의존성 그래프가 바뀌며, 이 train의 목표인 기존 경로 보강과 맞지 않는다.

## 공통 실행·소유권 경계

- 공개 설정 객체와 진단 객체는 모두 immutable이어야 한다. 내부 `Properties`, header map, session setting map은 생성 시 복사하고 외부 변경을 반영하지 않는다.
- `Database`, DataSource, connection pool, dispatcher의 생성·종료 소유권은 호출자에게 있다. 확장은 자신이 만든 `Statement`, `ResultSet`, 임시 buffer만 정리한다.
- URL 속성이 드라이버의 `Properties`보다 우선되는 JDBC 규칙은 유지한다. 옵션 객체는 URL을 다시 작성하거나 비밀번호를 query string에 넣지 않는다.
- 로그와 예외에는 비밀번호, bearer token, TLS key material, header 값 전체, SQL bind 값을 넣지 않는다. URL의 query 값도 validation 전에 key만 남기고 값은 `REDACTED`로 scrub한다.
- 재시도는 ClickHouse 드라이버와 기존 Exposed transaction 설정에 위임한다. 이 train은 별도 재시도나 exactly-once를 주장하지 않는다.
- blocking JDBC 호출의 즉시 coroutine 취소, deterministic socket timeout, remote query cancellation은 #863의 별도 계약이다.
- 새 의존성은 추가하지 않는다. Micrometer/OTel은 선택적 후속 통합으로 남기며 core API가 특정 관측 라이브러리에 의존하지 않게 한다.

## #865 연결·보안·세션 옵션

### 공개 모델

`ClickHouseV2Options`는 다음 그룹을 immutable property로 가진다.

인증 property의 정확한 shape은 `ClickHouseV2Authentication.Basic`,
`ClickHouseV2Authentication.AccessToken(value)`, `ClickHouseV2Authentication.BearerToken(value)`
중 하나이며, `accessToken`·`bearerToken`을 독립적으로 동시에 보관하지 않는다.

- 연결: `connectionTimeout`, `socketOperationTimeout`, `connectionRequestTimeout`, keep-alive/connection TTL
- 보안: `Basic | AccessToken | BearerToken` one-of 인증, TLS 활성화, trust/key store 참조, mTLS 설정, SNI/SSL auth
- 전송: client/server compression, LZ4 buffer, proxy, retry 정책
- 세션·식별: client name, session timezone, roles, log comment, server settings
- 확장: 명시적 custom header allowlist와 추가 raw property

민감한 값은 `toString`, debug log, 진단 callback에 원문으로 노출하지 않는다. 옵션은 `Properties`로 변환하는 단일 내부 경계를 가지며, 호출자가 제공한 map을 mutate하지 않는다. username/password는 기존 연결 인자에만 두고 raw map의 중복 credential key는 거부한다.

`rawProperties`는 V2 `ClientConfigProperties`의 명시된 key와
`clickhouse_setting_<name>`, `http_header_<allowlisted-name>` prefix만 허용한다.
`beta.row_binary_for_simple_insert`는 #867 `ClickHouseRowBinaryOptions`와
`ClickHouseConnectionProvider`가 단일 소유하므로 #865 raw map에서는 거부한다. `user`,
`password`, `database`, `access_token`, `bearer_token`, TLS secret과 중복되는 raw key는 typed 인자와
관계없이 거부하고, 알려지지 않은 key도 조용히 전달하지 않고 연결 전에 고정 예외로
거부한다. 새 driver key는 별도 이슈에서 mapping·redaction·fixture를 추가한 뒤 허용한다.

V2 `0.9.9`의 property mapping은 다음 key와 단위를 고정한다.

| typed option | V2 property key | 값/단위 | 기본값·검증 |
|---|---|---|---|
| `connectionTimeout` | `connection_timeout` | `Long`, milliseconds | `> 0`; unset은 driver default |
| `socketOperationTimeout` | `socket_timeout` | `Int`, milliseconds | `>= 0`; `0`은 driver default |
| `connectionRequestTimeout` | `connection_request_timeout` | `Long`, milliseconds | `>= 0`; driver default `10000` |
| `connectionTtl` | `connection_ttl` | `Long`, milliseconds | `-1` 또는 `> 0` |
| `httpKeepAliveTimeout` | `http_keep_alive_timeout` | `Long`, milliseconds | `> 0` |
| `connectionPoolEnabled` | `connection_pool_enabled` | `Boolean` | driver default `true` |
| `maxOpenConnections` | `max_open_connections` | `Int`, count | `> 0` |
| `connectionReuseStrategy` | `connection_reuse_strategy` | `ConnectionReuseStrategy` | driver default `FIFO` |
| `useServerTimeZone` | `use_server_time_zone` | `Boolean` | driver default `true` |
| `compressServerResponse` | `compress` | `Boolean` | driver default `true` |
| `compressClientRequest` | `decompress` | `Boolean` | driver default `false` |
| `useHttpCompression` | `client.use_http_compression` | `Boolean` | driver default `false` |
| `lz4UncompressedBufferSize` | `compression.lz4.uncompressed_buffer_size` | `Int`, bytes | `> 0`; driver default 유지 |
| `retryOnFailure` | `retry` | `Int`, count | `>= 0`; driver default `3` |
| `authentication=AccessToken(value)` | `access_token` | opaque `String` | empty 값 금지 |
| `authentication=BearerToken(value)` | `bearer_token` | opaque `String` | empty 값 금지 |
| `authentication` | `http_use_basic_auth` 및 위 token key | `Basic | AccessToken | BearerToken` one-of | token 모드는 basic auth를 끄고 복수 지정은 거부 |
| `clientName` | `client_name` | `String` | empty 허용 |
| `sessionDbRoles` | `session_db_roles` | comma-separated roles | 각 role non-blank |
| `sessionTimezone` | `use_time_zone` | `TimeZone` name | 유효한 IANA zone |
| `queryId` | `query_id` | opaque `String` | non-blank; #868 진단의 단일 출처 |
| `logComment` | `clickhouse_setting_log_comment` | opaque `String` | 제어문자 금지; custom header 중복 금지 |
| `serverSettings` | `clickhouse_setting_<name>` | `String` value | name allowlist·non-blank |
| `proxy` | `proxy_type`, `proxy_host`, `proxy_port`, `proxy_user`, `proxy_password` | typed proxy fields | host/port pair and secret redaction |
| `tls` | `ssl=true`, `trust_store`, `key_store_type`, `ssl_key_store`, `key_store_password`, `ssl_key`, `sslrootcert`, `sslcert`, `ssl_authentication`, `ssl_socket_sni` | typed TLS fields; `ssl_authentication` is Boolean | presence of `tls` enables secure transport; path/reference ownership is caller-owned |
| `customHeaders` | `http_header_<normalized-name>` | `String` value | strict allowlist, duplicate/control-character rejection |

Custom header는 case-insensitive로 정규화한 뒤 `X-ClickHouse-User-Agent`만 허용한다.
query id와 log comment는 typed option이 단일 출처이며 대응하는 custom header는 거부한다.
`Authorization`,
`Proxy-Authorization`, `Cookie`, `X-ClickHouse-Key`, `X-ClickHouse-User`, `Host`,
`Content-Length`, `Transfer-Encoding` 등 인증·routing·hop-by-hop header는 항상 거부한다.
이름과 값의 CR/LF 및 제어문자도 거부하며, 동일 header의 대소문자 중복은 하나로 합치지 않고
오류로 처리한다. 새 vendor header가 필요하면 allowlist를 별도 이슈에서 갱신한다.

인증은 `Basic`, `AccessToken`, `BearerToken` 중 정확히 하나의 mode로 해석한다. token이
지정되면 기본 인자 값인 `user="default"`, `password=""`는 placeholder로만 허용하고
그 외 `user/password`와 `http_use_basic_auth=true`를 함께 지정할 수 없다. token mode의
effective `Properties`에는 선택한 token key와 `http_use_basic_auth=false`만 남기며,
placeholder `user/password`조차 전달하지 않는다. token 두 개, token과 명시적 basic
credential, typed/raw 인증 key 중복은 연결 전에 fail-fast한다. Basic mode는 기존
`user/password` 인자를 사용하며 raw credential key와 raw `http_use_basic_auth` override는
허용하지 않는다.

### 연결 API 계약

- 기존 두 `connect` overload의 JVM 시그니처·기본 인자·동작을 유지한다.
- 신규 overload는 options를 필수 trailing 인자로 받아 기존 positional 호출과 분리한다. `Properties`를 별도 overload로 재수출하지 않고 options의 `rawProperties` map으로 한정한다.
- 정확한 Kotlin 선언은 다음과 같다. 두 신규 overload에는 options default가 없고, 기존 overload에 생성되는 `$default` bridge는 변경하지 않는다.

```kotlin
fun connect(
    host: String = "localhost",
    port: Int = 8123,
    database: String = "default",
    user: String = "default",
    password: String = "",
    options: ClickHouseV2Options,
): Database

fun connect(
    jdbcUrl: String,
    user: String = "default",
    password: String = "",
    options: ClickHouseV2Options,
): Database
```

JVM descriptor는 각각 `(String, int, String, String, String, ClickHouseV2Options)Database`와
`(String, String, String, ClickHouseV2Options)Database`다. `@JvmName`은 사용하지 않으며,
Java 호출자는 위 descriptor를 직접 사용한다. `user/password` 인자와 raw map의 동일 key는
중복으로 거부하고, typed option과 raw map의 동일 V2 key도 typed option 우선이 아니라
명시적 중복 오류로 처리한다.

실효값 precedence는 `JDBC URL query property > 기존 명시 인자 또는 typed option > rawProperties > driver default`다.
URL query와 중복되는 일반 값은 드라이버의 URL precedence를 그대로 따르며, options가 URL을
덮어쓰지 않는다. 단, 새 options overload의 URL에는 `user`, `password`, `access_token`,
`bearer_token`, `http_use_basic_auth` 같은 인증 key를 포함할 수 없다. 이 보안 key는 URL
우선순위로 token/basic one-of를 우회할 수 있으므로 연결 전에 scrub된 validation 오류로
거부하고, 인증은 options mode와 명시적 `user/password` 인자에서만 결정한다. 기존
options 없는 overload의 URL 인증 동작은 변경하지 않는다.

- 연결 실패 시 기존 overload는 기존 예외 계약을 유지한다. 신규 options overload는
  `ClickHouseConnectionException`으로 감싸되 SQLState와 vendor code를 복사하고, message에는
  scrub된 URL과 구조화된 원인만 둔다. 원본 예외 객체를 cause/suppressed로 붙이지 않아
  exception graph와 rendered stack trace의 secret 재노출을 막는다. cleanup 실패는 wrapper의
  suppressed에 추가하며, 원본 예외의 민감하지 않은 진단 필드는 내부 테스트 값으로만 보존한다.
  cleanup 실패는 `SanitizedCleanupException(reasonCode, sqlState, vendorCode)`로 변환하고
  원본 cleanup `Throwable`을 cause/suppressed에 연결하지 않는다. 이 sanitized 예외만
  wrapper의 suppressed에 추가해 전체 exception graph redaction을 닫는다.
- `jdbcUrl` validation 오류는 scheme/host/path만 표시하고 query value는 모두 `REDACTED`로
  바꾼다. 확장이 생성하는 message, cause, suppressed, logger에 token/password를 포함한
  synthetic URL이 다시 나타나지 않는 redaction 회귀 테스트를 둔다. ClickHouse driver가
  자체적으로 출력하는 외부 logger는 이 확장의 재출력 대상이 아니며, 해당 범위는 별도 driver
  설정과 운영 로그 정책으로 다룬다.

### 검증 계약

- 기본/신규 overload의 source compatibility와 JVM ABI를 별도로 확인한다.
- 기존 Kotlin named/positional consumer, 신규 Kotlin consumer, Java consumer와 `javap` descriptor를 확인하고, `api/bluetape4k-exposed-clickhouse.api`의 기존 symbol이 변하지 않았는지 확인한다.
- 표의 각 option이 정확한 V2 property·단위·기본값으로 변환되는지, URL precedence·중복/unknown key·허용되지 않은 header·CR/LF·exception graph redaction을 단위 테스트한다.
- TLS/mTLS와 bearer token은 실제 인증 성공을 주장하지 않고, Testcontainers 또는 mock driver에서 전달·미노출 경계를 검증한다.
- timeout 값은 유효 범위와 zero/negative 입력을 명시적으로 검사한다. 실제 원격 timeout 완료는 #863에서 검증한다.

## #866 중첩·복합·특수 타입

### 타입 계층

- Array adapter를 nullable element와 nested Array까지 표현하도록 일반화한다.
- Map, Tuple, Nested, JSON mode, UUID, IPv4/IPv6, Date/DateTime, Decimal, Enum을 명시적 converter로 제공한다.
- JDBC `Array`/`Struct`와 ClickHouse metadata를 converter 경계에서 왕복시킨다. converter가 소유하지 않는 JDBC 자원은 반환 후 보관하지 않는다.
- 서버 타입을 해석하지 못하면 값이나 타입을 String으로 묵시적 변환하지 않고, 지원 타입과 원인을 포함한 고정 예외를 낸다.

### 호환성과 데이터 경계

- 기존 primitive/nullable column type과 `chArray` 호출 형태를 유지한다.
- 새 adapter는 Exposed `ColumnType` 계약과 Kotlin nullability를 모두 검증하며, mutable collection을 외부에 그대로 노출하지 않는다.
- JSON serializer 선택은 기존 모듈 스타일을 따르고, serializer 의존성을 ClickHouse production 모듈에 새로 끌어오지 않는다.
- Enum/Decimal의 wire 표현과 scale/precision은 명시적 설정 또는 metadata로 결정한다. 추측에 의한 데이터 손실 fallback은 금지한다.

element nullability와 container nullability는 별도 축으로 고정한다. `Column<List<T?>>`는
nullable element를 허용하지만 container 자체는 null이 아니며, `Column<List<T?>?>`는
container null까지 허용한다. nested Array는 각 depth의 element adapter를 선언할 때만 만든다.
empty Array는 metadata 또는 DSL에 선언한 element type이 없으면 생성하지 않는다.

| 타입 | Kotlin/JDBC 입력 | wire·검증 계약 |
|---|---|---|
| Array(Nullable)/nested Array | `List<T?>`, `List<List<T?>>`; JDBC `Array` components | null 위치·empty 값·선언 depth 보존; element type 없이는 empty/null metadata를 추측하지 않음 |
| Map | `Map<K, V>`; JDBC `Map` 또는 entry `Struct` | key는 non-null; null key·중복 key·지원하지 않는 key type은 조기 실패 |
| Tuple | 선언된 `List<ColumnType<*>>`와 `List<Any?>`; JDBC `Struct` | position과 arity 고정; 누락·추가 field는 실패 |
| Nested | 선언된 tuple schema의 `List<List<Any?>>` | 각 tuple column의 길이 동일; ragged row는 실패 |
| JSON | raw `String` 또는 caller codec의 `T` | `Raw`는 유효 JSON text만, `Codec`은 encode/decode 실패를 보존; codec 의존성은 caller 소유 |
| UUID | `java.util.UUID` | canonical binary/string 변환만 허용; malformed text 실패 |
| IPv4/IPv6 | `java.net.InetAddress` | address family와 16-byte representation을 보존; ambiguous text 실패 |
| Date/DateTime | `LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime` | UTC/zone과 precision을 option으로 명시; epoch overflow·precision loss 실패 |
| Decimal | `BigDecimal` | declared precision/scale, `RoundingMode.UNNECESSARY`; overflow·rounding 필요 시 실패 |
| Enum | `Enum<*>` 또는 명시적 `(Enum<*>) -> String` mapper | ordinal을 사용하지 않으며 name/alias wire 값은 선언된 mapper만 사용 |
| UInt64·large integer | `ULong` 또는 명시적 `BigInteger` mapper | signed JDBC `Long`으로 자동 축소하지 않으며 범위 밖 값 실패 |

각 adapter는 `valueFromDB`, `notNullValueToDB`, `setParameter`, `readObject`의 입력·출력
nullability를 동일한 matrix로 구현한다. 반환 collection은 immutable copy이며, JDBC
`Array`/`Struct`는 읽은 뒤 즉시 해제한다. `DateTime64`는 precision과 zone을 선언하지
않으면 실패하고, UInt64는 high-bit를 잃을 수 있는 `Long` 변환을 금지한다.

### 검증 계약

- H2 단위 fixture와 ClickHouse Testcontainers를 사용해 Array(Nullable), nested Array, Map, Tuple, Nested, JSON, UUID/IP/Time/Decimal/Enum을 순차 검증한다. H2-only 결과와 ClickHouse-only 결과를 표에서 분리하고 H2가 ClickHouse wire semantics의 증거라고 해석하지 않는다.
- insert → select metadata → Exposed 값 복원 round-trip, null, empty, 중첩 depth, 잘못된 setter, 지원하지 않는 타입의 명확한 실패를 고정한다. 각 타입의 accepted JDBC class, overflow/rounding/timezone/precision/malformed 입력을 boundary matrix로 실행한다.
- JVM ABI와 Kotlin source compatibility를 기존 column type에 대해 별도로 확인하고 `ClickHouseComplexTypesTest`의 exact selector와 결과 XML을 보존한다.

## #867 RowBinary 배치 writer

### 적격성·fallback

- `beta.row_binary_for_simple_insert`는 기본 비활성이고 명시적 `ClickHouseRowBinaryOptions`에서만 활성화한다.
- V2 `ConnectionImpl.prepareStatement`가 connection-level property에 따라 statement 구현을
  한 번 선택하므로 driver-owned 경로를 채택한다. Exposed가 driver private buffer를 감싸거나
  setter 이후 statement를 교체하지 않는다.
- `beta.row_binary_for_simple_insert`는 connection-scoped property다. 따라서
  `ClickHouseRowBinaryExecutor`는 batch마다 preflight 결과에 맞는 caller-owned connection
  profile을 먼저 선택한다. 이를 위해 다음 public contract의
  `ClickHouseConnectionProvider`를 주입한다.

  ```kotlin
  fun interface ClickHouseConnectionProvider {
      fun open(rowBinaryEnabled: Boolean): Connection
  }
  ```

  `open(true)`는 provider가 새로 구성한 connection-level
  `beta.row_binary_for_simple_insert=true` profile만 반환하고, `open(false)`는 해당 key가
  false인 일반 JDBC profile만 반환해야 한다. provider는 요청한 profile을 확인할 수 없거나
  pooled connection에 다른 profile이 남아 있으면 connection을 반환하지 않고 고정
  `UnsupportedConfiguration`으로 fail-closed한다. 각 profile은 독립 `DataSource`/pool 또는
  동등한 connection factory로 구성하며 두 profile 사이에서 connection을 재사용하거나
  기존 `Database`/pool의 property를 mutate하지 않는다. provider와 그 내부
  `DataSource`/pool은 호출자가 소유하고, executor는 provider가 빌려준 connection·statement·
  result만 operation 종료 시 닫아 pool에 반환한다. provider는 connection을 반환하기 전에
  요청 profile과 실제 connection property의 일치를 검증하고, 그 검증을 수행할 수 없으면
  `UnsupportedConfiguration`을 낸다. executor는 provider 계약을 충족한 connection만
  statement에 사용한다. 적격 batch는 beta-enabled connection,
  부적격·capability-unknown batch는 beta-disabled 일반 JDBC connection을 **statement 생성
  전에** 연다. executor는 provider가 반환한 operation-scoped connection을 닫지만
  commit/rollback은 호출하지 않는다. transaction과 pool 반환 정책은 provider가 소유한다.
  ambient Exposed transaction connection은 넘기지 않으며, ambient transaction과 연결하려면
  provider가 그 경계를 별도로 명시하고 동일 profile 증거를 제공해야 한다. provider가 없는
  executor 호출은 잘못된 writer를 선택하는 대신 고정 `UnsupportedConfiguration`으로
  fail-closed한다.
- writer는 connection/statement를 만들기 전에 query가 단순 `INSERT ... VALUES (?, ...)`인지,
  단일 values group인지, 대상 column과 setter가 지원 matrix에 있는지, RowBinary option이
  유효한지 preflight한다. invalid option은 원래 예외로 fail-fast한다.
- unsupported query/type 또는 capability unknown은 `beta.row_binary_for_simple_insert`를
  켜지 않은 일반 `PreparedStatement` 경로를 **statement 생성 전에** 선택한다. fallback은
  첫 setter·첫 byte/row 전송 전 한 번만 허용하며, 전송 시작 후에는 fallback·재실행·자동 재시도를
하지 않는다. `internal ClickHouseRowBinaryFallbackEvent`가 이유 code만 기록하고 #868에서
public 진단 값으로 변환한다. 이 internal event는 provider가 실제로 beta-disabled
connection을 선택한 경우에만 발행하며, 고정 beta-enabled `Database`에서 fail-closed한
경우에는 발행하지 않는다.
- V2 setter가 statement 생성 후 실패하거나 no-op을 반환하면 writer를 unusable로 표시하고
  connection/statement를 닫은 뒤 원래 실패를 전달한다. 이 시점의 JDBC fallback은 허용하지 않는다.
- RowBinary 경로의 `executeBatch()` update count 배열은 `SUCCESS_NO_INFO`와
  `EXECUTE_FAILED`를 포함해 그대로 보존하고, aggregate accepted count를 별도 결과 값으로
  계산한다. 일부 batch가 수락된 뒤 실패하면 accepted count가 불완전할 수 있음을 명시하며,
  동일 writer 재사용과 자동 재전송을 금지한다.

### 메모리·실패 경계

- 호출자는 `maxRowsPerFlush`를 양수로 지정하고 writer는 그 행 수를 넘기기 전에 flush한다.
  이는 Exposed가 보관하는 input batch의 상한이다. V2 `WriterStatementImpl` 내부의
  `ByteArrayOutputStream`은 private이므로 byte 단위 상한이나 driver 전체 heap 상한을 보장하지
  않는다. benchmark는 실제 peak heap/RSS를 측정하고 byte bound를 통과했다고 주장하지 않는다.
- 한 batch flush 실패 후 동일 writer를 재사용할 수 없으면 명시적 상태 오류를 내고, 이미 전송된 batch를 자동 재전송하지 않는다. 재실행이 필요하면 호출자가 새 writer와 idempotency 정책을 준비한다.
- connection, statement, stream 자원은 writer가 소유한 범위에서 정리하며 외부 transaction/pool은 닫지 않는다.

기존 `Database`가 beta-enabled profile로 고정된 상태에서 unsupported SQL을 직접 실행하는
경우에는 일반 JDBC로 몰래 바꾸지 않고 `UnsupportedConfiguration`을 낸다. 일반 JDBC
fallback 증거는 주입된 provider가 beta-disabled profile을 선택한 경우에만 유효하다.

### 검증·benchmark 산출물

- 단순 INSERT/복합 INSERT, nullable·중첩·특수 타입, unsupported setter matrix, 빈 입력, 부분 flush, invalid option fail-fast, provider의 beta-enabled/beta-disabled profile 일치, preflight fallback, setter 후 실패, update count, 실패 후 unusable 상태를 검증한다.
- RowBinary opt-in과 기본 JDBC 경로를 같은 fixture에서 비교하고, 각 driver-specific Gradle task를 `--no-parallel --max-workers=1`로 실행한다. `junit.jupiter.execution.parallel.enabled=false`와 `@ResourceLock("clickhouse-v2")`로 공유 container/schema를 직렬화한다.
- benchmark matrix는 `rowCount={10_000,100_000,1_000_000}`, `maxRowsPerFlush={256,1024,4096}`, `rowShape={narrow,wide}`, `path={rowbinary,jdbc-fallback}`로 고정한다. 각 조합을 warmup 2회 후 독립 측정 5회 실행하고 median과 각 raw score를 모두 보존한다.
- 각 run은 JDK/OS/CPU architecture, implementation SHA와 dirty flag, catalog/driver artifact version, Docker image tag와 digest, heap/RSS, JVM flags, Gradle task와 arguments, input seed, row width를 raw JSON metadata에 기록한다. raw JSON은 `docs/benchmarks/clickhouse-v2-rowbinary/rowbinary-run-{1,2,3}.json`과 `jdbc-fallback-run-{1,2,3}.json`, SHA-256 manifest로 보존한다.
- chart는 `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg|png`와 `.ko.svg|png`를 semantic ledger와 deterministic renderer로 생성한다. missing run, non-finite score, provenance mismatch, driver 내부 전체 버퍼링 관측 시 차트와 성능 PASS를 만들지 않고 `PENDING`으로 남긴다.

## #868 query 식별자·로그·응답 메타데이터·메트릭

### 진단 모델

`ClickHouseQueryDiagnostics`는 query id, log comment, client name, session settings, 시작/종료 시각, elapsed, returned rows, vendor code, outcome을 immutable 진단 값으로 제공한다. query id는 호출자 값이 우선이며 없으면 `UUID.randomUUID().toString()` 형식의 내부 생성값을 사용한다. 이 값은 요청 범위에서 유일해야 하지만 전역 영속 유일성을 보장하지 않는다.

elapsed는 `TimeSource.Monotonic` 차이로 계산해 음수가 되지 않게 하고, 시작/종료 wall-clock은 UTC `Instant`로 기록한다. 드라이버가 제공하지 않는 vendor code, returned rows, elapsed는 null이며 임의의 zero로 바꾸지 않는다.

공개 callback은 `ClickHouseQueryListener.onEvent(event: ClickHouseQueryEvent)` 하나로
정의한다. callback은 현재 query를 실행한 dispatcher/thread에서 동기 호출되고 blocking I/O나
재진입 query를 수행하지 않아야 한다. 이벤트 순서는 `Started → RequestPrepared →
ResponseReceived → (Completed|Failed|Cancelled)`이며 terminal 이벤트는 자원 정리가 끝난 뒤
정확히 한 번 전달한다. callback 예외는 `callbackFailure`로 진단 값에 기록하고 SQL 결과,
원래 예외, `CancellationException`을 대체하거나 재시도하지 않는다.

호출자가 최종 진단을 관찰해야 할 때는 선택적 `ClickHouseQueryDiagnosticsSink.accept(
diagnostics: ClickHouseQueryDiagnostics)`를 주입한다. sink는 호출자가 소유하는 동기·
non-blocking 함수이며 자원 정리와 terminal event 전달이 끝난 뒤 정확히 한 번 호출된다.
listener 또는 sink가 던진 예외는 민감 값을 제거한 `callbackFailure`로 최종 진단에 남기고,
별도의 bounded `query_listener_failures_total` 관측값을 증가시키며 SQL 결과·원래 예외·취소를
대체하지 않는다. sink가 없으면 callbackFailure는 terminal event의 진단 값과 제한된
lifecycle log에서만 관찰한다.

취소 상태는 다음 네 가지를 구분한다.

| 상태 | 의미 | 이번 train 보장 |
|---|---|---|
| `LocalCancellation` | collector/thread가 로컬 취소를 관찰함 | `CancellationException` 보존 |
| `CancelRequested` | 취소 요청 API를 호출했지만 원격 종료를 아직 확인하지 못함 | 선택적 관측 |
| `RemoteTerminationObserved` | 서버/driver 응답으로 원격 종료를 확인함 | #863 또는 별도 fixture에서만 PASS |
| `Unknown` | driver가 원격 상태를 보고하지 않음 | 결과를 추정하지 않음 |

이번 train의 Flow 취소는 기본적으로 `LocalCancellation` 또는 `Unknown`이며,
`RemoteTerminationObserved`를 주장하지 않는다.

### 로깅·관측 경계

- lifecycle log는 query id와 제한된 구조화 필드만 기록한다. SQL text, bind 값, token, password, custom header 원문은 기록하지 않는다. `rawProperties`, nested map, throwable message/cause/suppressed/stack trace에 민감 값이 남지 않았는지 canary 테스트를 둔다.
- 메트릭 callback은 core ClickHouse 모듈에 Micrometer/OTel 타입을 노출하지 않는다. 어댑터가 필요하면 후속 모듈에서 이 모델을 소비한다.
- 선택적 메트릭 어댑터는 `query_started_total`, `query_completed_total`,
  `query_failed_total`, `query_cancelled_total`, `query_listener_failures_total`,
  `query_duration_ms`, `query_rows`만
  소비하며, label은 `outcome`, `transport`, `database`처럼 bounded cardinality인 값으로
  제한한다. `query_id`, SQL text, bind 값, 사용자 header는 label로 사용하지 않는다.
- response metadata는 JDBC vendor code, elapsed, rows처럼 드라이버가 실제 제공한 값만 노출한다. 제공되지 않는 값은 추정하지 않는다.
- query id/log comment/client name/session setting은 #865 옵션 모델과 중복 정의하지 않고 진단 값 사본에서 참조·복사한다. #867은 public 진단 타입을 사용하지 않고 `internal ClickHouseRowBinaryFallbackEvent(reasonCode, beforeFirstByte)`만 생성하며, #868이 이를 `ClickHouseQueryEvent`로 변환한다.

### 검증 계약

- caller-supplied/generated query id, monotonic elapsed, null metadata, redaction, callback 실행 위치·순서·정확히 한 번인 terminal event, 성공·실패·취소 lifecycle, callback 예외 격리를 검증한다.
- 응답 metadata와 vendor exception의 code/message 보존을 확인하고, 없는 metadata를 임의의 zero 값으로 포장하지 않는다.
- 기존 queryList/queryFlow의 결과·트랜잭션·재시도 동작을 변경하지 않는 회귀 테스트를 실행한다. `queryList`의 기존 transaction retry와 `queryFlow`의 `maxAttempts=1`을 matrix로 기록하고, local cancellation은 재시도하지 않는다.

재시도·취소 matrix는 다음과 같다.

| 경로 | 기존 재시도 | 취소 시 동작 | 이번 train의 원격 종료 주장 |
|---|---|---|---|
| `queryList` | 기존 transaction retry 설정을 그대로 사용 | `CancellationException`을 보존하고 재시도하지 않음 | 없음 |
| `queryFlow` | `maxAttempts=1`을 유지 | collector 취소 후 local/unknown으로 종료 | 없음 |
| RowBinary batch | driver retry 정책만 적용, Exposed 자동 재실행 없음 | 전송 전 취소만 local로 기록; 전송 후 자동 중단·resend 없음 | 없음 |
| #863 전용 fixture | 명시된 테스트 정책만 사용 | cancel request와 remote observed를 별도 기록 | fixture 증거가 있을 때만 `RemoteTerminationObserved` |

## PR train 통합 계약

| PR | 부모 base | 소유 공개 영역 | 다음 PR이 사용하는 계약 |
|---|---|---|---|
| #865 | `develop` | options, connect, redaction, 최소 event envelope 계약 | options와 session/query 식별 필드, event envelope |
| #866 | #865 exact head | column type adapters | typed value/capability 정보 |
| #867 | #866 exact head | driver-owned RowBinary writer, benchmark, `internal ClickHouseRowBinaryFallbackEvent` | options, type adapters, internal fallback event |
| #868 | #867 exact head | public diagnostics, lifecycle callback, internal-event conversion | options, writer/query 결과 경계, event envelope |

각 PR은 부모의 정확한 head에서만 분기하고 자신의 이슈에 `Closes #...`를 사용한다. 부모가 merge된 후 다음 PR을 `develop`으로 retarget할 때 exact head, checks, reviews, threads, mergeability를 다시 읽는다. PR 간 임시 API를 재수출하거나 부모 변경을 복제하지 않는다.

## 수용 기준과 산출물

각 기준은 `build/reports/clickhouse-v2/{ds-id}.json` receipt로 남긴다. receipt에는
`id`, `status`, `commands`, `expected`, `observed`, `artifacts`, `environment`,
`sha256`를 포함한다. `PASS`는 명령의 exit code가 0이고 기대한 테스트 수·파일·해시가
모두 일치할 때만 허용한다. `SKIPPED`와 `PENDING`은 미검증으로 보며 어느 기준도
충족시키지 않는다.

| ID | 기준 | 정확한 검증 명령·기대 결과 | 필수 산출물 |
|---|---|---|---|
| DS-01 | 기존 connect/column/query/writer API와 JVM ABI 보존 | `./gradlew :bluetape4k-exposed-clickhouse:checkKotlinAbi --no-daemon --console=plain` 성공, 기존 Kotlin named/positional fixture와 Java fixture 컴파일·실행 성공, `javap -classpath exposed/clickhouse/build/libs/bluetape4k-exposed-clickhouse-2.1.0.jar io.bluetape4k.exposed.clickhouse.ClickHouseDatabase`에서 두 신규 descriptor와 기존 descriptor 확인 | `api/bluetape4k-exposed-clickhouse.api`, `build/kotlin/abi/bluetape4k-exposed-clickhouse.api`, `build/reports/clickhouse-v2/ds-01.json`, source/Java fixture 결과 |
| DS-02 | #865 옵션 전달·URL precedence·redaction | `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseV2OptionsTest' --no-parallel --max-workers=1 --no-daemon --console=plain` 성공; key/unit/default/중복/header/CRLF/exception graph canary 각각 기대 assertion 통과 | `build/test-results/test/TEST-*ClickHouseV2OptionsTest.xml`, `build/reports/clickhouse-v2/ds-02.json` |
| DS-03 | #866 타입 round-trip 및 unsupported 명시 실패 | H2 의미론 단위: `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesH2Test' --no-parallel --max-workers=1 --no-daemon --console=plain`; ClickHouse wire: `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseComplexTypesTest' --no-parallel --max-workers=1 --no-daemon --console=plain`; 두 명령 모두 exit 0, H2 결과를 ClickHouse 증거로 합치지 않음 | 두 selector의 JUnit XML, `build/reports/clickhouse-v2/ds-03-h2.json`, `ds-03-clickhouse.json`, boundary matrix |
| DS-04 | #867 RowBinary opt-in·capability·JDBC fallback | `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --no-parallel --max-workers=1 --no-daemon --console=plain` 성공; simple/complex/unsupported/invalid-option/setter-failure/partial-count/unusable assertions와 internal fallback event reason code 일치 | JUnit XML, `build/reports/clickhouse-v2/ds-04.json`, fallback event matrix |
| DS-05 | input batch 상한과 재현 가능한 benchmark | `for run in 1 2 3; do ./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryBenchmarkTest' -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" --no-parallel --max-workers=1 --no-daemon --console=plain || exit 1; done` 실행; 각 run은 warmup 2회와 독립 측정 5회, 36조합 전부 기록. private driver buffer의 byte bound는 주장하지 않으며 peak heap/RSS만 관측 | `docs/benchmarks/clickhouse-v2-rowbinary/rowbinary-run-1.json`, `rowbinary-run-2.json`, `rowbinary-run-3.json`, `jdbc-fallback-run-1.json`, `jdbc-fallback-run-2.json`, `jdbc-fallback-run-3.json`, `SHA256SUMS`, `README.md`, `README.ko.md`, `docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json`, deterministic SVG/PNG chart 4종, `build/reports/clickhouse-v2/ds-05.json` |
| DS-06 | #868 immutable diagnostics와 callback 격리 | `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseQueryDiagnosticsTest' --no-parallel --max-workers=1 --no-daemon --console=plain` 성공; ID/monotonic elapsed/null metadata/redaction/callback thread·order/terminal-once/success-failure-cancel assertions 통과 | JUnit XML, `build/reports/clickhouse-v2/ds-06.json`, lifecycle event trace |
| DS-07 | 트랜잭션·자원 소유권·취소 경계 보존 | `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseResourceLifecycleTest' --no-parallel --max-workers=1 --no-daemon --console=plain` 성공; close/rollback/exception precedence와 `CancellationException` 보존, remote termination 미관측 상태를 `Unknown`으로 기록 | JUnit XML, `build/reports/clickhouse-v2/ds-07.json`, cleanup trace |
| DS-08 | 문서·KDoc·PR train metadata 일치 | `git diff --check`; `./gradlew :bluetape4k-exposed-clickhouse:detekt --no-daemon --console=plain`; `node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json docs/superpowers/specs/2026-09-09-clickhouse-v2-stacked-train-design.md exposed/clickhouse/README.ko.md`; issue/PR에 exact parent head와 `Closes #...` 확인 | `exposed/clickhouse/README.md`, `README.ko.md`, public KDoc, `docs/superpowers/specs`, `docs/superpowers/plans`, `docs/superpowers/reviews`, `docs/superpowers/lessons`, `docs/lessons/2026-09-09-clickhouse-v2-*.md`(repo lesson), `build/reports/clickhouse-v2/ds-08.json`, `build/reports/clickhouse-v2/central-manual-traceability.json` |
| DS-09 | 전체 검증과 독립 리뷰 | `./gradlew :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:checkKotlinAbi :bluetape4k-exposed-clickhouse:detekt --no-parallel --max-workers=1 --no-daemon --console=plain` 성공, 여섯 관점 독립 리뷰에서 P0/P1=0, hosted CI check·review thread·mergeability 재확인 | `build/reports/clickhouse-v2/ds-09.json`, 독립 리뷰 receipt, exact-head CI receipt |

필수 산출물은 구현 코드, 모듈 테스트, 공개 API baseline 갱신(필요한 경우), 양언어 README/KDoc, #867 benchmark chart/분석, 설계·계획·리뷰·lesson 문서다. 중앙 매뉴얼은 소유 저장소의 별도 변경으로 다루며 이 저장소에 두 번째 `docs/manual` 트리를 만들지 않는다.

### 테스트컨테이너 직렬화와 환경 증거

ClickHouse를 사용하는 모든 JUnit 클래스는 `@Execution(ExecutionMode.SAME_THREAD)`와
`@ResourceLock("clickhouse-v2")`를 함께 사용하고, `exposed/clickhouse/src/test/resources/junit-platform.properties`에
`junit.jupiter.execution.parallel.enabled=false`를 고정한다. Gradle 명령은 항상
`--no-parallel --max-workers=1`을 사용한다. 테스트 fixture는 클래스별 고유 table/schema
이름을 사용하고 `finally`에서 정리한다. receipt에는 ClickHouse image tag와 digest,
container id, fixture 이름, seed, 시작·종료 시각을 기록한다. 컨테이너 기동 실패나 lock
누락은 `SKIPPED`가 아니라 원인과 미검증 범위를 가진 `PENDING`이다.

### Benchmark 재현성 계약

DS-05의 36조합은 `rowCount={10_000,100_000,1_000_000}` ×
`maxRowsPerFlush={256,1024,4096}` × `rowShape={narrow,wide}` ×
`path={rowbinary,jdbc-fallback}`이다. `rowbinary-run-{1,2,3}.json`과
`jdbc-fallback-run-{1,2,3}.json`은 각 process run의 18조합 결과와 warmup/5회 raw
score를 모두 담는다. 구체적으로 `rowbinary-run-1.json`, `rowbinary-run-2.json`,
`rowbinary-run-3.json`, `jdbc-fallback-run-1.json`, `jdbc-fallback-run-2.json`,
`jdbc-fallback-run-3.json` 각각에 기록한다. provenance에는 JDK, OS, CPU architecture, implementation SHA와
dirty flag, catalog/driver artifact version, Docker image tag/digest, JVM flags, Gradle
task/arguments, input seed, row width, peak heap/RSS를 기록한다. 차트는 다음 명령으로
semantic ledger와 고정 renderer 버전을 사용해 생성한다.

```bash
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py \
  --input-dir docs/benchmarks/clickhouse-v2-rowbinary \
  --locale en \
  --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg \
  --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py \
  --input-dir docs/benchmarks/clickhouse-v2-rowbinary \
  --locale ko \
  --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg \
  --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg \
  -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.png
cairosvg docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg \
  -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png
sha256sum docs/benchmarks/clickhouse-v2-rowbinary/*.json \
  docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.* \
  > docs/benchmarks/clickhouse-v2-rowbinary/SHA256SUMS
```

SVG/PNG의 SHA-256을 manifest에 기록한다. run 누락,
non-finite score, provenance 불일치, driver 내부 전체 버퍼링 관측은 benchmark와 chart를
`PENDING`으로 남긴다.

### 문서 traceability

| 산출물 | 정식 경로 | 검증 |
|---|---|---|
| English/Korean module guide | `exposed/clickhouse/README.md`, `exposed/clickhouse/README.ko.md` | API 예제의 options·types·RowBinary opt-in이 동일하고 버전은 BOM만 사용 |
| Public KDoc | `exposed/clickhouse/src/main/kotlin/**`의 공개 options/type/diagnostics 선언 | `detekt`와 source/API fixture에서 문서 링크·계약 확인 |
| Design/plan/review | `docs/superpowers/specs/2026-09-09-clickhouse-v2-*`, `docs/superpowers/plans/2026-09-09-clickhouse-v2-*`, `docs/superpowers/reviews/2026-09-09-clickhouse-v2-*` | SPW receipt `docs/superpowers/reviews/2026-09-09-clickhouse-v2-writer-review.md`와 `git diff --check` |
| Lesson | `docs/lessons/2026-09-09-clickhouse-v2-*.md` | 구현·검증 후 Korean lesson과 known gaps 기록 |
| 중앙 매뉴얼 | `${MANUAL_SITE_ROOT:-../bluetape4k.github.io}/docs/manual/bluetape4k-exposed`의 EN/KO landing | 이 train에서는 현 저장소에 생성하지 않으며, 경로·링크 존재 여부를 `build/reports/clickhouse-v2/central-manual-traceability.json`에 `N/A` 또는 `PASS`로 명시 |

### 운영·실패 매트릭스

| 상황 | 기대 동작 | 재실행·운영 지침 |
|---|---|---|
| 인증 실패·DNS 실패·pool exhaustion | scrubbed `ClickHouseConnectionException`, 원인 분류, secret 미노출 | credential/host/pool을 확인한 뒤 새 연결로 재시도; 원본 예외 graph를 재출력하지 않음 |
| malformed typed/raw property 또는 header | 연결/statement 이전 fail-fast, fallback하지 않음 | 옵션을 수정하고 재실행 |
| driver capability/version skew 또는 profile provider 부재 | provider가 있으면 RowBinary statement 생성 전에 beta-disabled 일반 JDBC 경로 선택, 없으면 `UnsupportedConfiguration` fail-closed; reason code는 provider fallback 때만 기록 | driver 버전·provider profile·matrix를 receipt에 남기고 강제 opt-in하지 않음 |
| callback sink failure | SQL 결과·원래 예외·취소를 보존하고 `callbackFailure`만 진단 | sink를 비활성화하거나 non-blocking sink로 교체; query 재실행은 호출자 판단 |
| RowBinary partial acceptance 또는 duplicate/replay 위험 | accepted count를 불완전할 수 있는 값으로 표시, 자동 resend 금지 | 새 writer와 idempotency 정책으로 호출자가 명시적으로 재실행 |

## 설계 리뷰 결과와 처분

최초 설계본은 세 개의 독립 lane에서 여섯 관점(아키텍처·소유권, 보안·redaction,
API·JVM 호환성, Kotlin 타입 의미론, 테스트·성능, 문서·운영)으로 검토했다. 최초
리뷰의 P0는 0건이었지만 P1이 남아 수정본에 다음 처분을 반영했다.

| 관점 | 최초 finding | 수정본 처분 | 재검토 완료 조건 |
|---|---|---|---|
| 아키텍처·소유권 | V2 writer가 statement 생성 시 고정되고 private buffer는 무제한이라 기존 설계의 post-fallback·byte bound 주장이 불가능 | #867을 driver-owned fail-closed로 고정하고 connection-scoped beta profile을 preflight로 선택; 적격 batch만 beta-enabled connection을 열고 부적격 batch는 beta-disabled connection을 statement 생성 전에 선택한다. `maxRowsPerFlush`는 input 행 상한으로 한정하고 driver peak heap/RSS만 관측 | 재현 fixture에서 profile 혼합·전송 후 fallback·자동 resend가 없고 자원 소유권이 분리됨 |
| 보안·redaction | header allowlist와 exception graph 재노출 경계가 불명확하고 basic/token 인증 조합이 미정 | case-insensitive strict allowlist, query-id/log-comment typed-only, 인증·routing·hop-by-hop 거부, CR/LF 거부, URL query scrub, Basic/AccessToken/BearerToken one-of, 원본 cause/suppressed 미연결 wrapper와 sanitized cleanup exception·synthetic canary를 고정 | message·cause·suppressed·rendered stack·logger 전부 canary 비노출, 인증 mode 충돌 fail-fast |
| API·JVM 호환성 | overload descriptor/default bridge와 URL·typed·raw precedence가 미정 | 두 신규 overload의 정확한 선언/descriptor, options 필수 trailing 인자, 중복 오류와 precedence matrix, Kotlin·Java fixture·`javap` 증거를 고정 | 기존 API baseline 불변, 신규 fixture 컴파일·실행 성공 |
| Kotlin 타입 의미론 | nullable container/element와 Decimal·Enum·IP·UInt64 경계가 고수준 | nullability 축과 lifecycle matrix, accepted class·wire·overflow/rounding/timezone/precision/malformed 표를 고정 | H2-only와 ClickHouse-only 결과가 분리되고 boundary matrix 전부 통과 |
| 테스트·성능 | DS 기준·benchmark provenance·직렬화·PENDING 규칙이 불충분 | DS-01~09에 exact command/expected/artifact/receipt를 추가하고 Testcontainers lock, 36조합·3 run·raw SHA·chart ledger를 고정 | 누락·비결정성·환경 실패가 PASS로 승격되지 않음 |
| 문서·운영 | callback thread/terminal event·취소 상태·실패 운영 지침·문서 traceability가 미정 | synchronous nonblocking callback, cleanup 후 terminal once, 네 가지 취소 상태, 운영 실패 matrix, EN/KO·central manual trace receipt를 고정 | SPW-01~05 receipt와 중앙 매뉴얼 N/A/PASS가 명시됨 |

수정본은 독립 reviewer가 다시 읽어 P0/P1=0을 확인하기 전까지 설계 gate를
`BLOCKED`로 유지한다. 독립 lane을 사용할 수 없을 때만 동일한 여섯 관점을 소유
세션에서 inline fallback으로 재검토하고, 그 사실과 비독립성을 receipt에 명시한다.

## 위험과 복구

- 새 options/type/diagnostics API가 호환성 문제를 만들면 해당 PR의 opt-in 경로를 끄고 기존 overload/JDBC writer/query 경로로 되돌린다.
- RowBinary capability가 서버/driver 버전에 따라 달라지면 강제 활성화하지 않고 fallback하며, 재현 가능한 fixture를 추가한 뒤 후속 PR로 분리한다.
- 실제 driver timeout·remote cancellation·전체 응답 메모리 상한은 이 설계의 성공 조건이 아니다. 해당 증거가 필요하면 #863 또는 별도 이슈를 갱신한다.
- Testcontainers 또는 benchmark 환경 실패는 skip으로 숨기지 않고 환경 원인과 미검증 범위를 기록한다.

## 상태

- [x] 사용자 승인된 대안과 train 경계
- [x] 공통 API·자원·redaction 계약
- [x] #865~#868별 공개 모델·실패·검증 계약
- [x] stacked base와 자동 이슈 종료 계약
- [x] 최초 6관점 설계 리뷰와 P1 처분 반영
- [x] 수정본 6관점 설계 재검토(P0/P1=0, inline fallback·비독립)
- [ ] 실행 계획과 계획 리뷰
- [ ] TDD 구현·검증·PR 생성
- [ ] CI/최종 리뷰 및 merge-ready 보고

현재 상태는 `PENDING`이다. 이 문서만으로 구현·성능 통과·PR 생성·머지를 주장하지 않는다.
