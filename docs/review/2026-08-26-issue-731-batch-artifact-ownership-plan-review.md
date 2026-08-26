# Issue #731 batch artifact ownership 분리 실행 계획 검토

## 문서 상태

- Issue: [#731](https://github.com/bluetape4k/bluetape4k-exposed/issues/731)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
- 대상 설계: `docs/superpowers/specs/2026-08-26-issue-731-batch-artifact-ownership-design.md`
- 대상 계획: `docs/superpowers/plans/2026-08-26-issue-731-batch-artifact-ownership-plan.md`
- 선행 검토: `docs/review/2026-08-26-issue-731-batch-artifact-ownership-spec-review.md`
- 기준선: `:bluetape4k-exposed-batch:test` 379 tests, 7 skipped, H2/PostgreSQL/
  MySQL 통과, `BUILD SUCCESSFUL`
- 검토 상태: Step 3-R 계획 검토 완료, source mutation 전

이 문서는 Type A 구현 전에 실행 계획이 설계·검증·운영 경계를 빠짐없이
실행하는지 확인한다. 구현 결과나 hosted CI를 이미 통과했다고 주장하지 않는다.

## 검토 모델·범위·경계

6개 관점을 7-Tier로 읽었다.

1. public API·source ownership
2. dependency·build graph
3. nullability·exception·validation
4. concurrency·transaction·persistence
5. test·fixture·coverage·static scan
6. docs·manual·migration·consumer ergonomics
7. CI/Nightly·release·rollback·observability

검토 관점은 performance, security, stability, operations, developer/API,
caller compatibility다. source·GitHub·workflow mutation은 수행하지 않았다.

## 관점별 결과

| 관점 | 검토 결과 | P0 | P1 | P2/P3 | 근거와 경계 |
|---|---|---:|---:|---:|---|
| Performance | PASS | 0 | 0 | 0/0 | bounded deterministic race, repeated checkpoint version refresh, benchmark sidecar freshness, stale/pending 거부, throughput SLA 비주장 |
| Security | PASS | 0 | 0 | 0/0 | registry allowlist, raw payload/className/params redaction characterization, fail-closed owner/version/name validation, dependency negative scan |
| Stability | PASS | 0 | 0 | 0/0 | interruptible `ReentrantLock`, cancellation suppression, schema parity, sequential DB, Nightly serialization, rollback |
| Operations/release | PASS | 0 | 0 | 3/0 | isolated publication/Maven smoke, stable release baseline, manifest/diagram export·check, fail-closed Nightly set, evidence receipt 경로 |
| Developer/API | PASS (수정 후) | 0 | 0 | 0/0 | initial independent review의 P1 3건(aggregator ABI, `CheckpointJson` descriptor, checkpoint version lifecycle)을 plan/spec에 반영; builder init, assertions/tester mapping, source-set wiring 추가 |
| Caller compatibility | PASS (수정 후) | 0 | 0 | 0/0 | initial independent review의 P1 3건(stable manual baseline, fixture provenance/Maven, generated manifest)을 plan/spec에 반영; selective profile과 rollback을 명시 |

operations 관점의 P2 3건은 구현 단계에서 exact command와 expected matrix
parser를 실행하면 해소되는 재현성 개선 사항이며 source mutation 차단 사유는
아니다.

## 초기 P1 발견과 반영

| 발견 | 반영한 결정 | 반영 위치 |
|---|---|---|
| aggregator `api(project(...))`만으로는 기존 JAR 실효 ABI를 보장하지 않음 | old aggregator compile/runtime fixture, child JAR inventory, `checkProductionAbi`, non-empty baseline, suppression 금지 | 설계 Compatibility/Dependency, 계획 T2/T8 |
| `CheckpointJson` constructor만 보존하면 factory·mapper descriptor가 깨질 수 있음 | interface·`Companion.jackson3()`·두 mapper·JDBC/R2DBC constructor symbol ledger와 bridge fixture | 설계 Compatibility, 계획 T2 |
| checkpoint CAS가 version을 증가시키면 runner가 stale execution을 반복 전달할 수 있음 | additive `saveCheckpointAndReturn`, 반환 `StepExecution`으로 runner local 갱신, adapter transaction CAS | 설계 Trust boundary, 계획 T2/T3 |
| builder constructor가 이름 검증 경계에서 누락됨 | `BatchJobBuilder`/`BatchStepBuilder` init 즉시 `requireValidBatchName`, control-character·원문 미노출 test | 설계 Trust boundary, 계획 T3 |
| generic assertion 요구가 기존 tester 재사용을 보장하지 않음 | `MultithreadingTester`, `StructuredTaskScopeTester`, `SuspendedJobTester`와 `io.bluetape4k.assertions` matcher 매핑 | 설계 Test strategy, 계획 T3 |
| stable `1.12.1` release tree에 child source path가 없음 | child entry `releaseStatus: develop-only`, stable relative link는 기존 `utils/batch`만 사용, 다음 release에서 exact pin | 설계 Registration, 계획 T6/T8 |
| local publication이 stale artifact를 선택할 수 있음 | `publishPublicationValidation` + temporary repository + `--offline` Gradle 4개 + Maven 1개 + sourceHead provenance | 설계 Fixture, 계획 T1/T8 |
| generated manifest·diagram 검증 명령 누락 | `export_manifest.rb`, exporter test, release diagram `--check`/contract test, read-back | 계획 T8 |

## 7-Tier acceptance read-back

| Tier | 계획에서 확인한 수용 기준 |
|---:|---|
| 1 | nested core/JDBC/R2DBC와 aggregator ownership, package·public class 보존 |
| 2 | child-only dependency, core POM negative scan, benchmark/test source-set wiring |
| 3 | `requireValidBatchName`, null/blank owner, exception·redaction·legacy escape hatch |
| 4 | interruptible lock, owner/version CAS, checkpoint 반환 상태, schema parity, cancellation cleanup |
| 5 | TDD RED/GREEN, `bluetape4k-assertions`, 기존 concurrency tester, ABI/JAR/POM/Kover, 5 consumer fixture |
| 6 | BOM unversioned alias, EN/KO manual, develop-only stable baseline, manifest/diagram, migration/rollback |
| 7 | Colima/Docker preflight, H2→PostgreSQL→MySQL_V8, actionlint, changed-path, Nightly conclusion, exact-head PR gate |

## 검토 증거와 한계

- 선행 독립 developer/API review는 수정 전 P1=3을 발견했고, 수정 목록을
  다시 확인했다.
- 선행 독립 caller review는 수정 전 P1=3을 발견했고, stable manual·fixture·
  manifest 수정을 다시 확인했다.
- 선행 독립 operations review는 P0/P1=0, P2=3을 보고했으며 P2를 exact
  command와 fail-closed parser 요구로 반영했다.
- 수정 후 native 재검토 시도는 bounded window에서 응답하지 않아 중단했다.
  따라서 수정 후 6개 관점의 최신 판정은 leader read-only fallback으로
  기록했으며, 구현·runtime·hosted CI 증거와 혼동하지 않는다.
- fallback evidence:
  `.bluetape/issue-731-plan-performance-fallback-result.json`,
  `.bluetape/issue-731-plan-security-fallback-result.json`,
  `.bluetape/issue-731-plan-stability-fallback-result.json`,
  `.bluetape/issue-731-plan-ops-fallback-result.json`,
  `.bluetape/issue-731-plan-developer-fallback-result.json`,
  `.bluetape/issue-731-plan-caller-fallback-result.json`
- `git diff --check`와 한국어 용어 검사는 계획·설계·review 세 문서에서
  `findings=0`으로 통과했다.

## Step 3-R DoD

- [x] 설계와 실행 계획의 ownership·ABI·CAS·fixture·manual·CI·rollback
  경계가 일치한다.
- [x] 초기 P1을 모두 계획·설계에 반영했고 최신 leader fallback에서 P0/P1=0을
  확인했다.
- [x] 7-Tier 여섯 관점 표와 검토 경계를 기록했다.
- [x] SPW-01~05 및 terminology audit, `git diff --check`를 통과했다.
- [ ] source implementation, tests, ABI/POM/Kover, Testcontainers runtime,
  hosted CI, PR metadata는 구현 후 검증 대상이다.
- [ ] Step 6-R 구현 review, Lore commit, PR exact-head evidence는 후속 단계다.

## 결론

Step 3-R 계획 게이트는 `PASS (leader fallback; P0=0, P1=0)`이다. 다만
수정 후 native rerun이 응답하지 않았다는 검토 경계를 보존한다. 다음 단계는
TDD RED fixture와 consumer/ABI 경계부터 시작하는 source implementation이며,
구현 완료 전에는 PR·merge를 수행하지 않는다.

## SPW 체크리스트

- [x] **SPW-01** 독자가 문서 상태와 대상 범위를 먼저 확인할 수 있다.
- [x] **SPW-02** 6개 관점과 7-Tier 기준, PASS/한계를 명시했다.
- [x] **SPW-03** 명령·경로·fixture·rollback과 acceptance 증거를 구체화했다.
- [x] **SPW-04** P0/P1과 구현/runtime 미검증 경계를 분리했다.
- [x] **SPW-05** 설계·계획·검토 문서의 용어·경로·좌표를 교차 확인했다.
