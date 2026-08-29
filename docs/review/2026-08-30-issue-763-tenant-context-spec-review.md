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
| P0 | user/caller | 없음. resolver, resource ownership, unsupported authentication/routing 책임과 사용 좌표를 README/KDoc에 문서화한다. | 없음 | N/A |
| P2 | operator/Ops | `bluetape4k-dependencies#213` alias handoff가 live에서 OPEN이다. 현재 `2.0.0-SNAPSHOT` BOM version authority를 사용하고 후속 alias 전환을 명시했다. | 구현 시 direct coordinate·`2.0.0-SNAPSHOT` metadata를 다시 검증하고 후속 issue를 유지한다. | operator/Ops |

## 통합 판정

P0=0, P1=0이다. 초기 operator/Ops 지적은 운영 관측 분류와 producer/consumer
rollback 절차를 명세에 추가해 해소했다. P2는 외부 catalog handoff 상태를
명세에 이미 기록한 잔여 리스크이며 구현을 막지 않는다. JDBC·R2DBC adapter는 기존 helper의
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
- [x] P2 외부 의존성 handoff 리스크와 후속 검증 명시
- [x] 명세의 exact API import, module boundary, failure/cancellation semantics 재확인
- [x] `git diff --check`와 Korean terminology audit 재실행 예정

판정: **PASS — 구현 계획 단계로 진행 가능**
