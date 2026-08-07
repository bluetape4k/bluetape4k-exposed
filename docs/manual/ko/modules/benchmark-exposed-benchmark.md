---
manualId: "benchmark-exposed-benchmark"
id: "benchmark-exposed-benchmark"
title: "Exposed 저장소 벤치마크"
locale: "ko"
kind: "benchmark"
gradlePath: ":benchmark-exposed-benchmark"
sourceDir: "benchmark/exposed-benchmark"
releaseRef: "1.12.1"
artifact: null
---

# Exposed 저장소 벤치마크

> 성능 수치를 설계 판단에 쓰기 전에 어떤 작업을 어떤 환경에서 측정했는지 재현합니다.

## 제공하는 기능 {#problem}

JDBC와 R2DBC, 사용자 정의 식별자 테이블, 로컬·near cache, Redis client 작업을 나눠 실행하는 kotlinx-benchmark 모듈입니다. 회귀를 찾고 원인을 조사하는 도구이며 라이브러리 artifact를 배포하지 않습니다. 영속화 방식의 보편적인 순위를 정하는 도구도 아닙니다.

## 사용하기 좋은 경우 {#when-to-use}

같은 컴퓨터에서 두 commit을 비교하거나, 포함된 한 workload의 특성을 확인할 때 사용하세요. JDK, CPU 할당, 데이터베이스·서비스, 데이터 크기, 벤치마크 인자, 백그라운드 부하를 고정해야 합니다. 네트워크 topology와 동시성, tail latency가 핵심이라면 운영과 닮은 별도 부하 테스트가 필요합니다.

## 의존성 좌표 {#coordinates}

소비자가 추가할 의존성은 없습니다. 저장소 안에서는 `io.github.bluetape4k:bluetape4k-dependencies:<version>`가 벤치마크 라이브러리 버전을 맞춥니다.

## 작업과 지표 {#concepts}

1.11 설정은 모두 처리량(`ops/s`)을 측정하므로, 같은 작업과 환경에서는 값이 클수록 좋습니다. 기본 설정은 warmup 1회, 1초 iteration 3회, JSON 보고서입니다.

| 작업 | 측정 대상 | 실행 기반 |
| --- | --- | --- |
| `jdbcR2dbcBenchmark` | JDBC platform thread, JDBC virtual-thread dispatch, R2DBC suspend transaction 조회 | H2 PostgreSQL 모드 |
| `idTablesBenchmark` | UUID, time-based UUID, ULID, Base62 UUIDv7, Snowflake, KSUID 계열 insert·select | H2 PostgreSQL 모드 |
| `cacheBenchmark` | Caffeine hit, near-cache hit, read-through miss | 프로세스 내부 캐시 |
| `redisCacheBenchmark` | Lettuce·Redisson 원격 cache get | 접근 가능한 Redis, 예제 기본값 `127.0.0.1:6379` |
| `smokeBenchmark` | Redis를 뺀 짧은 컴파일·실행 경로 | 로컬 실행 |

## 빠르게 시작하기 {#quick-start}

먼저 100ms smoke 설정으로 컴파일과 기본 실행을 확인합니다.

```bash
./gradlew :benchmark-exposed-benchmark:smokeBenchmark
```

그다음 질문에 맞는 작업만 실행하세요.

```bash
./gradlew :benchmark-exposed-benchmark:jdbcR2dbcBenchmark
./gradlew :benchmark-exposed-benchmark:idTablesBenchmark
./gradlew :benchmark-exposed-benchmark:cacheBenchmark
./gradlew :benchmark-exposed-benchmark:redisCacheBenchmark \
  -Pbenchmark.parameters.redisUri=redis://127.0.0.1:6379
```

## 작업별 API {#api-by-task}

점수를 읽기 전에 클래스와 연산부터 확인합니다. `JdbcThreadingBenchmark`와 `R2dbcCoroutineBenchmark`는 H2에서 실행 방식을 비교하고, `CustomIdTableBenchmark`는 ID 생성과 테이블 연산을 함께 측정합니다. `CacheStrategyBenchmark`는 로컬·near cache 경로를, `RedisCacheBenchmark`는 Redis client 경계를 측정합니다. 서로 다른 질문이므로 하나의 순위표로 합치면 안 됩니다.

