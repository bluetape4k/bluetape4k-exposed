# Issue #248 Central Snapshot Retry

## 배경

GitHub runner가 Central Portal snapshot metadata에서 transient HTTP 403을 받으면
downstream CI와 Nightly run이 실패할 수 있습니다.

## 결정

Gradle command semantics를 바꾸지 않고 top-level Gradle build와 Nightly detekt gate를
bounded three-attempt retry loop으로 감쌉니다.

## 검증

- `git diff --check`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

## 다음 작업

bluetape4k SNAPSHOT dependency가 Central metadata 403으로 실패하면 먼저 upstream
publish status를 확인하고 dependency/catalog churn보다 bounded workflow retry를 우선합니다.
