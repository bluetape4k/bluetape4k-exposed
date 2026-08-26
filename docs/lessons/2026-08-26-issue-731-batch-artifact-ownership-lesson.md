# Issue #731 batch artifact ownership 분리 교훈

## Context

`utils/batch` 단일 artifact가 coroutine core, JDBC adapter, R2DBC adapter와
checkpoint serialization을 함께 소유하고 있었다. 이번 Type A 변경은 기존
aggregator를 유지하면서 core/JDBC/R2DBC published ownership을 분리하고,
R2DBC의 JDBC reverse edge와 owner/version 경쟁 경계를 제거하는 작업이었다.

## Decision or Finding

1. **public contract와 adapter를 분리하되 ABI bridge를 남긴다.**
   `io.bluetape4k.batch.CheckpointJson`을 core의 public API로 두고, 기존
   `io.bluetape4k.batch.internal.CheckpointJson` descriptor와 JDBC/R2DBC
   constructor·mapper overload는 deprecated bridge로 유지했다. 기존 aggregator는
   세 child artifact를 `api`로 노출하고 historical JAR surface를 계속 제공한다.
2. **checkpoint는 owner/version CAS와 반환된 version을 함께 다룬다.**
   `saveCheckpointAndReturn`을 additive API로 도입하고, InMemory/JDBC/R2DBC가
   owner·version 조건과 affected-row=1을 확인한 뒤 새 실행 상태를 반환한다.
   core runner는 매 checkpoint 후 local `StepExecution`을 교체한다. 구현하지
   않은 custom repository는 ID-only fallback 없이 `UnsupportedOperationException`으로
   fail-closed하며, null·blank owner는 모두 DB 접근 전 `IllegalStateException`으로
   거부한다.
3. **R2DBC와 JDBC의 table/mapping은 각각 소유한다.**
   production source 공유 대신 test-only schema descriptor가 SQL type·길이,
   nullability/default, PK/FK, index predicate/functions, enum 및
   params/checkpoint storage contract를 비교한다. descriptor만 복사해 두지 않도록
   production `CheckpointJson` exact envelope와 양 adapter `toParamsHash`의
   ASCII·비ASCII UTF-8 digest vector를 직접 호출하는 회귀를 함께 둔다. 구조화된
   `R2dbcException` 식별자만 unique conflict 재조회 근거로 사용한다.
4. **benchmark provenance는 producer 경계에서 고정한다.**
   profile별 Gradle finalizer가 해당 profile의 새 raw report에만 sidecar를
   생성한다. 이미 존재하는 metadata는 덮어쓰지 않고 stale이면 실패한다.
   validator는 measured benchmark/finite score, source HEAD, runId, metric을
   함께 확인하며 문서 생성은 검증을 통과한 report만 사용한다.
5. **환경 선택을 task input으로 만든다.**
   `EXPOSED_TEST_DB`를 Gradle Test input으로 등록해 H2/PostgreSQL/MySQL_V8
   전환에서 stale output을 재사용하지 않게 했다. Testcontainers 검증은 Colima
   Docker socket을 명시하고 DB별로 직렬 실행한다.

## Outcome

- `batch-core`, `batch-jdbc`, `batch-r2dbc`, compatibility aggregator가 독립
  Gradle project와 published-style fixture로 구성됐다.
- core/JDBC/R2DBC API baseline, child dependency graph, POM/module metadata,
  legacy bytecode bridge가 보존됐다.
- owner mismatch, stale version, unclaimed execution, cancellation cleanup,
  concurrent Step creation, schema drift, R2DBC non-unique negative path가
  `bluetape4k-assertions` 기반 테스트로 고정됐다.
- EN/KO module manual에 aggregator migration, BOM 좌표, Jackson 3/custom
  allowlist, `saveCheckpointAndReturn`, consumer fixture 명령을 기록했다.

## Verification

- 전체 H2 batch 테스트: core 195, JDBC 39(4 skipped), R2DBC 35(2 skipped),
  aggregator schema parity 5; failures/errors 0.
- `CheckpointJson` exact envelope와 JDBC/R2DBC production hash의
  ASCII·비ASCII UTF-8 vector 회귀가 각각 통과했다.
- PostgreSQL R2DBC, MySQL JDBC/R2DBC Testcontainers targeted 실행 성공.
- `detekt`, `checkProductionAbi`, Gradle metadata/POM/downstream consumer,
  격리 Gradle 5개 및 Maven consumer fixture 모두 PASS.
- Python benchmark sidecar 14 tests, H2 raw report 4개 sidecar validation,
  `generateBenchmarkDocs`, actionlint, manual/diagram contract, `git diff --check`
  PASS.
- batch production source `println`, `System.out/err`, `printStackTrace` 및
  R2DBC의 JDBC import 0건.

## Future Guidance

- custom repository migration 예제와 legacy JDBC/R2DBC constructor·mapper
  runtime probe를 다음 compatibility hardening issue로 추가한다.
- PostgreSQL active Job partial unique index는 애플리케이션 migration에
  명시하고, sidecar에는 dirty-tree/hash provenance를 검토한다.
- aggregator fat JAR 중복, global Kover/Nightly aggregate 판정, NaN/Infinity
  파일 fixture와 모듈별 artifact 집계를 별도 P2/P3 작업으로 추적한다.
- PR 단계에서는 commit 후 exact HEAD로 CI·review·mergeability·metadata를
  다시 읽는다. merge는 fresh 명시 승인이 없으면 수행하지 않는다.

## SPW 체크리스트

- [x] **SPW-01** context, 결정, 결과, 검증, 후속 조치를 분리했다.
- [x] **SPW-02** ownership·ABI·CAS·benchmark·환경 선택의 원인을 설명했다.
- [x] **SPW-03** 명령과 수치를 재현 가능한 형태로 기록했다.
- [x] **SPW-04** local PASS와 남은 P2/P3 및 hosted delivery 경계를 구분했다.
- [x] **SPW-05** EN/KO manual과 implementation review의 용어를 일치시켰다.
