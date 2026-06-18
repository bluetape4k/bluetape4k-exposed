# examples-exposed-bigquery-dry-run

한국어 | [English](./README.md)

Google Cloud credential 없이 BigQuery dry-run 검증 경로를 실행하는 예제입니다.
`BigQueryContext.validateRawQuery`로 SQL을 검증하고, billed-byte 상한, label,
priority, location, timeout 같은 query-job option이 REST `QueryRequest`에
반영되는지 확인합니다.

## 실행

이 예제는 mock BigQuery REST client를 사용하므로 Google Cloud credential이
필요하지 않습니다.

```bash
./gradlew :examples-exposed-bigquery-dry-run:test
```

## 시나리오

![BigQuery dry-run example flow](../../docs/images/readme-diagrams/examples-exposed-bigquery-dry-run-flow-01.png)

- H2 기반 SQL 생성용 DB로 `BigQueryContext`를 구성합니다.
- `validateRawQuery`로 SQL을 dry-run 요청으로 전송합니다.
- 생성된 BigQuery REST `QueryRequest`가 `dryRun=true`와 요청한 job option을
  포함하는지 검증합니다.

## 참고

- [`exposed-bigquery`](../../exposed/exposed-bigquery/README.ko.md) — BigQuery REST executor
