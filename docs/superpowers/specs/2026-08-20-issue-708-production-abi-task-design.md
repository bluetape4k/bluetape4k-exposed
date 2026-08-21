# Issue #708 production ABI 검증 task 설계

## 문서 상태

- 대상 이슈: [#708](https://github.com/bluetape4k/bluetape4k-exposed/issues/708)
- 상위 Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 선행 runtime 변경: [#697](https://github.com/bluetape4k/bluetape4k-exposed/issues/697), PR #706
- 기준 base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- 구현 branch: `ci/issue-708-production-abi`
- worktree: `.worktrees/ci-issue-708-production-abi`
- 분류: **Type E — build/CI compatibility maintenance**
- 안정 manual: `docs/manual/**` `1.12.1` 불변

## 문제 정의

현재 production ABI는 모듈별 Kotlin 테스트 fixture에 흩어져 있다. 예를 들어
`spring-boot/jdbc`는 `ExposedJdbcRepositoryAbiCompatibilityTest`와 checked-in
descriptor resource를 소유하고, `ktor/exposed`는 별도의 `javap`/reflection
검증을 소유한다. 루트 build와 CI에는 published JVM module 전체의 inventory,
기준 artifact provenance, 누락 baseline을 fail-closed로 판정하는 공통 task가 없다.
따라서 production public signature 변경이 fixture가 없는 모듈에 들어가도 기본
게이트에서 일관되게 발견되지 않는다.

## 목표

1. 기존 `publishableProjects`/`exportPublicationInventory`가 계산하는 35개 공개
   publication에서 `java-platform` BOM 1개만 제외한 **34개 published JVM module**을
   task 입력으로 파생한다. 새 YAML을 module inventory의 두 번째 SSOT로 만들지 않고,
   별도 metadata에는 owner와 예외만 둔다.
2. exact base `develop@9fda4b0984d30d9e0f4514281e663d4bd4221e04`에서 KGP ABI dump를
   bootstrap해 current development baseline을 고정한다. `1.12.1` release-to-`2.0.0`
   비교는 의도된 major-line 차이를 포함하므로 별도 release-compatibility issue로
   분리한다.
3. Kotlin Gradle Plugin 2.4.10의 내장 `abiValidation`/`checkKotlinAbi`를 주 게이트로
   사용하고 `binariesSource = MAVEN_PUBLICATIONS`를 단일 구성 지점에서 설정한다.
   public constructor/method의 제거·descriptor 변경·추가는 모두 baseline update 전
   실패로 판정한다.
4. 루트 `checkProductionAbi`가 34개 모듈의 task 존재, non-empty actual dump, checked-in
   `api/<project>.api` baseline, orphan/unknown baseline을 fail-closed로 확인한다.
5. 기존 `spring-boot/jdbc`, `spring-boot/r2dbc`, `ktor/exposed` ABI fixture는
   module-specific consumer smoke로 유지하고, 공통 task가 보호하는 descriptor와
   parity를 검증한다.

## 불변 경계와 비목표

- production Kotlin/Java API, constructor, ABI descriptor를 이 slot에서 변경하지
  않는다. 변경이 필요하면 별도 feature/fix issue로 분리한다.
- public API를 annotation 또는 source diff로 추론하지 않는다. compiled JVM output과
  exact descriptor가 기준이다.
- 새로운 compatibility plugin/dependency를 추가하지 않는다. KGP 2.4.10에 이미
  포함된 ABI validation을 사용하며, legacy `kotlinx.binary-compatibility-validator`
  신규 도입은 거부한다.
- baseline 중앙 경로는 root `api/<project-name>.api`이며 각 publication project에
  `referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))`를 명시한다.
  초기 bootstrap만 exact base
  `develop@9fda4b0984d30d9e0f4514281e663d4bd4221e04`에서 수행하고 `updateKotlinAbi`는
  CI에서 실행하지 않는다. 이후 갱신은 API owner `debop`이 linked API decision과
  승인된 candidate head를 확인한 뒤에만 수행한다. 임의의 current output overwrite,
  additive-only report, release artifact provenance를 이 slot의 성공 조건으로
  사용하지 않는다.
- `docs/manual/**`, BOM/catalog, publishing credential, release/tag/central upload,
  R2DBC/JDBC runtime semantics는 변경하지 않는다.
- 기존 module-specific consumer/ABI fixture를 삭제하지 않는다. 공통 task가 같은
  contract를 증명하는지 확인한 뒤 별도 정리 issue에서 삭제 여부를 결정한다.

## 선택지와 결정

### A. KGP 내장 ABI validation — 채택

Kotlin Gradle Plugin 2.4.10의 `abiValidation`, `checkKotlinAbi`, `updateKotlinAbi`와
`MAVEN_PUBLICATIONS` source를 주 게이트로 사용한다. immutable catalog가 고정한 KGP
버전에서만 opt-in을 허용하고 catalog upgrade 시 `help`/`checkProductionAbi` compile
gate를 다시 실행한다. 공식 문서: [Kotlin binary compatibility validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html).

### B. 외부 japicmp/Revapi plugin 도입

이번 slot에서는 채택하지 않는다. release-JAR 간 비교가 필요한 후속 gate에서만
별도 dependency decision으로 검토한다.

### C. source signature grep/diff

거부한다. Kotlin default method, synthetic bridge, generic erasure와 JVM descriptor를
표현하지 못하며 `javap` 기반 기존 fixture와도 계약이 다르다.

### D. focused `javap` parity/negative probe — 보조 도구

custom `javap`는 주 비교기를 재구현하지 않고 KGP dump가 비어 있거나 orphan baseline을
놓치는 negative probe와 기존 fixture parity에만 사용한다. KGP task가 actual dump를
생성하지 못하면 aggregate guard가 즉시 실패한다.

## 산출물 구조(설계)

```text
api/
  <project-name>.api              # KGP checked-in baseline, central referenceDumpDir
build/abi/
  reports/production-abi.txt       # derived inventory/task evidence
```

publication inventory는 `build.gradle.kts:721-757`의 existing source of truth에서
파생한다. aggregate task는 34개 expected module 각각에 대해 `checkKotlinAbi` task,
non-empty actual dump, non-empty 중앙 `api/<project>.api` baseline을 확인하고,
orphan 또는 unknown baseline을 실패시킨다. 예외 파일은 이 slot에 만들지 않으며
예외가 필요하면 별도 API decision issue에서 owner·사유·만료를 승인받는다.
`generatedAt` 같은 비결정적 metadata는 비교 입력에 포함하지 않는다. KGP가 생성하는
ABI dump의 canonical blank-at-EOF는 `.gitattributes`로 whitespace 예외를 고정한다.

## 실패·복구 계약

| 상황 | 판정 | 복구 |
| --- | --- | --- |
| current class/jar 누락 | FAIL | compile/jar task와 module mapping 수정 |
| baseline 파일/메타데이터 누락 | FAIL | exact base 또는 승인된 candidate head에서 재생성하고 owner 승인 provenance commit |
| `javap` timeout/non-zero 또는 malformed descriptor | FAIL | toolchain/classpath 원인 조사; skip 금지 |
| removed/changed/added public descriptor | FAIL | API owner 승인 후 candidate-head baseline update 또는 별도 compatibility issue |
| actual dump 0개/empty baseline/orphan baseline | FAIL | module mapping·KGP task·baseline owner 수정 |
| proven docs-only path-filtered skip | N/A | ABI 영향이 없음을 기록; required ABI gate PASS로 집계 금지 |
| non-doc selector miss/task skip | FAIL | selector와 required classifier를 수정하고 재실행 |

## 수용 기준

- [ ] existing publication inventory에서 파생한 34개 JVM module 모두 중앙
  `referenceDumpDir` 아래 non-empty KGP baseline을 갖고, API owner/review 링크가
  기록된다. 이 slot에는 suppressing exception file이 없다.
- [ ] 루트 `checkProductionAbi`가 모든 module `checkKotlinAbi`, actual dump, baseline,
  orphan/unknown 조건을 fail-closed로 검증한다.
- [ ] public descriptor 제거·변경·추가는 승인된 baseline update 전 모두 실패한다.
- [ ] 기존 spring-boot/jdbc·spring-boot/r2dbc·ktor/exposed fixture와 공통 task의 parity가
  테스트로 고정된다.
- [ ] PR CI required job이 task를 실행하고, missing artifact/filtered skip을 PASS로
  처리하지 않는다.
- [ ] `detekt`, affected compile/test, `git diff --check`, inventory/provenance
  validator와 README/manual 안정성 검사가 통과한다.

## 설계 gate

- [x] SPW-01 — Issue/Epic/base, 기존 fixture와 publishing 경계를 current source/live
  GitHub에서 대조했다.
- [x] SPW-02 — 선택지, fail-closed, baseline provenance, rollback과 금지 범위를 고정했다.
- [x] SPW-03 — 한국어 prose와 `javap`, `JDK 25`, `N/A`, `PENDING`, API token을 보존했다.
- [x] SPW-04 — publication inventory, KGP 2.4.10 ABI DSL, CI skip classifier와 기존
  JDBC/R2DBC/Ktor fixture를 대조하고 새 dependency를 제외했다.
- [x] SPW-05 — Markdown headings, table, code fence, checklist와 issue acceptance를
  read-back했다.

## 설계 판정

`CLEAR` — KGP 내장 ABI, exact-base baseline, 34-module aggregate guard, R2DBC
parity와 무재시도 CI 실행을 반영했고, 독립 architect/critic review에서 P0/P1/P2
`0/0/0`을 확인했다. implementation은 별도 worktree에서 완료했으며, hosted
exact-head CI와 PR review는 이 문서의 로컬 DoD 이후 별도 gate로 남긴다.

## Implementation read-back (2026-08-21)

- `buildSrc/src/main/kotlin/ProductionAbiSupport.kt`가 empty/missing/orphan
  inventory를 fail-closed로 검증하고, root `build.gradle.kts`가 34개 publication을
  KGP task와 중앙 `api/` baseline에 연결한다.
- `api/`에는 exact base에서 생성한 non-empty baseline 34개가 있으며 aggregate
  report는 `modules=34/34`, `baselines=34/34`, `actualDumps=34/34`, orphan `0`,
  `emptyBaselines=0`이다. Linux의 case-sensitive ABI 출력에서만 구분되는
  `UUID`/Kotlin `Uuid` 공개 descriptor 쌍도 JDBC/R2DBC baseline에 고정했다.
- 기존 JDBC/R2DBC/Ktor fixture는 각각 `3/3`, `2/2`, `3/3` pass로 순차 검증했다.
- CI compile retry에서 `checkKotlinAbi`를 제외하고, 후속 ABI 단계는 `pipefail`/`tee`,
  non-empty report, 실제 publication inventory JSON과 `if: always()` artifact upload,
  non-doc skip fail-closed를 적용했다.
- `buildSrc` TDD RED/GREEN, full `buildSrc:test`, `detekt`, `actionlint`,
  `git diff --check`를 로컬에서 통과했다. macOS의 case-insensitive classpath는
  `UUID`와 Kotlin `Uuid` class 파일을 충돌시켜 local `checkProductionAbi`가
  `Uuid` descriptor 제거로 실패하므로, 이 쌍의 최종 증거는 hosted Linux에서만
  판정한다. `bluetape4k-exposed-core` 기준선에
  public descriptor 추가·제거·descriptor 변경을 각각 임시 적용한 controlled
  probe가 모두 `checkKotlinAbi` exit `1`/`ABI has changed`로 실패한 뒤 원복됐다.
  hosted PR run `32435651147`은 이전 head에서 이 Linux 전용 descriptor 누락을
  발견해 실패했지만, baseline 보정 후 corrected head run
  `32438771629`의 compile·POM·no-retry ABI·두 artifact upload가 모두 성공했다.
  nightly backend evidence는 아직 실행하지 않았다.
