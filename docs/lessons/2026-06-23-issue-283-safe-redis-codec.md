# 이슈 283 Redis codec 안전성 교훈

Date: 2026-06-23
Issue: #283

## 교훈

Redis 데이터가 신뢰할 수 없는 writer의 영향을 받을 수 있다면 repository cache 기본값이 일반 Redis utility binary codec을 상속하면 안 됩니다. exposed repository 계층에는 repository 경계의 명시적 entity codec 또는 명시적 trusted-data opt-in이 필요합니다.

## 지침

- repository entity 값에 `LettuceLoadedMap` 또는 `LettuceSuspendedLoadedMap` 기본값을 의존하지 마세요. backing utility는 현재 LZ4/Fory를 선택합니다.
- Lettuce repository 모듈에서는 value codec을 repository constructor 계약의 일부로 만들고 기본 sentinel이 거부됨을 증명하는 테스트를 유지합니다.
- Redisson repository 모듈에서는 알려진 Fory/Kryo/JDK 계열 codec을 기본적으로 거부하고, private trusted Redis 데이터에만 `trustedBinaryCache = true`를 요구합니다.
- codec helper 이름만으로 충분한 근거로 삼지 마세요. 신뢰할 수 없는 Redis payload에 안전하다고 부르기 전에 encode/decode fallback 동작을 검사합니다.
- 예제와 테스트 fixture는 정직하게 유지합니다. 호환성이나 성능을 위해 trusted binary codec을 보존한다면 opt-in이 constructor 호출에 보여야 합니다.
- repository constructor 안전성이 바뀔 때마다 codec 기대치를 영어와 한국어 README 파일 모두에 문서화합니다.

## 후속 조치

upstream Redis utility 모듈이 binary fallback 없는 structural JSON codec을 제공하게 되면 Redisson 기본값을 재검토하고 trusted-binary opt-in보다 type-bound structural codec을 우선합니다.
