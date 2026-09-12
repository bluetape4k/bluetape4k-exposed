# ClickHouse JDBC V2 query diagnostics lesson

## 결정

`exposed/clickhouse`의 `queryList`, `queryFlow`, RowBinary batch writer에
선택적인 immutable diagnostics 경계를 추가했다. caller query ID 우선순위와 활성
요청 범위의 UUID 충돌 검사를 고정하고, `logComment`·client name·session
setting·timezone·role을 요청에 적용한다. lifecycle listener는 JDBC dispatcher에서
동기로 실행하고 terminal sink는 cursor·statement·transaction 정리 뒤 한 번만
실행한다. batch writer는 `acceptedCount`만 row summary로 연결하고
`QueryResponse`가 없어 server metadata를 추정하지 않는다.

응답의 `serverDisplayName`과 driver allowlist response header는 제공될 때만
복사한다. vendor code·elapsed·row summary가 없는 경계에서는 값을 추정하지
않는다. SQL·bind·row·credential·알 수 없는 header와 callback 원문 예외는
payload에 넣지 않으며, listener/sink 실패는 sanitized callback failure와
bounded `query_listener_failures_total`로만 관찰한다. Remote
`KILL QUERY` 완료는 주장하지 않고 #863 후속 범위로 남겼다.

## 실제 실패와 재실행

1. 첫 RED selector는 코드 오류가 아니라 Dokka plugin 환경의
   `kotlinx/serialization/StringFormat` plugin 생성 실패로 종료했다. Gradle
   daemon을 중지한 뒤 같은 selector를 재실행해 컴파일 경계를 다시 확인했다.
2. 첫 GREEN 실행에서 세 resource-lifecycle 검증이 공개
   `IllegalStateException`/`CancellationException` 인스턴스 동일성에서
   실패했다. Kotlin coroutine channel iterator의 `recoverStackTrace()`가
   공개 예외를 복사하는 것이 원인이었다.
3. consumer가 channel iterator 대신 `receiveCatching()`으로 close cause를
   직접 읽도록 바꾸고, 테스트 marker exception을 private subtype으로
   한정했다. callback 예외의 message가 secret을 포함하지 않도록
   `sanitizeThrowable`도 사용했다.
4. response metadata는 `ResultSetImpl`/`QueryResponse`가 노출하는 경우에만
   reflection으로 읽고, wrapper·method·field가 없는 driver에서는 빈 값으로
   fail-open한다. 허용 header 외 값은 삭제한다.

## 검증 증거

다음 명령은 2026-09-12에 실행되어 11개 diagnostics/resource 테스트와 0
failures/errors, 15개 RowBinary 테스트와 0 failures/errors를 확인했다.
response metadata wiring과 batch diagnostics 이후에도 동일한 결과였다.

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseQueryDiagnosticsTest' \
  --tests '*ClickHouseResourceLifecycleTest' \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryTest' \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

기존 `ClickHouseQueryLifecycleTest`의 close/조회 단계 오류 7개와 mapper
중간 오류 1개도 별도 selector에서 통과했다. 전체 모듈 test는 251개 중
failures/errors 0, 의도적으로 비활성화된 integration/benchmark 2개 skip이었다.
ABI와 detekt도 통과했다. 이 검증은 local Testcontainers 환경에서 수행한 것이며
최종 PR hosted CI와 mergeability는 PR 생성 후 exact head에서 다시 읽어야 한다.

## 후속 위험

- V2 connection의 기본 `QuerySettings`는 driver가 statement에 merge한다. 현재
  diagnostics 적용은 ClickHouseDatabase가 생성한 operation connection 경계에
  한정되며, 외부 pooled connection에서 설정 복원과 per-statement 적용을
  보장하지 않는다. pooled DataSource 재사용을 지원 범위로 넓힐 때 별도
  lifecycle/restore 이슈로 분리한다.
- `queryFlow`의 local cancellation은 JDBC blocking read를 즉시 중단하거나
  원격 query를 종료했다는 뜻이 아니다. deterministic socket timeout과
  in-flight remote termination evidence는 #863에서 별도로 검증한다.
- 독립 코드 리뷰 lane은 이번 세션에서 agent capacity 오류로 실행되지 않아
  inline fallback review만 수행한다. 따라서 리뷰 결과는 비독립 증거이며
  hosted review를 대체하지 않는다.
