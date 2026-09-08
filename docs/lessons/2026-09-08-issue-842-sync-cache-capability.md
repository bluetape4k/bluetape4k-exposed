# 캐시 capability는 실제 구현에서 결정한다

## 원인과 결정

#842에서 동기 Repository는 near-cache 설정만 읽어 `NEAR_CACHE`를 보고했지만,
실제 조회는 Redis loaded map만 사용했다. 존재하지 않는 기능을 새로 구현하지 않고
생성 단계에서 미지원 설정을 거부하며 `REMOTE`만 보고하도록 수정한다.

## 검증과 재발 방지

동기 Repository의 Read-through, Write-through, Write-behind 테스트에서는
지원하지 않는 `nearCacheEnabled=true` 중첩 fixture를 제거하고, 동기 경로의
Remote fixture만 유지한다. NearCache 동작 검증은 이를 실제로 지원하는 suspend
Repository 테스트의 책임으로 남긴다.

`JdbcCacheCapabilityTest`는 `READ_ONLY`, `WRITE_THROUGH`, `WRITE_BEHIND`에
해당하는 세 near-cache preset을 각각 생성해 Redis 호출 전에 동일한
`IllegalArgumentException`이 발생하는지 확인한다. Remote 세 preset은 생성 후
`CacheMode.REMOTE`를 보고하며 Redis에 접근하지 않는지 확인한다. 설정 flag만으로
capability를 추론하지 말고 실제 읽기·무효화·종료 경로와 일치하는지 확인해야 한다.

기준 `842-final.log`에서는 이 미지원 fixture 때문에 672 passing, 60 pending,
144 failing이었고, `MaxLineLength` 두 건도 함께 보고되었다.

최종 전체 모듈 검증은 732개 중 672개 통과, 실패·오류 0, 기존 assumption에
따른 skip 60개였다. capability 회귀 2개는 skip 없이 통과했다. skip에는
cache write-mode/auto-increment/DB 격리 수준에 맞지 않는 조합과 로컬
StructuredTaskScope runtime 미지원 1개가 포함되므로 이를 성공으로 세지 않는다.
모듈 `detekt`와 `checkKotlinAbi`도 통과했다.
