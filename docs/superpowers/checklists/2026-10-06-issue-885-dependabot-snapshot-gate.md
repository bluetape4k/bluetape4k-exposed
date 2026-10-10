#885 Gradle 의존성 그래프 제출 게이트

상태: 원인 분석 완료 · 중앙 ignore 정책과 catalog pin 로컬 검증 완료 · hosted 재검증 대기

- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 브랜치: `develop`
- 작업 브랜치: `fix/issue-885-dependabot-snapshots`

## 확인된 현상과 원인

- Dependabot run [#36483348889](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/36483348889)은
  `afb97831e1025e3c98bd7153c68a21b95f7d25ec`에서
  `Job dependencies not found in the dependency snapshot`을 보고했습니다.
- 같은 SHA의 dependency submission run
  [#34837074281](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34837074281)은 성공했고,
  run log도 `Dependency results for the repo have been successfully updated`라고
  기록했습니다.
  보존 artifact의 SHA/ref는 동일하며, 1,247개 resolved node가 포함된
  `settings.gradle.kts` 단일 manifest 안에 오류 로그에 나온 group/artifact가
  여러 version으로 존재합니다. 그러므로 해당 실행의 artifact만으로는 오류 이름의
  좌표가 제출 결과에서 누락된 것이 원인이라고 결론 내릴 수 없습니다.
- 2026-10-09 중앙 catalog 조사에서는 동일한 Exposed SHA
  `a12896750f3130841ac4cf142fc63e438228312d`의 submission run
  [#37914457800](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37914457800)이
  1,283개 좌표를 제출하고 성공한 뒤 22초 후 시작한 Dependabot run
  [#37914573273](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37914573273)이
  manifest parsing 단계에서 실패한 사실을 확인했습니다. 중앙 조사 기록은 외부 catalog가
  `.gradle` 아래로 내려받아져 Dependabot이 지원하는 체크인 manifest로 해석되지 않는
  불일치를 근본 원인으로 판단합니다. 제출 지연만으로는 이 결과를 설명할 수 없습니다.
- 실패 로그는 updater가 오류를 보고한 group/artifact 이름만 기록합니다. 각 job이
  조회한 정확한 그래프 ID, manifest 경로, version을 제공하지 않아 artifact 항목과
  failed lookup을 1:1로 대조할 수 없습니다.
- 최신 develop SHA `770b3edb42bc9aa0eda21363968125c3daf610ef`의 dependency
  submission run [#37310287810](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37310287810)도
  성공했고, 보존 artifact의 `sha`, `ref`, 대상 좌표를 확인했습니다.
- 같은 SHA의 Dependabot run
  [#37310309441](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37310309441)은
  dependency submission 완료 시각보다 먼저 시작해 실패했습니다. 이 실행은
  제출과의 순서 경쟁 가능성을 보여 주지만, 과거에 같은 SHA로 제출한 그래프가
  성공했는데도 이후 실행이 실패한 현상까지 설명하지는 않습니다.
- 현재 GitHub dependency-graph SBOM에도 대상 group/artifact가 있으나, 응답에는
  해당 그래프의 commit SHA가 없어 최신 develop SHA에 대한 실시간 Dependabot 해소 여부를
  증명하지 않습니다.
- 기존 workflow는 push event의 `github.sha` 대신 이동 가능한
  `refs/heads/develop`을 checkout했습니다. 이를 특정 과거 실패의 입증된 원인으로
  보지는 않습니다. 다만 workflow가 생성하는 트리와 event SHA가 어긋나지 않도록
  보강할 수 있는 provenance 결함입니다.
- 따라서 **외부 catalog와 Dependabot이 읽는 manifest 사이의 불일치를 근본 원인으로
  판단합니다**. 이 수정의 hosted 검증에서는 같은 SHA의 그래프 제출 이후 보안 업데이트가
  오류 없이 처리되는지와 대상 경고가 실제로 해소됐는지를 확인해야 합니다.

## 적용한 수정

- checkout ref를 `${{ github.sha }}`로 고정했습니다.
- checkout 직후 `git rev-parse HEAD`와 `GITHUB_SHA`를 비교해 불일치 시 실패하고,
  exact commit/ref를 `GITHUB_STEP_SUMMARY`에 기록합니다.
- action의 기본 제출 모드와 report directory는 이미
  `generate-submit-and-upload` 및 `dependency-graph-reports`이므로 중복 설정하지
  않습니다. artifact 보존 여부는 다음 hosted run에서 확인합니다.
- `.github/dependabot.yml`은 Gradle 항목과 중앙 catalog에서 생성한 ignore 목록을
  유지합니다. 중앙 소유 좌표의 버전 갱신은 `bluetape4k-dependencies`에서 처리하고,
  `open-pull-requests-limit: 0`으로 일반 Gradle 버전 업데이트 PR을 막습니다. 이 한도는
  보안 업데이트 경로를 끄지 않으며, `github-actions` 업데이트도 유지합니다.
- Exposed의 기본 catalog ref와 CI 검증 ref를 병합된 중앙 catalog commit
  `198147fd6ca2992fae32e175bd5f3d396c497096`으로 맞췄습니다.
- 중앙 저장소의 [Gradle 및 GitHub Actions Dependabot 설정](https://github.com/bluetape4k/bluetape4k-dependencies/blob/develop/.github/dependabot.yml)과
  [#885 원인 분석 및 중앙 catalog 정책](https://github.com/bluetape4k/bluetape4k-dependencies/blob/develop/docs/lessons/2026-10-09-gradle-dependabot-central-catalog.md)을
  기준 정보로 사용합니다.

## Hosted 완료 게이트

- [ ] provenance guard가 반영된 exact `develop` SHA에서 Dependency Submission run이
  성공합니다.
- [ ] run summary와 artifact가 동일한 event SHA/ref를 기록하고 의존성 그래프 JSON을 보존합니다.
- [ ] Exposed CI가 중앙 catalog commit
  `198147fd6ca2992fae32e175bd5f3d396c497096`을 체크아웃하고 검증합니다.
- [ ] 해당 SHA의 submission 이후 Gradle 보안 업데이트 run에서 `dependency_not_found`가 발생하지 않거나,
  중앙 ignore 대상이면 해당 경고와 중앙 catalog 적용 결과를 근거로 확인합니다.
- [ ] 대상 alert/graph coordinate의 현재 상태를 live API에서 다시 읽습니다.

중앙 ignore는 보안 업데이트도 제외하므로, 무시된 좌표의 alert를 해결된 것으로 간주하지
않습니다. catalog 반영 뒤 소비 graph 버전과 live alert 상태를 확인합니다.

정확한 head push와 PR 갱신은 CG-11부터 CG-13까지의 승인·검증 절차로 수행합니다.
병합, workflow dispatch, 중앙 Dependabot 재실행 및 이슈 종료는 이 작업 범위에
포함하지 않습니다. #885는 hosted 게이트와 alert 상태를 확인할 때까지 열어 둡니다.

## 로컬 근거

- `actionlint .github/workflows/ci.yml .github/workflows/dependency-submission.yml`: 통과
- `.github/dependabot.yml` YAML 파싱: Gradle/Actions 항목과 Gradle PR 한도 `0` 확인
- Dependencies 작업 트리에서 `python3 scripts/sync-shared-versions.py --workspace /Users/debop/work/bluetape4k --repo bluetape4k-exposed --check --summary`: `Central catalog adoption is clean.`
- Dependencies 작업 트리에서 `python3 scripts/sync-dependabot-ignores.py --workspace /Users/debop/work/bluetape4k/bluetape4k-exposed/.worktrees/fix --repo issue-885-central-catalog-policy --check --summary`: 중앙 ignore 목록 일치
- `BLUETAPE4K_DEPENDENCIES_CATALOG_REF=198147fd6ca2992fae32e175bd5f3d396c497096 ./gradlew help --no-daemon --no-configuration-cache --no-build-cache --console=plain`: `BUILD SUCCESSFUL`
- `git diff --check HEAD^ HEAD`: 통과
- `audit-korean-terms.mjs`로 이 문서를 검사: findings 없음
- 과거/최신 workflow run의 event SHA, 제출 artifact metadata와 대상 좌표,
  Dependabot run 순서를 비교했습니다. 원인 평가는 위의 중앙 조사 기록에 근거하며,
  이 로컬 대조 결과만으로 수정의 효과나 alert 해소를 입증하지는 않습니다.
- 공식 문서:
  - [GitHub dependency submission API](https://docs.github.com/en/rest/dependency-graph/dependency-submission)
  - [Gradle dependency-submission action](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md)
