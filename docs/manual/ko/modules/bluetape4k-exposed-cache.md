---
manualId: "bluetape4k-exposed-cache"
id: "bluetape4k-exposed-cache"
title: "Exposed 캐시 기반 라이브러리"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-cache"
sourceDir: "exposed/cache"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-cache
---

# Exposed 캐시 기반 라이브러리

`bluetape4k-exposed-cache`는 Caffeine, Lettuce, Redisson 어댑터가 함께 쓰는 저장소 계약과 설정을 정의합니다. 캐시 클라이언트를 만들거나 애플리케이션 대신 백엔드를 고르지는 않습니다.

## 해결하려는 문제 {#problem}

캐시 저장소에는 캐시와 데이터베이스라는 두 상태 소유자가 있습니다. 캐시 미스를 누가 채우는지, 쓰기가 언제 영속화되는지, 무효화가 무엇을 지우는지, 백엔드 자원을 누가 닫는지 먼저 정해야 합니다. 이 모듈은 어댑터마다 달라지기 쉬운 결정을 같은 용어로 표현합니다.

## 언제 사용하는가 {#when-to-use}

캐시 어댑터를 구현하거나 `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`, `R2dbcCacheRepository` 공통 계약을 기준으로 코드를 작성할 때 사용합니다. 일반 애플리케이션은 구체적인 백엔드 모듈 하나에 의존하는 편이 낫습니다. 기반 모듈은 전이 의존성으로 들어옵니다.

## 의존성 좌표 {#coordinates}

