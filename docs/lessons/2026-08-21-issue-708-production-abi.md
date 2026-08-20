# Issue #708 production ABI 공통 게이트 lesson

## 결정

Kotlin Gradle Plugin `2.4.10`에 포함된 `abiValidation`을 공개 JVM 호환성의
주 게이트로 채택했다. `MAVEN_PUBLICATIONS`를 입력으로 사용하고, 각 published
module이 root `api/<project-name>.api`를 기준선으로 공유하도록 구성했다. 별도
compatibility plugin이나 custom `javap` 비교기는 도입하지 않았다.

publication inventory는 기존 `publishableProjects`와
`exportPublicationInventory`에서 파생했다. 현재 publication 35개에서
`bluetape4k-exposed-bom` 1개를 제외한 34개가 production ABI 대상이다. 이 목록을
새 YAML로 복제하지 않아 inventory drift의 두 번째 SSOT를 만들지 않았다.

최초 기준선은 exact base `develop@9fda4b0984d30d9e0f4514281e663d4bd4221e04`에서
수동 wrapper로 bootstrap했다. `updateKotlinAbi`는 CI에 연결하지 않으며, 이후 갱신은
API owner `debop`이 연결된 API decision과 승인된 candidate head를 확인한 뒤에만
수행한다. `1.12.1` release와 `2.0.0` development line의 release-to-release 비교는
별도 issue 범위다.

## 실패에서 얻은 교훈

- KGP의 `checkKotlinAbi`만 믿으면 actual dump가 비어 있는 경우를 충분히 판정하지
  못할 수 있으므로, root `checkProductionAbi`가 expected/baseline/actual의
  missing·orphan 집합을 별도로 fail-closed 검증해야 한다.
- KGP ABI dump는 canonical blank-at-EOF를 생성하므로, baseline 자체를 변형하기보다
  `.gitattributes`에서 `api/*.api`의 해당 whitespace 규칙만 명시해야 한다.
- compile/build retry에 ABI 검사를 포함하면 실패 결과를 재시도로 덮고 비용도 늘린다.
  CI에서는 retry build에서 `-x checkKotlinAbi`를 사용하고, 산출물이 준비된 뒤
  ABI aggregate를 무재시도로 실행한다.
- 실패한 aggregate 뒤에도 진단을 남기려면 로그는 `tee`로 미리 만들 수 있지만
  `production-abi.txt`를 placeholder로 선행 생성하면 artifact 검증이 무력화된다.
  report는 task가 실제로 생성한 뒤 `test -s`와 `34/34` read-back으로 확인하고,
  derived publication inventory JSON도 별도 artifact로 보존한다.
- buildSrc 순수 helper의 empty/missing/orphan negative는 실제 JDBC/R2DBC/Ktor
  consumer fixture와 같은 증거가 아니다. helper 단위 테스트, aggregate positive,
  세 fixture smoke를 각각 실행하고 결과를 합쳐야 한다.

## 검증 evidence

- TDD RED: helper를 구현하기 전 `ProductionAbiSupportTest` 컴파일이
  `validateProductionAbiInventory` 미해결로 실패했다.
- TDD GREEN: `:buildSrc:test --tests ProductionAbiSupportTest` 통과.
- full buildSrc test: `BUILD SUCCESSFUL`, 6 actionable tasks.
- `checkProductionAbi`: `modules=34/34`, `baselines=34/34`,
  `actualDumps=34/34`, `orphanBaselines=0`, `orphanActuals=0`,
  `emptyBaselines=0`.
- ABI consumer fixture: JDBC 3/3, R2DBC 2/2, Ktor 3/3; failure/error 0/0.
- compile retry-equivalent build, `detekt`, `actionlint`, `git diff --check` 통과.
- `bluetape4k-exposed-core` 기준선에 descriptor 추가·제거·변경을 임시 적용한
  controlled probe가 각각 `checkKotlinAbi` exit `1`/`ABI has changed`로 실패한 뒤
  원복됐다.

## 남은 경계

이 lesson은 로컬 implementation evidence를 기록한다. PR exact-head review, hosted
CI, nightly backend run, merge는 이 slot에서 실행하지 않았으며 별도 권한과 fresh
evidence가 필요하다. KGP catalog upgrade, release-JAR `japicmp`/`Revapi` 비교, JDBC
force-abort는 별도 issue로 유지한다.

## DoD Status

Required checks: 7/8; N/A: 0; Blocked: 0

Final status: **PENDING — 로컬 구현과 검증은 완료했지만 PR/hosted exact-head gate는
아직 실행하지 않음**
