---
title: "캐시 백엔드 선택"
locale: "ko"
releaseRef: "1.12.1"
---

# 캐시 백엔드 선택

Redis 클라이언트부터 고르지 마세요. 먼저 같은 캐시 값을 봐야 하는 범위, 오래된 값을 허용할 시간, 성공 응답 시점에 DB 영속화가 끝나야 하는지를 정해야 합니다.

![캐시 백엔드와 쓰기 정책 선택 지도](../../assets/cache/cache-selection.png)

## 공유 범위부터 정한다

| 요구 사항 | 먼저 검토할 모듈 | 이유 | 주요 비용 |
| --- | --- | --- | --- |
| 애플리케이션 프로세스 하나 | Caffeine | 네트워크와 외부 서비스가 없음 | JVM마다 상태가 다름 |
| 여러 인스턴스가 상태 공유 | Lettuce 또는 Redisson 원격 캐시 | Redis가 공통 상태를 소유 | 네트워크와 Redis가 가용성 경로에 들어옴 |
| 로컬의 빠른 조회와 Redis 공유 | Near Cache | L1이 일부 네트워크 왕복을 줄임 | 두 계층의 무효화와 만료를 맞춰야 함 |

JDBC와 R2DBC는 캐시 백엔드와 별도로 고릅니다. JDBC 어댑터는 동기 경로를 제공하고 일부에는 suspend JDBC 변형도 있습니다. R2DBC 어댑터는 저장소 연산을 suspend 함수로 유지합니다. 이 차이는 취소와 트랜잭션 소유권을 바꾸지만 캐시 정합성 문제를 없애지는 않습니다.

## 쓰기 계약을 고른다

| 패턴 | 미스 처리 | 쓰기 소유자 | 반환 시점의 의미 | 적합한 경우 |
| --- | --- | --- | --- | --- |
| Cache-aside | 애플리케이션이 DB를 읽고 캐시에 저장 | 애플리케이션 트랜잭션 | DB 커밋 완료. 무효화는 아직 실패할 수 있음 | 기존 서비스가 쓰기를 소유 |
| Read-through | Loader가 DB 조회 | 캐시 loader | 미스 로딩 완료 | 저장소에서 미스 처리를 통일 |
| Write-through | 설정한 writer가 DB 갱신 | 캐시 MapWriter | DB 쓰기 완료 또는 실패 | 동기 영속성이 필요 |
| Write-behind | 읽기는 loader가 처리 | 제한된 비동기 writer | 캐시가 쓰기를 받음. DB 커밋은 아직일 수 있음 | 지연 영속화와 유실 구간을 허용 |
| 무효화 | 로딩하지 않음 | 기본값은 캐시 | 선택한 캐시 상태 제거 | DB가 원본 상태를 소유 |

평범한 `put` 호출만 보고 Write-through라고 판단하면 안 됩니다. `CacheWriteMode`, `LettuceCacheConfig`·`RedissonCacheConfig`, 구체적인 map에 DB writer가 연결됐는지를 함께 확인하세요.

## 백엔드 비교

| 항목 | Caffeine | Lettuce | Redisson |
| --- | --- | --- | --- |
| 위치 | 프로세스 내부 | Redis. 일부 경로에 Caffeine Near Cache 추가 | Redis `RMap`/`RLocalCachedMap` |
| 키·값 계약 | 접두사와 로컬 ID·값 | 명시적 `RedisCodec<String, E>`, 저장할 때 TTL 적용 | Redisson 코덱. 위험한 바이너리 계열은 신뢰 opt-in 필요 |
| 클라이언트 수명주기 | 저장소가 로컬 캐시와 실행 범위 소유 | 애플리케이션이 `RedisClient`, 저장소가 연결 소유 | 애플리케이션이 `RedissonClient` 소유 |
| 부분 실패 | 큐 포화 또는 프로세스 장애 | Redis 타임아웃·재시도, dead letter, DB 실패 | Redis future, writer·DB 실패, 선택적 무효화 시 DB 삭제 |
| 테스트 격리 | 테스트마다 고유 접두사 | 격리한 Redis와 접두사 | 격리한 Redis와 map 이름 |

코덱과 연결을 직접 통제하려면 Lettuce가 잘 맞습니다. Loaded map과 local cached map 정책이 필요하면 Redisson을 검토하세요. 어느 쪽이 항상 빠르다는 뜻은 아닙니다. 소유권과 장애 처리 방식이 선택 기준입니다.

## TTL, 무효화, 오래된 값

TTL은 업무에서 오래된 값을 허용할 시간으로 정합니다. 로컬 캐시는 다른 인스턴스의 무효화를 볼 수 없습니다. Near Cache는 L1과 Redis를 함께 지우거나 동기화해야 합니다. Redisson의 안전한 기본값은 `deleteFromDBOnInvalidate=false`입니다. 이 값을 켜면 캐시 연산이 DB 삭제로 바뀝니다.

키 접두사, 캐시 이름, 코덱, 엔티티 스키마도 저장 데이터 계약입니다. 하나라도 바뀌면 마이그레이션하거나 버전이 붙은 새 이름 공간을 사용하세요. 새 코덱으로 기존 Redis 바이트를 조용히 다시 해석해서는 안 됩니다.

## 장애와 종료 점검표

- 캐시 장애 때 요청을 실패시킬지, 캐시를 우회할지, 로컬의 오래된 값을 허용할지 정합니다.
- 재시도, 타임아웃, Write-behind 큐, 종료 drain 시간을 제한합니다.
- 큐 깊이, 거부된 쓰기, dead letter, Circuit Breaker 상태, 무효화 지연을 관찰합니다.
- 저장소를 먼저 닫고 애플리케이션이 소유한 Redis 클라이언트를 나중에 닫습니다.
- R2DBC와 suspend JDBC 경로의 취소를 테스트합니다.
- 테스트마다 고유 이름 공간을 쓰고 teardown에서 지웁니다.

## 권장 학습 경로

[Exposed 캐시 기반 라이브러리](../modules/bluetape4k-exposed-cache.md)부터 읽으세요. 백엔드 하나를 `READ_ONLY`·Read-through로 구현하고 캐시 전용 무효화를 검증한 뒤 쓰기 정책을 추가합니다. Near Cache와 Write-behind는 각각 두 번째 정합성 경계와 지연 영속화 구간을 만들므로 마지막에 적용하는 편이 안전합니다.

## 소스

- [`CacheMode`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheMode.kt)
- [`CacheWriteMode`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheWriteMode.kt)
- [`LocalCacheConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/LocalCacheConfig.kt)
- [JDBC Lettuce 저장소](../../../../exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/repository/AbstractJdbcLettuceRepository.kt)
- [R2DBC Redisson 저장소](../../../../exposed/r2dbc-redisson/src/main/kotlin/io/bluetape4k/exposed/r2dbc/redisson/repository/AbstractR2dbcRedissonRepository.kt)