생태계 BOM은 한 번만 가져오고, 라이브러리 버전은 생략합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cache")
}
```

## 핵심 개념 {#concepts}

| 결정 | 값 | 운영 관점의 의미 |
| --- | --- | --- |
| 저장 위치 | `LOCAL`, `REMOTE`, `NEAR_CACHE` | 한 JVM, Redis, 로컬 전면 캐시와 Redis 조합 |
| 쓰기 정책 | `READ_ONLY`, `WRITE_THROUGH`, `WRITE_BEHIND` | 캐시에만 저장, DB에 동기 저장, DB 저장 지연 |
| Read-through | `get`/`getAll` | 미스가 나면 DB에서 읽어 캐시를 채움 |
| 무효화 | `invalidate`, `invalidateAll`, `clear` | 캐시 상태 제거. 공통 계약은 DB 행을 지우지 않음 |

평범한 `put`을 무조건 Write-through라고 부르면 안 됩니다. 실제 영속성은 저장소의 `cacheWriteMode`와 구체적인 writer 설정이 결정합니다.

## 빠른 시작 {#quick-start}

```kotlin
val entity = repository.get(id)       // 캐시 미스 -> DB 조회 -> 캐시 저장
repository.invalidate(id)             // 캐시만 제거하고 DB 행은 유지
repository.close()                    // 어댑터가 소유한 큐와 연결 정리
```

먼저 [캐시 선택 가이드](../guides/cache-selection.md)에서 로컬·원격·Near Cache 중 하나를 고른 뒤, 해당 백엔드의 추상 저장소에 테이블 매핑을 구현하세요.

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 캐시를 건너뛰고 조회 | `findByIdFromDb`, `findAllFromDb`, `countFromDb` |
| 캐시를 거쳐 조회 | `containsKey`, `get`, `getAll` |
| 정책에 따라 저장 | `put`, `putAll` |
| 캐시 상태 제거 | `invalidate`, `invalidateAll`, `clear` |
| 자원 정리 | `close` |

JDBC에는 동기 계약과 suspend 계약이 있습니다. R2DBC는 행을 엔티티로 바꾸는 단계까지 suspend 함수로 제공합니다.

## 권장 패턴 {#patterns}

캐시와 DB를 원자적으로 커밋할 수 없다면, 트랜잭션이 소유한 DB 변경을 코드에 드러내세요. Cache-aside에서는 DB 변경을 먼저 커밋한 다음 관련 키를 무효화합니다. 어댑터에 MapWriter가 연결된 경우만 진짜 Write-through로 다룹니다. Write-behind는 지연 영속화와 장애 시 유실 구간을 받아들일 수 있는 데이터에만 사용합니다.

`keyPrefix`나 캐시 이름으로 키 공간을 나누세요. 직렬화한 키와 값의 형식도 저장 데이터 계약입니다. 마이그레이션 없이 바꾸면 기존 항목을 읽지 못하거나 같은 데이터를 다른 키에 중복 저장합니다.

## 연동 모듈 {#integrations}

- Caffeine: JDBC 또는 R2DBC에서 쓰는 프로세스 내부 `LOCAL` 캐시
- Lettuce: 값 코덱을 명시하는 Redis 저장소. 일부 suspend 경로는 Caffeine Near Cache를 덧붙일 수 있음
- Redisson: loader와 writer를 연결한 Redis `RMap`/`RLocalCachedMap`
- 테스트 fixture: Read-through, Write-through, Write-behind, 무효화 시나리오 재사용

## 설정 {#configuration}

`LocalCacheConfig` 기본값은 최대 10,000개, 쓰기 후 10분 만료, `READ_ONLY`입니다. 키 접두사는 비어 있으면 안 되고 크기와 시간은 양수여야 합니다. Write-behind 큐는 적어도 한 배치를 담아야 합니다. Redis 어댑터는 자체 백엔드 설정을 추가하며, `RedisRepositoryResilienceConfig`로 재시도·타임아웃·Circuit Breaker를 선택적으로 켤 수 있습니다.

## 실패 방식 {#failures}

- 로컬 캐시는 애플리케이션 인스턴스끼리 공유되지 않아 무효화 시점이 어긋날 수 있습니다.
- Redis 장애 정책을 정하지 않으면 캐시가 가용성을 떨어뜨리는 의존성이 됩니다.
- Write-behind는 큐에 들어갔지만 아직 DB에 반영되지 않은 데이터를 프로세스 장애 때 잃을 수 있습니다.
- 코덱이나 키 형식을 바꾸면 기존 Redis 항목을 읽지 못할 수 있습니다.
- `close`를 호출하지 않으면 큐·연결·CoroutineScope가 남을 수 있습니다.

## 운영 {#operations}

캐시 hit/miss, 백엔드 지연, 재시도와 Circuit Breaker 상태, Write-behind 큐 깊이, 거부된 쓰기, 종료 시 drain 결과를 관찰하세요. TTL은 임의의 기본값이 아니라 데이터가 낡아도 되는 시간으로 정합니다. Redis 장애 때 요청을 실패시킬지, 캐시를 우회할지, 로컬의 오래된 값을 허용할지도 문서로 남겨야 합니다.

## 테스트 {#testing}

모듈의 test fixture로 미스 로딩, `getAll` 일부 미스, 동기 영속화, 지연 flush, 캐시만 무효화하는 동작을 고정하세요. 테스트마다 고유한 키 접두사나 캐시 이름을 쓰고 teardown에서 비운 다음 저장소와 애플리케이션 소유 클라이언트를 닫습니다. 분산 어댑터는 개발자 Redis를 공유하지 말고 격리한 Redis로 검증합니다.

## 학습 경로 {#workshops}

1. [캐시 선택 가이드](../guides/cache-selection.md)에서 로컬·원격·Near Cache의 차이를 익힙니다.
2. 선택한 Caffeine, Lettuce, Redisson 모듈로 가장 작은 저장소를 만듭니다.
3. Write-behind나 Near Cache를 켜기 전에 장애 시나리오 테스트를 추가합니다.
4. [exposed-workshop](https://github.com/bluetape4k/exposed-workshop)에서 캐시 저장소를 실제 애플리케이션에 연결하는 예제를 살펴봅니다.

## 제약 사항 {#limitations}

이 계약은 캐시와 DB 사이에 분산 트랜잭션을 제공하지 않습니다. 직렬화 형식을 고르거나 Redis를 구성하지도 않고, JVM마다 따로 있는 로컬 캐시를 자동으로 일치시키지도 않습니다. writer나 무효화 설정이 더 구체적인 경우에는 백엔드 모듈의 규칙을 따릅니다.

## 소스 {#sources}

- [모듈 개요](../../../../exposed/cache/README.md)
- [`JdbcCacheRepository`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/JdbcCacheRepository.kt)
- [`R2dbcCacheRepository`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/R2dbcCacheRepository.kt)
- [`LocalCacheConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/LocalCacheConfig.kt)
- [`RedisRepositoryResilienceConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/redis/RedisRepositoryResilienceConfig.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Repository Interface 클래스 다이어그램

[![Repository Interface 클래스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-01.svg)

_배포본 README: [`exposed/cache/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.ko.md)_

### Cache Configuration 선택 지도

[![Cache Configuration 선택 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-02.svg)

_배포본 README: [`exposed/cache/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.ko.md)_

### Cache write strategy 처리 흐름

[![Cache write strategy 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-sequence-01.svg)

_배포본 README: [`exposed/cache/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.ko.md)_

<!-- release-readme-diagrams:end -->
