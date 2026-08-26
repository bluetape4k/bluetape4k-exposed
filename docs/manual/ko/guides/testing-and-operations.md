---
title: "테스트와 운영"
locale: "ko"
releaseRef: "1.12.1"
---

# 테스트와 운영

운영에서 실제로 실패할 수 있는 경계를 시험하세요. 드라이버 동작, 트랜잭션 소유권, 재실행, 취소, 외부 서비스 설정이 여기에 해당합니다. 애플리케이션 컨텍스트나 H2 테스트가 성공했다는 사실만으로 이 특성을 모두 증명할 수는 없습니다.

## 검증 수준

| 수준 | 확인할 수 있는 것 | 확인할 수 없는 것 |
| --- | --- | --- |
| 단위 테스트 | 매핑, 분기, 재시도 분류, 순수 SQL 생성 | 드라이버, 서버, 트랜잭션, 생명주기 동작 |
| H2 통합 테스트 | 빠른 저장소 계약, 기본 JDBC·R2DBC 구성 | 운영 dialect, 잠금, extension, 네트워크 동작 |
| Testcontainers | 테스트가 사용한 실제 엔진과 드라이버 경로 | 운영 topology, credential, TLS, 용량, 관리형 서비스 정책 |
| 환경 선택형 smoke test | 현재 설정으로 특정 외부 서비스에 연결 가능 | 반복 가능한 성능과 폭넓은 장애 복구 |
| 부하·벤치마크 실행 | 명시한 환경에서 명시한 workload의 결과 | 라이브러리나 데이터베이스의 보편적인 순위 |

## 필요한 작업만 먼저 실행하기

```bash
./gradlew :bluetape4k-exposed-ktor:test
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test
./gradlew :bluetape4k-exposed-spring-boot-batch:test
./gradlew :bluetape4k-exposed-spring-modulith:test
./gradlew :bluetape4k-exposed-batch:test
```

실행 가능한 예제는 정확한 Gradle 경로를 사용합니다.

```bash
./gradlew :examples-ktor-exposed-demo:test
./gradlew :exposed-spring-boot-jdbc-demo:test
./gradlew :exposed-spring-boot-r2dbc-demo:test
./gradlew :examples-exposed-bigquery-dry-run:test
./gradlew :examples-exposed-clickhouse-oltp-olap:test
```

ClickHouse 예제에는 Docker가 필요합니다. BigQuery dry-run 예제는 모의 REST 서비스를 사용하므로 클라우드 자격 증명 없이 실행할 수 있지만, 실제 프로젝트 권한이나 location, quota, 실행 결과를 증명하지는 않습니다.

## 트랜잭션과 재시작 테스트

JDBC와 Spring JDBC에서는 한 비즈니스 작업에 포함된 두 쓰기가 함께 롤백되는지 확인합니다. R2DBC에서는 명시적인 `suspendTransaction` 경계와 취소 전파를 검증합니다. 경량 배치 실행기는 writer가 성공한 뒤에만 checkpoint를 전진시킵니다. writer 호출 직후 실패하는 테스트로 재실행 동작을 확인하고, writer는 여러 번 호출돼도 안전하게 만드세요. 프로세스가 사라진 뒤에도 재시작해야 한다면 영속 checkpoint 저장소를 사용합니다.

Spring Batch는 실행 구조가 다릅니다. reader는 `ExecutionContext`에 `lastKey`를 저장하고 writer는 chunk 트랜잭션에 참여하며, chunk commit과 함께 재시작 위치가 전진합니다. 안정적으로 증가하는 정렬 키를 사용하고 chunk 사이에서 프로세스가 중단되는 상황을 시험하세요.

![배치 실행과 재시작 경계](../../assets/batch/runtime.png)

## 준비 상태, 종료, 관측성

- 필요한 데이터베이스 레지스트리나 풀이 작업을 처리할 수 있을 때만 준비 완료를 알립니다.
- 데이터베이스의 일시적인 장애가 곧 프로세스 재시작 사유가 아니라면 liveness와 분리합니다.
- 종료할 때는 새 작업 수락을 멈추고, 진행 중인 작업을 취소하거나 비운 뒤 배치 상태를 저장하고 풀을 닫습니다.
- 트랜잭션 실패, 재시도, 건너뛴 배치 항목, checkpoint 위치, 풀 포화도, 쿼리 지연을 기록합니다.
- 자격 증명, 가리지 않은 SQL 인자, 직렬화된 비밀 값은 로그에 남기지 않습니다.

Spring Modulith의 publication 테이블은 이벤트 전달 상태를 추적합니다. 감사 이력이 아닙니다. 완료되지 않은 publication을 감시하고 완료 시 update, delete, archive 중 어떤 방식을 쓸지 명확히 정하세요.

## 벤치마크 해석

벤치마크 모듈은 기본적으로 짧은 JMH 실행을 사용합니다. H2 PostgreSQL 모드의 JDBC·R2DBC 측정치는 로컬 회귀를 찾는 데 유용하지만 운영 데이터베이스 성능을 예측하지는 못합니다. Redis 벤치마크에는 별도로 실행 중인 Redis가 필요합니다. 결과를 공개할 때는 CPU, JVM, 운영체제, fork, warmup, iteration, 데이터베이스·서비스 버전, 데이터 크기를 함께 기록하세요.

정확한 실행 작업과 해석상의 제약은 [벤치마크 모듈](../modules/benchmark-exposed-benchmark.md)에서 확인할 수 있습니다.

## 근거 자료

- [모듈 등록](../../../../settings.gradle.kts)
- [경량 배치 테스트](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/utils/batch/src/test/kotlin/io/bluetape4k/batch/core/BatchStepRunnerTest.kt)
- [Spring Batch 테스트](../../../../spring-boot/batch-exposed/src/test/kotlin/io/bluetape4k/spring/batch/exposed/integration/RestartIntegrationTest.kt)
- [벤치마크 빌드](../../../../benchmark/exposed-benchmark/build.gradle.kts)
