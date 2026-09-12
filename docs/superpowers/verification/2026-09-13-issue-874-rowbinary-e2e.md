# #874 RowBinary 실제 서버·배치 경계 검증 증적

## 실행 식별자

| 항목 | 값 |
|---|---|
| 이슈 | [#874](https://github.com/bluetape4k/bluetape4k-exposed/issues/874) |
| 기준 ref | `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166` |
| 실행 HEAD | `c63145f7173f194135244e62f4ad421804ce7c4b` + working-tree test/docs 변경 |
| Gradle task | `:bluetape4k-exposed-clickhouse:test` |
| selector | `-PclickhouseV2Integration=true`, `--tests '*ClickHouseRowBinaryIntegrationTest'` |
| 실행 제약 | `--no-parallel --max-workers=1 --no-daemon --console=plain` |
| driver | `com.clickhouse.jdbc.ClickHouseDriver` `0.9.9` (V2) |
| server image | `clickhouse/clickhouse-server:26.7.3.19` |
| image digest | `sha256:f90a77560f72b10802106ee49e9870e41668cbc496e280c3911f6e3b216657f3` |
| Docker context | `default`, Docker Server `29.2.1`, Colima running |
| XML receipt | `exposed/clickhouse/build/test-results/test/TEST-io.bluetape4k.exposed.clickhouse.ClickHouseRowBinaryIntegrationTest.xml` |

## 실행 명령과 결과

```bash
colima status
docker context show
docker info --format '{{.ServerVersion}}'
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

XML 결과는 `tests=6`, `failures=0`, `errors=0`, `skipped=0`, `time=7.738s`였다. 두 시나리오가 각각 `@RepeatedTest(3)`으로 실행되어 동일한 컨테이너 경계에서 총 여섯 회를 통과했다.

## 관찰 행렬

| 시나리오 | 실제 관찰 | 판정 |
|---|---|---|
| RowBinary enabled, 3 rows, `maxRowsPerFlush=2` | `WriterStatementImpl`, `updateCounts=[1,1,1]`, `acceptedCount=3`, `executeBatchSizes=[2,1]` | PASS |
| RowBinary `DEFAULT` expression | 같은 V2 writer profile, `updateCounts=[1]`, default label count `1` | PASS |
| JDBC fallback `INSERT ... SELECT` | `PreparedStatementImpl`, fallback reason `INSERT_SELECT`, `updateCounts=[1]` | PASS |
| nullable `setNull` | `Nullable(String)`에 `NULL` 1개 저장, actual path `ROW_BINARY`, fallback event 없음 | PASS |
| 전체 실행 receipt | combined `executeBatchSizes=[2,1,1,1]`, 총 행 5개, default label 2개 | PASS |
| resource cleanup | statement 3개와 connection 전부 정확히 한 번씩 close | PASS |

SQL 본문·JDBC URL·credential·raw exception message는 이 문서에 기록하지 않았다. table 이름은 테스트 소스의 고정된 issue 전용 식별자이며 실행 receipt에는 비밀값이 없다.

## 경계와 미실행 항목

- fixture unit test는 before-first-byte fallback, first-byte no-replay, sentinel(`SUCCESS_NO_INFO`/`EXECUTE_FAILED`), empty input을 계속 검증한다. 실제 통합 test의 성공이 이 실패 경계를 대체하지 않는다.
- `ClickHouseRowBinaryBenchmarkTest`는 이번 실행에서 호출하지 않았다. 실제 wire throughput 측정과 chart 변경은 provenance가 별도로 확보될 때만 수행하며, 이번 증적에는 성능 수치를 추가하지 않는다(`N/A`).
- `async_insert`, 일반 stream writer, remote cancellation 및 `KILL QUERY` 완료 보장은 범위 밖이다. remote cancellation/timeout 후속 범위는 [#875](https://github.com/bluetape4k/bluetape4k-exposed/issues/875)에서 다룬다.
- hosted CI와 merge 후 canonical sync는 PR 단계의 별도 증적이다.

## 재현 및 롤백

동일 selector와 고정 image/driver로 명령을 다시 실행한다. Docker/image/driver가 준비되지 않으면 source를 변경하지 않고 `PENDING` 또는 `N/A`와 원인을 새 receipt에 남긴다. benchmark provenance가 맞지 않으면 raw 결과와 chart를 커밋하지 않는다. root의 기존 dirty Ktor 변경과 다른 worktree에는 revert/reset을 적용하지 않는다.

## 검증 체크

- [x] actual-server RowBinary writer/fallback와 statement class 확인
- [x] empty/single/multi-flush 및 `DEFAULT`/`NULL` 결과 확인
- [x] close exactly-once와 row count 확인
- [x] driver/server/image/selector/HEAD provenance 기록
- [x] XML `failures=0`, `errors=0`, `skipped=0` 확인
- [x] throughput/chart 미실행을 N/A로 분리
