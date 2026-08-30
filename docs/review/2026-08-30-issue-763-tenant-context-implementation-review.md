# Issue #763 TenantContext Ktor bridge 구현 검토

## 검토 범위

- 두 선택형 adapter의 Kotlin API와 helper 위임 순서
- missing/unknown context, resolver·transaction 예외, cancellation, 동시 call
  테스트
- dependency allowlist, published POM/Gradle metadata, production ABI, BOM,
  CI/nightly와 문서 등록
- 검토 근거: 현재 branch의 diff, generated receipts, live tenant 배포 artifact checker와
  Ktor scope gate 결과

## 판정

| 우선순위 | 영역 | 결과 | 근거 | 조치 |
|---|---|---|---|---|
| P0 | API/동시성 | 0 | adapter는 call-local `KtorTenantContext.requireCurrent` 후 동기 resolver만 호출하고 기존 backend helper에 직접 위임한다. 공유 cache·scope·lock이 없다. | 없음 |
| P0 | 보안/격리 | 0 | context 누락·unknown tenant는 resolver/transaction 전에 fail-closed하고 default fallback이 없다. raw tenant/header/URL/SQL/credential을 기록하지 않는다. | 없음 |
| P1 | lifecycle/cancellation | 0 | JDBC `blockingDispatcher`, DB/pool, R2DBC DB, registry 종료를 caller에게 남기며 기존 helper의 `CancellationException`과 timer outcome을 보존한다. | 없음 |
| P1 | dependency/ABI | 0 | tenant upstream 두 좌표는 새 module에만 `api`로 노출되고 `ktor/core`에는 나타나지 않는다. 두 adapter는 중앙 catalog alias를 사용하며 `checkKtorDependencyBoundary`와 ABI 44/44가 통과했다. | 없음 |
| P2 | 외부 handoff | 해소 | `bluetape4k-dependencies#214`가 `develop`에 merge되어 `bluetape4k-tenant`, `bluetape4k-tenant-reactor`, `bluetape4k-ktor-tenant` alias가 생겼다. 이 branch는 merge commit `29d858bd22553a31709123908a2eb5c5644093b3`을 고정한다. | `bluetape4k-dependencies#213`과 upstream `#1562`의 publication·closeout 상태는 별도로 추적한다. |
| P2 | 환경 | 비차단 | 공유 publication/consumer 상태를 사용하는 검증을 병렬 실행한 최초 시도는 consumer fixture에서 실패했다. 순차 재실행은 통과했다. allowlist 최초 전체 실행에서는 기존 Dokka classpath의 `kotlinx/serialization/StringFormat` 오류가 한 번 발생했지만, 실패 test 단독 실행과 같은 seed의 전체 재실행은 통과했다. | repository guard에 따라 Ktor consumer, boundary, ABI, allowlist 검증을 순차 실행한다. 기존 관련 lesson을 재사용하므로 새 lesson은 `N/A`다. |

## 검증 증거

- latest tenant 배포 metadata/POM/JAR checker: `PASS`, timestamp `20260829.185300`,
  build `4`, artifacts `2` (the upstream metadata advanced after an earlier
  scope gate, so the manifest was refreshed and the checker was rerun)
- central catalog producer test: `2 tests`, `OK`; merge commit
  `29d858bd22553a31709123908a2eb5c5644093b3`의 tenant alias 3개와 checksum을
  확인
- 두 adapter는 `bt4k.bluetape4k.tenant`와
  `bt4k.bluetape4k.ktor.tenant`를 사용하며 runtime classpath에서 두 좌표가
  각각 `2.0.0-SNAPSHOT`으로 해석됨
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
- [x] 중앙 catalog alias handoff를 적용하고 direct coordinate 제거
- [x] publication, PR 생성, merge를 별도 권한 경계로 유지

판정: **PASS — 중앙 catalog handoff 적용 완료, PR 생성·publication·merge는 별도 승인 대기**
