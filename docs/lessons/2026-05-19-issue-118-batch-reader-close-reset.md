# Issue #118 — Batch Reader Close가 Cursor State를 초기화함

**Date**: 2026-05-19
**Issue**: #118
**Module**: `utils/batch`

## 배경

`ExposedJdbcBatchReader.close()`와 `ExposedR2dbcBatchReader.close()`는 in-memory
buffer를 비웠지만 `open()`과 같은 path에서 reader state를 reset하지 않았습니다.
이전 close path는 `lastFetchedKey`를 `null`로 설정했으며, 같은 reader instance를
retry code가 재사용할 때 `minKey`를 사용하는 partitioned reader를 깨뜨릴 수 있었습니다.

## 결정

reader state reset을 shared `resetState()` helper로 옮기고 `open()`과 `close()`
모두에서 사용합니다. helper는 `lastFetchedKey`를 `minKey`로 복원하고 read/commit
cursor와 buffer를 비우며 `exhausted`를 reset합니다.

## 결과

JDBC와 R2DBC batch reader는 이제 `close()` 뒤 partition lower bound를 보존합니다.
`minKey`가 설정된 reader instance를 재사용해도 table 처음부터 조용히 다시 시작하지
않습니다.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-exposed-batch:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --tests "io.bluetape4k.batch.jdbc.ExposedJdbcBatchReaderTest.close 후*" --tests "io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchReaderTest.close 후*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --console=plain --no-daemon`
- Claude CLI review 및 rereview: P0/P1=0
- Codex current-session review: P0/P1=0

## 향후 guard

reader lifecycle method가 cursor state를 reset할 때는 `open()`과 `close()`에서 같은
helper를 사용합니다. partitioned reader는 `lastFetchedKey`를 `null`로 reset하지 말고
configured partition lower bound를 복원합니다.
