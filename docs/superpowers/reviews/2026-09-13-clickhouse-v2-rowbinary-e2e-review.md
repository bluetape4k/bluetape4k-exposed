# #874 RowBinary 실제 서버 보강 7-Tier 코드 리뷰

## 검토 provenance와 범위

- 기준 branch: `feat/issue-874-rowbinary-e2e`
- 기준 implementation HEAD: `c63145f7173f194135244e62f4ad421804ce7c4b` 이후의 working-tree diff
- 변경 범위: `ClickHouseRowBinaryIntegrationTest.kt`, ClickHouse module EN/KO README, verification receipt, lesson
- production Kotlin/public API/dependency/workflow 변경: 없음
- 검토 방식: 독립 code-review lane이 실패/비가용이어서 주 세션에서 각 관점을 순차 재검토한 **비독립 inline fallback**이다. 1인 개발자 human-review lane은 N/A로 기록한다.
- 관련 이슈: [#874](https://github.com/bluetape4k/bluetape4k-exposed/issues/874); PR 본문은 `Closes #874`를 사용한다.

## Tier별 판정

| Tier | 확인 내용 | 근거 | 판정 |
|---|---|---|---|
| 1 성능 | fixture와 wire provenance 분리, flush 관찰, 비용 통제 | actual Testcontainers는 명시 selector·순차 실행·3회 반복이다. fixture benchmark와 처리량 주장을 합치지 않고 chart도 변경하지 않았다. | PASS |
| 2 안정성 | batch/fallback/no-replay와 cleanup | 실제 writer `[2,1]`, fallback `[1]`, combined `[2,1,1,1]`, statement/connection close exactly-once를 확인한다. 기존 unit fixture가 first-byte no-replay와 sentinel을 계속 담당한다. | PASS |
| 3 보안 | SQL/credential/exception redaction | proxy는 delegate class·batch size·counter만 기록하고 receipt는 SQL 본문·URL·credential·raw exception을 제외한다. table 식별자는 고정 issue 전용 값이다. | PASS |
| 4 운영 | selector·driver/server/image·재현성 | `clickhouseV2Integration=true`, driver `0.9.9`, image `26.7.3.19`와 digest, source SHA, Docker context, XML path를 receipt에 고정했다. | PASS |
| 5 개발/API | Kotlin/JDBC 계약과 공개 영향 | `RecordingProvider`는 기존 `ClickHouseConnectionProvider`/fallback recorder를 구현하고 proxy delegate 예외를 보존한다. production/public signature와 dependency는 변하지 않았다. | PASS |
| 6 사용자 | README EN/KO 계약과 N/A wording | 두 locale에서 실제 writer/fallback class, flush·row·default·NULL 수치와 fixture-only benchmark 한계를 동일하게 설명한다. remote cancellation은 #875로 분리했다. | PASS |
| 7 통합 | issue/DoD/lesson/CI 경계 | verification·lesson·spec·plan이 같은 issue에 trace되고, hosted CI/merge 후 검증은 아직 별도 단계로 남겼다. | PASS |

## 테스트와 정적 검증 증거

| 검사 | 결과 |
|---|---|
| RowBinary unit + actual integration | `SUCCESS: Executed 20 tests in 9s`; unit 14, integration 6; failures/errors/skipped 모두 0 |
| Actual integration XML | `tests=6`, `failures=0`, `errors=0`, `skipped=0` |
| module detekt + compileKotlin + compileTestKotlin | Gradle `BUILD SUCCESSFUL` |
| Korean terminology audit | changed Korean docs findings `[]` |
| `git diff --check` | PASS |
| benchmark/chart | 실행하지 않음, N/A로 기록 |

## 결론과 잔여 위험

- P0: 0
- P1: 0
- P2: 0
- P3: 0

실제 `setNull` RowBinary 지원은 현재 pinned driver/server 조합에서 관찰되었지만, 다른 driver/server 조합의 capability 보장을 의미하지 않는다. Testcontainers가 없는 기본 CI selector에서는 통합 테스트가 실행되지 않으므로 hosted CI receipt에서 selector와 skip 정책을 별도로 확인해야 한다. V2 timeout·remote `KILL QUERY` 종료 보장은 #875에서 다룬다.

**Step 6-R verdict: PASS (P0=0, P1=0).** PR 생성 전 exact head, diff, issue metadata를 다시 읽고 `Closes #874`를 확인한다.
