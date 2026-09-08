# #857 구현 계획 검토

## 판정과 범위

검토 대상은 [실행 계획](../superpowers/plans/2026-09-08-issue-857-clickhouse-streaming-plan.md)과 `eb719216`의 [승인 명세](../superpowers/specs/2026-09-08-issue-857-clickhouse-streaming-design.md)다. 최초 판정은 `NEEDS FIX`였으며 아래에 당시 근거를 보존한다. 후속 승인과 보완 후 최신 판정은 마지막 절의 `PASS`다. production 코드·신규 테스트·의존성은 계획 검토 중 변경하지 않았으며 구현 검증 PASS를 뜻하지 않는다.

최신 사용자 `승인`은 작성된 명세에 반영했다. HikariCP 테스트 의존성 추가는 기존 명세의 새 의존성 제외 범위에 해당하므로 아직 실행하지 않았다.

## 검토 출처

| 관점 | 실제 실행 | 결과 |
|---|---|---|
| 성능 | native `plan_perf857`, `code-reviewer` 생성 후 90초 이상 실질 증거 없이 진행되어 interrupt, 메인 inline fallback | T6의 재현 가능한 메모리 계측 절차 부족. 독립 판정 없음 |
| 안정성 | native `plan_stable857`, `verifier` | PARTIAL, 풀·정리·취소 검증 구체화 필요 |
| 보안 | native `plan_security857`, `code-reviewer` | REQUEST CHANGES, 승인 범위·세션 비상속 검증 보완 |
| 운영·API·호출자 | 아직 독립 검토하지 않음 | PENDING, 선행 범위 결정과 계획 보완 후 진행 |
| 메인 통합 | 현재 명세·계획·모듈 설정·실행 결과 대조 | 아래 미해결 항목 보존, A-04 미통과 |

역할 설정은 `code-reviewer`와 `verifier` 모두 `gpt-5.6-luna`/`max`다. native 생성에는 역할만 전달했으며 별도 모델 override는 없었다. 이는 역할 설정의 출처이며 응답에 없는 외부 모델 실행 증명을 추가하지 않는다. 실패한 실행 기록을 성공으로 변경하지 않았다.

## 보완 항목

| ID | 심각도 | 위치·근거 | 필요한 수정·상태 |
|---|---|---|---|
| R1 | P1, 실행 범위 | 계획 T4.2, `exposed/clickhouse/build.gradle.kts` | HikariCP는 대상 testRuntimeClasspath에 없음. `testImplementation(bt4k.hikaricp)`만 추가하는 범위 결정 필요. 승인 전 PENDING |
| R2 | P1, 안정성 | 계획 T2.2·T3.1~3 | tracked Database 구성, 원인 병합 함수, close 오류 주입과 identity/suppressed 검사, bounded blocking gate·finally 해제 코드를 실행 가능한 수준으로 보완 |
| R3 | P1, 보안 | 계획 T4.1 | 연결 ID 외에 connection-local marker 비상속과 명시적 tenant predicate 결과를 확인하는 부정 테스트 구체화. 라이브러리가 tenant 보안 계층인 것으로 설명하지 않음 |
| R4 | P1, 성능 | 계획 T6.1~2, 메인 inline fallback | 예제 `sum`/`fold`는 처리량만으로 peak live heap·TTFI를 입증하지 않음. fork 설정, 기준선·live heap 샘플링, RSS/direct/JFR 수집과 계산 절차·명령 보완 |
| R5 | P2, 보안 | 계획 T4.1·T5.2 | 악성 바인딩 값의 정확한 결과·추가 statement 0회 검사, 확장 함수의 민감 정보 로깅 금지 검증 추가. driver/Exposed logger와 구분 |
| R6 | P1, 계획 완성도 | 계획 T2~T6, 메인 self-review | `writing-plans`의 완결된 코드·정확한 명령 요구에 비해 설명 중심 단계가 남음. 테스트 fixture·취소·측정 코드를 구체화한 뒤 계획 SPW-02/04 재검토 |

R1은 제품 보안 결함이 아니라 실행 승인 범위의 불일치다. 범위가 정해지면 R2~R6은 에이전트가 수정하고 해당 관점을 재검토한다. P0=0, 미해결 P1=5, P2=1로 정규화했다. 미실행 관점이 있으므로 전체 7-Tier 검토 완료를 주장하지 않는다.

## 실제 검증

- `:bluetape4k-exposed-clickhouse:cleanTest :bluetape4k-exposed-clickhouse:test --tests io.bluetape4k.exposed.clickhouse.ClickHouseExtensionsTest --no-build-cache --console=plain`: exit 0. XML에서 tests=3, skipped=0, failures=0, errors=0 확인.
- 최초 실행은 테스트 프로세스가 `/var/run/docker.sock`을 찾지 못해 초기화 실패했다. 정상 Colima 소켓을 확인한 뒤 해당 프로세스에만 Docker 환경을 전달했다. Docker 재시작·전역 설정 변경 없음.
- `:bluetape4k-exposed-clickhouse:dependencyInsight --dependency HikariCP --configuration testRuntimeClasspath --console=plain`: exit 0, `No dependencies matching given input were found`.
- `benchmark/exposed-benchmark/build.gradle.kts`에는 HikariCP가 이미 있지만 `ClickHouseConnectionWrapper`는 internal이고 공개 팩터리는 DriverManager만 사용한다. 단순 검증 이동 대신 테스트 전용 의존성을 권장한다. 공개 API 확대나 별도 풀 구현은 하지 않는다.
- 기본 테스트 성공은 신규 API·취소·메모리 수용 기준의 성공 증거가 아니다. #857은 GitHub에서 OPEN으로 확인했으며 변경하지 않았다.

