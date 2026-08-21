# Issue #708 production ABI task 설계 review

## 범위

- Issue: [#708](https://github.com/bluetape4k/bluetape4k-exposed/issues/708)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 기준 base: `develop@9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- 검토 worktree: `ci/issue-708-production-abi`
- 검토 대상:
  - `docs/superpowers/specs/2026-08-20-issue-708-production-abi-task-design.md`
  - `docs/superpowers/plans/2026-08-20-issue-708-production-abi-task-plan.md`

## 독립 review 결과

**CLEAR — P0=0, P1=0, P2=0**

이전 review의 차단 사유를 다음과 같이 해소했다.

1. KGP `2.4.10` 내장 `abiValidation`/`checkKotlinAbi`와
   `MAVEN_PUBLICATIONS`를 주 gate로 고정하고, custom `javap`는 보조 probe로 낮췄다.
2. baseline 중앙 경로를 `api/<project-name>.api`와 명시적 `referenceDumpDir`로
   고정했다. 최초 bootstrap은 exact base에서만 수행하고, 이후에는 API owner `debop`의
   linked decision과 승인된 candidate head가 있어야 `updateKotlinAbi`를 실행한다.
3. publication SSOT에서 35개 중 BOM을 제외한 34개 JVM module을 파생한다. 별도 module
   inventory YAML이나 suppressing exception file은 만들지 않는다.
4. Task 0은 discovery만 수행하고 RED는 Task 2 구현 이후 실행한다. buildSrc 순수
   helper negative와 JDBC/R2DBC/Ktor 실제 consumer smoke를 분리해 KGP `.api`를 raw
   descriptor로 번역하는 가짜 parity를 만들지 않는다.
5. hosted CI는 compile/build retry 뒤 ABI aggregate를 재시도 없이 실행한다.
   `--rerun-tasks`는 local negative 재현에만 사용한다. 최종 report는 placeholder가
   아니라 aggregate가 생성한 non-empty `production-abi.txt`이며 `34/34`를 검사한다.
6. proven docs-only skip만 N/A이고, non-doc selector miss와 task skip은 FAIL이다.
   계획 자체 SPW-01~05 read-back도 완료했다.

## 설계 gate 검증 evidence (historical)

- Korean terminology audit: `3/3` files, findings `0`
- `git diff --check`: PASS
- architecture independent review: PASS, P0/P1/P2 `0/0/0`
- 당시 implementation/tests: N/A — 이 항목은 설계 gate 시점의 기록이다.

## Implementation 7-Tier read-back (2026-08-21)

현재 구현 diff를 기준으로 production source/API, catalog/BOM, `docs/manual/**`를
변경하지 않았고, root/buildSrc/CI와 exact-base `api/` baseline만 변경했다.

- **Tier 1 구조/경계:** KGP `2.4.10` 내장 ABI를 root configuration으로 연결하고,
  pure buildSrc helper와 Gradle task를 분리했다. 새로운 runtime abstraction이나
  dependency는 없다.
- **Tier 2 계약/호환성:** 35 publication에서 BOM을 제외한 34개를 derived inventory로
  고정하고 central `referenceDumpDir`를 사용한다. baseline update는 manual-only다.
- **Tier 3 실패/복구:** missing/orphan/empty inventory를 helper와 aggregate에서
  fail-closed로 처리한다. CI는 compile retry와 ABI no-retry를 분리하고 report를
  placeholder로 만들지 않는다.
- **Tier 4 동시성/수명:** production runtime 코드를 건드리지 않으며, Gradle task
  dependency가 actual dump 생성 뒤 aggregate를 실행한다.
- **Tier 5 테스트:** TDD RED/GREEN, full buildSrc test, JDBC `3/3`, R2DBC `2/2`,
  Ktor `3/3` fixture, aggregate 구조 `34/34`, detekt/actionlint를 확인했다.
  macOS local ABI dump는 case-insensitive classpath 때문에 `UUID`/Kotlin `Uuid`
  쌍을 재현하지 못하므로 최종 aggregate 증거는 hosted Linux에서 확인한다.
- **Tier 6 운영/CI:** `pipefail`/`tee`, `if: always()` artifact upload,
  `if-no-files-found: error`, non-doc build skip fail-closed를 적용했다.
- **Tier 7 문서/유지보수:** 설계·계획·lesson을 현재 구현 evidence와 일치시켰고,
  Korean terminology audit와 `git diff --check`를 통과했다.

controlled public descriptor mutation은 로컬에서 addition/removal/change 모두
실행했고, 각 probe가 `checkKotlinAbi` exit `1`로 실패한 뒤 기준선을 원복했다.
hosted PR run `32435651147`은 이전 head에서 Linux 전용 `Uuid` baseline 누락으로
실패했지만, JDBC/R2DBC baseline과 canonical EOF 보정 후 corrected-head run
`32438771629`와 최신 exact-head run `32439775309`
(`03111acf993e9170590aaddc224889ba9fb56971`)의 compile·POM·no-retry ABI·두
artifact upload가 모두 성공했다. 최신 run은 13개 성공, 25개
path-filtered skipped, 실패 0이다.
nightly backend run과 fresh PR review/merge는 별도 gate다.

## 남은 구현 경계

- 이 review는 설계·계획과 로컬 implementation 7-Tier gate를 함께 기록한다.
  `api/` 34개 bootstrap, root Gradle task, CI 변경, fixture 실행은 로컬에서
  검증됐고, hosted exact-head gate는 별도 DoD다.
- `1.12.1` release-to-`2.0.0` 비교, KGP catalog upgrade, release-JAR japicmp/Revapi,
  JDBC force-abort는 별도 issue 범위다.
- 안정 `docs/manual/**` `1.12.1`, production runtime/API, BOM/catalog는 불변이다.

## DoD Status

Required checks: 7/8; N/A: 0; Blocked: 0

| Check | Status | Evidence |
| --- | --- | --- |
| 설계·범위·기준선 | PASS | exact base, 34-module derived inventory, owner lifecycle |
| tool/implementation boundary | PASS | KGP built-in primary, no new dependency |
| fail-closed/negative path | PASS | Task 2 negative matrix, no placeholder report |
| fixture/CI parity | PASS | JDBC/R2DBC/Ktor smoke commands, no-retry aggregate |
| implementation 7-Tier/static gate | PASS | buildSrc/build/ABI, detekt, actionlint, terminology audit, diff check |
| documentation/evidence synchronization | PASS | design, plan, review, lesson read-back |
| controlled descriptor negative | PASS | core addition/removal/change probes each exit `1`, then baseline restored |
| hosted exact-head CI/PR review | PENDING | latest exact-head run `32439775309` passed hosted CI; fresh PR review remains |

Final status: **PENDING — latest exact-head hosted CI passed; fresh PR review/merge
and nightly backend gates remain**

Unchecked required items: fresh PR exact-head review, approved merge, nightly backend evidence.
