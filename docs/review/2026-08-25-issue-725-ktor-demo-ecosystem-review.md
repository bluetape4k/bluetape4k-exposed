# Issue #725 Ktor 예제 생태계 정리 설계 검토

## 검토 범위와 근거

- 대상 artifact: `docs/superpowers/specs/2026-08-25-issue-725-ktor-demo-ecosystem-design.md`
- 기준 코드: `origin/develop`의 `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 대상 모듈: `examples/ktor-exposed-demo`
- 적용 기준: `bluetape-workflow` Type A, `bluetape-kotlin-patterns`,
  Type A six-perspective review, `bluetape-writer` SPW-01~05

세 개의 native review lane을 독립 검토로 요청했으나 90초 이상 startup 응답이
없어 main-session fallback으로 대체했다. 각 렌즈를 같은 문서를 다시 읽고 현재
source anchor에 대조했으며, 이 대체 사실과 liveness 근거를 receipt/lesson에
남긴다. 구현 전 설계 검토이므로 코드 수정은 수행하지 않았다.

## Six-lane 결과

| 우선순위 | 렌즈 | 근거 | 조치 | 상태 |
|---|---|---|---|---|
| P0/P1 | Performance | UUID 생성은 오류 응답·startup/shutdown 실패 경로에 한정되고, assertion/logger 변경은 DB round trip을 추가하지 않는다. | benchmark는 비적용으로 기록하고 compile·대상 테스트로 allocation/계약 회귀를 확인한다. | 통과 |
| P0/P1 | Stability | `runKtorExposedDemo`의 cleanup·CancellationException 흐름은 변경하지 않고 sink의 logger 호출만 바꾼다. PostgreSQL task는 기존 `maxParallelForks=1`을 유지한다. | logger 주입 단위 테스트와 H2/PostgreSQL 순차 테스트를 계획에 포함한다. | 통과 |
| P0/P1 | Security | `DemoDiagnostic` allowlist만 조립하며 예외·raw payload·비밀값을 추가하지 않는다. UUIDv7은 시간 비트를 포함하므로 토큰이 아님을 문서에 명시해야 한다. | README와 테스트에서 correlation ID의 비밀성·재시도 용도 아님을 유지하고 UUIDv7 노출은 식별자 계약으로 한정한다. | 통과 |
| P0/P1 | Operator/Ops | `println`/`System.err` 직접 호출을 제거하면 출력 목적지는 backend 설정에 따른 logger가 된다. 기존 README의 “stderr” 단정은 logger 진단 레코드로 정정해야 한다. | 두 README의 문구를 “application logger”로 맞추고 allowlist 한 줄 포맷을 유지한다. | 통과(수정 반영) |
| P0/P1 | Developer/API | `Uuid.V7.nextId()`는 저장소에서 이미 검증된 helper이고 `shouldBeSameInstanceAs`는 참조 동일성 의도를 보존한다. `java.util.UUID`는 입력 parser와 domain type에 남는다. | 별도 dependency/abstraction을 추가하지 않고 함수형 logger 주입만 둔다. public API·예외 타입은 유지한다. | 통과 |
| P0/P1 | User/Caller | HTTP `503` response의 canonical UUID 문자열과 sanitized response-to-diagnostic 연결은 유지되어야 한다. 두 README locale은 같은 계약을 설명해야 한다. | UUIDv7을 명시하되 canonical 36자·non-token 제약을 함께 적고 locale parity를 검증한다. | 통과 |

## 통합 판정

- 중복 finding은 “직접 출력 제거 + logger backend 목적지 문서 정정”으로 통합했다.
- UUIDv7의 시간 비트는 공개 correlation ID에서 허용하는 식별자 특성으로
  분류했다. 인증·재시도·이벤트 재발행 토큰으로 사용하지 않는다는 기존 계약을
  유지하므로 P1 보안 결함은 아니다.
- `java.util.UUID` 자체 제거를 요구하지 않았다. 입력 canonicalization과
  `UUID`-typed Exposed repository key가 실제 책임 경계이기 때문이다.
- CI/workflow/module registration은 변경 대상이 아니며, 새 module·artifact·DB
  schema가 없어 해당 gate는 N/A다.
- CHANGELOG/release note는 예제 내부 refactor이고 release-facing API 변경이
  없어 N/A로 둔다. PR body에는 Issue #725와 fresh verification을 연결한다.

## SPW / Kotlin checklist

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 대상 artifact, reader, source ref, exact symbols, unresolved logger destination을 기록했다. |
| SPW-02 | PASS | 범위·선택·대안·실패 모드·호환성·검증·DoD·비목표를 포함했다. |
| SPW-03 | PASS | 한국어 기술 문체와 동일 용어(`correlationId`, `canonical`, `logger`)를 사용했다. |
| SPW-04 | PASS | source anchor와 README 계약을 기준 commit에 대조했다. |
| SPW-05 | PASS | 문서를 read-back했고 terminology/naturalness 검사를 다음 artifact 단계에도 적용한다. |
| KT-01 | PASS | `bluetape-kotlin-patterns`, `references/testing.md`, `references/checklist.md`를 읽고 trigger를 분류했다. |
| KT-02 | PASS | UUID/assertion/logger 기존 helper와 모든 호출 지점을 검색했다. |
| KT-03 | PASS | logging, cancellation, cleanup, API compatibility, docs parity를 설계에 반영했다. |
| KT-04 | PASS | `compileKotlin`, `compileTestKotlin`, `compilePostgresIntegrationTestKotlin`, H2 32개, PostgreSQL 4개, Detekt, static scan, `git diff --check`를 fresh 실행했다. |
| KT-05 | PASS | 최종 diff에서 X=10 checklist rows, Blocked=0, P0/P1=0으로 수렴했다. |

## Gate verdict

`P0 = 0`, `P1 = 0`으로 설계 검토를 통과한다. P2/P3는 README 목적지 문구와
UUIDv7 비토큰 제약으로 명시적으로 해소했다. 구현·계획 검토와 최종 T1~T7은
별도 gate이며 이 문서는 구현 승인을 대신하지 않는다.

## Step 3-R 계획 검토

설계 검토와 같은 six-lane 요청을 native lane으로 전달했으나 startup 응답이
없어 main-session fallback으로 계획을 검토했다. 계획의 각 명령·파일·증거를
현재 worktree와 대조했다.

| 우선순위 | 영역 | 확인 결과 | 필요한 수정 |
|---|---|---|---|
| P0/P1 | 요구사항 매핑 | UUID helper, assertions, logger, canonical response, lifecycle, PostgreSQL, 7-Tier, lesson/PR이 계획-요구사항 표에 각각 연결된다. | 없음 |
| P0/P1 | 실행 순서 | RED → production GREEN → README parity → sequential validation → verifier/review/PR 순서가 후속 산출물 의존성을 지킨다. | 없음 |
| P0/P1 | 테스트 | sink allowlist, assertion identity, H2/HTTP, PostgreSQL, static scan과 cancellation/lifecycle 기존 테스트가 포함된다. | Testcontainers는 실제 fresh output에서 skip/failure를 분리 기록한다. 계획에 이미 반영됨. |
| P0/P1 | 문서 | README.md와 README.ko.md, spec/plan/review/lesson, Korean PR body를 모두 명시했다. | 없음 |
| P0/P1 | 안정성/운영 | `maxParallelForks=1`, `--no-parallel`, logger 목적지·rollback·container 오류 분류가 명시된다. | 없음 |
| P0/P1 | API/호환성 | parser의 `UUID`, 예외 타입, `DemoDiagnosticSink`, public HTTP contract를 보존하고 신규 dependency를 금지한다. | 없음 |
| P2 | 성능 | benchmark는 오류 진단/fixture 경로에 비적용이다. | targeted compile/test 결과에 “추가 DB round trip 없음”을 기록한다. |

`P0 = 0`, `P1 = 0`으로 Step 3-R을 통과한다. P2는 구현 후 changed-file
review에서 확인하며, 계획을 차단하지 않는다.

## Step 6-R / 7-Tier 최종 검토

### 대상 diff와 fresh evidence

검토 대상은 Ktor demo의 production 2개, test 4개, README 2개와 설계·계획·리뷰
artifact다. 기준 base는 `1242e5eb990a1f362233dba9542aa6e4d7192730`이고, 다음
fresh 결과를 현재 diff에 대조했다.

- `compileKotlin`, `compileTestKotlin`, `compilePostgresIntegrationTestKotlin`:
  모두 `BUILD SUCCESSFUL`.
- H2 module test: 32 tests, failures 0, errors 0, skipped 0.
- PostgreSQL Testcontainers: 4 tests, failures 0, errors 0, skipped 0.
- `:examples-ktor-exposed-demo:detekt`: `NO-SOURCE`, `BUILD SUCCESSFUL`.
- 대상 examples 정적 scan: JUnit assertion import/call, `kotlin.test`,
  `assertSame`, `UUID.randomUUID`, `println`, `System.out`, `System.err` 0건.
- `git diff --check`: PASS. Korean terminology audit(README 포함 4개): findings 0.

### Tier findings

| Tier | 관점 | 결과와 근거 |
|---|---|---|
| T1 | Performance | P0/P1/P2/P3=0. UUIDv7 생성은 실패 correlation/fixture 경로에만 있고 DB round trip·retry·buffer를 추가하지 않는다. logger message는 lazy lambda로 만들어 disabled level에서 문자열을 만들지 않는다. benchmark는 hot path 변경이 없어 N/A다. |
| T2 | Stability | P0/P1/P2/P3=0. `CancellationException` rethrow, cleanup 순서, R2DBC lifecycle은 변경하지 않았고 H2 32개·PostgreSQL 4개가 pass했다. PostgreSQL task는 `--no-parallel`, class는 SAME_THREAD다. |
| T3 | Security | P0/P1/P2/P3=0. 진단 allowlist 필드만 logger로 보내며 예외·credential·raw payload를 추가하지 않는다. UUIDv7의 시간 비트는 공개 correlation 식별자 특성으로 문서화하고 token 용도는 금지한다. |
| T4 | Operator/Ops | P0/P1/P2/P3=0. 직접 `println`/`System.err` 경로를 제거하고 bluetape4k `KotlinLogging`/`error`를 사용한다. README 두 locale이 application logger와 backpressure 제한을 같은 의미로 설명한다. |
| T5 | Developer/API | P0/P1/P2/P3=0. `Uuid.V7.nextId()`, `bluetape4k-assertions`, `shouldBeSameInstanceAs`를 기존 repository 패턴으로 사용한다. parser의 `java.util.UUID`, exception contract, `DemoDiagnosticSink`, HTTP API는 유지한다. 새 dependency와 `!!`는 없다. |
| T6 | User/Caller | P0/P1/P2/P3=0. `503` response의 canonical 36자 UUID 문자열과 response-diagnostic 연결을 유지하고 README.md/README.ko.md를 parity로 수정했다. README의 retry/event-republish 비토큰 제약도 유지한다. |
| T7 | Integration | P0/P1/P2/P3=0. spec→plan→source/test→README→lesson→PR traceability를 확보했고 module registration/CI/schema 변경은 N/A다. Detekt NO-SOURCE는 성공 근거이지 전체 repository lint 증거가 아님을 기록한다. |

### 통합 결론과 잔여 gate

통합 결과 `P0 = 0`, `P1 = 0`, `P2 = 0`, `P3 = 0`이다. native lane startup
응답 부재로 six-lane 검토는 main-session fallback으로 수행했으며, 이 사실은
설계·계획 단계와 receipt에 남긴다. `detekt NO-SOURCE`와 benchmark N/A는 현재
scope의 명시적 한계다. Hosted CI/review와 merge는 PR delivery 이후 별도 gate로
남긴다.
