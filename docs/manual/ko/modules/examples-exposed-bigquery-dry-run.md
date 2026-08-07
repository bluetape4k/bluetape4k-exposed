---
manualId: "examples-exposed-bigquery-dry-run"
id: "examples-exposed-bigquery-dry-run"
title: "BigQuery Dry-run 예제"
locale: "ko"
kind: "example"
gradlePath: ":examples-exposed-bigquery-dry-run"
sourceDir: "examples/exposed-bigquery-dry-run"
releaseRef: "1.12.1"
artifact: null
---

# BigQuery Dry-run 예제

> 예제를 운영 배포 절차로 오해하지 않으면서 생성한 BigQuery 작업과 비용 관련 메타데이터를 검증합니다.

## 학습 내용 {#problem}

`BigQueryContext.validateRawQuery`로 BigQuery SQL을 만들고 모의 REST client에 요청을 보내는 예제입니다. 클라우드 자격 증명이나 비용이 발생하는 질의 없이 dry-run 요청의 모양을 검증합니다. 배포 라이브러리가 아니라 학습용 모듈입니다.

## 사전 조건 {#prerequisites}

- JDK와 저장소의 Gradle Wrapper
- Google Cloud 자격 증명, 프로젝트, 데이터셋, Docker, 실제 BigQuery 서비스는 필요 없음

테스트는 Exposed SQL 생성용으로만 H2를 사용하고 BigQuery REST client는 요청을 메모리에 기록하는 가짜 구현으로 바꿉니다.

## 실행 {#run}

```bash
./gradlew :examples-exposed-bigquery-dry-run:test
```

## 확인할 결과 {#expected-result}

테스트는 REST `QueryRequest` 하나를 기록합니다. `dryRun=true`와 최대 청구 byte, label, priority, location, timeout이 요청에 들어갔는지 단언하고 생성한 SQL도 확인합니다. 실제 클라우드 작업이나 질의 결과는 만들지 않습니다.

## 실패 진단 {#failures}

- 예상한 요청이 기록되지 않음: SQL을 바꾸기 전에 모의 REST client 연결을 확인합니다.
- 요청 옵션이 빠짐: `validateRawQuery` 인자와 기록된 `QueryRequest`를 비교합니다.
- 생성 SQL이 다름: Exposed 테이블·질의 정의와 BigQuery 변환 경계를 확인합니다.
- 실제 클라우드 오류가 발생함: 예제가 문서화된 모의 경계를 벗어난 것입니다. 가짜 client를 복구하거나 별도의 환경 선택형 테스트로 옮깁니다.

## 다음 학습 경로 {#next}

[BigQuery 어댑터](bluetape4k-exposed-bigquery.md)와 [데이터베이스 어댑터 비교표](../guides/database-adapter-matrix.md)를 읽은 뒤 [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)에서 저장소 패턴을 익혀 보세요.

## 사용하기 좋은 경우 {#when-to-use}

질의 비용 제한이나 BigQuery 작업 메타데이터를 추가하면서 클라우드 계정 없이 빠른 계약 테스트를 만들 때 사용하세요. IAM과 dataset location, quota, 실제 서비스 동작은 별도의 환경 선택형 smoke test로 확인합니다.

## 의존성 좌표 {#coordinates}

이 예제는 라이브러리를 배포하지 않습니다. 애플리케이션에서는 `io.github.bluetape4k:bluetape4k-dependencies:<version>`를 가져오고 개별 라이브러리 버전은 생략하세요.

## 핵심 개념 {#concepts}

`BigQueryContext`는 H2로 SQL을 만들고 BigQuery 어댑터는 REST `QueryRequest`를 구성합니다. dry run은 요청 메타데이터와 예상 작업을 검증하지만 JDBC 트랜잭션이 아니며 다른 BigQuery 작업을 롤백할 수도 없습니다.

## 빠르게 시작하기 {#quick-start}

위의 Gradle 명령을 실행하고 `BigQueryDryRunExampleTest`를 확인합니다. Gradle이 시작됐다는 사실이 아니라 기록된 요청의 단언이 모두 통과해야 성공입니다.

## 작업별 API {#api-by-task}

H2 기반 `BigQueryContext`를 만들고 `validateRawQuery`를 호출한 뒤 기록된 `QueryRequest`에서 dry-run과 작업 옵션을 확인합니다. 실제 실행과 결과 paging은 이 예제 밖에 둡니다.

## 권장 패턴 {#patterns}

SQL 생성 테스트는 결정적으로 유지하고, 최대 청구 byte와 location을 명시하세요. 필수 비용 보호 옵션이 빠졌을 때 실패하는 음성 테스트도 추가합니다.

## 연동 {#integrations}

`bluetape4k-exposed-bigquery`, H2를 통한 Exposed SQL 생성, 가짜 BigQuery REST client를 조합합니다. Google 인증은 의도적으로 연동하지 않습니다.

## 설정 {#configuration}

최대 청구 byte, label, priority, location, timeout 같은 비용·작업 옵션을 코드에 명시합니다. 운영 자격 증명과 프로젝트 선택은 이 테스트가 아니라 애플리케이션 환경에서 관리합니다.

## 운영 {#operations}

이 테스트는 빌드 시점 요청 계약으로 사용합니다. 운영에서는 BigQuery 작업 상태, 예상·청구 byte, quota 오류, location 불일치, timeout을 별도로 관찰하세요.

## 테스트 {#testing}

모의 테스트는 빠른 테스트 suite에 둡니다. 실제 IAM과 서비스 호환성을 증명해야 할 때만 별도의 opt-in smoke test를 추가하고, 일반 기여자에게 클라우드 자격 증명을 요구하지 마세요.

## 학습 경로와 예제 {#workshops}

[BigQuery 어댑터](bluetape4k-exposed-bigquery.md)에서 실행과 paging을 익힌 뒤 [데이터베이스 어댑터 표](../guides/database-adapter-matrix.md)에서 분석 어댑터를 비교하세요.

## 제약 사항 {#limitations}

이 mock은 요청 구성만 증명합니다. IAM, 프로젝트·데이터셋 존재 여부, 지역 호환성, quota, 실제 byte 추정치, 결과 정확성, 실서비스 paging, 운영 비용 제한은 증명하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### BigQuery dry-run example 흐름

[![BigQuery dry-run example 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-bigquery-dry-run-flow-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-bigquery-dry-run-flow-01.svg)

_배포본 README: [`examples/exposed-bigquery-dry-run/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/exposed-bigquery-dry-run/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [예제 소스](../../../../examples/exposed-bigquery-dry-run/README.ko.md)
- [Gradle 빌드](../../../../examples/exposed-bigquery-dry-run/build.gradle.kts)