## 권장 패턴 {#patterns}

- 저장소 commit과 변경 상태, JDK, OS, CPU, 메모리 제한, fork, warmup, iteration, 원본 JSON을 함께 기록합니다.
- 가장 빠른 표본 하나가 아니라 분포와 오차를 비교합니다.
- 벤치마크 전후에 별도의 정확성 테스트를 실행합니다.
- 한 번에 변수 하나만 바꾸고, 잡음과 신호를 구분할 만큼 반복합니다.

## 연동 {#integrations}

JDBC와 R2DBC workload는 운영 PostgreSQL이 아니라 H2 PostgreSQL 모드를 사용합니다. Redis는 외부 서비스이며 smoke에서 의도적으로 빠져 있습니다. 전체 실행 뒤 저장소의 표와 차트를 갱신하려면 다음 작업을 실행합니다.

```bash
./gradlew :benchmark-exposed-benchmark:generateBenchmarkDocs
```

## 설정 {#configuration}

1.11 기본값은 일부러 짧습니다. warmup 1회와 1초 iteration 3회를 사용합니다. smoke는 `rowCount=100`, `cacheSize=1000`으로 100ms iteration을 한 번 실행합니다. 기술 결정을 뒷받침하려면 warmup과 iteration, fork, 데이터 크기를 늘리고 정확한 명령을 보존하세요.

## 실패 유형과 해결 방법 {#failures}

- `redisCacheBenchmark` 연결 실패: Redis를 실행하거나 올바른 `redisUri`를 넘깁니다. smoke 결과로 대신하지 마세요.
- 점수 편차가 큼: 경쟁 작업을 없애고 fork와 iteration을 늘린 뒤 allocation·GC를 확인합니다.
- 비현실적으로 빠름: 연산 결과가 실제로 소비되는지, 이미 데운 cache만 읽는지 확인합니다.
- JDBC·R2DBC 수치를 데이터베이스 순위로 해석함: 두 workload 모두 로컬 H2 PostgreSQL 모드라는 조건을 다시 명시합니다.

## 운영 {#operations}

1.11 README에는 2026-06-23에 생성한 대표 결과가 있습니다. 그 실행에서는 JDBC platform-thread 조회가 약 30,179 ops/s, R2DBC suspend-transaction 조회가 약 2,474 ops/s였습니다. 하지만 컴퓨터와 JVM, fork, 서비스 환경이 충분히 기록되지 않았으므로 일반화할 수 없습니다. 재현용 표본과 회귀 기준점으로만 사용하세요.

## 테스트 {#testing}

먼저 `smokeBenchmark`로 컴파일과 기본 실행을 확인합니다. 벤치마크 점수는 정확성 단언이 아닙니다. 조회 결과와 트랜잭션 동작, cache 무효화, 식별자 유일성은 기존 저장소 테스트로 계속 검증해야 합니다.

## 학습 경로와 예제 {#workshops}

[테스트와 운영](../guides/testing-and-operations.md)을 읽고 한 작업을 재현한 뒤, 실제 데이터베이스와 workload를 명시한 실험을 [bluetape4k-workshop](https://github.com/bluetape4k/bluetape4k-workshop)으로 발전시키세요.

## 제약 사항 {#limitations}

이 suite는 운영 지연과 확장성, 가용성, 정확성을 증명하지 않으며 다른 workload에서도 한 영속화 방식이 더 낫다고 보장하지 않습니다. 로컬 H2 결과에는 네트워크와 서버 스케줄링, 운영 쿼리 계획, 관리형 서비스 제한이 포함되지 않습니다.

## 근거 자료 {#sources}

- [1.11 벤치마크 설명과 대표 결과](../../../../benchmark/exposed-benchmark/README.ko.md)
- [벤치마크 설정과 작업](../../../../benchmark/exposed-benchmark/build.gradle.kts)
