# 이슈 #642 JDBC `FluentQuery` pre-PR 검토

## 대상

- 기준 branch: `origin/develop`
- 기준 commit: `f8a480537dc9677facdeab78c3b2e413089c0647`
- head branch: `feat/spring-jdbc-fluentquery`
- 범위: Issue #642 구현, 테스트, API/ABI, EN/KO README, unreleased 기록
- 제외: 실제 1.13.0 릴리스 전에는 변경하지 않는 `docs/manual/**`

## 구현 결과

- `findBy(Example, FluentQuery)`를 immutable callback-scoped plan과 단일 QBE
  compiler로 구현했다.
- closed interface, Kotlin DTO, Java record projection과 exact property selection을
  SQL selected column에 반영했다.
- entity terminal은 기존 Exposed DAO identity/cache 경로를 유지하고 projection
  terminal은 selected row만 eager mapping한다.
- sort, limit, page, count, exists, cardinality terminal을 SQL pushdown하며
  `oneValue()`는 caller limit과 무관하게 최대 2행으로 cardinality를 판정한다.
- `stream()`은 Exposed 1.4.0 `JdbcTransaction.execQuery` cursor lease를 직접 소유하고
  owner transaction/thread, close, exhaustion, SQLException 변환을 검증한다.
- 열린 stream 중 같은 Exposed transaction의 nested SQL은
  `StatementInterceptor`로 즉시 거부하고 close 뒤 정상 실행을 복구한다.
- 기존 one-argument constructor JVM descriptor를 유지하고 factory/direct 생성
  경로의 collaborator parity를 보존했다.

## 독립 검토 결과

최신 diff를 성능, 안정성, 보안, 운영, developer/API, user/caller의 여섯 관점으로
분리해 정확한 HEAD `1d995d294ae0e17537ba4b166ddc82cf5bd96b85`에서 독립 검토했다.

| 관점 | P0 | P1 | 판정 |
| --- | ---: | ---: | --- |
| 성능 | 0 | 0 | PASS |
| 안정성 | 0 | 0 | PASS |
| 보안 | 0 | 0 | PASS |
| 운영 | 0 | 0 | PASS |
| developer/API | 0 | 0 | PASS |
| user/caller | 0 | 0 | PASS |

현재 판정은 **PASS — 6개 관점 모두 P0=0/P1=0**이다.

## 주요 P1 closure

- `limit(1).oneValue()`가 첫 행을 반환하던 cardinality 오류를 RED로 재현하고
  terminal 전용 limit 2로 수정했다.
- global/property matcher alias, transformer, non-string matcher의 validation을 probe
  getter보다 먼저 수행하도록 canonical preflight를 추가했다.
- empty `project()`는 automatic selection으로 복귀시키고, snake_case source
  property alias를 projection descriptor와 분리해 mapping했다.
- custom `searchQuery`는 `Op.TRUE` 구조 preflight로 join/group/distinct/partial shape를
  probe getter와 transformer 실행 전에 거부한다.
- zero-argument projection, escaped callback, closed stream, cursor `SQLException`의
  예외 taxonomy와 resource close를 회귀 테스트로 고정했다.
- companion internal factory는 `@JvmSynthetic`으로 Java public surface에서 숨기고
  checked-in ABI baseline과 Java/Kotlin consumer fixture로 검증했다.
- 열린 cursor의 nested Exposed SQL을 결정적으로 거부하고 close 뒤 interceptor를
  해제하는 RED→GREEN 통합 테스트를 추가했다.
- projection constructor, QBE getter, cursor advance, row materialization, resource
  cleanup의 원본 cause graph를 안전한 진단 정보로 치환하고 control character와
  과도한 길이를 제한했다.
- wrong-thread consumption은 owner resource를 건드리지 않고 실패하며, owner
  transaction에서 후속 close가 정확히 한 번 수행됨을 검증했다.
- cleanup 실패 시 nested-SQL guard를 해제하지 않고 caller에게
  `DataAccessResourceFailureException`을 노출해 transaction 종료를 강제한다.
- `EntityClass.new {}`로 insert 예약된 probe는 ID snapshot/write-set으로 SQL과 getter
  접근 전에 거부한다.
- cursor-backed stream은 positive `DatabaseConfig.defaultFetchSize`를 존중하고,
  미설정·비양수 값에는 bounded fetch size `100`을 적용한다. PostgreSQL/MySQL 실제
  driver suite에서 적용된 `Query.fetchSize`를 검증하고 MySQL 검증 URL에는
  `useCursorFetch=true`를 설정했다.
