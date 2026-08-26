# Issue #731 batch artifact ownership 분리 구현 검토

## 문서 상태

- Issue: [#731](https://github.com/bluetape4k/bluetape4k-exposed/issues/731)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 브랜치: `refactor/batch-artifact-ownership`
- 기준 ref: `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
- 대상 계획: `docs/superpowers/plans/2026-08-26-issue-731-batch-artifact-ownership-plan.md`
- 대상 구현: `utils/batch/core`, `utils/batch/jdbc`, `utils/batch/r2dbc`,
  compatibility aggregator 및 benchmark/CI/문서 계약
- 검토 유형: Step 6-R source·test·ABI·runtime·docs·CI 검토
- 검토 경계: 이 문서는 local dirty worktree의 구현 검토이며 hosted exact-head
  CI, PR review, merge를 통과했다고 주장하지 않는다.

## 독립 검토 provenance

각 검토자는 leader와 독립적으로 source를 읽고 파일을 수정하지 않았다.

| 관점 | 모델·역할 | 추론 | 결과 |
|---|---|---|---|
| Operations/docs | `gpt-5.6-sol`, `architect (Oracle)` | high | P0=0, P1=0, P2=3, P3=0, WATCH |
| Performance | `gpt-5.6-luna`, `code-reviewer` | max | 초기 P1=5와 보완 후 P1=2를 발견했으며, profile finalizer·schema descriptor·Decimal/codec/hash 보완 후 최종 P0=0, P1=0, P2=4를 확인함 |
| Security/ABI/caller | `gpt-5.6-luna`, `verifier` | max | P0=0, P1=0, P2=3, delivery PENDING/PARTIAL |

Performance 검토의 P1은 다음 수정으로 닫혔다.

1. R2DBC unique conflict 판정을 `R2dbcException`의 PostgreSQL SQLSTATE
   `23505` 또는 MySQL/MariaDB error code `1062`로 제한하고 애플리케이션 예외
   메시지 negative test를 추가했다.
2. benchmark task별 profile finalizer를 만들고, 기존 `metadata.json`은 재작성하지
   않으며 stale sidecar는 fail-closed하도록 writer와 회귀 테스트를 보강했다.
3. schema descriptor가 실제 benchmark source 경로와 일치하도록 문서를 고쳤다.
4. column SQL type/길이/enum, nullability/default, database-generated/PK/FK,
   index unique/name/type/filter/functions, params/checkpoint storage contract를
   비교하고 type drift 회귀 테스트를 추가했다.
5. `EXPOSED_TEST_DB`를 Gradle `Test` task input으로 등록해 DB matrix 전환 시
   stale task output을 재사용하지 않도록 했다.
6. `DecimalColumnType`의 precision/scale과 versioned `CheckpointJson`/
   `Map.toParamsHash` storage contract를 descriptor에 포함하고, default·nullable·
   storage·index·foreign-key·codec/hash별 drift 회귀를 추가했다.
7. production ABI inventory를 38개로 올린 build logic와 CI의 `38/38` 검증 문자열을
   동기화했다.
8. production `CheckpointJson`의 exact typed-envelope와 JDBC/R2DBC
   `toParamsHash`의 ASCII·비ASCII UTF-8 digest vector를 직접 호출하는 회귀를
   추가해 codec/hash drift를 실제 구현에서 탐지하도록 했다.
9. owner-aware checkpoint의 null·blank owner를 모두 DB 접근 전
   `IllegalStateException`으로 거부하고 InMemory/JDBC/R2DBC whitespace 회귀를
   추가해 설계의 예외 타입 계약을 고정했다.

## 7-Tier 결과

| Tier | 상태 | 구현 근거 | 검토 경계 |
|---|---|---|---|
| 1 Source/ownership | PASS | core·JDBC·R2DBC source set이 분리되고 R2DBC가 자체 table/mapper를 소유함 | compatibility aggregator JAR에는 의도적인 child class 중복이 남음(P2) |
| 2 Dependency/build | PASS | child dependency 방향, BOM alias, aggregator API, R2DBC reverse-edge negative scan | hosted dependency graph는 PR exact head에서 재확인 필요 |
| 3 API/validation | PASS | public `CheckpointJson`, deprecated internal bridge, 이름·owner 검증, null·blank owner의 `IllegalStateException`, fail-closed 기본 CAS | 기존 custom repository runtime fixture는 P2 |
| 4 concurrency/persistence | PASS | owner/version CAS, affected-row=1, update 후 재조회, deterministic Step race, cancellation suppression | 외부 partial unique index 설치는 애플리케이션 책임(P2) |
| 5 test/ABI | PASS | 195 core, 39 JDBC(H2, 4 skipped), 35 R2DBC(H2, 2 skipped), schema parity/drift 5, ABI/publication/consumer gate | hosted matrix와 legacy mapper runtime은 별도 확인 필요 |
| 6 docs/migration | PASS | EN/KO core·JDBC·R2DBC migration, Jackson 3/custom allowlist, fixture 명령, manual inventory parity | 신규 manual은 현재 develop-only release ref |
| 7 CI/runtime/release | PASS (local) | daily/nightly path·Python gate·Kover 파일 검증, actionlint, H2 raw benchmark/sidecar | PostgreSQL/MySQL benchmark, hosted exact-head CI/PR metadata는 PENDING |

## 검증 증거

### 구현·테스트

- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-batch-core:test :bluetape4k-exposed-batch-jdbc:test :bluetape4k-exposed-batch-r2dbc:test :bluetape4k-exposed-batch:test --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon --max-workers=1 --console=plain`
  → `BUILD SUCCESSFUL`; XML 합계 core 195, JDBC 39(4 skipped), R2DBC
  35(2 skipped), aggregator schema parity 5, failures/errors 0.
- `CheckpointJsonTest` exact envelope vector와 JDBC/R2DBC production
  `toParamsHash` ASCII·비ASCII UTF-8 digest vector → 각각 `BUILD SUCCESSFUL`;
  digest는 `3befc559...b74d1fc`, `ce97c167...12ab3b`와 일치한다.
- PostgreSQL R2DBC targeted, MySQL JDBC targeted, MySQL R2DBC targeted
  Testcontainers 실행 → 각각 `BUILD SUCCESSFUL`. 최초 PostgreSQL R2DBC의
  Docker 탐색 실패는 Colima socket을 명시한 재실행에서 해소했다.
- `python3 -m unittest discover -s scripts/batch -p 'test_*.py'`
  → 14 tests, `OK`.
- `python3 scripts/batch/validate_benchmark_sidecars.py utils/batch/build/reports/benchmarks --source-root . --expected-head b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
  → `benchmark-sidecars: PASS reports=4`.
- H2 R2DBC 새 raw report
  `utils/batch/build/reports/benchmarks/h2R2dbc/2026-08-27T00.15.15.144258/`
  와 profile 전용 finalizer sidecar → 단일 report validator PASS. 기존 report
  sidecar의 sourceRef/metric은 변경되지 않았다.
- `./gradlew :bluetape4k-exposed-batch:generateBenchmarkDocs ...`
  → sidecar validation PASS, `BUILD SUCCESSFUL`.

### 정적·계약 검증

- `./gradlew detekt ...` → `BUILD SUCCESSFUL`.
- `./gradlew checkProductionAbi ...` → `BUILD SUCCESSFUL`.
- production ABI report → `modules=38/38`, `baselines=38/38`,
  `actualDumps=38/38`, `emptyBaselines=0`.
- `ruby scripts/publication/validate_module_metadata.rb` → failures=0, files=39,
  variants=80, dependencies=931.
- `ruby scripts/publication/validate_poms.rb` → failures=0, files=39,
  dependencies=11356, maven_models=39.
- `ruby scripts/publication/validate_downstream_consumer.rb` → publications=39,
  libraries=38, fixtures=1, compileTasks=77, runtimeTasks=77.
- `bash scripts/batch/validate_consumer_fixtures.sh` → 다섯 Gradle fixture와
  Maven JDBC fixture compile/runtime 및 offline 재실행 PASS, sourceHead는
  기준 HEAD와 일치.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  → PASS.
- manual export/validation과 관련 Ruby contract test → manifest aligned,
  6 runs/11 assertions, manual contract 9 runs/44 assertions, migration parity
  6 runs/16 assertions, diagram contract 5 runs/19 assertions PASS.
- `git diff --check` → PASS.
- `rg` source scan → batch production의 `println`, `System.out`, `System.err`,
  `printStackTrace` 0건; R2DBC production의 JDBC import 0건.

## 잔여 P2/P3 및 PENDING

- P2: 기존 custom `BatchJobRepository` 구현체가 새
  `saveCheckpointAndReturn`에서 의도한 `UnsupportedOperationException`을
  받는 runtime fixture와 legacy JDBC/R2DBC constructor·mapper 실행 fixture가
  아직 없다. 정적 ABI와 `CheckpointJson` legacy binary probe는 통과했다.
- P2: PostgreSQL active Job partial unique index는 application-owned migration
  전제이며 adapter table에 자동 생성하지 않는다. 배포자는 KDoc/테스트의 DDL을
  migration에 반영해야 한다.
- P2: sidecar는 commit HEAD를 기록하지만 dirty worktree diff나 report hash를
  provenance에 포함하지 않는다. 현재 report는 dirty source에서 생성된 local
  evidence다.
- P2: schema parity descriptor의 versioned contract 문자열은 test-local golden
  constant이며 field별 mutation 범위는 더 넓힐 수 있다. 다만 production codec/hash
  자체는 exact behavior vector로 직접 보호한다.
- P2: aggregator fat JAR의 child class 중복, global Kover/Nightly aggregate의
  fail-open/aggregate-only 판정, benchmark raw JSON provenance는 별도 hardening
  대상이다.
- P3: NaN/Infinity 전용 파일 fixture, close throwable proxy 단언, 모듈별 JUnit
  artifact 집계, 실제 release ref manual 검증은 보강 여지가 있다.
- PENDING: PR 생성 전에는 hosted exact-head CI·review·mergeability·PR metadata가
  없으며, PostgreSQL/MySQL raw benchmark 수치는 아직 생성하지 않았다.

## Step 6-R DoD

- [x] core/JDBC/R2DBC ownership과 compatibility aggregator가 설계·계획과 일치한다.
- [x] owner/version CAS, cancellation, schema parity, R2DBC 구조화 예외 판정이
  테스트와 함께 fail-closed다.
- [x] `bluetape4k-assertions` matcher와 `bluetape4k-kotlin-patterns`를 적용했고
  source scan에서 `println`을 제거했다.
- [x] 7-Tier 및 operations/performance/security·ABI 독립 검토 provenance와
  P0/P1 결과를 기록했다.
- [x] EN/KO migration manual, benchmark sidecar/finalizer, consumer fixture,
  ABI/publication/manual 계약을 검증했다.
- [ ] commit·push·PR exact-head CI/review 및 fresh merge approval은 다음 gate다.

## 결론

구현 source gate는 `PASS (P0=0, P1=0)`이다. local 검증과 독립 검토에서
merge를 막는 P0/P1은 남지 않았지만, 위 P2/P3와 hosted delivery 증거는 PR
단계에서 별도로 추적한다. 다음 단계는 Lore commit, branch push, Korean PR
생성 후 exact-head CI/review를 확인하는 것이며, merge는 fresh 명시 승인이
있을 때만 수행한다.

## SPW 체크리스트

- [x] **SPW-01** 상태·대상·검토 경계를 문서 앞부분에 명시했다.
- [x] **SPW-02** 7-Tier와 6-lens 독립 검토 결과를 P0/P1/P2/P3로 분리했다.
- [x] **SPW-03** 명령·경로·fixture·환경과 실제 결과를 기록했다.
- [x] **SPW-04** local PASS와 hosted/dirty/PENDING 한계를 혼동하지 않았다.
- [x] **SPW-05** EN/KO manual, benchmark path, artifact 좌표, API bridge 용어를
  교차 확인했다.
