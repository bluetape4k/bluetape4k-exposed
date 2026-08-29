# Issue #763 TenantContext Ktor bridge 설계 검토

## 검토 범위

- 대상 명세: `docs/superpowers/specs/2026-08-30-issue-763-tenant-context-ktor-design.md`
- 근거: live Issue #763, upstream `bluetape4k-projects` PR #1566/#1568와
  공개 `2.0.0-SNAPSHOT` POM/metadata, 현재 `ktor/core`·`ktor/jdbc`·`ktor/r2dbc`
  구현과 baseline 테스트
- 검토 방식: 여섯 독립 관점과 main-session 통합 검토

## 독립 관점 결과

| Priority | Lens | Evidence | Required edit | Rerun lane |
|---|---|---|---|---|
| P0 | performance | 없음. adapter는 tenant 조회와 resolver 호출 뒤 기존 helper로 직접 위임하고 새 cache/retry/metric을 만들지 않는다. | 없음 | N/A |
| P0 | stability | 없음. Ktor call attribute는 upstream 소유이며 DB·pool·dispatcher lifecycle을 adapter가 소유하지 않는다. 기존 cancellation 경계를 유지한다. | 없음 | N/A |
| P0 | security | 없음. 인증·인가와 identifier 검증은 caller 책임으로 명시하고, missing context는 default fallback 없이 fail-fast한다. | 없음 | N/A |
| P1 | operator/Ops | (수정 완료) 초기 검토에서 예외 분류·저카디널리티 관측과 producer/consumer rollback 절차가 부족하다는 지적이 있었다. 명세의 운영 관측 계약과 rollback 절차를 보강했다. | 없음 | operator/Ops 재검토 PASS |
| P0 | developer/API | 없음. JDBC/R2DBC backend별 module과 분리된 함수 이름으로 기존 overload와 source/binary compatibility를 보존한다. | 없음 | N/A |
| P1 | user/caller | (수정 완료) 초기 검토에서 동기 resolver의 blocking/I/O 오용, unknown tenant의 default fallback, 실행 가능한 binding 예제가 부족하다는 지적이 있었다. resolver를 non-blocking exact-match 계약으로 고정하고 fallback 금지·unknown 거부를 명시했다. 실행 예제는 Step 4 문서 작업에서 추가한다. | Step 4 문서에 `KtorTenantContext.bindTenant`, `map.getValue`, 두 transaction 호출 예제를 추가 | user/caller 재검토 |
| P0 | user/caller | 없음. resolver, resource ownership, unsupported authentication/routing 책임과 사용 좌표를 README/KDoc에 문서화한다. | 없음 | N/A |
| P1 | developer/API | (수정 완료) 초기 검토에서 `#213` alias handoff가 OPEN인데 direct coordinate workaround가 구현을 계속 허용한다는 점이 선행조건과 충돌했다. `#1562` timestamped POM/JAR는 현재 live 검증했지만, `#213` 완료 또는 fresh owner decision 전에는 direct coordinate를 provisional compile/test 증거로만 사용하고 PR/publication/release/merge를 hold하며, 각 downstream gate에서 checksum/metadata parity를 재검증하도록 명세를 고쳤다. | 없음 | dependency/API + release-train gate 재검토 |
| P2 | developer/API | (수정 완료) public signature의 `TenantId`와 `KtorTenantContext`를 위해 `bluetape4k-tenant`와 `bluetape4k-ktor-tenant`를 모두 직접 `api` 의존성으로 고정하고 generated POM/Gradle metadata를 검증하도록 보강했다. | 없음 | dependency boundary + ABI |
| P2 | developer/API | (수정 완료) exact timestamp/build와 metadata·POM·JAR SHA-256을 manifest에 기록하고 `validate_issue_763_tenant_snapshot.rb` fail-closed checker를 downstream gate 명령으로 추가했다. | 없음 | dependency/release-train |
| P2 | operator/Ops | `bluetape4k-dependencies#213` alias handoff가 live에서 OPEN이다. 현재 timestamped `2.0.0-SNAPSHOT` BOM/POM/JAR를 확인했지만 downstream gate마다 재검증한다. | mismatch/404/drift 시 구현을 `PENDING`으로 되돌리고 후속 gate를 hold한다. | operator/Ops |

## 통합 판정

P0=0, P1=0이다. 초기 operator/Ops 지적은 운영 관측 분류와 producer/consumer
rollback 절차를, user/caller 지적은 non-blocking exact-match resolver와
default fallback 금지를 명세에 추가해 해소했다. developer/API 지적은
provisional direct-coordinate gate와 두 upstream direct `api` 의존성으로
해소했다. 실행 예제와 구체적인 `StatusPages` 매핑은 구현 문서 단계의 P2
후속 항목이다. 외부 catalog handoff 상태는 명세에 기록한 잔여 리스크이며
downstream gate를 hold할 수 있지만, 현재 local compile/test의 provisional
증거 자체는 허용한다. JDBC·R2DBC adapter는 기존 helper의
dispatcher, metric, exception, cancellation semantics를 재사용하는 얇은
경계로 수렴한다. `ktor/core`와 기존 backend module에는 tenant dependency를
추가하지 않는다.

## Writer gate

| Check | Result |
|---|---|
| SPW-01 구조·문서 목적 | PASS |
| SPW-02 독자·용어·문장 | PASS |
| SPW-03 코드/좌표 정확성 | PASS |
| SPW-04 링크·식별자·locale | PASS |
| SPW-05 자연스러운 한국어와 read-back | PASS |

## Step 2-R DoD

- [x] 여섯 관점과 main-session 통합 검토 완료
- [x] P0/P1 blocker 0건
- [x] P1 direct-coordinate provisional gate와 외부 의존성 handoff hold 조건 명시
- [x] P2 public `TenantId`/`KtorTenantContext` direct `api` 및 metadata 검증 명시
- [x] P2 exact upstream digest manifest와 fail-closed checker 명시
- [x] 명세의 exact API import, module boundary, failure/cancellation semantics 재확인
- [x] `git diff --check`와 manual validator 재실행
- [x] 현재 환경에 제공되지 않는 별도 Korean terminology audit helper 대신
      한국어 README/manual read-back으로 용어·좌표·링크를 확인

판정: **PASS — 구현 계획 단계로 진행 가능**
