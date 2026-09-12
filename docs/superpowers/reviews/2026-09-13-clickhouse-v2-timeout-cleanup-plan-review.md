# #875 구현 계획 검토

## 검토 범위와 방법

- 대상 계획: `docs/superpowers/plans/2026-09-13-clickhouse-v2-timeout-cleanup-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-09-13-clickhouse-v2-timeout-cleanup-design.md`
- 기준 ref: `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166`
- 검토 방식: 독립 code-review lane을 사용할 수 없어 주 세션에서 여섯 관점을 각각 재검토한 inline fallback이다. 본 검토는 **비독립**이고, 1인 개발자 human-review lane은 N/A다.
- 선행 조치: 설계 review의 P2였던 redaction assertion, bounded observation receipt 경로, EN/KO wording 검증을 각각 Task 1·Task 5·Task 6의 구체 명령과 artifact path에 반영했다.

## 계획 검토 결과

| Tier | 확인 항목 | 근거와 판정 | 필요한 조치 |
|---|---|---|---|
| 1 성능 | timeout elapsed, bounded polling, Testcontainers 비용 | timeout 종류를 분리하고 repeat=3, caller deadline, 순차 Docker 실행을 명시한다. throughput/무제한 polling 주장은 없다. | receipt에 각 행 elapsed와 repeat를 기록한다. |
| 2 안정성 | cancellation/close, primary/suppressed, pool reuse | `CancellationException` 재전파, `NonCancellable` join, resource counter, cleanup failure, Hikari 1-slot 후속 query를 Task 3·4에 연결한다. | deterministic fake와 실제 driver 경계를 혼합하지 않고 각각 증명한다. |
| 3 보안 | query id binding과 redaction | Task 1이 query id parameter binding, 빈 id `UNAVAILABLE`, query/credential/exception 원문 금지와 redaction assertion을 요구한다. | receipt read-back에서 raw SQL·URL·비밀값이 없는지 확인한다. |
| 4 운영 | selector, image/version/SHA, N/A·rerun | V2 selector, driver/server/image/version/SHA, 권한·버전 차이의 `UNAVAILABLE`/`N/A`, 동일 selector rerun을 Task 2·5·6에 고정한다. | hosted CI에서 selector와 artifact 경로를 보존한다. |
| 5 개발/API | Exposed ownership, Kotlin contract, public drift | `queryFlow` public signature와 transaction ownership을 유지하며 generic abort/KILL API와 새 dependency를 배제한다. concrete defect일 때만 최소 source diff를 허용한다. | compile/detekt와 public ABI 영향 N/A 근거를 기록한다. |
| 6 사용자 | timeout/cancel/remote distinction, EN/KO parity | pool/connect/socket/server timeout, cancel request, remote termination, caller finite-timeout 책임을 양 locale에서 별도로 설명하도록 Task 5가 요구한다. | audit finding과 구조적 parity를 PR 전 확인한다. |
| 7 통합 | issue/PR/lesson/DoD traceability | Task 1 observation, Task 2 matrix, Task 3 cleanup, Task 4 state isolation, Task 5 receipt/lesson, Task 6 review가 #875 수용 기준에 연결된다. PR 본문은 `Closes #875`를 사용한다. | exact head·CI·issue metadata를 PR 생성 직전에 재검증한다. |

## 위험·롤백 판정

- P0: 0
- P1: 0
- P2: 0 (설계 단계 P2 조치가 plan에 반영됨)
- P3: 0

원격 query disappearance 또는 V2 socket timeout이 관찰되지 않으면 N/A로 남기며 성공 보장으로 바꾸지 않는다. source defect가 재현되지 않으면 test/docs-only PR로 유지한다. root의 dirty Ktor 변경과 다른 worktree는 롤백 대상이 아니다.

## SPW-01~05 통합 review gate

- **SPW-01 PASS**: 계획 독자·목표·기준 ref·issue·source/selector·stop condition을 기록했다.
- **SPW-02 PASS**: Task 1~6마다 파일, RED/GREEN 명령, expected evidence, rollback/rerun, DoD를 확인했다.
- **SPW-03 PASS**: 한국어 기술 register와 `요청 수락`/`원격 종료`/`정리`/`재사용`, API·driver token을 유지했다.
- **SPW-04 PASS**: 설계 수용 기준과 #860/#863/#864 source evidence를 각 task 및 verification/lesson 경로에 trace했다.
- **SPW-05 PASS**: 전체 plan을 read-back하고 표·코드 블록·체크리스트·미완성 표식을 점검했다.

**Step 3-R verdict: PASS (P0=0, P1=0).** 구현 단계는 Task 1부터 순차 진행한다.
