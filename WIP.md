# WIP - bluetape4k-exposed

Snapshot: 2026-08-01 KST
범위: `1.12.0` stable-release preflight 및 repository-state review follow-up.
열린 항목: 1.12.0 milestone review follow-up 10건 ([#600](https://github.com/bluetape4k/bluetape4k-exposed/issues/600), [#601](https://github.com/bluetape4k/bluetape4k-exposed/issues/601), [#602](https://github.com/bluetape4k/bluetape4k-exposed/issues/602), [#605](https://github.com/bluetape4k/bluetape4k-exposed/issues/605), [#608](https://github.com/bluetape4k/bluetape4k-exposed/issues/608), [#609](https://github.com/bluetape4k/bluetape4k-exposed/issues/609), [#610](https://github.com/bluetape4k/bluetape4k-exposed/issues/610), [#611](https://github.com/bluetape4k/bluetape4k-exposed/issues/611), [#612](https://github.com/bluetape4k/bluetape4k-exposed/issues/612), [#613](https://github.com/bluetape4k/bluetape4k-exposed/issues/613)).

## 범위 고정

이번 작업은 **release preflight만** 수행합니다. Tag, Maven Central publication, GitHub
Release, workflow dispatch, milestone close, stable-manual promotion 및 shipped 상태 선언은
이 작업 범위에서 수행하지 않습니다. 여기서는 후보 문서, 검증 증거, version authority 확인
준비 및 release hold만 갱신합니다.

## 현재 방향

`gradle.properties`의 `baseVersion=1.12.0` 및 빈 `snapshotVersion`을 release candidate
기준으로 유지합니다. 이는 tag, Maven Central publication 또는 GitHub Release가 생성되었다는
뜻이 아닙니다. 현재 마지막 공개 릴리스는 `1.11.0`(2026-06-27)입니다.

매뉴얼의 안정 참조는 정확한 `1.12.0` tag와 commit이 생긴 뒤에만 승격합니다. 따라서
`docs/manual/manifest.yaml`의 `1.11.0` release anchor는 이번 준비 변경에서 유지합니다.

## 릴리스 준비 상태

| 항목 | 상태 | 2026-08-01 KST 근거 및 다음 조치 |
|---|---|---|
| 기능/문서 milestone 작업 | 진행 중 | 현재 live milestone은 265개 closed / 10개 open입니다. 열린 review follow-up을 해결하고 full-matrix evidence를 고정해야 합니다. |
| CHANGELOG 후보 섹션 | 완료(후보) | [#598](https://github.com/bluetape4k/bluetape4k-exposed/issues/598)은 closed이고 [#599](https://github.com/bluetape4k/bluetape4k-exposed/pull/599)은 merged입니다. `CHANGELOG.md`의 2026-07-31 `1.12.0` section은 publication 선언이 아닌 후보 요약입니다. |
| 후보 head CI | 대기 | local candidate commit의 targeted/build 검증은 통과했습니다. 기준 `develop` head `4a3c6de7ece55d23f074cb62c37792897c5da4de`의 Nightly #30662576566은 14개 job success / 24개 conditional skip이며, candidate의 full database/cache/Testcontainers matrix 증거는 아직 없습니다. |
| 외부 version authority | 대기 | `bluetape4kVersion=1.11.0-SNAPSHOT`은 이 저장소에서 `1.12.0`으로 추정 변경하지 않습니다. 해당 POM/카탈로그 authority와 소비자 resolution 증거를 release checklist에서 확정해야 합니다. |
| tag 및 publication | 수행하지 않음 | release tag, Maven Central publication, GitHub Release 및 milestone close는 이번 preflight 범위에서 실행하지 않습니다. 별도 release authority와 별도 작업이 필요합니다. |
| stable manual 승격 | 대기 | immutable `1.12.0` ref, release-tree inventory, bilingual manifest 검증이 선행되어야 합니다. |

## Review follow-up evidence

- #601 Detekt multi-module aggregation, per-project baselines, XML report guard 및 nightly
  artifact upload를 적용했습니다. `./gradlew detekt`는 34개 non-empty XML report와 함께
  통과했습니다.
- 전체 `./gradlew test`는 40개 test-task summary에서 6,678 tests executed / 250 skipped /
  0 failure, `BUILD SUCCESSFUL`로 끝났습니다. `./gradlew build -x test`도 통과했습니다.
- #602, #605, #608, #609, #610, #611, #612는 targeted regression/compile 검증을
  통과했습니다. 로그는 caller-owned ID, entity, checkpoint, SQL/exception payload를
  직접 출력하지 않도록 제한했습니다.
- #613의 bounded public API report는 local candidate commit을 기준으로
  [`docs/review/2026-08-01-issue-613-public-api-compatibility.md`](docs/review/2026-08-01-issue-613-public-api-compatibility.md)와
  JSON machine-readable 결과로 보존했습니다. stable source-level removal은 0건이며,
  Ktor/Spring internal 또는 compiler-generated JVM 항목과 pre-existing constant 제거를
  별도로 분류했습니다.

## 활성 대기열

| 우선순위 | Issue | Milestone | Notes |
|---|---|---|---|
| P0 | [#600](https://github.com/bluetape4k/bluetape4k-exposed/issues/600) epic(review): close 1.12.0 repository-state findings | 1.12.0 | 하위 review follow-up을 통합하고 release hold를 유지합니다. |
| P1 | [#601](https://github.com/bluetape4k/bluetape4k-exposed/issues/601), [#602](https://github.com/bluetape4k/bluetape4k-exposed/issues/602), [#605](https://github.com/bluetape4k/bluetape4k-exposed/issues/605) | 1.12.0 | Detekt, BigQuery/logging, Lettuce 회귀 및 lint 증거를 정리합니다. |
| P1 | [#608](https://github.com/bluetape4k/bluetape4k-exposed/issues/608), [#609](https://github.com/bluetape4k/bluetape4k-exposed/issues/609), [#610](https://github.com/bluetape4k/bluetape4k-exposed/issues/610) | 1.12.0 | Ktor, Modulith, JDBC virtual-thread 경계를 검증합니다. |
| P1 | [#611](https://github.com/bluetape4k/bluetape4k-exposed/issues/611), [#612](https://github.com/bluetape4k/bluetape4k-exposed/issues/612), [#613](https://github.com/bluetape4k/bluetape4k-exposed/issues/613) | 1.12.0 | logging, Spring health, public API/full-matrix release hold를 검증합니다. |

## 열린 PR

현재 `develop` 대상 open PR은 없습니다. [#599](https://github.com/bluetape4k/bluetape4k-exposed/pull/599)은
#598을 참조한 문서 준비 PR로 merged 되었으며 tag/publish 권한을 포함하지 않았습니다.

## 갱신 메모

- 2026-08-01 KST에 `gh`와 repository configuration으로 검증했습니다. Live milestone은
  265 closed / 10 open, `develop` 대상 open PR은 0건이며, #598은 closed, #599는 merged입니다.
- 현재 live `develop` head는 `4a3c6de7ece55d23f074cb62c37792897c5da4de`이고 Nightly
  #30662576566은 해당 exact head에서 14개 job success / 24개 conditional skip입니다.
- 마지막 공개 `bluetape4k-exposed` 및 `bluetape4k-projects` release는 `1.11.0`이며,
  `bluetape4k-dependencies`의 마지막 공개 release는 `1.3.1`입니다.
- `bluetape4k-*` issue와 이를 해결하는 PR의 milestone을 일치시킵니다. Review follow-up의
  publication, tag, release, dispatch 및 milestone close는 별도 권한과 exact-head evidence
  전까지 보류합니다.
