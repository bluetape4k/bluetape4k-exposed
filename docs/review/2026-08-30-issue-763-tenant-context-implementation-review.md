# Issue #763 TenantContext Ktor bridge 구현 검토

## 검토 범위

- 두 선택형 adapter의 Kotlin API와 helper 위임 순서
- missing/unknown context, resolver·transaction 예외, cancellation, 동시 call
  테스트
- dependency allowlist, published POM/Gradle metadata, production ABI, BOM,
  CI/nightly와 문서 등록
- 검토 근거: 현재 branch의 diff, generated receipts, live snapshot checker와
  Ktor scope gate 결과

## 판정

| 우선순위 | 영역 | 결과 | 근거 | 조치 |
|---|---|---|---|---|
| P0 | API/동시성 | 0 | adapter는 call-local `KtorTenantContext.requireCurrent` 후 동기 resolver만 호출하고 기존 backend helper에 직접 위임한다. 공유 cache·scope·lock이 없다. | 없음 |
| P0 | 보안/격리 | 0 | context 누락·unknown tenant는 resolver/transaction 전에 fail-closed하고 default fallback이 없다. raw tenant/header/URL/SQL/credential을 기록하지 않는다. | 없음 |
| P1 | lifecycle/cancellation | 0 | JDBC `blockingDispatcher`, DB/pool, R2DBC DB, registry 종료를 caller에게 남기며 기존 helper의 `CancellationException`과 timer outcome을 보존한다. | 없음 |
| P1 | dependency/ABI | 0 | tenant upstream 두 좌표는 새 module에만 `api`로 노출되고 `ktor/core`에는 나타나지 않는다. `checkKtorDependencyBoundary`와 ABI 44/44가 통과했다. | 없음 |
| P2 | 외부 handoff | 잔여 | `bluetape4k-dependencies#213`과 upstream `#1562`가 여전히 OPEN이고 catalog alias가 없다. | direct `2.0.0-SNAPSHOT`은 compile/test 임시 증거로만 유지하며 PR/publication/release/merge를 hold한다. |
| P2 | 환경 | 비차단 | 전체 refresh gate의 최초 시도에서 기존 `exposed-spring-boot4-starter` Maven 조회가 TLS `bad_record_mac`으로 실패했으나 재시도는 통과했다. | 외부 저장소 장애 재발 시 해당 gate만 재시도하고 변경 모듈의 캐시·target evidence와 구분한다. |

## 검증 증거

- latest snapshot metadata/POM/JAR checker: `PASS`, timestamp `20260829.185300`,
  build `4`, artifacts `2` (the upstream metadata advanced after an earlier
  scope gate, so the manifest was refreshed and the checker was rerun)
- tenant JDBC/R2DBC tests: 각 6개, `failures=0`, `errors=0`, `skipped=0`
- Ktor scope gate: `BUILD SUCCESSFUL`, `Ktor dependency boundary passed:
  selectiveArtifacts=6`; the latest manifest-only checker also passes after the
  upstream metadata refresh
- production ABI: `modules=44/44`, `baselines=44/44`, `actualDumps=44/44`,
  `orphanBaselines=0`, `orphanActuals=0`, `emptyBaselines=0`
- targeted `detekt`와 두 Kover XML report: `BUILD SUCCESSFUL`; line counter는
  각 report에서 `missed=0`, `covered=4`
- allowlist/adversarial suite: `14 runs, 314 assertions, 0 failures, 0 errors,
  0 skips`
- manual inventory/validator: `Manuals are aligned.`; workflow YAML:
  `actionlint` 통과
- generated BOM POM에 두 tenant artifact가 `2.0.0` constraint로 포함됨

## DoD

- [x] P0/P1 구현 blocker 없음
- [x] acceptance 기준의 missing/unknown/resolver/transaction/cancellation/
      A-B 동시 routing을 테스트로 고정
- [x] backend별 dependency와 기존 explicit helper API 호환 경계 확인
- [x] 문서·README·manifest·CI/nightly·Kover·ABI·allowlist 등록
- [x] upstream alias handoff가 완료되기 전의 release hold를 기록

판정: **PASS — PR 생성·publication·merge 전 handoff hold 유지**
