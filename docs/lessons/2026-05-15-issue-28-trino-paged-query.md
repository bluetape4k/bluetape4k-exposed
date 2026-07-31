# Issue #28 Trino 페이지 쿼리 교훈

## 맥락

`exposed-trino`에는 JDBC `ResultSet`의 수명을 Exposed 트랜잭션 밖으로 노출하지 않는 대용량 결과 집합 API가 필요했다.

## 교훈

- 현재 JDBC + Exposed 트랜잭션 경계에서는 행 단위 `Flow` 스트리밍이 안전하지 않다. 수집 작업이 `ResultSet`을 소유한 트랜잭션보다 오래 지속될 수 있기 때문이다.
- 페이지 단위 구체화가 더 안전한 계약이다. 각 페이지는 짧은 트랜잭션 안에서 읽고, 트랜잭션이 끝난 뒤 방출한다.
- Trino SELECT 문법은 `ORDER BY ... OFFSET ... LIMIT ...`를 허용하지만, Exposed 기본 순서인 `LIMIT ... OFFSET ...`는 실제 Trino에서 거부된다. 첫 `pagedQueryFlow` 스모크 테스트에서 Testcontainers가 이 문제를 발견했다.
- Exposed dialect의 `FunctionProvider`를 교체하면 상속받던 벤더 함수 매핑이 사라진다. 특정 SQL 조각에 custom provider가 필요하면, 모듈이 이미 사용하던 벤더별 매핑을 복사하거나 맞춰야 한다.
- Trino 475는 Testcontainers 환경에서 `STRING_AGG`를 등록하지 않았다. 이 모듈에서는 `ARRAY_JOIN(ARRAY_AGG(...), separator)`가 검증된 group-concat 매핑이다.
- 취소 테스트는 방출된 값과 요청한 페이지 offset을 모두 검증해야 한다. 그래야 수집 취소 뒤 다음 페이지 요청이 시작되지 않음을 보일 수 있다.
- 대용량 결과 집합 문서에서는 애플리케이션 메모리 경계(`pageSize`)와 spooling protocol 같은 Trino JDBC/클러스터 처리량 메커니즘을 구분해야 한다.

## 검증

- 첫 대상 실행은 Trino SQL 문법 오류 `mismatched input 'OFFSET'`로 실패했다.
- `TrinoDialect`의 limit/offset SQL 생성을 수정했다.
- `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoExtensionsTest"`는 8개 테스트를 통과했다.
- 이후 Claude PR 리뷰에서 custom provider가 만든 `groupConcat`/`locate` 회귀와 오래된 README roadmap 항목을 발견했다.
- Trino 함수 매핑을 수정하고 `groupConcat`, `locate`의 실제 Trino 테스트를 추가했다.
- `./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.query.SelectTest"`는 8개 테스트를 통과했다.
- `./gradlew :exposed-trino:test`는 61개 테스트를 통과했다.

## 후속 지침

- Trino 쿼리 API를 추가할 때는 Exposed DSL 예상만 확인하지 말고, 실제 Trino Testcontainers 실행으로 생성 SQL을 검증한다.
- `ResultSet` 수명, 취소, 커넥션 정리에 명시적 소유자가 생기기 전까지 cursor 스타일 API를 public surface에 추가하지 않는다.
