# 이슈 #845 BigQuery regional continuation location 교훈

## 문제

`jobs.query` 초기 요청은 `BigQueryQueryOptions.location`을 사용하지만, 후속
`jobs.getQueryResults` 요청이 작업 위치를 전달하지 않으면 `US`·`EU` 외
regional job의 polling 또는 다음 페이지 조회가 실패할 수 있습니다.

## 근본 원인

List와 Flow가 각각 `getQueryResults` 요청을 직접 생성하면서 `pageToken`과
요청별 `timeoutMs`만 설정하고 작업 위치를 누락했습니다. 따라서 초기 요청이
성공해도 continuation 경로의 REST URL에는 `location`이 포함되지 않았습니다.

## 결정

두 경로가 공유하는 private builder를 사용하고, 초기 응답의
`jobReference.location`을 우선합니다. 응답에 위치가 없을 때만
`BigQueryQueryOptions.location`을 fallback으로 사용합니다. `timeoutMs`는
기존처럼 각 `getQueryResults` 요청에 설정하며 전체 query deadline으로
확장하지 않습니다.

## 검증

실제 BigQuery나 유료 query 없이 `MockHttpTransport`로 regional multi-page
List와 incomplete polling Flow의 모든 continuation URL을 검증했습니다.
추가 회귀 2개를 포함한 전체 `*UnitTest` 26개가 실패·오류·skip 없이 통과했습니다.
완료된 초기 페이지의 연속 token, 두 번의 incomplete polling 뒤 schema 도착,
응답 location 우선과 옵션 fallback, 요청별 timeout을 검증했습니다.
모듈 `detekt`와 `checkKotlinAbi`도 통과했습니다. 실제 Google 자격 증명과
유료 BigQuery 호출은 검증 범위에 포함하지 않았습니다.

## 향후 지침

BigQuery query-job continuation 경로를 추가하거나 변경할 때는 List·Flow가
동일한 request builder를 공유하고, `jobReference.location` 우선 및 옵션
fallback을 유지해야 합니다. regional job 동작은 Mock HTTP URL 검증으로
고정하고 실제 자격 증명 호출에 의존하지 않습니다.
