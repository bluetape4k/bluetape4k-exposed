---
manualId: "bluetape4k-exposed-jdbc-caffeine"
id: "bluetape4k-exposed-jdbc-caffeine"
title: "Exposed JDBC Caffeine 캐시"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc-caffeine"
sourceDir: "exposed/jdbc-caffeine"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine
---

# Exposed JDBC Caffeine 캐시

이 어댑터는 동기 JDBC 또는 suspend JDBC 경로와 프로세스 내부 Caffeine을 연결합니다. 수명주기와 영속성 선택을 감추지 않으면서 공통 캐시 저장소 계약을 구현합니다.

## 해결하려는 문제 {#problem}

캐시 미스, 쓰기, 무효화, 타임아웃, 취소, 종료가 일부만 끝나면 캐시와 DB 상태가 달라집니다. 이 모듈은 연결 코드를 제공하지만, 어느 정도의 오래된 값과 실패를 허용할지는 애플리케이션이 정해야 합니다.

## 언제 사용하는가 {#when-to-use}

캐시 백엔드로 프로세스 내부 Caffeine을 선택하고 영속성 경로가 동기 JDBC 또는 suspend JDBC일 때 사용합니다. 인프라를 추가하기 전에 [캐시 선택 가이드](../guides/cache-selection.md)에서 여섯 어댑터를 비교하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine")
}
```

사용자가 선택할 버전은 중앙 BOM 버전 하나입니다. 이 페이지는 안정 배포본 `1.12.1` 소스 계보를 기록합니다.

## 핵심 개념 {#concepts}

`get`과 `getAll`은 Read-through 연산입니다. Cache-aside는 애플리케이션이 DB를 바꾼 뒤 캐시를 무효화하는 방식입니다. 진짜 Write-through는 설정한 writer가 DB 쓰기를 마친 뒤 반환합니다. Write-behind는 영속화를 미룹니다. writer 정책을 확인하지 않은 평범한 `put`을 Write-through라고 부르면 안 됩니다. 이 어댑터는 로컬 전용이므로 JVM 사이의 항목을 동기화하지 않습니다.

안정 배포본 `1.12.1`에서는 JDBC transaction, dispatcher, Caffeine cache의 소유권이 어댑터에 남고 write-behind는 명시적인 지연 내구성 모드입니다. 아래의 lifecycle coordinator, bounded admission 회계, publication lease, terminal retry 동작은 현재 develop 변경이며 안정 배포본 `1.12.1` 계약에 포함되지 않습니다.

**Develop-only write-behind 계약:** `writeBehindBatchSize`와 `writeBehindQueueCapacity`는 각각 `1..100_000` 범위여야 하고 queue capacity는 batch size 이상이어야 합니다. 0·음수·`100_000` 초과 값 또는 batch보다 작은 queue는 `IllegalArgumentException`으로 즉시 거부됩니다. flush 실패는 최대 8회까지 10 ms에서 1 s로 증가하는 capped exponential backoff로 재시도하며, 재시도 한도 전에는 보류 배치를 버리지 않습니다. terminal failure는 worker를 `FAILED`로 남기며 보류 배치를 durable outbox나 dead-letter 저장소로 자동 이동하지 않습니다.

## 빠른 시작 {#quick-start}

`AbstractJdbcCaffeineRepository`를 상속하고 `table`, `extractId`, `ResultRow.toEntity`, update/insert DSL hook을 구현한 뒤 검토한 `LocalCacheConfig`로 생성합니다.

```kotlin
repository.use { repo ->
    val current = repo.get(id)          // 미스 -> DB loader -> 캐시
    current?.let { repo.put(id, it) }   // 영속성은 쓰기 모드가 결정
    repo.invalidate(id)                 // 기본값은 캐시만 제거
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 캐시 조회 | `containsKey`, `get`, `getAll` |
| DB 직접 조회 | `findByIdFromDb`, `findAllFromDb`, `countFromDb` |
| 정책에 따른 쓰기 | `put`, `putAll` |
| 무효화 | `invalidate`, `invalidateAll`, `clear` |
| 엔티티 매핑 | `ResultRow.toEntity`, `extractId`, update/insert hook |
| 수명주기 | `close` |

## 권장 패턴 {#patterns}

캐시 이름 공간마다 저장소 하나를 두고 애플리케이션과 함께 닫으세요. Cache-aside에서는 DB 트랜잭션 커밋 뒤 무효화합니다. Write-through 실패는 쓰기가 끝나지 않은 상태입니다. Write-behind를 켜기 전에는 큐 상태와 종료 drain 시간을 노출하세요. `putAll`은 입력 순서대로 처리하며 중간 실패 시 앞선 side effect와 실패 지점 예외를 보존하고 implicit rollback을 제공하지 않습니다. accepted queue handoff 뒤 cache publication이 실패하면 해당 key를 invalidate합니다. 키 접두사와 직렬화 형식은 안정적으로 유지해야 합니다.

## 연동 모듈 {#integrations}

이 모듈은 Exposed JDBC, 공통 캐시 기반 모듈, 프로세스 내부 Caffeine을 연결합니다. loader·writer가 캐시와 테이블 사이를 맡고, 애플리케이션이 서비스 트랜잭션과 클라이언트 수명주기를 소유합니다.

## 설정 {#configuration}

`LocalCacheConfig`가 이름 공간, TTL·만료, 캐시·쓰기 모드, 백엔드 제한을 정합니다. 값은 프로세스 안에 머물러 Redis 전송 코덱을 쓰지 않습니다. 시작할 때 시간이 양수인지, 배치와 큐 크기가 제한되어 있는지 확인하세요.

## 실패 방식 {#failures}

Write-behind 채널이 가득 차면 캐시도 갱신하지 않고 예외를 냅니다. 큐에 들어갔지만 아직 flush하지 않은 항목은 프로세스 장애 때 유실될 수 있습니다. 캐시 hit도 오래된 값일 수 있고, 백엔드 성공 뒤 DB가 실패할 수 있으며, DB 커밋 뒤 무효화가 실패할 수도 있습니다. 이 상태를 하나의 캐시 오류로 뭉개지 말고 따로 기록하세요. durable retry/dead-letter 복구가 필요하면 outbox schema, replay/idempotency, alert와 운영 runbook을 애플리케이션이 소유해야 하며 coordinator의 `FAILED`는 영속 복구 장치가 아닌 관찰 경계입니다.

## 운영 {#operations}

저장소가 Caffeine 캐시와 제한된 Write-behind 실행 범위를 소유합니다. develop 구현의 `close()`는 새 admission을 막고 유한 종료 경계 안에서 publication과 worker drain을 시도한 뒤 cache를 invalidate합니다. 시간 초과나 중단이 발생하면 잔여 배치나 failure가 남을 수 있으므로 관찰해야 합니다. hit/miss, 백엔드 지연, 재시도·타임아웃, 큐 깊이, 거부 쓰기, 무효화 지연과 종료 drain을 관찰합니다. 캐시와 DB 경로의 SLO도 따로 잡아야 합니다.

## 테스트 {#testing}

격리한 캐시와 DB fixture에 고유한 이름 공간을 사용합니다. 미스 로딩, `getAll` 일부 미스, 켠 쓰기 모드, 캐시 전용 무효화, TTL, 부분 실패, 수명주기 정리를 검증하세요.

```bash
./gradlew :bluetape4k-exposed-jdbc-caffeine:test
```

## 학습 경로 {#workshops}

1. [Exposed 캐시 기반 라이브러리](bluetape4k-exposed-cache.md)에서 공통 계약을 익힙니다.
2. [캐시 선택 가이드](../guides/cache-selection.md)에서 백엔드를 고릅니다.
3. Read-through와 캐시 전용 무효화를 먼저 구현합니다.
4. Near Cache나 Write-behind 전에 부분 실패와 종료 테스트를 추가합니다.
5. [exposed-workshop](https://github.com/bluetape4k/exposed-workshop)에서 실행 가능한 구성을 살펴봅니다.

## 제약 사항 {#limitations}

이 어댑터는 분산 트랜잭션을 만들거나 백엔드를 설치하지 않습니다. 저장 데이터를 마이그레이션하거나 오래된 값을 허용할지도 대신 정하지 않습니다. 로컬 전용이므로 JVM 사이의 항목을 동기화하지 않습니다.

## 소스 {#sources}

- [모듈 README](../../../../exposed/jdbc-caffeine/README.md)
- [추상 저장소](../../../../exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/AbstractJdbcCaffeineRepository.kt)
- [모듈 빌드](../../../../exposed/jdbc-caffeine/build.gradle.kts)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### JDBC Caffeine local cache 아키텍처

[![JDBC Caffeine local cache 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-caffeine-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-caffeine-diagram-01.svg)

_배포본 README: [`exposed/jdbc-caffeine/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-caffeine/README.ko.md)_

### JDBC Caffeine write strategy 처리 흐름

[![JDBC Caffeine write strategy 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-caffeine-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-caffeine-sequence-01.svg)

_배포본 README: [`exposed/jdbc-caffeine/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-caffeine/README.ko.md)_

<!-- release-readme-diagrams:end -->
