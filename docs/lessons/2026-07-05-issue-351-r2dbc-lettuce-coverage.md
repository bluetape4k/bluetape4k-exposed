# 교훈과 학습: Issue #351 r2dbc-lettuce 커버리지

## 배경

`r2dbc-lettuce`의 명령어 커버리지는 현재 저장소의 모듈 평균보다 낮았다.
커버되지 않은 영역이 가장 넓은 파일은 `ExposedR2dbcLettuceSuspendedLoadedMap.kt`였으며,
이 파일은 JDBC Lettuce 모듈에서 이미 검증한 직접 중단 맵 계약과 대응한다.

## 효과적이었던 접근

- 테스트를 작성하기 전에 Kover XML을 파싱하여 가장 적합한 커버리지 대상을 식별했다.
- 형제 모듈인 `jdbc-lettuce`의 직접 맵 계약 테스트를 이식하여 변경 범위를 좁게
  유지하고 프로덕션 코드 리팩터링을 피했다.
- 새 Redis 기반 suspend 테스트는 가상 시간 코루틴 스케줄링이 아니라 실제
  Testcontainers IO를 수행하므로 `runSuspendIO`를 사용해야 한다.
- Testcontainers 기반 모듈 테스트를 하나의 `--no-parallel` Gradle 호출로 실행하여
  저장소의 Redis 테스트 안정성 규칙을 지켰다.

## 근거

- 기준 XML 명령어 커버리지: `73.71%`.
- 최종 XML 명령어 커버리지: `88.33%`.
- 최종 모듈 테스트/Kover 명령:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:koverXmlReport :bluetape4k-exposed-r2dbc-lettuce:koverLog`
  - 결과: `138 passing`, `4 pending`, `BUILD SUCCESSFUL`.

## 향후 주의 사항

형제 캐시 모듈의 커버리지 이슈를 다룰 때는 Kover XML sourcefile 카운터부터
확인한다. 이후 저장소 시나리오를 확장하기 전에 커버되지 않은 프로덕션 영역이
가장 넓은 대상에 직접 계약 테스트를 우선 적용한다.