- mapper가 `Error`를 던져도 JDBC resource cleanup을 먼저 수행하고, driver cleanup의
  unchecked `Exception`도 각 resource별로 독립 수집·redact한다.
- 진단 문자열은 ISO control뿐 아니라 Unicode format, line separator, paragraph
  separator도 제거한다.

## 검토 중 철회·기각한 항목

- `JdbcTransaction.execQuery` callback 반환 시 `ResultSet`이 자동 close된다는 우려는
  철회했다. Exposed 1.4.0 source의 `JdbcTransaction.kt:231-258, 288-295`는 callback
  결과를 반환할 뿐 `ResultSet.use`를 적용하지 않으며, callback 반환 뒤 stream을
  소비하는 H2/PostgreSQL/MySQL 통합 테스트가 통과했다.
- mapping context가 같은 property를 중복 등록한다는 우려는 resolver의 property
  count 검증과 기존 mapping test로 재현되지 않아 기각했다.
- one-argument public constructor ABI 때문에 direct-constructor fallback을 완전히
  제거하지 않았다. factory lifecycle에서는 등록된 collaborator를 사용하고,
  direct path는 호환 fallback을 사용하는 의도적 trade-off다.

## 검증 증거

- JDK 25, H2/대표 multi-DB 포함 전체 module test: 259 tests, 0 failures
- Detekt: PASS, findings 0
- PostgreSQL 대표 multi-DB suite 강제 재실행: 26 tests, 0 failures, 0 errors, 0 skipped
- MySQL V8 `useCursorFetch=true` 대표 multi-DB suite 강제 재실행: 26 tests,
  0 failures, 0 errors, 0 skipped
- public ABI/Java/Kotlin consumer fixture: 3 tests, PASS
- EN/KO README parity validator: 6 runs, 16 assertions, PASS
- production README parity validation: PASS
- `git diff --check`: PASS
- `docs/manual/**` diff: 없음

## 비차단 잔여 위험

- resource close 실패 시 JDBC resource를 모두 한 번씩 정리하되 nested-SQL guard를
  유지한다. Java `Stream.close()`는 실패 뒤 handler를 재실행하지 않으므로 caller는
  해당 transaction을 종료해야 한다.
- 같은 thread/transaction 사용은 caller contract다. raw JDBC/JdbcTemplate nested
  SQL까지 차단하는 계약은 아니며 Exposed repository/statement 경계만 보호한다.
- Wrong-thread `Stream.close()`는 Java `Stream` pipeline을 closed로 만든 뒤 owner-close
  handler가 실패하므로 owner thread에서 다시 close할 수 없다. 이 계약을 위반한 caller는
  해당 transaction을 종료해야 하며, 모든 intermediate stream을 감싸는 별도 API 계층은
  이번 범위에 추가하지 않았다.
- custom `searchQuery`는 안전한 shape를 fail-fast 하기 위해 preflight와 실제 query를
  두 번 구성한다. 사용자 정의 builder가 무거운 경우 비용이 생길 수 있다.
- selected-row projection은 행마다 reflection mapping과 작은 map allocation을 수행한다.
- 기존 one-argument constructor ABI 때문에 direct construction은 registry miss 시
  독립 collaborator fallback을 유지한다. Spring factory 경로는 registry를 사용한다.
- multi-DB 검증은 H2 전체와 PostgreSQL/MySQL 대표 suite다. 모든 dialect의 전체
  FluentQuery 의미 조합을 실행한 것은 아니다.
- `CLOSE_FAILED` 상태에서 explicit close를 다시 호출하면 JDBC cleanup을 재시도할 수
  있다. 일부 driver의 중복 close 진단 가능성이 있으므로 cleanup failure 뒤에는 재사용
  또는 재시도하지 않고 transaction을 종료해야 한다.
- mapped getter 보강은 현재 domain type에 직접 선언된 getter를 대상으로 하므로 향후
  상속 계층의 mapped getter 지원은 별도 확장 범위다.

## DoD Status

- [x] 설계와 독립 설계 검토 완료
- [x] 구현 계획과 독립 계획 검토 완료
- [x] TDD RED→GREEN 구현 완료
- [x] targeted/H2/PostgreSQL/MySQL/Detekt/API·ABI 검증 완료
- [x] EN/KO/KDoc 및 stable manual 경계 검증 완료
- [x] 6개 관점 독립 pre-PR 검토 P0=0/P1=0
- [x] Lore commit 완료
- [ ] PR 생성
- [ ] exact-head CI, review/thread, mergeability 확인
- [ ] fresh merge 승인 후 merge·sync·cleanup

상태: `PENDING` — local 구현·검증·pre-PR 검토와 Lore commit은 완료됐고 PR 생성이
남아 있다.
