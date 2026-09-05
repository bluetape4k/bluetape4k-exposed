# #817 Exposed upstream cleanup 예외 전달 계약

## 배경

[#817](https://github.com/bluetape4k/bluetape4k-exposed/issues/817)은 #808에서 분리한
후속 검증이다. 기준 catalog ref `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`는
JetBrains Exposed `1.5.0`을 사용한다. 이 작업은 dependency, public API, production adapter를
변경하지 않고 JDBC와 R2DBC transaction cleanup 실패가 호출자에게 어떻게 전달되는지 확인한다.

## 재현

실제 H2와 PostgreSQL 연결을 proxy로 감싸 transaction 본문의 주원인과 cleanup 실패를 독립적으로 주입했다.
JDBC는 `rollback`, statement `close`, connection `close`를, R2DBC는
`rollbackTransaction`, connection `close` Publisher를 검증한다. 실패를 신호하기 전에 실제
rollback 또는 close를 완료해서 fault-injection fixture가 자원을 남기지 않게 했다.

처음에는 cleanup 실패가 주원인의 `suppressed`에 들어간다고 단언했다. JDBC 3개와 R2DBC 2개가
모두 `java.util.NoSuchElementException: Array is empty`로 실패했다. 이후 테스트를 현재
`1.5.0` 동작의 characterization으로 바꾸고 다음 항목을 함께 단언했다.

- transaction 본문에서 발생한 주원인 인스턴스가 그대로 전달된다.
- rollback과 close는 각 경로에서 한 번 호출된다.
- cleanup 실패는 주원인의 `suppressed`에 추가되지 않는다.
- R2DBC SPI에는 `Statement.close()`가 없으므로 JDBC와 같은 statement close 실패 경로를 만들지 않는다.

## upstream 소스와 중복 확인

Exposed `1.5.0`과 2026-09-05의 `main`은 같은 처리 방식을 사용한다.

- [JDBC `closeStatementsAndConnection`](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/transactions/Transactions.kt#L441-L463)은 statement와 connection cleanup 예외를 로그로 남긴다.
- [R2DBC `closeStatementsAndConnection`](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-r2dbc/src/main/kotlin/org/jetbrains/exposed/v1/r2dbc/transactions/Transactions.kt#L263-L285)은 statement 상태를 비우고 connection cleanup 예외를 로그로 남긴다.
- rollback helper에 전달한 callback도 cleanup 예외를 로깅할 뿐 주원인에 연결하지 않는다.

JetBrains/Exposed GitHub issues에서 `suppressed exception cleanup`,
`closeStatementsAndConnection exception`, `rollback close exception`을 검색했지만 같은 요청은
찾지 못했다. [#1167](https://github.com/JetBrains/Exposed/issues/1167)과
[#1171](https://github.com/JetBrains/Exposed/issues/1171)은 과거 connection leak 또는 cleanup
경고 문제로 이 계약과 다르다.

YouTrack의 `project: EXPOSED`에서 `suppressed exception cleanup`,
`transaction close exception`, `rollback close exception`을 검색해도 같은 요청은 없었다.
[EXPOSED-982](https://youtrack.jetbrains.com/issue/EXPOSED-982)는 취소 중 cleanup 자체가 중단되는
connection leak을, [EXPOSED-1047](https://youtrack.jetbrains.com/issue/EXPOSED-1047)는 R2DBC
`beginTransaction()` 실패 시 connection 반환을 다룬다. 두 이슈 모두 cleanup 실패를 주원인의
`suppressed`에 보존하는 계약은 다루지 않는다.

## 결정

로컬 adapter는 Exposed 내부에서 소비한 cleanup 예외를 받지 못한다. 이를 복원하려고 upstream
transaction 소스를 복사하거나 로그를 파싱하면 내부 구현과 로깅 형식에 결합된다. 따라서 로컬
우회 API를 추가하지 않는다.

upstream에는 다음 범위로 개선을 제안할 수 있다.

- transaction 본문의 주원인은 그대로 유지한다.
- rollback, statement cleanup, connection close 실패를 발생 순서대로 주원인의 `suppressed`에 추가한다.
- cleanup만 실패한 정상 완료 경로의 예외 계약은 별도로 정의한다.
- JDBC와 R2DBC에서 같은 원인 우선순위를 검증한다.

실제 upstream 제안 게시는 별도 승인을 받은 뒤 수행한다.

## 검증과 재발 방지

Exposed 버전을 승격할 때 두 characterization test를 먼저 실행한다. upstream이 cleanup 실패를
`suppressed`에 보존하도록 변경하면 현재의 `shouldBeEmpty()` 단언이 실패한다. 그때 upstream
release note와 구현을 확인하고 기대값을 `CleanupFailure` 보존으로 바꾼 뒤 H2와 PostgreSQL
행렬을 다시 실행한다. 단순히 로그가 존재한다는 사실을 예외 전달 증거로 사용하지 않는다.

## Assets

없음.
