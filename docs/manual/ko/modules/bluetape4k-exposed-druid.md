---
manualId: "bluetape4k-exposed-druid"
id: "bluetape4k-exposed-druid"
title: "Exposed Druid JDBC 유틸리티"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-druid"
sourceDir: "exposed/druid"
releaseRef: "1.11.0"
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

## 소스 {#sources}

- [`DruidConnectionOptions`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidConnectionOptions.kt)
- [`DruidJdbc`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidJdbc.kt)
- [`DruidJdbcTest`](../../../../exposed/druid/src/test/kotlin/io/bluetape4k/exposed/druid/DruidJdbcTest.kt)