## 문서 검증과 다음 경계

이 검토 기록의 SPW-01~05: 한국어 구현 담당자 대상, exact 파일·명령·판정·출처와 미실행 범위를 구분했다. KO-01~07에 따라 사실·식별자·수치 보존, 자연스러운 기술 문체, 표·링크·용어 검사를 적용한다. 최종 read-back·용어 검사·diff 검사의 실행 결과로 문서 자체를 검증한다. 계획 문서는 완성도 보완이 남아 SPW-02/04와 A-04를 통과 처리하지 않는다.

다음 순서는 R1 범위 결정 → 실행 코드·검증 절차 보완 → 나머지 관점 및 해당 관점 재검토 → 계획 커밋 → TDD 구현이다. PR 생성·push·머지·Full Nightly·중앙 매뉴얼 변경은 실행하지 않았다.

## 후속 승인·수정 후 판정 — 2026-09-09 KST

사용자가 `testImplementation(bt4k.hikaricp)`의 테스트 전용 추가를 승인했다. 명세와 계획에 이를 반영했으며 catalog·런타임 범위는 그대로다. 계획의 마지막 보완 절에 종료 알고리즘, JDBC 관측 fixture, 유한 취소 gate, 원인 표, 보안 부정 테스트, 정확한 계측 명령을 추가했다.

| 관점 | 최신 근거 | 판정 |
|---|---|---|
| 성능 | 독립 `rplan_perf857` 재검토: JVM pair 순서, List 반환 checkpoint, reachabilityFence, checksum, JFR 불확실성 처리 확인 | PASS |
| 안정성 | 독립 `rplan_stable857`: 종료 후 join·suppressed·유한 latch·pool 반환 계획 확인 | PASS |
| 보안 | 독립 `rplan_security857`: test-only 승인, connection marker, bound predicate, logger 부정 테스트 확인 | PASS |
| 운영 | 독립 `rplan_ops857` 무응답 중단 후 메인 inline fallback. T4/T6/T8의 pool 소유권, 유한 driver timeout, 별도 JVM·정확한 명령, 재설계/미측정 PENDING, PR/배포 보류 확인 | PASS, 독립 아님 |
| API | 독립 `rplan_api857` 무응답 중단 후 메인 inline fallback. 기존 facade/함수 유지, arity로 구분된 overload, 모듈 ABI만 갱신, raw JDBC는 Query iterator materialization 회피용 내부 경계, 테스트 전용 fixture 확인 | PASS, 독립 아님 |
| 호출자 | 독립 `rplan_caller857` 무응답 중단 후 메인 inline fallback. T5/T8의 README 언어별 대응, bounded take 예제, collector 연결 점유, 외부 session 비상속, 기존 API 호환성 및 중앙 매뉴얼 별도 범위 확인 | PASS, 독립 아님 |
| 메인 통합 | R1~R6과 AC-01~09를 보완 절·테스트 행렬·실측 절차에 재대조 | PASS |

독립 lane은 역할별 설정을 그대로 사용했다. 중단된 lane의 실패 기록은 보존하고 메인 검토를 독립 attestation으로 표시하지 않는다. R1은 승인으로, R2/R6은 종료·fixture·gate 코드로, R3/R5는 marker/binding/logger 부정 검사로, R4는 JVM pair/반환 checkpoint/JFR 판정 절차로 해소했다. 최신 P0=0/P1=0이다.

안정성 P2 권고인 producer/collector 오류 경합, 일반 오류·취소·채널 종료 원형 비교, join 후에만 종료 상태 읽기를 T3의 검증 항목에 포함한다. `take(1)`의 내부 취소가 외부 오류로 새지 않는지 실제 테스트에서 확인한다. 이것은 계획 통과이지 실제 드라이버 동작의 보증이 아니다.

계획·수정 명세·통합 리뷰 각각 SPW-01~05를 다시 적용했다. 한국어 구현/검토 담당자, 승인 범위·소스·미확인 결과를 고정하고 문서 구조·명시된 코드/명령·AC 추적·용어·최종 read-back을 확인했다. KO-01~07 검사와 contextual terminology audit는 0건, `git diff --check`는 성공이다. 기술 문서의 계획/현재 동작/실측 결과를 구분했고 API 토큰과 기준 수치를 보존했다. A-04는 계획 커밋으로 마무리한 뒤 A-06 테스트 우선 구현으로 진행한다.
