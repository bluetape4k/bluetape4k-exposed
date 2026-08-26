# Issue #731 batch artifact ownership 설계 7-Tier 통합 검토

## 검토 범위와 기준

- 대상 issue: [#731](https://github.com/bluetape4k/bluetape4k-exposed/issues/731)
- 대상 설계: `docs/superpowers/specs/2026-08-26-issue-731-batch-artifact-ownership-design.md`
- 기준 ref: `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
- workflow: Type A `bluetape-full-feature`, Step 2-R 설계 검토
- 적용 기준: 7-Tier, `$bluetape-kotlin-patterns`,
  `io.bluetape4k.assertions`, SPW-01..05
- 검토 경계: 설계와 기준선만 읽었으며 source·GitHub·release 상태는 변경하지
  않았다. 구현·실행 결과는 Step 3-R 이후의 별도 증거다.

기준선은 `./gradlew :bluetape4k-exposed-batch:test --no-configuration-cache
--no-daemon --console=plain`으로 다시 확인했다. 결과는 379 tests, 7 skipped,
H2/PostgreSQL/MySQL 성공, `BUILD SUCCESSFUL`이다. 이 결과는 source split 전
회귀 기준이며 새 child module의 통과를 뜻하지 않는다.

## 관점별 결과

| 관점 | 결과 | P0 | P1 | P2 | 증거와 경계 |
|---|---|---:|---:|---:|---|
| Performance | PASS | 0 | 0 | 0 | ABI expected set, fail-closed scan, H2 benchmark raw JSON/metadata, hot-path와 round-trip 보존 조건을 확인했다. 독립 replacement 결과를 사용했다. |
| Stability | PASS | 0 | 0 | 0 | `runInterruptible` lock, cancellation suppressed cause, owner CAS, schema/race oracle, Nightly 직렬화와 rollback을 확인했다. 독립 rerun 결과다. |
| Security/Public API | PASS | 0 | 0 | 0 | stable `CheckpointJson`와 조건부 bridge, owner fail-closed, null/blank owner, name validation, dependency/Jackson 경계를 확인했다. 독립 replacement 결과다. |
| Operator/Operations | PASS (leader fallback) | 0 | 0 | 0 | native replacement은 제한시간 초과로 실패했지만 leader가 최신 설계의 Docker preflight, DB 직렬화, actionlint, Kover/POM, 경로 시뮬레이션, Nightly conclusion, benchmark freshness를 재검토했다. |
| Developer/API | PASS (leader fallback) | 0 | 0 | 0 | native replacement은 제한시간 초과로 실패했지만 leader가 최신 설계의 Kotlin 규칙, module graph, ABI bridge, assertions, TDD와 consumer profile을 재검토했다. |
| User/Caller | PASS after repair (leader rerun) | 0 | 0 | 0 | 독립 호출자 review가 발견한 P1/P2를 모두 설계에 반영했다. native rerun은 결과 없이 종료되어 leader가 최신 문서의 manual, migration, runtime smoke, publish rollback을 재확인했다. |

독립 관점의 원래 결과는 성급히 PASS로 덮지 않았다. 호출자 review의
REQUEST CHANGES 결과와 P1/P2 근거를 먼저 기록하고 설계를 보수한 뒤 leader
rerun으로 해소 여부를 확인했다. 운영·개발자 replacement의 독립 결과 부재는
fallback 경계로 명시하며, implementation review에서는 같은 두 관점을 다시
독립적으로 요청한다.

## 초기 발견과 수정

| 관점 | 초기 발견 | 설계 수정 | 최종 상태 |
|---|---|---|---|
| Performance | ABI inventory, fail-open scan, benchmark 측정·신선도 증거가 약함 | 35→38 expected set, negative scan, finite raw JSON/sidecar metadata, current `sourceHead`·`runId`·`pending` gate를 추가 | P0/P1=0 |
| Stability | non-interruptible lock, cancellation cleanup, DB race, Nightly 병렬성의 경계가 불명확함 | `runInterruptible`/`finally`, suppressed cleanup cancellation, legacy schema oracle, sequential DB와 `max-parallel: 1`, rollback 중단 기준을 추가 | P0/P1=0 |
| Security/Public API | internal `CheckpointJson`, owner default 위임, null owner와 name 경계, Jackson optional runtime이 불명확함 | stable public API와 조건부 bridge, owner/version CAS·fail-closed·null/blank 거부, 모든 public name 경계, Jackson 유무 consumer profile을 추가 | P0/P1=0 |
| Operations | Colima/Docker preflight, sequential DB command, actionlint, changed-path, Kover/POM 검증이 빠짐 | 구체적인 preflight·순차 loop·actionlint·Kover XML·POM/경로 검사를 추가 | P0/P1=0 (fallback) |
| User/Caller | manual manifest/generated inventory/release link 체인과 migration 좌표가 추상적이고 runtime smoke·publish 이후 downgrade가 빠짐 | child별 manifest와 EN/KO manual·source/test path·release diagram, 실제 Maven/Gradle 좌표·BOM·CheckpointJson/Jackson 예제, 4 consumer fixture 명령, corrective delivery/downgrade를 추가 | P0/P1=0 (repair 후) |

## 통합 설계 판정

### Module/API 경계

`batch-core`가 Exposed/JDBC/R2DBC를 끌어오지 않고, JDBC와 R2DBC가 각자의
table·mapper를 소유하며, 기존 aggregator가 `api(project(...))`로 호환성을
보존하는 방향은 ownership 문제를 직접 해결한다. R2DBC의 JDBC import 금지와
test-only schema parity는 reverse edge와 schema drift를 별도 gate로 만든다.

`CheckpointJson`은 public adapter 생성자에 노출된 실제 API라는 사실을 반영해
stable package로 승격한다. baseline descriptor가 있을 때만 deprecated bridge와
constructor overload를 한 minor line 동안 유지하고, 없을 때는 ABI artifact와
migration 문서에 판단을 남긴다. 이는 ABI suppression으로 삭제를 숨기지 않는
조건과 일치한다.

### Concurrency와 trust boundary

`ReentrantLock` 대기를 `runInterruptible`로 감싸고 lock 보유 구역을
memory-only 연산으로 제한하는 계약은 coroutine cancellation과 virtual-thread
환경에서 재현 가능한 정책이다. owner-aware checkpoint는 ID-only legacy escape
hatch와 분리되고, owner/version CAS·affected-row 1건·null/blank owner 거부가
InMemory/JDBC/R2DBC에 공통 적용된다. STOPPED cancellation의 primary 예외와
cleanup suppressed cause를 함께 보존하는 조건도 acceptance에 연결되어 있다.

### 운영·문서·소비자 경험

Docker/Testcontainers는 Colima 상태, Docker context/info, socket을 확인한 뒤
H2→PostgreSQL→MySQL_V8을 순차 실행한다. CI/Nightly는 job-level serialization과
terminal job conclusion mapping을 요구하므로 단일 Gradle mutex를 분산 runner
보장으로 오해하지 않는다.

manual은 Gradle inventory에 나타나는 네 artifact를 모두 manifest에 등록하고,
EN/KO child 문서와 release-pinned source link를 실제 파일에 묶는다. export와
두 validator를 순서대로 실행하므로 새 child가 manifest에서 빠져 release tree가
깨지는 실패를 조기에 차단한다. 선택 artifact 예제는 BOM 좌표를 기준으로 하며,
Jackson 없는 custom strategy와 Jackson 3 runtime을 별도 fixture에서 실행한다.

## P0/P1/P2/P3 처분

- P0: 0 — data loss, deadlock, trust-boundary bypass, compatibility 삭제를
  허용하는 설계 공백이 없다.
- P1: 0 — 초기 ABI/scan/benchmark/lock/owner/ops/manual/migration 결함을
  모두 설계와 acceptance gate에 반영했다.
- P2: 0 — caller가 지적한 runtime smoke, release rollback/downgrade,
  fixture 명령까지 추가해 구현 전 남은 모호성을 제거했다.
- P3: 0 — 현재 문서에서 추적만 필요한 잔여 품질 이슈가 없다.

## Step 2-R DoD

- [x] 독립 7-Tier 관점 결과와 fallback provenance를 기록했다.
- [x] 설계 수정 후 P0/P1=0을 확인했다.
- [x] baseline 379 tests/7 skipped와 H2/PostgreSQL/MySQL 결과를 fresh output으로
  기록했다.
- [x] Korean terminology audit가 통과했다.
- [x] `git diff --check`가 통과했다.
- [x] source mutation 전이며 변경은 승인된 feature worktree의 설계 문서뿐이다.
- [x] 구현 전 review verdict는 `Step 2-R: PASS`다.
- [ ] child module 구현, 새 테스트, ABI/POM/Kover/CI/Nightly/manual 실행 증거는
  Step 3-R 이후에 수집한다.

## Writer quality gate

- [x] **SPW-01** issue URL, 기준 ref, source ledger와 baseline을 연결했다.
- [x] **SPW-02** 범위·비범위, ownership, compatibility, rollback, acceptance와
  검증 명령을 명시했다.
- [x] **SPW-03** Korean technical prose를 사용하고 API·Gradle task·machine token은
  원문을 보존했다.
- [x] **SPW-04** 각 결론을 설계 section, receipt evidence, baseline command 또는
  구체적인 future verification command에 연결했다.
- [x] **SPW-05** terminology audit, `git diff --check`, 7-Tier P0/P1 gate와
  implementation handoff 경계를 고정했다.

## 통합 결론

`Step 2-R: PASS` — 설계는 implementation plan과 Step 3-R로 진행할 수 있다.
단, 이 PASS는 설계 품질 gate이며 child source 이동이나 새 artifact publish의
성공을 의미하지 않는다. 다음 단계는 실행계획 작성·6관점 계획 검토·TDD RED
고정이며, 새 source mutation은 그 gate 이후에만 허용한다.
