---
manualId: "bluetape4k-exposed-jdbc-redisson"
id: "bluetape4k-exposed-jdbc-redisson"
title: "Exposed JDBC Redisson 캐시"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc-redisson"
sourceDir: "exposed/jdbc-redisson"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson
---

# Exposed JDBC Redisson 캐시

이 어댑터는 동기 JDBC 또는 suspend JDBC 경로와 Redisson 기반 Redis를 연결합니다. 수명주기와 영속성 선택을 감추지 않으면서 공통 캐시 저장소 계약을 구현합니다.

## 해결하려는 문제 {#problem}

캐시 미스, 쓰기, 무효화, 타임아웃, 취소, 종료가 일부만 끝나면 캐시와 DB 상태가 달라집니다. 이 모듈은 연결 코드를 제공하지만, 어느 정도의 오래된 값과 실패를 허용할지는 애플리케이션이 정해야 합니다.

## 언제 사용하는가 {#when-to-use}

캐시 백엔드로 Redisson 기반 Redis를 선택하고 영속성 경로가 동기 JDBC 또는 suspend JDBC일 때 사용합니다. 인프라를 추가하기 전에 [캐시 선택 가이드](../guides/cache-selection.md)에서 여섯 어댑터를 비교하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson")
}
```

사용자가 선택할 버전은 중앙 BOM 버전 하나입니다. 이 페이지는 안정 배포본 `1.11` 소스 계보를 기록합니다.

## 핵심 개념 {#concepts}

`get`과 `getAll`은 Read-through 연산입니다. Cache-aside는 애플리케이션이 DB를 바꾼 뒤 캐시를 무효화하는 방식입니다. 진짜 Write-through는 설정한 writer가 DB 쓰기를 마친 뒤 반환합니다. Write-behind는 영속화를 미룹니다. writer 정책을 확인하지 않은 평범한 `put`을 Write-through라고 부르면 안 됩니다. 이 어댑터는 설정으로 `RMapCache` 또는 `RLocalCachedMap`을 고르며 Near Cache 동기화는 정합성에도 영향을 줍니다.

## 빠른 시작 {#quick-start}

`AbstractJdbcRedissonRepository`를 상속하고 `table`, `extractId`, `ResultRow.toEntity`, update/insert DSL hook을 구현한 뒤 검토한 `RedissonCacheConfig`로 생성합니다.

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

캐시 이름 공간마다 저장소 하나를 두고 애플리케이션과 함께 닫으세요. Cache-aside에서는 DB 트랜잭션 커밋 뒤 무효화합니다. Write-through 실패는 쓰기가 끝나지 않은 상태입니다. Write-behind를 켜기 전에는 큐·dead-letter 상태와 종료 drain 시간을 노출하세요. 키 접두사와 직렬화 형식은 안정적으로 유지해야 합니다.

## 연동 모듈 {#integrations}

이 모듈은 Exposed JDBC, 공통 캐시 기반 모듈, Redisson 기반 Redis를 연결합니다. loader·writer가 캐시와 테이블 사이를 맡고, 애플리케이션이 서비스 트랜잭션과 클라이언트 수명주기를 소유합니다.

## 설정 {#configuration}

`RedissonCacheConfig`가 이름 공간, TTL·만료, 캐시·쓰기 모드, 백엔드 제한을 정합니다. Fory/Kryo/JDK 계열 코덱은 `trustedBinaryCache=true`를 요구하며, 그 밖에는 검토한 코덱을 제공해야 합니다. 시작할 때 시간이 양수인지, 배치와 큐 크기가 제한되어 있는지 확인하세요.

## 실패 방식 {#failures}

기본 무효화는 캐시만 지웁니다. `deleteFromDBOnInvalidate=true`는 무효화를 DB 삭제로 바꾸므로 파괴적 정책으로 따로 검토해야 합니다. 캐시 hit도 오래된 값일 수 있고, 백엔드 성공 뒤 DB가 실패할 수 있으며, DB 커밋 뒤 무효화가 실패할 수도 있습니다. 이 상태를 하나의 캐시 오류로 뭉개지 말고 따로 기록하세요.

## 운영 {#operations}

애플리케이션이 `RedissonClient`를 소유합니다. 저장소에서 다른 구성요소와 공유하는 클라이언트를 종료하면 안 됩니다. hit/miss, 백엔드 지연, 재시도·타임아웃, 큐 깊이, 거부·dead-letter 쓰기, 무효화 지연, 종료 drain을 관찰합니다. 캐시와 DB 경로의 SLO도 따로 잡아야 합니다.

## 테스트 {#testing}

격리한 캐시와 DB fixture에 고유한 이름 공간을 사용합니다. 미스 로딩, `getAll` 일부 미스, 켠 쓰기 모드, 캐시 전용 무효화, TTL, 부분 실패, 수명주기 정리를 검증하세요.

```bash
./gradlew :bluetape4k-exposed-jdbc-redisson:test
```

## 학습 경로 {#workshops}

1. [Exposed 캐시 기반 라이브러리](bluetape4k-exposed-cache.md)에서 공통 계약을 익힙니다.
2. [캐시 선택 가이드](../guides/cache-selection.md)에서 백엔드를 고릅니다.
3. Read-through와 캐시 전용 무효화를 먼저 구현합니다.
4. Near Cache나 Write-behind 전에 부분 실패와 종료 테스트를 추가합니다.
5. [exposed-workshop](https://github.com/bluetape4k/exposed-workshop)에서 실행 가능한 구성을 살펴봅니다.

## 제약 사항 {#limitations}

이 어댑터는 분산 트랜잭션을 만들거나 백엔드를 설치하지 않습니다. 저장 데이터를 마이그레이션하거나 오래된 값을 허용할지도 대신 정하지 않습니다. 설정으로 `RMapCache` 또는 `RLocalCachedMap`을 고르며 Near Cache 동기화는 정합성에도 영향을 줍니다.

## 소스 {#sources}

- [모듈 README](../../../../exposed/jdbc-redisson/README.md)
- [추상 저장소](../../../../exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/repository/AbstractJdbcRedissonRepository.kt)
- [모듈 빌드](../../../../exposed/jdbc-redisson/build.gradle.kts)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### JDBC Redisson Redis cache 아키텍처

[![JDBC Redisson Redis cache 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-01.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### JDBC Redisson synchronous repository hierarchy 다이어그램

[![JDBC Redisson synchronous repository hierarchy 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-02.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### JDBC Redisson read-through 시퀀스 다이어그램

[![JDBC Redisson read-through 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-01.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### JDBC Redisson write-through 시퀀스 다이어그램

[![JDBC Redisson write-through 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-02.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### JDBC Redisson write-behind 시퀀스 다이어그램

[![JDBC Redisson write-behind 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-03.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### Suspended JDBC Redisson read-through 시퀀스 다이어그램

[![Suspended JDBC Redisson read-through 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-04.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-04.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### Suspended JDBC Redisson write-through 시퀀스 다이어그램

[![Suspended JDBC Redisson write-through 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-05.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-05.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

### Suspended JDBC Redisson write-behind 시퀀스 다이어그램

[![Suspended JDBC Redisson write-behind 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-06.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-06.svg)

_배포본 README: [`exposed/jdbc-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.ko.md)_

<!-- release-readme-diagrams:end -->
