# Release Workflow Standardization

배경: Central Portal release campaign은 `bluetape4k-projects`를 canonical release
workflow 형태로 사용합니다.

결정: workflow display name은 `Nightly`로 유지하면서 Nightly workflow file 이름을
`nightly-tests.yml`로 바꿉니다.

결과: release preparation script는 bluetape4k repository 전체에서 같은 workflow
file name을 신뢰할 수 있습니다.

검증: `actionlint .github/workflows/nightly-tests.yml .github/workflows/publish-snapshot.yml .github/workflows/release.yml`.

향후 guard: repository-specific exception이 `AGENTS.md`에 문서화되어 있지 않다면
release workflow file name을 `bluetape4k-projects`와 정렬합니다.
