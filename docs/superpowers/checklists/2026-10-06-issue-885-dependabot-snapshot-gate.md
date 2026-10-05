# #885 Gradle dependency snapshot 게이트

상태: 원인 미확정 · provenance guard 로컬 검증 완료 · hosted 재검증 대기

저장소: `bluetape4k/bluetape4k-exposed`  
기준 브랜치: `develop`  
작업 브랜치: `fix/issue-885-dependabot-snapshots`

## 확인된 현상과 원인 경계

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
  좌표가 snapshot에서 빠진 것이 원인이라고 결론 내릴 수 없습니다.
- 실패 로그는 updater가 오류를 보고한 group/artifact 이름만 기록합니다. 각 job이
  조회한 정확한 snapshot ID, manifest 경로, version을 제공하지 않아 artifact 항목과
  failed lookup을 1:1로 대조할 수 없습니다.
- 최신 develop SHA `770b3edb42bc9aa0eda21363968125c3daf610ef`의 dependency
  submission run [#37310287810](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37310287810)도
  성공했고, 보존 artifact의 `sha`, `ref`, 대상 좌표를 확인했습니다.
- 같은 SHA의 Dependabot run
  [#37310309441](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/37310309441)은
  dependency submission 완료 시각보다 먼저 시작해 실패했습니다. 이 실행은
  제출과의 순서 경쟁 가능성을 보여 주지만, 같은 SHA로 이미 성공한 과거 snapshot과
  이후의 실패까지 설명하지는 않습니다.
- 현재 GitHub dependency-graph SBOM에도 대상 group/artifact가 있으나, 응답에는
  snapshot commit SHA가 없어 최신 develop SHA에 대한 실시간 Dependabot 해소 여부를
  증명하지 않습니다.
- 기존 workflow는 push event의 `github.sha` 대신 이동 가능한
  `refs/heads/develop`을 checkout했습니다. 이를 특정 과거 실패의 입증된 원인으로
  보지는 않습니다. 다만 workflow가 생성하는 트리와 event SHA가 어긋나지 않도록
  보강할 수 있는 provenance 결함입니다.
- 따라서 **Dependabot의 `dependency_not_found` 근본 원인은 아직 미확정**입니다.
  제출 artifact에 좌표가 있는데도 오류가 반복되어, 추가 hosted 재실행과 정확한
  시간 순서 및 API snapshot 확인이 필요합니다.

## 적용한 수정

- checkout ref를 `${{ github.sha }}`로 고정했습니다.
- checkout 직후 `git rev-parse HEAD`와 `GITHUB_SHA`를 비교해 불일치 시 실패하고,
  exact commit/ref를 `GITHUB_STEP_SUMMARY`에 기록합니다.
- action의 기본 제출 모드와 report directory는 이미
  `generate-submit-and-upload` 및 `dependency-graph-reports`이므로 중복 설정하지
  않습니다. artifact 보존 여부는 다음 hosted run에서 확인합니다.
- `.github/dependabot.yml`은 변경하지 않아 기존 GitHub Actions 업데이트 동작을
  유지합니다.

## Hosted 완료 게이트

- [ ] provenance guard가 반영된 exact `develop` SHA에서 Dependency Submission run이
  성공합니다.
- [ ] run summary와 artifact가 동일한 event SHA/ref를 기록하고 snapshot JSON을 보존합니다.
- [ ] snapshot 제출이 완료된 뒤 시작한 후속 Dependabot Gradle security run에
  `dependency_not_found` 또는 `Job dependencies not found in the dependency snapshot`이
  없습니다.
- [ ] 대상 alert/graph coordinate의 현재 상태를 live API에서 다시 읽습니다.

push, workflow dispatch, Dependabot 재실행 및 issue close는 이 로컬 작업 범위에
포함하지 않습니다. 위 hosted 게이트는 해당 권한과 별도 승인이 해소된 뒤 수행합니다.

## 로컬 근거

- `actionlint .github/workflows/dependency-submission.yml`
- `git diff --check`
- 과거/최신 workflow run의 event SHA, snapshot artifact metadata와 대상 좌표,
  Dependabot run 순서를 비교했습니다. 이 자료만으로 근본 원인은 확정되지 않습니다.
- 공식 문서:
  - [GitHub dependency submission API](https://docs.github.com/en/rest/dependency-graph/dependency-submission)
  - [Gradle dependency-submission action](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md)
