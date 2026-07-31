# 교훈: 이슈 #349 clickhouse 커버리지

## 배경

`clickhouse`의 명령어 커버리지는 현재 저장소의 모듈 평균보다 훨씬 낮았다.
Kover XML을 확인한 결과, 커버리지가 가장 크게 누락된 영역은 데이터베이스에서만 나타나는 동작이 아니라
결정론적인 DSL 및 열 타입 변환 계약이었다.

## 효과가 있었던 접근

- Kover XML을 소스 파일별로 분석해 `EngineDsl.kt`, `BasicColumnTypes.kt`,
  `UnsignedColumnTypes.kt`를 가장 효과가 큰 대상으로 식별했다.
- ClickHouse 엔진 빌더의 모든 설정 오버로드를 호출해 프로덕션 코드를 변경하지 않고도
  `EngineDsl.kt`의 명령어 커버리지를 높였다.
- 열 타입 변환을 직접 테스트해 ClickHouse 컨테이너 실행 시간을 더 늘리지 않으면서 부호 있는 타입,
  부호 없는 타입, 부동 소수점 타입, null 허용 타입, 고정 길이 문자열 및 테이블 확장 빌더 경로를 검증했다.
- 새 커버리지를 대부분 단위 테스트 수준으로 유지함으로써 모듈의 기존 컨테이너 기반 왕복 커버리지를
  보존하면서 이슈를 빠르게 검증할 수 있게 했다.

## 검증 결과

- 기준 XML 명령어 커버리지: `60.54%`.
- 최종 XML 명령어 커버리지: `85.73%`.
- 최종 모듈 테스트/Kover 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-clickhouse:test :bluetape4k-exposed-clickhouse:koverXmlReport :bluetape4k-exposed-clickhouse:koverLog`
  - 결과: `138 passing`, `BUILD SUCCESSFUL`.

## 향후 가드레일

ClickHouse 커버리지 작업에서는 XML 분석을 바탕으로 DSL 및 `ColumnType` 계약 테스트를 먼저 작성한다.
커버되지 않은 동작이 실제 ClickHouse 실행에 의존할 때만 새로운 컨테이너 기반 테스트를 추가한다.
