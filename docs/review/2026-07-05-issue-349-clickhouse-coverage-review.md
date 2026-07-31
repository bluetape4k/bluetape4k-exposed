# 이슈 #349 clickhouse 커버리지 리뷰

## 범위

- 이슈: <https://github.com/bluetape4k/bluetape4k-exposed/issues/349>
- 모듈: `:bluetape4k-exposed-clickhouse`
- 변경된 테스트 범위:
  - `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/engine/MergeTreeDslTest.kt`
  - `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/types/UnsignedTypesTest.kt`

## 리뷰 결과

- P0/P1 지적 사항: 0
- Tier 4 정확성: PASS
- Tier 5 테스트 적정성: PASS
- Tier 7 증거 무결성: PASS

## 증거

- 기준 Kover XML 명령어 커버리지: `60.54%` (`covered=2686`, `missed=1751`, `total=4437`).
- 이슈의 기준 목표: 저장소 모듈 평균인 `80.81%`를 초과하도록 높인다.
- ClickHouse 계약에 초점을 맞춘 단위 테스트 커버리지를 추가했다.
  - `MergeTree`, `ReplacingMergeTree`, `SummingMergeTree`, `AggregatingMergeTree`의 원시/타입 지정 DSL 오버로드.
  - 숫자, 불리언, 문자열 값에 대한 `String` 및 `ClickHouseSettingName` 이름의 ClickHouse 엔진 설정 오버로드.
  - 안전하지 않은 원시 프래그먼트/이름 검증 분기.
  - 기본 부호 있음, 부호 없음, 부동소수점, 고정 문자열, nullable, 테이블 확장 컬럼 타입 변환.
- 집중 검증 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:compileTestKotlin :bluetape4k-exposed-clickhouse:test --tests 'io.bluetape4k.exposed.clickhouse.engine.MergeTreeDslTest' --tests 'io.bluetape4k.exposed.clickhouse.types.UnsignedTypesTest'`
  - 결과: `62 passing`, `BUILD SUCCESSFUL`.
- 전체 모듈 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:koverXmlReport :bluetape4k-exposed-clickhouse:koverLog`
  - 결과: `138 passing`, `BUILD SUCCESSFUL`.
- 최종 Kover:
  - 라인 커버리지: `88.0065%`.
  - XML 명령어 커버리지: `85.73%` (`covered=3804`, `missed=633`, `total=4437`).

## 참고

- 프로덕션 동작은 변경되지 않았다.
- 커버리지는 ClickHouse 컨테이너 왕복 테스트를 추가하지 않고 결정적인 DSL/타입 계약에 집중해 높였다.
- Testcontainers 기반 검증은 `--no-parallel`로 실행했다.
