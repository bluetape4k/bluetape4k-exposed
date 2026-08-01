# WIP - bluetape4k-exposed

Snapshot: 2026-08-01 KST
범위: `1.12.0` stable-release preflight 및 #615 한국어 CHANGELOG 용어 follow-up.
열린 항목: 1.12.0 milestone documentation follow-up 1건 ([#615](https://github.com/bluetape4k/bluetape4k-exposed/issues/615)).

## 범위 고정

이번 작업은 **release preflight 문서와 #615 용어 정리만** 수행합니다. Tag, Maven Central publication, GitHub
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
| 기능/문서 milestone 작업 | 진행 중 | 현재 live milestone은 275개 closed / 1개 open이며 남은 항목은 #615입니다. #615 문서 검증을 마친 뒤에도 full-matrix evidence와 release hold를 유지해야 합니다. |
| CHANGELOG 후보 섹션 | 갱신(후보) | `CHANGELOG.md`의 모든 한국어 `Fixed` 범주를 `버그 수정`으로 통일하고 #615를 문서화했습니다. 2026-07-31 `1.12.0` section은 publication 선언이 아닌 후보 요약입니다. |
| 후보 head CI | 확인 | 현재 `develop` head `f73039ee16a6616ed86f6ed7e80bffb9b2f42bed`의 CI #30690742771은 성공했습니다. 이번 문서 브랜치는 diff/reference 검증만 수행하며 full database/cache/Testcontainers matrix 증거로 간주하지 않습니다. |
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
- #613의 bounded public API report는 merged candidate commit을 기준으로
  [`docs/review/2026-08-01-issue-613-public-api-compatibility.md`](docs/review/2026-08-01-issue-613-public-api-compatibility.md)와
  JSON machine-readable 결과로 보존했습니다. stable source-level removal은 0건이며,
  Ktor/Spring internal 또는 compiler-generated JVM 항목과 pre-existing constant 제거를
  별도로 분류했습니다.

## 활성 대기열

| 우선순위 | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#615](https://github.com/bluetape4k/bluetape4k-exposed/issues/615) | 1.12.0 | 한국어 CHANGELOG의 `Fixed` 범주를 `버그 수정`으로 표준화하고 문서 상태를 최신화합니다. release hold는 유지합니다. |

## 열린 PR

현재 `develop` 대상 open PR은 없습니다. [#599](https://github.com/bluetape4k/bluetape4k-exposed/pull/599)은
#598을 참조한 문서 준비 PR로, [#614](https://github.com/bluetape4k/bluetape4k-exposed/pull/614)은
review follow-up PR로 merged 되었으며 어느 쪽도 tag/publish 권한을 포함하지 않았습니다.

## 갱신 메모

- 2026-08-01 KST에 `gh`와 repository configuration으로 검증했습니다. Live milestone은
  275 closed / 1 open이며, 열린 항목은 #615입니다. `develop` 대상 open PR은 0건이고,
  #598은 closed, #599와 #614는 merged입니다.
- 현재 live `develop` head는 `f73039ee16a6616ed86f6ed7e80bffb9b2f42bed`이고 CI
  #30690742771은 success입니다. 이 CI 결과는 full database/cache/Testcontainers matrix를
  대체하지 않습니다.
- 마지막 공개 `bluetape4k-exposed` 및 `bluetape4k-projects` release는 `1.11.0`이며,
  `bluetape4k-dependencies`의 마지막 공개 release는 `1.3.1`입니다.
- `bluetape4k-*` issue와 이를 해결하는 PR의 milestone을 일치시킵니다. #615의 문서 변경은
  별도 PR 검토 대상이며, publication, tag, release, dispatch 및 milestone close는 별도
  권한과 exact-head evidence 전까지 보류합니다.
