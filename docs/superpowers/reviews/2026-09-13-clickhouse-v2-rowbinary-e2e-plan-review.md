# #874 구현 계획 검토

## 검토 범위와 방법

- 대상 계획: `docs/superpowers/plans/2026-09-13-clickhouse-v2-rowbinary-e2e-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-09-13-clickhouse-v2-rowbinary-e2e-design.md`
- 기준 ref: `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166`
- 검토 방식: 독립 code-review lane을 사용할 수 없어 주 세션에서 여섯 관점을 각각 재검토한 inline fallback이다. 본 검토는 **비독립**이고, 1인 개발자 human-review lane은 N/A다.
- 선행 조치: 설계 review의 P2였던 실행 receipt 경로와 문서 예제 확인을 각각 `docs/superpowers/verification/2026-09-13-issue-874-rowbinary-e2e.md` 및 README source-name grep 명령으로 계획에 반영했다.

## 계획 검토 결과

| Tier | 확인 항목 | 근거와 판정 | 필요한 조치 |
|---|---|---|---|
| 1 성능 | fixture/wire provenance, chart claim, Testcontainers 비용 | fixture benchmark와 actual wire 실행을 분리하고 실제 측정이 없으면 chart를 변경하지 않는다. 반복 실행과 순차 Docker 명령이 bounded다. | 실행 receipt에서 실제 측정 여부를 명시하고 미측정이면 N/A로 유지한다. |
| 2 안정성 | fallback/replay, flush, cleanup, pool reuse | before-first-byte fallback과 first-byte 이후 no-replay, close exactly-once, empty/multi-flush, pool 재사용이 Task 1·2·6에 연결된다. | 구현 시 provider counter와 fixture sentinel을 모두 유지한다. |
| 3 보안 | SQL/table 식별자와 credential redaction | 고정 table suffix, provider payload 비기록, SQL credential 비기록이 명시되어 있다. | verification artifact read-back에서 민감정보 부재를 확인한다. |
| 4 운영 | selector, source/driver/server/image provenance, rerun | `clickhouseV2Integration=true`, SHA·driver·image·selector를 receipt에 기록하고 Docker 실패/benchmark mismatch rerun 절차가 있다. | 동일 selector와 artifact 경로를 PR 본문에 연결한다. |
| 5 개발/API | 기존 executor/result/provider 계약, Kotlin/API drift | production/public API·dependency를 변경하지 않고 실제 결과의 `updateCounts`, `acceptedCount`, `acceptedCountMayBeIncomplete`를 검증한다. | compile/detekt와 public diff를 실행한다. |
| 6 사용자 | EN/KO 문서와 example parity | README 양 locale에 동일 selector/API·수치·fixture/wire 제약을 반영하며 source-name grep으로 예제 토큰을 확인한다. | 한국어 용어 audit finding을 모두 처리한다. |
| 7 통합 | issue/PR/lesson/DoD traceability | Task 3 receipt, Task 5 README, Task 6 review·commit이 연결되고 PR 본문은 `Closes #874`를 사용하도록 계획되어 있다. | PR 생성 전 exact head와 issue metadata를 재확인한다. |

## 위험·롤백 판정

- P0: 0
- P1: 0
- P2: 0 (설계 단계 P2 조치가 plan에 반영됨)
- P3: 0

Docker/image/driver 차이는 source workaround로 감추지 않고 receipt의 실패 또는 N/A로 남긴다. benchmark provenance가 맞지 않으면 raw 결과와 chart를 커밋하지 않는다. root의 dirty Ktor 변경과 다른 worktree는 롤백 대상이 아니다.

## SPW-01~05 통합 review gate

- **SPW-01 PASS**: 계획 독자·목표·기준 ref·issue·source/selector와 stop condition을 기록했다.
- **SPW-02 PASS**: Task 1~6마다 파일, RED/GREEN 명령, expected evidence, rollback/rerun, DoD를 확인했다.
- **SPW-03 PASS**: 한국어 기술 register와 `행`/`정리`/`증적`, API·driver token을 일관되게 유지했다.
- **SPW-04 PASS**: 설계 수용 기준과 선행 #867/#871/#863 근거를 각 task 및 artifact 경로에 trace했다.
- **SPW-05 PASS**: 전체 plan을 read-back하고 표·코드 블록·체크리스트·미완성 표식을 점검했다.

**Step 3-R verdict: PASS (P0=0, P1=0).** 구현 단계는 Task 1부터 순차 진행한다.
