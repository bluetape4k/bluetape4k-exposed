# Issue #643 R2DBC 코루틴 QueryByExample·FluentQuery 계획 검토

## 검토 범위와 기준

- 대상: `:bluetape4k-exposed-spring-boot-r2dbc`, Issue [#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643), milestone `1.13.0` 개발선
- 입력: 승인된 설계 `docs/superpowers/specs/2026-08-16-issue-643-r2dbc-coroutine-fluentquery-design.md`, 구현 계획 `docs/superpowers/plans/2026-08-16-issue-643-r2dbc-coroutine-fluentquery-plan.md`, 현재 R2DBC repository/factory/test/build 소스, Exposed 1.4.0 R2DBC source
- 고정 범위: coroutine-native API 1번만 구현하고 Reactor 이중 facade, 새 dependency, `docs/manual/**` release 승격, production code/test 변경은 이 계획 단계에서 수행하지 않는다.
- 검토 절차: 1차 performance/security/stability 독립 검토 후 P0/P1/P2 findings를 계획에 반영하고, 수정된 계획을 operability/developer/user-caller 관점에서 2차 독립 검토한 뒤, 보완된 설계·계획을 동일 관점에서 3차 재검토한다.

## 1차 독립 검토와 반영

| 관점 | lane | 최초 결과 | 계획 반영 |
| --- | --- | --- | --- |
| performance | `plan-performance` | P1 1건, P2 2건 | `Pageable.unpaged()` + positive fluent `limit`의 predicate-only count/최대 2 statements, 128-entry bounded projection cache/eviction, backend `EXPLAIN`·특정 index 선택은 보장 범위 밖이라는 명시를 Task 4/6 및 traceability에 추가했다. |
| security | `plan-security` | P1 1건, P2 2건 | factory/direct 양쪽 `expectedDomainType` exact 검증과 raw/unchecked/subtype probe의 zero-SQL 거부, projection constructor 예외 redaction, 기존 health bridge를 제외한 신규 QBE surface 한정 검사를 Task 2/3/4/5/6에 추가했다. |
| stability | `plan-stability` | P1 1건 | Exposed 1.4.0 top-level `R2dbcException` retry로 streaming Flow가 중복 방출되지 않도록 top-level streaming transaction에만 `maxAttempts = 1`을 설정하고 outer caller 설정은 보존하는 정책, retry-injection 회귀, README/KDoc contract를 Task 4/6/7에 추가했다. |

### 반영 원칙

각 finding은 새 public API나 repository-owned database로 우회하지 않고, 승인된 immutable snapshot·cold `Flow`·caller-owned transaction 경계를 보존하는 최소 변경으로 계획에 반영했다. Exposed automatic retry를 caller-owned `DatabaseConfig`에 전역 적용하지 않으며, caller가 전체 Flow 수집을 감싸 재시도할 수 있도록 문서화한다.

## 2차 독립 검토와 반영

| 관점 | lane | 결과 | 근거/조치 |
| --- | --- | --- | --- |
| operability | `plan-operator` | P1 1건, P2 1건 | 현재 CI producer가 없는 `multi-db-tests` required check 선언을 제거하고 기존 `implementation-verification` 입력으로 PostgreSQL/MySQL 순차 evidence·unavailable/timeout/0-test 분류를 고정했다. non-streaming retry/backoff/timeout 위임과 attempt evidence도 Task 4/6/7에 추가했다. |
| developer/API | `plan-developer` | P1 1건, P2 1건 | opt-in parent의 `@NoRepositoryBean`·scan regression, factory QBE dispatch test, exact exception taxonomy, internal/private collaborator와 public 4인자 ABI 경계를 추가했다. 이후 enum allowlist, sanitizer RED 명령, `FACTORY`/`DIRECT` mode, 빈 `project()` semantics를 보완했다. |
| user/caller | `plan-user` | P1 1건, P2 1건 | direct `findOne` cardinality를 fluent `one()`과 동일하게 고정하고 active outer preflight·caller `maxAttempts` 보존, Flow collection-time DB context, matcher/projection parity를 Task 4/6/7과 README contract에 추가했다. |

2차의 모든 P0/P1을 계획의 task·acceptance·test에 반영한 뒤 영향 관점의 3차 재검토를 수행했다.

## 3차 독립 재검토

| 관점 | lane | 결과 | 확인 근거 |
| --- | --- | --- | --- |
| operability | `plan-operator-rereview` | PASS — P0=0, P1=0, P2=0 | 기존 required check topology, H2 → PostgreSQL → MySQL 순차 실행, backend별 실제 test count와 unavailable/timeout/0-test 분류, retry ownership을 재확인했다. |
| developer/API 및 security | `plan-developer-rereview` | PASS — P0=0, P1=0, P2=0 | `R2dbcQbeOperation` enum allowlist, sanitizer test command, `R2dbcQbeConstructionMode { FACTORY, DIRECT }`, sort taxonomy, `@NoRepositoryBean`, ABI·dispatch, direct `findOne`, 빈 `project()` semantics를 현재 설계·계획에 대조했다. |
| user/caller 및 stability | `plan-user-rereview` | PASS — P0=0, P1=0, P2=0 | direct `findOne`/`one()` cardinality, empty `project()` reset, outer transaction·`useNestedTransactions` fail-fast, caller `maxAttempts` 보존, top-level streaming `maxAttempts=1`, Flow 수집 DB context와 EN/KO parity를 재확인했다. |

최종 3차 재검토는 P0=0, P1=0, P2=0으로 종료했다. 따라서 계획 검토 자체는 완료할 수 있으나 production implementation은 별도 승인 전까지 시작하지 않는다.

## Writer gate

- SPW-01: Issue #643, 대상 모듈, 독자, 승인 설계와 source boundary를 명시했다.
- SPW-02: public/internal file map, TDD RED/GREEN 순서, 명령, rollback/rerun, approval gate를 계획에 고정했다.
- SPW-03: 한국어 기술 문체를 사용하고 API·identifier·command·version·URL을 보존한다.
- SPW-04: 설계 acceptance와 계획 task/검증 증거를 traceability table에 매핑했다.
- SPW-05: 최종 commit 전에 Markdown heading/table/list/code fence와 `docs/manual/**` exclusion을 read-back한다.
- KO-01..06: 사실·식별자 보존, 번역투 제거, 용어 일관성, 비유 배제, EN/KO reader-facing parity를 검증한다.

## 검증 증거

- 계획/검토는 feature worktree에서 수행하며 canonical checkout의 workflow state와 혼동하지 않는다.
- 1차·2차·3차 review lane은 모두 read-only로 완료했고, 각 finding 반영 뒤 현재 설계·계획을 재검토했다.
- `git diff --check`, Markdown code-fence balance, `docs/manual/**` target-path diff audit는 최종 commit 직전에 다시 실행한다.
- 이 단계에서는 production code, tests, README, CHANGELOG, WIP, PR, CI, merge, sync, cleanup을 변경하거나 실행하지 않는다.

## DoD Status

- [x] 승인된 설계와 coroutine-only 범위를 계획에 고정했다.
- [x] 1차 performance/security/stability findings를 P0/P1/P2와 함께 계획에 반영했다.
- [x] 2차 operability/developer/user-caller 독립 검토 findings를 계획에 반영했다.
- [x] 3차 영향 관점 재검토에서 P0=0/P1=0/P2=0을 확인했다.
- [x] 계획·설계 보완과 본 검토 artifact를 Lore commit으로 고정한다.
- [ ] 계획 commit 이후 사용자에게 구현 시작 승인을 별도 요청한다.
- [ ] production implementation, tests, CI, PR, merge, local sync, cleanup은 구현 승인 이후 단계다.

상태: `PENDING` — 계획·설계·검토 artifact commit은 완료했지만 별도 implementation approval 전에는 구현을 시작하지 않는다.
