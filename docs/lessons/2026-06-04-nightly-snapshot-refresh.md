# Nightly Snapshot Refresh

## 배경

Nightly는 Gradle cache를 restore하고 mutable bluetape4k Central snapshot artifact를
소비합니다. stale snapshot metadata 또는 simultaneous Central snapshot metadata request가
test 실행 전에 module job을 실패시킬 수 있습니다.

## 결정

Nightly Gradle invocation에 `--refresh-dependencies`를 전달하고 scheduled cron minute를
stagger해 snapshot metadata를 다시 확인하되 모든 downstream repository가 동시에 시작하지
않게 합니다.

## 결과

Nightly는 build state용 cache reuse를 유지하면서 mutable metadata를 refresh하고 scheduled
cross-repository Central snapshot contention을 줄입니다.

## 검증

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
