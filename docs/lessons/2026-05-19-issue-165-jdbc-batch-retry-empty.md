# Issue #165 — JDBC Batch Retry Empty Re-query

**Date**: 2026-05-19
**Issue**: #165
**Module**: `utils/batch`

## 배경

`ExposedJdbcBatchJobRepository.findOrCreateJobExecution()`은 unique-constraint race를
competing job execution의 re-query로 처리했지만 JDBC retry path는 `.first()`를
사용했습니다. retry query 전에 winner row가 사라지거나 restartable status filter와
일치하지 않으면 caller는 일반적인 `NoSuchElementException`을 받았습니다.

R2DBC counterpart는 이미 job context가 포함된
`firstOrNull() ?: IllegalStateException(...)`을 사용했습니다.

## 결정

retry re-query를 internal helper로 옮겨 JDBC를 R2DBC와 정렬합니다. helper는
`firstOrNull()`로 winner row를 반환하거나 `jobName`과 `params`를 포함한 contextual
`IllegalStateException`을 던집니다. retry catch를 수정하는 동안 broad exception
handling 전에 `CancellationException`을 다시 던져 coroutine cancellation을 명시적으로
유지합니다.

## 결과

JDBC retry path는 missing winner row에 더 이상 `NoSuchElementException`을 노출하지
않습니다. winner row가 있으면 반환하고, 없으면 unique-violation retry state를 충분한
job context와 함께 설명합니다.

## 검증

- `./gradlew :bluetape4k-exposed-batch:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --tests "io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest*unique violation retry*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --console=plain --no-daemon`
- `git diff --check`

## 향후 guard

unique violation 뒤 catch-and-retry가 winner row를 다시 select할 때 retry query에서
`.first()`를 사용하지 않습니다. `firstOrNull()`을 사용하고 retry에 쓴 identifying key를
담은 domain-relevant exception을 던집니다.
