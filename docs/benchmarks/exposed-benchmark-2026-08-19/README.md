# Exposed benchmark evidence (2026-08-19)

이 디렉터리는 JDK 25에서 같은 benchmark profile을 세 번 순차 실행한 원시 JSON 결과와 재현 정보를 보관합니다. 차트와 요약 표는 각 benchmark 시리즈의 세 실행 중앙값을 사용하며, 단일 실행의 최고값을 대표값으로 선택하지 않습니다.

## 실행 환경

- JDK: Oracle GraalVM `25.0.4` (Java 25)
- Database: H2
- JMH: benchmark 모듈의 기본 profile, 각 시리즈를 동일 조건으로 세 번 순차 실행
- Redis: endpoint를 제공하지 않아 `N/A`; Redis 결과를 H2 결과와 섞지 않음
- 선택한 실행 시각: `2026-08-19T13:01:54.169778`, `2026-08-19T13:06:42.860074`, `2026-08-19T13:11:28.277259`

## 재현 명령

```bash
./gradlew --no-daemon \
  :benchmark-exposed-benchmark:jdbcR2dbcBenchmark \
  :benchmark-exposed-benchmark:idTablesBenchmark \
  :benchmark-exposed-benchmark:cacheBenchmark \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

첫 시도에서는 zsh 예약 변수명 `status`를 wrapper에서 사용해 benchmark 이전에 shell이 실패했습니다. 예약어를 `rc`로 바꾼 동일 Gradle 명령은 세 번 모두 성공했습니다. 이 디렉터리의 JSON은 성공한 세 실행에서 복사한 결과입니다.

## 산출물

- `cache-{1,2,3}.json`: cache strategy benchmark
- `jdbcR2dbc-{1,2,3}.json`: JDBC platform/virtual thread와 R2DBC suspend transaction 비교
- `idTables-{1,2,3}.json`: custom ID table select benchmark
- `docs/images/readme-charts/exposed-benchmark-suite.svg` 및 `.png`: 세 실행 중앙값을 패널별로 표시한 차트

차트는 cache, DB select, custom ID를 각각 별도 panel의 선형 폭으로 그려 단위와 비교 범위를 분리합니다. 이는 cache hit 처리량과 DB select 처리량을 하나의 순위표로 오해하지 않게 하기 위한 것입니다.

## 해석 기준

- near-cache hit 중앙값은 local Caffeine hit보다 약 `3.79x`, read-through miss보다 약 `4.38x` 높았습니다. 이 수치는 동일 cache profile 내부 비교입니다.
- H2 단건 조회에서 virtual thread는 platform thread의 약 `70.3%`, R2DBC suspend transaction은 약 `55.3%`였습니다. 이 결과만으로 다른 driver나 병렬 key enumeration의 기본 동작을 결정하지 않습니다.
- custom ID 이름 조회의 최고/최저 중앙값 차이는 약 `10.3%`였습니다. 특정 ID 전략의 보편적 승자를 의미하지 않으며, #690의 opt-in parallel enumeration을 대체하지 않습니다.
- benchmark는 throughput 지표이며, 실제 서비스의 connection pool, row cardinality, cache hit ratio, driver latency를 재현하지 않습니다. 비-H2 driver와 Redis는 별도 환경 검증이 필요합니다.
