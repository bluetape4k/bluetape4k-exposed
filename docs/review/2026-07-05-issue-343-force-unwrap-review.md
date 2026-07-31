# Issue #343 검토 — 강제 언래핑 집중 지점

## 변경 범위

- 범위에 포함된 프로덕션 코드에서 강제 언래핑을 제거했다.
  - Ktor Exposed 상태 확인 준비성 dispatcher 불변 조건.
  - Batch step builder의 필수 reader/writer 상태.
  - JDBC Redisson 리포지토리 `getAll`의 map 값 필터링.
- 범위에 포함된 공유 도우미 코드에서 강제 언래핑을 제거했다.
  - JDBC `withDb`, `withDbSuspending`, `withTables`, `withTablesSuspending`.
  - nullable 기본 격리 수준 처리를 포함한 R2DBC `withDb`, `withTables`.
- 프로덕션 코드 검색에 불필요한 결과가 나오지 않도록 `src/main`의 KDoc/주석에서 강제 언래핑을 문자 그대로 사용한 예제와 근거 표시를 제거했다.

## 남아 있는 사용

- 이번 작업 후 최종 Kotlin 검색 요약:
  - 전체 `!!` 줄: 212.
  - `src/main`의 `!!` 줄: 0.
  - 범위에 포함된 집중 지점 파일의 `!!` 줄: 0.
- 남아 있는 강제 언래핑은 테스트 전용 단언문, 픽스처 편의 코드, 벤치마크 정리 코드 또는 후속 단언문 정규화 대상이다. 이 항목들은 의도적으로 issue #337이나 더 좁은 모듈별 정리 작업에 남겨 두었다.

## 검증

- 코드 변경 전 기준선: `./gradlew --no-parallel :bluetape4k-exposed-ktor:test :bluetape4k-exposed-batch:test :bluetape4k-exposed-jdbc-tests:compileTestKotlin :bluetape4k-exposed-r2dbc-tests:compileTestKotlin :bluetape4k-exposed-jdbc-redisson:test` — 36s 만에 BUILD SUCCESSFUL.
- 집중 지점 편집 후: 같은 명령 — 1m 46s 만에 BUILD SUCCESSFUL.
- KDoc/주석 정리 후: 같은 명령 — configuration cache를 재사용하여 1s 만에 BUILD SUCCESSFUL.

## 검토 결과

- #343에 명시된 프로덕션 및 공유 도우미 집중 지점에서는 더 이상 강제 언래핑을 사용하지 않는다.
- 실패 의미가 명확하다. 호출자가 제공해야 하는 Ktor/builder 입력에는 `requireNotNull`을 유지하고, 내부 DB 도우미 상태와 R2DBC 격리 불변 조건에는 `checkNotNull`을 사용한다.
