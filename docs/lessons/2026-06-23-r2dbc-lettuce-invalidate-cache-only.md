# 교훈 - R2DBC Lettuce cache-only invalidate (2026-06-23)

Issue: #286

## 교훈

Repository `invalidate` method는 backing cache map 계약보다 shared cache 계약을 먼저 따라야 합니다. R2DBC Lettuce에서 `cache.delete` 호출은 cache 제거처럼 보이지만 실제로는 write-through writer delete 경로에 들어가 DB 행을 지울 수 있었습니다. repository 계약이 cache-only invalidation을 말한다면 `evict` / `evictAll`을 사용합니다.

## 근거

- `R2dbcCacheRepository`는 `invalidate`와 `invalidateAll`을 DB 영향 없는 cache 제거로 문서화합니다.
- `AbstractR2dbcLettuceRepository`는 DB writer delete를 호출할 수 있는 `delete` / `deleteAll`을 사용했습니다.
- 회귀 테스트는 production 수정 전 실패했고 `evict` / `evictAll`로 바꾼 뒤 통과했습니다.

## 향후 보호 장치

cache map을 repository 의미론으로 감쌀 때는 두 abstraction 수준에서 method 이름을 검토합니다. `delete`는 write-through map에서 persistent delete를 뜻할 수 있지만 `evict`는 cache-only 연산입니다.
