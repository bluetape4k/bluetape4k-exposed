# Issue #707 JDBC driver 강제 abort 설계 review

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-20-issue-707-driver-abort-design.md`
- 계획: `docs/superpowers/plans/2026-08-20-issue-707-driver-abort-design-plan.md`
- 기준 head: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- source 근거: `JdbcParallelKeyEnumeration.kt:221-275`, #694 PostgreSQL/MySQL fixture,
  `JdbcParallelKeyEnumerationOptions` public data class
- 검토 방식: 7-Tier read-only architect review + local JDK driver bytecode/source 대조

## 판정

**BLOCK (production/public API) — P0=0, P1=5, P2=3**

현재 #706 runtime lifecycle 계약은 CLEAR다. 차단 대상은 driver·pool 증거 없이
generic force-abort public API를 추가하는 구현 단계다.

이번 재검토는 승인된 test-only evidence slice를 추가했다. 따라서 PostgreSQL query
cancel fixture 범위는 **CLEAR**, production/public API 구현과 전체 backend runtime
matrix는 여전히 **BLOCK/PENDING**이다.

## P1 findings

1. **generic `forceAbort`/임의 timeout 금지** — `Statement.cancel()`은 DBMS와
   driver가 함께 지원해야 하는 조건부 요청이며, `Connection.abort(Executor)`는 물리
   연결을 폐기할 수 있다. 둘 다 transaction cleanup의 terminal barrier가 아니다.
   기존 `completion` latch 대기를 우회하면 #697 계약을 되돌린다.
2. **identity-bound active handle 선행** — 현재 child state는 future/latch/permit만
   추적하며 active statement·connection generation이 없다. Hikari 반환 후 재대여된
   연결에 stale abort가 전달될 수 있다.
3. **abort executor와 terminal barrier 분리** — abort task 완료·timeout은 caller 반환
   조건이 아니며, enumeration executor와 분리된 소유권을 명시해야 한다. 유일한
   terminal은 transaction wrapper 종료 뒤의 lifecycle latch다.
4. **네 backend exact evidence 필요** — PostgreSQL/pgjdbc, MySQL Connector/J,
   MariaDB Connector/J, CockroachDB+pgjdbc 각각 query cancel, rollback, active lease 0,
   next-query recovery를 real fixture로 증명하거나 `UNSUPPORTED/N/A/PENDING`로 고정해야
   한다. Cockroach는 pgjdbc 의존만으로 PostgreSQL semantics를 상속할 수 없다.
5. **public data class 확장 금지** — `JdbcParallelKeyEnumerationOptions`에 defaulted
   field를 추가하면 constructor/copy/component/$default ABI가 변한다. 첫 구현은
   design/test-only로 끝내고, public opt-in이 필요하면 새 type/overload와 ABI fixture를
   별도 승인한다.

## P2 findings

- Cockroach SQL `CANCEL QUERY`는 권한·query-id·target race가 있어 generic fallback에서
  제외해야 한다.
- Hikari raw `Connection.abort()`보다 pool-aware eviction/폐기 adapter가 필요하다.
- cancellation/abort 후 자동 retry는 결과 불명확성과 중복 효과 때문에 금지하고 원래
  cause를 보존해야 한다.

## 7-Tier 결과

| Tier | 결과 | 근거 |
| --- | --- | --- |
| 요구사항·계약 | BLOCK | cancel/abort/cleanup terminal을 분리해야 함 |
| 동시성·수명주기 | BLOCK | generation identity와 stale lease 방지 미구현 |
| Kotlin/API·ABI | BLOCK | public data-class field 확장 위험 |
| 성능·자원 | WATCH | abort executor·pool eviction 비용은 후속 설계 |
| 안정성·환경 | BLOCK | 네 backend exact fixture 또는 Unsupported 필요 |
| 문서·운영 | PASS | 범위·N/A·manual `1.12.1` 경계가 기록됨 |
| 검증·복구 | BLOCK | synthetic/real fixture 실행 전 |

## 다음 gate

다음 구현 전 반드시 (1) 네 backend capability matrix, (2) generation-bound handle,
(3) 순차 Testcontainers 검증 명령과 evidence schema, (4) public API가 필요할 때
별도 type/ABI 계획을 승인한다. 현재는 production/public API 구현을 시작하지 않는다.

## 재검토 — 2026-08-23

### 새 증거

- `PostgreSQLJdbcParallelKeyEnumerationTest.kt:51-98`은 pgjdbc `42.7.13`의
  `PGConnection.cancelQuery()`를 active `pg_sleep(30)`에 적용하고 SQLState `57014`,
  명시적 `rollback()`, 후속 `SELECT 1`, Hikari tracker `active == 0`을 확인한다.
- `PostgreSQLJdbcParallelKeyEnumerationTest.kt:427-458`은 `pg_stat_activity` polling과
  `testRuntimeOnly` driver를 보존하는 reflection unwrap helper를 제공한다.
- 수정 후 targeted PostgreSQL test `1/1`, 전체 PostgreSQL class `8/8`, MySQL class
  `11/11`, H2 class `10/10`이 각각 순차 `BUILD SUCCESSFUL`이다.
- MySQL Connector/J `9.7.0`의 `StatementImpl.cancel()` source 동작은 확인했지만
  active query runtime fixture가 없어 `PENDING`이다. MariaDB `3.5.10`과 CockroachDB
  역시 runtime cancel/recovery fixture가 없어 `PENDING/N/A`이다.

### Finding disposition

| 기존 finding | 재검토 결과 | 남은 조건 |
| --- | --- | --- |
| P1-1 generic `forceAbort`/timeout 금지 | 유지, PostgreSQL query cancel 증거로 조건부 cancel과 destructive abort를 분리하는 근거 강화 | public API 제안 전 별도 adapter/ABI plan |
| P1-2 generation-bound active handle | 유지 | synthetic race 또는 production handle 구현 전에는 BLOCK |
| P1-3 abort executor와 terminal barrier | 유지, PG fixture에서 cancel acknowledgement와 rollback/lease terminal을 분리 | abort worker 소유권과 timeout semantics 후속 설계 |
| P1-4 네 backend exact evidence | 부분 완화 | PostgreSQL만 PASS; MySQL/MariaDB/CockroachDB runtime row 필요 |
| P1-5 public data-class 확장 금지 | 유지, source diff와 test-only 변경으로 회귀 없음 | 새 public type/overload 필요 시 별도 approval |

### 재검토 결론

test-only PostgreSQL evidence slice는 CLEAR지만 Issue #707 전체 수용 기준은
`PARTIAL / PENDING`이다. 이 review에서 production `JdbcParallelKeyEnumeration.kt`,
`JdbcParallelKeyEnumerationOptions`, dependency/catalog, `docs/manual/**`를 변경하지
않았다. 다음 구현 gate는 generation identity와 backend별 runtime evidence를 채운 뒤
별도 승인받아야 한다.

## Writer DoD

- [x] SPW-01~05 재검토 범위·source/driver links·runtime evidence와 remaining gaps를
  대조했다.
- [x] terminology audit: 4개 artifact, findings `[]`.
- [x] `git diff --check`: PASS; `docs/manual/**` guard도 PASS.
- [x] P0/P1/P2와 `BLOCK/PENDING` 상태를 완화하지 않고, PG evidence slice만 CLEAR로
  분리했다.
