# ClickHouse JDBC V2 stacked PR train 설계

## 상태와 승인 범위

- 대상 저장소: `bluetape4k/bluetape4k-exposed`
- 대상 모듈: `exposed/clickhouse`
- 이슈 순서: [#865](https://github.com/bluetape4k/bluetape4k-exposed/issues/865) → [#866](https://github.com/bluetape4k/bluetape4k-exposed/issues/866) → [#867](https://github.com/bluetape4k/bluetape4k-exposed/issues/867) → [#868](https://github.com/bluetape4k/bluetape4k-exposed/issues/868)
- 기준: `develop@ac6a0dc330d29a2812f83dd1a7aaa7db42fd0b26`, Exposed `1.5.0`, ClickHouse JDBC `0.9.9`
- 브랜치 train: `feat/issue-865-clickhouse-v2-options` → `feat/issue-866-clickhouse-v2-types` → `feat/issue-867-clickhouse-v2-rowbinary` → `feat/issue-868-clickhouse-v2-diagnostics`
- 사용자는 위 train의 상세 설계를 2026-09-09 승인했다. 이 문서는 설계 계약이며 구현 완료를 의미하지 않는다.
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
- 로그와 예외에는 비밀번호, bearer token, TLS key material, header 값 전체, SQL bind 값을 넣지 않는다. redaction은 키 이름 allowlist/denylist가 아니라 민감한 값의 명시적 제거를 포함해야 한다.
- 재시도는 ClickHouse 드라이버와 기존 Exposed transaction 설정에 위임한다. 이 train은 별도 재시도나 exactly-once를 주장하지 않는다.
- blocking JDBC 호출의 즉시 coroutine 취소, deterministic socket timeout, remote query cancellation은 #863의 별도 계약이다.
- 새 의존성은 추가하지 않는다. Micrometer/OTel은 선택적 후속 통합으로 남기며 core API가 특정 관측 라이브러리에 의존하지 않게 한다.

## #865 연결·보안·세션 옵션

### 공개 모델

`ClickHouseV2Options`는 다음 그룹을 immutable property로 가진다.

- 연결: `connectionTimeout`, `socketOperationTimeout`, `connectionRequestTimeout`, keep-alive/connection TTL
- 보안: TLS 활성화, trust/key store 참조, mTLS 설정, bearer/access token, SNI/SSL auth
- 전송: client/server compression, LZ4 buffer, proxy, retry 정책
- 세션·식별: client name, session timezone, roles, log comment, server settings
- 확장: 드라이버가 안전하게 허용한 custom header와 추가 `Properties`

민감한 값은 `toString`, debug log, 진단 callback에 원문으로 노출하지 않는다. 옵션은 `Properties`로 변환하는 단일 내부 경계를 가지며, 호출자가 제공한 `Properties`를 mutate하지 않는다.

### 연결 API 계약

- 기존 두 `connect` overload의 JVM 시그니처·기본 인자·동작을 유지한다.
- 신규 overload는 기존 positional 호출을 깨지 않도록 별도 `options` 또는 `Properties` 인자를 받는다.
- `options`와 `Properties`를 함께 받는 경우 typed option이 우선하고, 명시적으로 허용된 raw property만 병합한다. 비허용 key는 연결 전에 고정된 예외로 거부한다.
- URL 속성 우선 규칙을 보존하므로 URL에 있는 값은 options 병합으로 덮어쓰지 않는다.
- 연결 실패 시 새 wrapper가 원래 예외를 보존하며, cleanup 실패는 suppressed 예외로만 추가한다. 비밀번호와 token은 예외 메시지에서 제거한다.

### 검증 계약

- 기본/신규 overload의 source compatibility와 JVM ABI를 별도로 확인한다.
- 각 옵션이 정확한 V2 property로 변환되는지, URL 우선·허용되지 않은 header·redaction을 단위 테스트한다.
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

### 검증 계약

- H2 단위 fixture와 ClickHouse Testcontainers를 사용해 Array(Nullable), nested Array, Map, Tuple, Nested, JSON, UUID/IP/Time/Decimal/Enum을 순차 검증한다.
- insert → select metadata → Exposed 값 복원 round-trip, null, empty, 중첩 깊이, 잘못된 setter, 지원하지 않는 타입의 명확한 실패를 고정한다.
- JVM ABI와 Kotlin source compatibility를 기존 column type에 대해 별도로 확인한다.

## #867 RowBinary 배치 writer

### 적격성·fallback

- `beta.row_binary_for_simple_insert`는 기본 비활성이고 명시적 `ClickHouseRowBinaryOptions`에서만 활성화한다.
- writer는 query가 단순 INSERT인지, 대상 table/column이 지원 타입인지, setter matrix가 RowBinary converter와 일치하는지 먼저 판정한다.
- 부적격 query, unsupported setter, capability 불명확, 옵션 오류는 RowBinary로 강제하지 않고 기존 JDBC batch writer로 fallback한다. fallback 이유는 secret과 bind 값을 제외한 구조화된 진단에 남긴다.
- RowBinary 경로의 결과 행 수, 실패 시 예외, 재사용 후 buffer reset을 명시한다. 기본 JDBC 경로와 반환 계약은 변경하지 않는다.

### 메모리·실패 경계

- buffer는 batch size/byte limit 중 먼저 도달하는 bounded 정책을 사용한다. 전체 입력을 무제한 materialize하지 않는다.
- 한 batch flush 실패 후 동일 writer를 재사용할 수 없으면 명시적 상태 오류를 내고, 이미 전송된 batch를 자동 재전송하지 않는다.
- connection, statement, stream 자원은 writer가 소유한 범위에서 정리하며 외부 transaction/pool은 닫지 않는다.

### 검증·benchmark 산출물

- 단순 INSERT/복합 INSERT, nullable·중첩·특수 타입, unsupported setter matrix, 빈 입력, 부분 flush, 실패 후 reset/fallback을 검증한다.
- RowBinary opt-in과 기본 JDBC 경로를 같은 fixture에서 비교하고, ClickHouse Testcontainers를 순차 실행한다.
- benchmark는 warmup과 반복 횟수, 행 수·행 폭·JVM·driver·heap·Docker image를 기록한다. throughput, time-to-first-result, peak heap/RSS를 측정하고 결과 chart와 한국어 분석 문서를 함께 만든다.
- benchmark가 driver 내부 전체 버퍼링을 드러내거나 결과가 재현되지 않으면 성능 개선 완료로 표시하지 않고 `PENDING`으로 남긴다.

## #868 query 식별자·로그·응답 메타데이터·메트릭

### 진단 모델

`ClickHouseQueryDiagnostics`는 query id, log comment, client name, session settings, 시작/종료 시각, elapsed, returned rows, vendor code, outcome을 immutable 진단 값으로 제공한다. query id는 호출자 값이 우선이며 없으면 안전한 내부 생성값을 사용한다.

callback은 query 시작·headers/options 적용·응답 수신·정상 종료·실패 종료 이벤트를 전달한다. callback 실패가 SQL 결과나 원래 예외를 대체하지 않으며, 사용자 제공 callback은 호출자가 소유한다.

### 로깅·관측 경계

- lifecycle log는 query id와 제한된 구조화 필드만 기록한다. SQL text, bind 값, token, password, custom header 원문은 기록하지 않는다.
- 메트릭 callback은 core ClickHouse 모듈에 Micrometer/OTel 타입을 노출하지 않는다. 어댑터가 필요하면 후속 모듈에서 이 모델을 소비한다.
- response metadata는 JDBC vendor code, elapsed, rows처럼 드라이버가 실제 제공한 값만 노출한다. 제공되지 않는 값은 추정하지 않는다.
- query id/log comment/client name/session setting은 #865 옵션 모델과 중복 정의하지 않고 진단 값 사본에서 참조·복사한다.

### 검증 계약

- caller-supplied/generated query id, redaction, callback 순서, 성공·실패·취소 lifecycle, callback 예외 격리를 검증한다.
- 응답 metadata와 vendor exception의 code/message 보존을 확인하고, 없는 metadata를 임의의 zero 값으로 포장하지 않는다.
- 기존 queryList/queryFlow의 결과·트랜잭션·재시도 동작을 변경하지 않는 회귀 테스트를 실행한다.

## PR train 통합 계약

| PR | 부모 base | 소유 공개 영역 | 다음 PR이 사용하는 계약 |
|---|---|---|---|
| #865 | `develop` | options, connect, redaction | options와 session/query 식별 필드 |
| #866 | #865 exact head | column type adapters | typed value/capability 정보 |
| #867 | #866 exact head | RowBinary writer, benchmark | options, type adapters, fallback diagnostics |
| #868 | #867 exact head | diagnostics, lifecycle callback | options와 writer/query 결과 경계 |

각 PR은 부모의 정확한 head에서만 분기하고 자신의 이슈에 `Closes #...`를 사용한다. 부모가 merge된 후 다음 PR을 `develop`으로 retarget할 때 exact head, checks, reviews, threads, mergeability를 다시 읽는다. PR 간 임시 API를 재수출하거나 부모 변경을 복제하지 않는다.

## 수용 기준과 산출물

| ID | 기준 | 증거 |
|---|---|---|
| DS-01 | 기존 connect/column/query/writer API 보존 | Kotlin source compatibility와 JVM ABI 결과 |
| DS-02 | #865 옵션 전달·URL precedence·redaction | 단위 테스트와 property 변환 receipt |
| DS-03 | #866 타입 round-trip 및 unsupported 명시 실패 | H2/ClickHouse 순차 테스트 |
| DS-04 | #867 RowBinary opt-in·capability·JDBC fallback | setter matrix·실패·reset 테스트 |
| DS-05 | bounded buffer와 재현 가능한 benchmark | raw result, chart, 한국어 분석 문서 |
| DS-06 | #868 immutable diagnostics와 callback 격리 | 성공/실패/취소 lifecycle 테스트 |
| DS-07 | 트랜잭션·자원 소유권·취소 경계 보존 | close/rollback/예외 우선순위 테스트 |
| DS-08 | 문서·KDoc·PR train metadata 일치 | README 양언어, issue/PR body, exact-head receipt |
| DS-09 | 전체 검증 | detekt, targeted tests, ABI/API, CI checks, independent review |

필수 산출물은 구현 코드, 모듈 테스트, 공개 API baseline 갱신(필요한 경우), 양언어 README/KDoc, #867 benchmark chart/분석, 설계·계획·리뷰·lesson 문서다. 중앙 매뉴얼은 소유 저장소의 별도 변경으로 다루며 이 저장소에 두 번째 `docs/manual` 트리를 만들지 않는다.

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
- [ ] 6관점 설계 리뷰 및 반영
- [ ] 실행 계획과 계획 리뷰
- [ ] TDD 구현·검증·PR 생성
- [ ] CI/최종 리뷰 및 merge-ready 보고

현재 상태는 `PENDING`이다. 이 문서만으로 구현·성능 통과·PR 생성·머지를 주장하지 않는다.
