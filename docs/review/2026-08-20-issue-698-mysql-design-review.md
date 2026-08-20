# Issue #698 MySQL 8 JDBC conformance 설계 review

## 문서 상태

- 대상: [Issue #698](https://github.com/bluetape4k/bluetape4k-exposed/issues/698)
- 상위: [Issue #694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- Epic: [Issue #659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 검토 대상: `docs/superpowers/specs/2026-08-20-issue-698-mysql-driver-conformance-design.md`
- 기준 head: `ea19b9e0c6d5135d2447c9a95435c85c1127e3b3`
- 검토 방식: Type A 6-perspective read-only review, production source·기존 PostgreSQL
  fixture·공식 MySQL 문서·Epic train 경계를 줄 단위로 대조
- 최종 상태: **PASS (P0=0, P1=0)**

## 검토 범위와 근거

| 관점 | 대조한 근거 | 판정 |
| --- | --- | --- |
| 아키텍처·stack 경계 | `JdbcParallelKeyEnumeration.kt:38-151`, Issue #696 fixture, 설계의 12-34·71-106 | MySQL 전용 test slice로 한정하고 production/API/ABI를 건드리지 않아 PASS |
| Kotlin·API 계약 | `JdbcParallelKeyEnumerationOptions`의 `readOnly` 기본값, caller-owned executor, 내부 `rangeReader` overload | fault case만 `readOnly=false`를 명시하고 public 기준 데이터 계약으로 확장하지 않아 PASS |
| 테스트·TDD | sparse/range/empty, exact pool barrier, isolation writer barrier, marker rollback, retry request count matrix | 결과 정규화 없이 원본 순서·중복·rollback 원인을 직접 검증하도록 고정해 PASS |
| 성능·동시성 | Hikari pool `1/2/4`, `maxConcurrency=2`, bounded latch, virtual-thread executor | upper bound만 보던 초기안은 폐기하고 `min(pool, 2)`에 도달하는 barrier와 exact peak를 요구해 PASS |
| 보안·운영·소유권 | `TestDB.kt:144-164`, `Containers.kt:60-69`, `AbstractExposedTest.kt:50-56`, nightly MySQL job | Testcontainers endpoint/credential만 사용, container stop은 `ShutdownQueue`에 위임, startup 오류는 skip하지 않아 PASS |
| 문서·릴리스·stack train | `README.md`/`README.ko.md` 추가 evidence 범위, `docs/manual/**` 불변, #697/#690 후속 경계 | EN/KO parity와 N/A/WATCH 판정, 안정 manual `1.12.1` 보존을 명시해 PASS |

## 초기 review에서 발견한 결함과 수정 결과

초기 검토는 P1 세부 결함을 확인했으며, 구현 시작 전에 설계에 모두 반영했다.

1. pool 검증이 `peak <= min(pool, 2)`만 확인해 직렬 실행을 통과시킬 수 있었다. 이제
   pool `2/4`는 두 reader가 lease를 얻을 때까지 bounded acquisition barrier로 대기하고
   exact peak를 확인한다. pool `1`은 under-provisioned pressure case로 명시했다.
2. `readOnly=true` 기본 transaction에서 duplicate INSERT를 수행하던 fault 시나리오를
   `readOnly=false`로 한정했다. sentinel을 먼저 INSERT한 뒤 unique 충돌을 만들고,
   별도 transaction에서 sentinel 부재와 seed 불변을 확인해 rollback을 인과적으로 증명한다.
3. lease fault가 retry 횟수를 고정하지 않았다. fault 전용 fixture에
   `defaultMaxAttempts=2`, retry delay `0`을 명시하고 단일 range의 acquisition request
   count가 정확히 2회인지 확인한다. acquisition 전에 모두 실패하므로 `active=0`을
   acquired sibling cleanup 증거로 과장하지 않고 #697/N/A로 경계를 분리했다.
4. `Containers.MySQL8` 시작 전 `TestDB.MYSQL_V8`의 localhost fallback을 사용할 위험을
   제거했다. selector/configuration만 `Assumptions` skip하고 실제 container·driver·schema
   오류는 FAIL/PENDING으로 남긴다. credential과 full JDBC URL은 로그/README에 남기지 않는다.
5. fixture setup/teardown에서 schema drop 실패 시 pool이 누수될 수 있던 경계를 factory
   `catch`와 nested `finally`로 고정했다. proxy는 delegate `close()` 성공 뒤에만 active를
   반환 처리하고 cleanup failure를 primary failure에 suppressed로 보존한다.
6. MySQL 기본 isolation을 테스트하지 않으면서 기본값을 공개하던 문구를 제거했다. 설계의
   실행 계약은 명시적 `READ_COMMITTED`/`REPEATABLE_READ` 관찰로만 제한한다.

## 잔여 경계와 후속 이슈

- `SERIALIZABLE`의 locking read timing과 network/TCP reset·실제 cancellation은 이 slot의
  acceptance가 아니며 `N/A`로 기록한다.
- `rangeReader` 두-SELECT mutation fixture는 test-only 내부 주입 경로다. public overload가
  하나의 SELECT에 대해 공유 기준 데이터를 제공한다는 문구를 추가하지 않는다.
- acquired sibling/cancellation connection lifecycle은 Epic #659의 후속 [Issue #697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697),
  cross-driver raw JSON·median·grouped chart는 [Issue #690](https://github.com/bluetape4k/bluetape4k-exposed/issues/690)에서 다룬다.
- 기존 nightly MySQL job의 재시도 후 첫 실패가 사라진 경우 clean conformance PASS가
  아니라 `WATCH`로 분류하고 최초 로그를 조사한다.

## Gate 판정

- [x] P0=0
- [x] P1=0
- [x] SPW-01 — Issue/source/audience와 외부 MySQL 근거를 고정함
- [x] SPW-02 — fixture ownership, matrix, failure modes, rollback과 범위를 포함함
- [x] SPW-03 — 한국어 technical register와 machine token을 보존함
- [x] SPW-04 — production source, 선행 fixture, TestDB와 H2 evidence를 대조함
- [x] SPW-05 — Markdown read-back, terminology audit와 implementation gate를 확인함
- [x] production/API/ABI/catalog/workflow/manual 범위가 설계에 고정됨
- [x] Testcontainers credential·lifecycle·skip/FAIL 경계가 고정됨
- [x] deterministic barrier, retry count, rollback oracle이 수용 기준에 포함됨
- [x] EN/KO README evidence parity와 후속 issue 경계가 고정됨
- [x] 설계 문서 terminology audit와 `git diff --check`를 재실행할 수 있는 상태

다음 단계는 이 설계를 그대로 매핑한 구현 계획을 작성하고, 같은 6-perspective plan review에서
P0/P1이 없음을 확인한 뒤에만 RED 테스트를 추가하는 것이다.
