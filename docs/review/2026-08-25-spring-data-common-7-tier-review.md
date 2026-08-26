# Issue #729 공통 Spring Data 구현 7-Tier review

## 검토 범위와 기준

- 대상 Issue: [#729](https://github.com/bluetape4k/bluetape4k-exposed/issues/729)
- 대상 branch: `refactor/spring-data-common`
- worktree: `.worktrees/refactor/spring-data-common`
- 기준 base: `origin/develop` `1242e5eb`
- module slice: `bluetape4k-exposed-spring-boot-common`, JDBC adapter,
  R2DBC adapter, 두 예제, BOM, manual, CI/Nightly
- 검토 대상: 승인된 설계와 계획, 현재 source/test/doc diff, generated ABI,
  의존성 경계, Kover/Detekt/Gradle 검증
- 검토 방식: 1인 개발자 단일 `main-session`에서 source-read-only 일곱 관점을
  순차적으로 적용했다. review 중 production source를 수정하지 않았고,
  검토에서 발견한 테스트 관용구 수정은 재검증 후에만 반영했다.
- 기준: `$bluetape-kotlin-patterns`와 triggered `testing`, `spring-boot`,
  `module-setup`, `checklist`, `repository-hazards` reference, 그리고
  bluetape-workflow Type A의 P0/P1 게이트

## 통합 판정

| 단계 | P0 | P1 | P2 | P3 | 판정 |
| --- | ---: | ---: | ---: | ---: | --- |
| 현재 7-Tier implementation review | 0 | 0 | 2 | 0 | 로컬 구현 CLEAR, delivery PENDING |

P0/P1은 모두 0이다. P2-1은 현재 변경과 무관한 기존 JDBC ABI baseline 차이이며,
P2-2는 새 dependency-boundary 보조 task의 configuration-cache 제약이다. 두 항목은
숨기지 않고 아래에 고정했으며, #729의 공통 SPI 설계·구현을 되돌리거나 범위를 넓힐
근거는 아니다.

## 7단계 검토

| 단계 | 검토 범위 | P0 | P1 | P2 | P3 | 결과와 근거 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1. 보안 | raw SQL annotation 경계, sort property, 입력/로그 | 0 | 0 | 0 | 0 | `ExposedQueryCreator`는 Exposed expression을 생성하고 `Sort.toExposedOrderBy`는 실제 `Table.columns`만 선택한다. 알 수 없는 sort property는 Bluetape4k logging으로 건너뛰며 임의 identifier를 SQL에 삽입하지 않는다. 새 secret/credential/log payload 경로는 없다. |
| 2. SRE/운영 | auto-configuration, bean ownership, diagnostics, rollback | 0 | 0 | 1 | 0 | R2DBC auto-configuration은 database/pool/transaction을 소유하지 않고 type/name 조건으로 기존 `exposedMappingContext`를 보존한다. 경계 task는 resolved configuration을 실행 시 읽으므로 `notCompatibleWithConfigurationCache`를 명시했고 no-cache 실행을 통과했다. |
| 3. 구조적 영향 | module registration, dependency direction, backend ownership | 0 | 0 | 1 | 0 | `settings.gradle.kts`, README EN/KO, CI/Nightly, Kover, manual manifest, BOM POM에 common을 등록했다. R2DBC는 common만 `api`로 사용하며 JDBC adapter와 `spring-jdbc`를 compile/runtime graph에서 제외한다. JDBC legacy package는 deprecated binary facade로 남긴다. |
| 4. Kotlin/API 품질 | `$bluetape-kotlin-patterns`, public ABI, KDoc, assertions/logging | 0 | 0 | 0 | 0 | 공통 public KDoc은 한국어이며 새 source에 `!!`, `println`, `System.out`, `System.err`가 없다. 테스트는 `bluetape4k-assertions`를 사용하고 mapping race는 `MultithreadingTester`로 검증한다. common/JDBC/R2DBC module ABI check가 모두 통과했다. |
| 5. 테스트/타입/무응답 실패 | RED/GREEN, adapter semantics, silent failure | 0 | 0 | 0 | 0 | annotation/mapping/query/sort RED를 먼저 확인한 뒤 common 14 tests GREEN, JDBC 260 tests GREEN, R2DBC 317 tests GREEN(8 skipped)을 확보했다. common `Query`를 JDBC method metadata에서 실제로 읽고, R2DBC auto-config의 positive/back-off 조건을 `ApplicationContextRunner`로 검증한다. |
| 6. 성능/안정성 | cache identity, contention, transaction/resource regression | 0 | 0 | 0 | 0 | `ExposedMappingContext` 동시 조회 32회가 하나의 cached entity identity를 반환한다. JDBC/R2DBC full suite, Spring Modulith test, JDBC/R2DBC example compile이 통과했으며 새 unbounded retry, monitor, coroutine lifecycle 변경은 없다. |
| 7. 문서/릴리스/근거 | public docs, manual parity, changelog, BOM/coverage/evidence | 0 | 0 | 0 | 0 | common README/manual EN/KO와 adapter migration 문서를 갱신했다. `exportManualModuleInventory`와 `validate_manuals.rb`가 통과했고 BOM POM에 common artifact가 포함된다. Kover aggregate는 common 64.86%, JDBC 83.03%, R2DBC 83.85%, total 82.69%이다. |

## 통합 발견 사항과 disposition

### P2-1 — 기존 JDBC UUID ABI baseline 차이

`./gradlew :bluetape4k-exposed-jdbc:checkKotlinAbi --no-configuration-cache`는
`api/bluetape4k-exposed-jdbc.api`에만 있는 `UuidJdbcRepository`와
`UuidSoftDeletedJdbcRepository` descriptor 90줄 때문에 실패한다. 현재 source에는 두
선언이 여전히 존재하며, `git diff --name-only origin/develop -- exposed/jdbc
api/bluetape4k-exposed-jdbc.api`는 비어 있다. 따라서 #729가 만든 변경이 아니며, 공통
모듈 범위를 벗어나므로 baseline을 삭제하거나 갱신하지 않았다. common/JDBC/R2DBC
Spring Data module ABI는 각각 별도로 통과했다.

처리: **P2, 후속 UUID ABI 이슈로 유지**. 전체 `checkProductionAbi`의 최종 verdict는
이 차이 때문에 `PENDING`이며, 이 PR에서 해결됐다고 주장하지 않는다.

### P2-2 — dependency-boundary task의 configuration-cache 제약

`checkR2dbcDependencyBoundary`는 compile/runtime resolved artifact를 검사하기 위해
실행 시 configuration을 읽는다. Gradle configuration-cache에서 warning이 발생하지만
`notCompatibleWithConfigurationCache`를 선언하고 `--no-configuration-cache` 실행은
통과한다.

처리: **P2, 명시된 운영 제약**. 기능 검증은 no-cache 명령으로 재현 가능하며, 향후
Gradle task를 file-input 기반으로 바꿀 때 이 경계를 다시 검토한다.

## PR exact-head 사후 검증과 수정

PR #744의 exact head hosted CI를 fresh-read한 결과, 로컬 구현과 별개로
publication/CI 계약 두 건이 실패했다.

1. `spring-boot/common`의 `runtimeElements`가
   `org.jetbrains.kotlinx:kotlinx-coroutines-core`를 versionless external dependency로
   내보냈다. common이 API graph에서 coroutine을 노출하지만 coroutine BOM을 API-visible
   platform으로 선언하지 않은 것이 원인이었다.
2. root production ABI inventory가 35개로 갱신됐는데 CI workflow의 고정 검사는
   `34/34`를 계속 요구했다.

두 실패는 각각 `api(platform(bt4k.kotlinx.coroutines.bom))` 추가와 workflow의
`modules/baselines/actualDumps=35/35` 갱신으로 최소 수정했다. publication metadata/POM,
downstream consumer와 root compile/audit를 재실행해 모두 GREEN을 확인했고, JDBC는
worker 수를 1로 제한한 순차 실행에서 260 tests GREEN을 확인했다. 이 수정은 source
API나 legacy ABI facade를 변경하지 않는다.

수정 후에도 root `checkProductionAbi`의 UUID repository baseline 차이는 기존 P2-1로
남아 있으며, hosted exact-head rerun과 human review가 끝날 때까지 delivery verdict는
**PENDING**이다.

## 검증 근거

- `./gradlew :bluetape4k-exposed-spring-boot-common:test --no-configuration-cache` → **14 passing**, `BUILD SUCCESSFUL`
- `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test --no-configuration-cache` → **260 passing**, `BUILD SUCCESSFUL`
- `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-configuration-cache` → **317 tests**, **8 skipped**, `BUILD SUCCESSFUL`
- `./gradlew :bluetape4k-exposed-spring-boot-common:detekt :bluetape4k-exposed-spring-boot-jdbc:detekt :bluetape4k-exposed-spring-boot-r2dbc:detekt` → **BUILD SUCCESSFUL**
- `./gradlew :bluetape4k-exposed-spring-boot-common:checkKotlinAbi :bluetape4k-exposed-spring-boot-jdbc:checkKotlinAbi :bluetape4k-exposed-spring-boot-r2dbc:checkKotlinAbi --no-configuration-cache` → **BUILD SUCCESSFUL**
- `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:checkR2dbcDependencyBoundary` → boundary 통과; configuration-cache 제약은 P2-2로 기록
- `./gradlew :exposed-spring-boot-jdbc-demo:compileKotlin` → **BUILD SUCCESSFUL**
- `./gradlew :exposed-spring-boot-r2dbc-demo:compileKotlin` → **BUILD SUCCESSFUL**
- `./gradlew :bluetape4k-exposed-spring-modulith:compileKotlin` 및 `:test` → **BUILD SUCCESSFUL**
- `./gradlew exportManualModuleInventory` 및 `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml` → **Manuals are aligned.**
- `./gradlew :bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication --no-configuration-cache` → **BUILD SUCCESSFUL**; generated POM에 `bluetape4k-exposed-spring-boot-common` 존재
- `python3 .github/scripts/aggregate-kover-coverage.py spring-boot` → **total 82.69%**
- PR #744 exact-head hosted CI 최초 실패: common Gradle Module Metadata의 versionless
  coroutine runtime dependency 및 CI `34/34` inventory 계약 불일치
- 수정 후 `build -x test`, publication metadata/POM audit, downstream consumer audit →
  **모두 exit 0**; metadata `failures=0`, POM `failures=0`, downstream
  `publications=36/libraries=35/fixtures=1`
- 수정 후 `:bluetape4k-exposed-spring-boot-jdbc:test --max-workers=1` → **260 passing**
- R2DBC/common/production output import guard → **통과**; `println`, `System.out`, `System.err` hit 없음
- `git diff --check` → **통과**

## 최종 게이트

**CLEAR for local implementation; DELIVERY-PENDING for external integration.**

P0/P1은 0이다. PR 생성 후 exact-head hosted CI와 human review thread를 fresh-read해야
하며, #728 PR #743의 CI/review/merge 대기도 독립 상태로 남아 있다. merge, issue close,
canonical develop sync, worktree cleanup은 이 review가 승인하지 않는다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue, base, worktree, module slice, reference와 7개 tier 범위를 고정했다.
- [x] SPW-02 — P0/P1/P2/P3 disposition, ABI/CI delivery boundary와 후속 제약을 기록했다.
- [x] SPW-03 — 한국어 review prose와 code/command/API token을 보존했다.
- [x] SPW-04 — source diff, RED/GREEN, full test counts, Detekt, ABI, BOM, manual, Kover를 대조했다.
- [x] SPW-05 — 표·링크·unchecked gate와 최종 delivery 상태를 read-back했다.
