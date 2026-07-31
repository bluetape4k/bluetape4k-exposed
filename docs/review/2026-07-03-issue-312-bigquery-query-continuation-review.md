# 이슈 #312 BigQuery 쿼리 연속 처리 리뷰

## 범위

- 이슈: #312 `test(bigquery): lock query-job pagination and partial completion contracts`
- 리뷰한 파일:
  - `exposed/bigquery/src/test/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryContinuationUnitTest.kt`

## 검토 결과

- P0/P1: 없음.
- 4단계 구현 리뷰: 테스트는 프로덕션 코드 변경 없이 기존 `BigQueryContext`
  연속 처리 경로를 실행한다.
- 5단계 테스트 리뷰: `jobComplete=false`, `pageToken`, 연속 처리 스키마 폴백,
  페이지 단위 오류, 누락된 `jobReference`, 다음 페이지를 가져오기 전의 Flow 취소를
  검증 범위로 고정한다.
- MockK 패턴 리뷰: BigQuery 작업 목은 클래스 필드이며, `@BeforeEach`에서 공유 동작을
  다시 스텁하기 전에 `clearMocks(...)`를 사용한다.
- 생태계 재사용 리뷰: 단언문은 `assertFailsWith`와
  `coInvoking { ... } shouldThrow`를 포함해 `bluetape4k-assertions`를 사용한다.
- 후속 코드 패턴 리뷰: 컬렉션 원소 수 단언문은
  `collection.size shouldBeEqualTo n` 대신 `shouldHaveSize`를 사용한다.

## 검증

- `git diff --check`: PASS.
- `./gradlew :bluetape4k-exposed-bigquery:test --tests "io.bluetape4k.exposed.bigquery.BigQueryQueryContinuationUnitTest"`:
  PASS, 테스트 5개.
- `rg ".size shouldBeEqualTo|.shouldBeEqualTo\\(|shouldBeEqualTo (true|false)"`
  명령으로 BigQuery 연속 처리 테스트를 검사한 결과: PASS.

## 잔여 위험

- 이 변경은 이슈 범위에 따라 목 기반으로 검증하며, 로컬에서 BigQuery 에뮬레이터
  통합 테스트 스위트를 실행하지 않는다.
