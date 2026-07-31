# WIP - bluetape4k-exposed

Snapshot: 2026-07-31 KST
범위: `1.12.0` stable-release preflight.
열린 항목: release-preflight documentation 1건 ([#598](https://github.com/bluetape4k/bluetape4k-exposed/issues/598)).

## 현재 방향

`gradle.properties`의 `baseVersion=1.12.0` 및 빈 `snapshotVersion`을 release candidate
기준으로 유지합니다. 이는 tag, Maven Central publication 또는 GitHub Release가 생성되었다는
뜻이 아닙니다. 현재 마지막 공개 릴리스는 `1.11.0`(2026-06-27)입니다.

매뉴얼의 안정 참조는 정확한 `1.12.0` tag와 commit이 생긴 뒤에만 승격합니다. 따라서
`docs/manual/manifest.yaml`의 `1.11.0` release anchor는 이번 준비 변경에서 유지합니다.

## 릴리스 준비 상태

| 항목 | 상태 | 2026-07-31 KST 근거 및 다음 조치 |
|---|---|---|
| 기능/문서 milestone 작업 | 완료 | #598 생성 전 1.12.0 milestone의 264개 delivery issue가 모두 닫혔고, `develop` 대상 open PR은 0건이었습니다. |
| CHANGELOG 후보 섹션 | 진행 중 | [#598](https://github.com/bluetape4k/bluetape4k-exposed/issues/598)에서 2026-07-31자 `1.12.0` section을 실제 closed issue로 정리합니다. |
| 후보 head CI | 대기 | tag 후보와 정확히 일치하는 full/nightly 검증 결과가 아직 이 WIP에 기록되지 않았습니다. |
| 외부 version authority | 대기 | `bluetape4kVersion=1.11.0-SNAPSHOT`은 이 저장소에서 `1.12.0`으로 추정 변경하지 않습니다. 해당 POM/카탈로그 authority와 소비자 resolution 증거를 release checklist에서 확정해야 합니다. |
| tag 및 publication | 대기 | release tag, Maven Central publication, GitHub Release 및 milestone close는 release authority가 부여된 뒤에만 수행합니다. |
| stable manual 승격 | 대기 | immutable `1.12.0` ref, release-tree inventory, bilingual manifest 검증이 선행되어야 합니다. |

## 활성 대기열

| 우선순위 | Issue | Milestone | Notes |
|---|---|---|---|
| P0 | [#598](https://github.com/bluetape4k/bluetape4k-exposed/issues/598) docs(release): refresh 1.12.0 changelog and release preflight state | 1.12.0 | 후보 변경, 현재 근거 및 미완료 release gate를 갱신합니다. tag/publish는 범위 밖입니다. |

## 열린 PR

[#599](https://github.com/bluetape4k/bluetape4k-exposed/pull/599)가 #598을 참조하는 유일한
`develop` 대상 release-preflight PR입니다. 이 PR은 문서 준비만 포함하며 tag/publish 권한을
포함하지 않습니다.

## 갱신 메모

- 2026-07-31 KST에 `gh`와 repository configuration으로 검증했습니다.
- 마지막 공개 `bluetape4k-exposed` 및 `bluetape4k-projects` release는 `1.11.0`이며,
  `bluetape4k-dependencies`의 마지막 공개 release는 `1.3.1`입니다.
- `bluetape4k-*` issue와 이를 해결하는 PR의 milestone을 일치시킵니다.
