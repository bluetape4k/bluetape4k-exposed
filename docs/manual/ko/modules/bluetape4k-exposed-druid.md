---
manualId: "bluetape4k-exposed-druid"
id: "bluetape4k-exposed-druid"
title: "Exposed Druid JDBC 유틸리티"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-druid"
sourceDir: "exposed/druid"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-druid
---

# Exposed Druid JDBC 유틸리티

`bluetape4k-exposed-druid`는 Calcite Avatica 원격 드라이버를 통해 Apache Druid에 연결하는 의도적으로 query-only인 JDBC 경계입니다. Druid를 Exposed 트랜잭션이나 DAO 데이터베이스로 만들지는 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

Druid Avatica endpoint에서 파라미터화한 SQL 조회와 metadata 읽기가 필요할 때 사용합니다. 변경 작업, 여러 단계의 일관성, 스키마 소유권은 이 어댑터 밖에 둡니다. 도우미는 연결을 열기 전에 query가 아닌 문장을 거부합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-druid")
}
```

## 연결과 조회 경계 {#connection-and-query-boundary}

`DruidConnectionOptions`는 JSON 또는 Protobuf 직렬화 선택, context 값, 인증 속성을 포함한 공식 Avatica Router URL을 만듭니다. `DruidJdbc.connection()`은 이 옵션으로 JDBC 연결을 만들고, `DruidJdbc.query()`는 query 문장만 받아 `PreparedStatement`로 파라미터를 바인딩합니다.

```kotlin
val options = DruidConnectionOptions(
    endpoint = "https://druid.example.com",
    context = mapOf("sqlTimeZone" to "UTC")
)
val rows = DruidJdbc.query(options, "SELECT datasource FROM INFORMATION_SCHEMA.TABLES") { statement ->
    statement.executeQuery().use { resultSet ->
        generateSequence { if (resultSet.next()) resultSet.getString(1) else null }.toList()
    }
}
```

## 권장 패턴 {#patterns}

- JDBC 연결을 열기 전에 endpoint, query-only 값, context, 인증을 검증합니다.
- 사용자 입력 값은 반드시 파라미터로 바인딩하고 SQL literal을 조합하지 않습니다.
- Druid 조회는 애플리케이션이 소유한 경계에 두고, 트랜잭션 쓰기는 OLTP 데이터베이스에서 수행합니다.
- Avatica timeout, 전송 오류, 결과 decoding 오류는 rollback할 수 있는 트랜잭션이 아니라 조회 실패로 처리합니다.

## 테스트 {#testing}

단위 테스트는 공식 Avatica URL 생성, Protobuf 설정, 속성 전달, 조기 검증, 파라미터화한 metadata SQL, query가 아닌 문장 거부를 확인합니다.

```bash
./gradlew :bluetape4k-exposed-druid:test --no-daemon
```

## 문제 {#problem}

Druid는 원격 읽기 중심 SQL 경계입니다. Avatica 연결을 Exposed transaction처럼 다루면 rollback과 일관성에 대해 잘못된 기대가 생깁니다.

## 핵심 개념 {#concepts}

이 어댑터는 endpoint 구성, query 검증, 파라미터 바인딩, 결과 매핑을 분리합니다. Druid schema나 다중 문장 transaction을 소유하지 않습니다.

## 빠른 시작 {#quick-start}

`DruidConnectionOptions`를 만들고 `DruidJdbc.query`를 호출한 뒤 callback 안에서 `ResultSet`을 매핑합니다. endpoint와 context 값은 애플리케이션 설정으로 둡니다.

## 작업별 API {#api-by-task}

- `DruidConnectionOptions`로 Router URL을 만듭니다.
- 더 낮은 수준의 JDBC 작업이 필요하면 `DruidJdbc.connection`을 사용합니다.
- `DruidJdbc.query`로 파라미터화한 읽기를 실행합니다.

## 연동 {#integrations}

서비스나 repository 경계에서 이 helper를 사용합니다. Spring과 Ktor 애플리케이션은 endpoint 설정을 주입하고 자체 요청·timeout 정책으로 결과를 노출해야 합니다.

## 설정 {#configuration}

Avatica endpoint, serialization mode, SQL context, 인증 속성, connection timeout을 설정합니다. credential을 소스 관리 URL에 넣지 않습니다.

## 실패 유형 {#failures}

연결 전에 query가 아닌 문장을 거부합니다. Avatica 전송 오류, timeout, 잘못된 응답, 매핑 실패는 애플리케이션 retry 정책으로 처리하는 읽기 실패입니다.

## 운영 {#operations}

credential이나 민감한 query parameter를 기록하지 말고 논리 query 이름, endpoint 식별자, 소요 시간, row 수, 실패 유형을 기록합니다. 결과 크기와 요청 시간을 제한합니다.

## 학습 경로와 예제 {#workshops}

query-only 예제부터 시작해 URL 구성과 파라미터 바인딩 테스트를 확인한 뒤 서비스 경계에 어댑터를 연결합니다.

## 제약 사항 {#limitations}

이 모듈은 Exposed DAO, 분산 transaction, schema migration, write 문장 또는 원격 Druid query의 idempotency를 보장하지 않습니다.

## 소스 {#sources}

- [`DruidConnectionOptions`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidConnectionOptions.kt)
- [`DruidJdbc`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidJdbc.kt)
- [`DruidJdbcTest`](../../../../exposed/druid/src/test/kotlin/io/bluetape4k/exposed/druid/DruidJdbcTest.kt)
