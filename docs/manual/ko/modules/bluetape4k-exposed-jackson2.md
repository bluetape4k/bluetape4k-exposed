---
manualId: "bluetape4k-exposed-jackson2"
id: "bluetape4k-exposed-jackson2"
title: "Exposed Jackson 2 직렬화"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jackson2"
sourceDir: "exposed/jackson2"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2
---

# Exposed Jackson 2 직렬화

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Jackson 2로 Kotlin 값을 Exposed JSON·JSONB 컬럼에 매핑합니다. JDBC `ResultRow`와 R2DBC `Readable`에서 타입 값이나 JSON tree를 읽을 수 있고, Dialect별 JSON 식도 제공합니다. 문서 버전의 호환성은 애플리케이션이 관리합니다.

## 사용하기 좋은 경우 {#when-to-use}

이미 Jackson 2 annotation, module, `com.fasterxml.jackson` 타입을 쓰는 서비스에 적합합니다. 기존 Jackson 2 코드베이스에서는 마이그레이션 비용이 가장 작습니다. 한 컬럼의 계약에 Jackson 2와 Jackson 3 타입을 섞지 마세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2")
}
```

## 핵심 개념 {#concepts}

- `jackson<T>`은 Dialect의 JSON 타입을, `jacksonb<T>`는 JSONB를 매핑합니다.
- 기본값은 `DefaultJacksonSerializer`이며, Jackson 2 serializer를 직접 전달할 수도 있습니다.
- `getJackson`과 `getJsonNode`가 JDBC·R2DBC 행을 타입 값이나 tree로 읽습니다.
- `contains`, `exists`, `extract<T>`의 SQL 지원 범위는 Dialect마다 다릅니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
data class Preferences(val locale: String = "en", val digest: Boolean = true)

object Users : LongIdTable("users") {
    val preferences = jackson<Preferences>("preferences")
}

transaction {
    Users.insert { it[preferences] = Preferences("ko", false) }
    val value = Users.selectAll().single()[Users.preferences]
}
```

## 작업별 API {#api-by-task}

| 작업 | 1.11 안정판 API |
| --- | --- |
| JSON/JSONB | `jackson`, `jacksonb`, `JacksonColumnType`, `JacksonBColumnType` |
| 타입 읽기 | `getJackson`, `getJacksonOrNull` |
| Tree 읽기 | `getJsonNode`, `getJsonNodeOrNull` |
| 조건 | `contains`, `exists` |
| 값 추출 | `extract<T>` |

## 권장 패턴 {#patterns}

컬럼별로 이름 규칙, Kotlin module, 날짜·시간, 다형성, 알 수 없는 프로퍼티 정책을 고정하세요. 호환되지 않는 변경은 두 형태를 읽는 reader를 먼저 넣고 데이터를 변환한 뒤 예전 형태를 제거합니다. 변경 주기가 다르다면 API DTO와 저장용 JSON 모델도 분리하는 편이 안전합니다.

## 연동 {#integrations}

Exposed core 컬럼 타입과 JDBC·DAO·R2DBC용 reader를 제공합니다. JSON 쿼리 연산은 현재 Dialect에 위임합니다. H2 피드백 테스트만으로 PostgreSQL JSONB 동작을 보증할 수는 없습니다.

## 설정 {#configuration}

기본 설정이 맞지 않으면 컬럼 또는 reader overload에 `JacksonSerializer`를 전달합니다. Serializer 설정은 꾸밈이 아니라 저장 데이터의 동작 계약입니다. subtype id나 프로퍼티 이름 규칙을 바꾸면 기존 행을 읽지 못할 수 있습니다.

## 실패 유형과 해결 방법 {#failures}

- 생성자 필수값 누락, 알 수 없는 subtype, 맞지 않는 scalar 타입은 역직렬화 중 실패합니다.
- non-null 매핑에서 serializer가 `null`을 반환하면 즉시 실패합니다.
- 드라이버가 지원하지 않는 타입을 넘기면 컬럼 경계에서 실패합니다.
- 지원하지 않는 JSON 경로나 연산은 SQL 생성 또는 실행 중 실패합니다.
- 호환성 테스트 없이 Jackson 3으로 바꾸면 저장된 JSON을 읽지 못할 수 있습니다.

## 운영 {#operations}

복원 오류에는 테이블·컬럼·레코드 id와 예외 종류만 기록하고 민감한 문서는 남기지 마세요. 새 reader를 배포하는 동안 실패 수를 관찰합니다. JSONB 인덱스와 실행 계획은 serializer 선택과 별도로 검토해야 합니다.

## 테스트 {#testing}

예전·현재 payload fixture, 기본값, 알 수 없는 필드, nullable 값, 다형성 값, 깨진 JSON을 round-trip으로 검증합니다. 사용하는 JSON 조건식은 운영 DB Dialect마다 실행하세요.

```bash
./gradlew :bluetape4k-exposed-jackson2:test
```

## 학습 경로와 예제 {#workshops}

[직렬화와 암호화 선택 가이드](../guides/serialization-and-encryption.md)에서 codec과 마이그레이션 비용을 비교하세요. 이어서 모듈의 JSON·JSONB 및 행 reader 테스트를 읽고, [트랜잭션 경계](../guides/transaction-boundaries.md)에 맞춰 repository에 적용하면 됩니다.

## 제약 사항 {#limitations}

문서 버전 관리, 기존 행 변환, 인덱스 선택은 이 모듈의 역할이 아닙니다. Dialect 간 JSON 함수의 동일 동작도 보장하지 않습니다. Jackson 2와 3은 패키지와 타입 생태계가 다르므로 코드가 비슷하다는 사실만으로 호환성을 판단하면 안 됩니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Jackson 2 JSON column boundary

[![Jackson 2 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-diagram-01.svg)

_배포본 README: [`exposed/jackson2/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson2/README.ko.md)_

### Jackson 2 JSON round trip

[![Jackson 2 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson2-flow-02.svg)

_배포본 README: [`exposed/jackson2/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson2/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [Gradle 빌드 파일](../../../../exposed/jackson2/build.gradle.kts)
- [JSON 컬럼 타입](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnType.kt)
- [JSONB 컬럼 타입](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonBColumnType.kt)
- [행 reader](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/ResultRowExtensions.kt)
- [컬럼 테스트](../../../../exposed/jackson2/src/test/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnTest.kt)
