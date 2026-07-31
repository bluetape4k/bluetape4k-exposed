# Issue 126 Redisson `upsertAll`

## 배경

Milestone 1.8.1에는 cache warming 및 bulk write-through/write-behind path용 explicit
Redisson bulk upsert API가 필요했습니다.

## 결정

Redisson 4.4.0의 `putAll(Map, batchSize)` / `putAllAsync(Map, batchSize)`를
implementation으로 사용합니다. issue는 `fastPutAllAsync`를 언급했지만 local Redisson
API에는 해당 method가 없습니다.

## 결과

JDBC, Suspended JDBC, R2DBC Redisson repository API에 `upsertAll`을 추가하고
`putAll`을 이를 통해 centralize했으며 module README pair에 새 API를 문서화했습니다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc-redisson:compileKotlin :bluetape4k-exposed-r2dbc-redisson:compileKotlin :bluetape4k-exposed-jdbc-redisson:compileTestKotlin :bluetape4k-exposed-r2dbc-redisson:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test --tests "io.bluetape4k.exposed.redisson.repository.ReadWriteThroughCacheTest" --tests "io.bluetape4k.exposed.r2dbc.redisson.repository.R2dbcReadWriteThroughCacheTest"`: 421 passing
- `./gradlew :bluetape4k-exposed-jdbc-redisson:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-redisson:test --tests "io.bluetape4k.exposed.redisson.repository.ReadWriteThroughCacheTest" --tests "io.bluetape4k.exposed.redisson.repository.SuspendedReadWriteThroughCacheTest"`: 320 passing

## 향후 지침

Redisson bulk primitive을 선택하기 전 actual local Redisson jar API를 확인합니다.
`fastPutAll*`이 version 전반에 존재한다고 가정하지 않습니다.
