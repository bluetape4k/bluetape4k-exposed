# Issue #730 설계 명세 통합 리뷰

## 리뷰 범위

- 대상: `docs/superpowers/specs/2026-08-27-issue-730-ktor-backend-boundaries-design.md`
- 기준: `origin/develop@c5e9d499d9c1baeb6f92a531345d184c16febc27`
- workflow: Type A `bluetape-full-feature`
- 독립 관점: API/ABI, 보안·관측성, 성능·동시성, 안정성·호환성,
  사용자·호출자, 운영·검증
- 판정 규칙: P0/P1은 구현 계획 진입을 차단하고, P2/P3는 계획에 추적한다.

## 최종 관점별 판정

| 관점 | P0 | P1 | P2 | 판정 | 증거 |
|---|---:|---:|---:|---|---|
| API/ABI | 0 | 0 | 1 | READY | `spec730-final2-api`, `/tmp/spec730-final2-api-result.json` |
| 보안·관측성 | 0 | 0 | 2 | READY | `spec730-final2-security`, `/tmp/spec730-final2-security-result.json` |
| 성능·동시성 | 0 | 0 | 1 | READY | `spec730-final2-performance`, `/tmp/spec730-final2-performance-result.json` |
| 안정성·호환성 | 0 | 0 | 0 | READY | `spec730-final2-stability`, receipt sequence 144 |
| 사용자·호출자 | 0 | 0 | 0 | READY | `spec730-final2-user`, receipt sequence 147 |
| 운영·검증 | 0 | 0 | 3 | READY | `spec730-final2-ops`, `/tmp/spec730-final2-ops-result.json` |
| **합계** | **0** | **0** | **7** | **READY** | run `20260827T071052Z-812cf75f` |

모든 P0/P1이 0이므로 구현 계획으로 진행할 수 있다. P2는 계약을 약화하지
않는 범위에서 계획과 검증 산출물에 반영한다.

## 통합 결정

1. core의 direct/transitive dependency allowlist는 명세의 표와 exact
   coordinate 목록을 하나의 canonical source로 만들고, source graph,
   classpath, POM, Gradle metadata 검사에서 같은 값을 사용한다.
2. `component`와 route path는 deployment-static·opaque 값이라는 caller
   provenance 계약을 유지한다. request, tenant, key, URL, SQL, namespace,
   secret에서 파생한 값은 예제와 negative fixture에서 거부한다.
3. cache contributor supplier는 request마다 O(1)·non-blocking인 기존 계약을
   유지하고, blocking consistency 검사는 별도 adapter dispatcher/timeout
   경계 밖으로 내보내지 않는다.
4. 운영 P2는 구현 계획에 exact dependency-boundary task/fixture, `.api`와
   CI receipt 경로, H2→PostgreSQL→MySQL 상태 manifest schema를 고정한다.
5. legacy aggregator는 실제 JVM class/constructor/`$default` bridge와
   독립 phase budget을 보존하며, child/core whole-deadline 계약과 섞지 않는다.

## 게이트 결과

- `git diff --check`: 명세 변경에서 PASS.
- 독립 6관점 최종 리뷰: P0 0, P1 0.
- 구현 전 남은 일: 위 P2의 실행 task·fixture·manifest를 구현 계획에 반영.
- 구현/PR/merge/release는 이 리뷰에서 수행하지 않았다.

## 결론

`READY`: 승인된 설계 명세는 구현 계획 작성 단계로 이동할 수 있다. P2는
구현 계획의 검증 항목으로 추적하며, 새 P1이 발견되면 구현 전에 명세 게이트를
다시 연다.
