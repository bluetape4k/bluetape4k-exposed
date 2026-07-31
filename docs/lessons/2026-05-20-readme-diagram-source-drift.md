# README Diagram Source Drift Correction

## 배경

생성된 class diagram은 deprecated `HasIdentifier`를 primary exposed-core API처럼
보이게 했습니다. current source는 이를 deprecated로 표시하고 대신 `Serializable`
record를 권장합니다.

## 결정

복원한 Mermaid만이 아니라 current source에서 README diagram을 재생성합니다. deprecated
compatibility API는 compatibility note로 명시적으로 문서화하지 않는 한 central class
diagram에서 제외합니다.

## 결과

`exposed-core`는 이제 `AuditableIdTable`과 `ExposedPage`를 중심에 두며,
Redisson/R2DBC README snippet은 source API와 일치하는 `Serializable`,
`RedissonCacheConfig`, `table`, `containsKey` name을 사용합니다.

## 검증

README와 SVG text에서 stale `HasIdentifier`, `RedisCacheConfig`, `entityTable`,
marker-only class label을 확인했고 regenerated exposed-core diagram을 visual review했습니다.

## 다음 작업

class/API diagram을 render하기 전 image에 보이는 모든 class, field, method, relationship을
current source에서 grep합니다.
