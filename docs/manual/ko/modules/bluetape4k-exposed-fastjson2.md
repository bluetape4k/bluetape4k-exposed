---
manualId: "bluetape4k-exposed-fastjson2"
id: "bluetape4k-exposed-fastjson2"
title: "Exposed Fastjson2 직렬화"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-fastjson2"
sourceDir: "exposed/fastjson2"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-fastjson2
---

# Exposed Fastjson2 직렬화

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Fastjson2로 Kotlin 값을 Exposed JSON·JSONB 컬럼에 매핑합니다. 타입을 지정해 행을 읽는 확장 함수와 Dialect별 `contains`, `exists`, `extract` 식도 제공합니다. DB에는 JSON만 저장되고 Kotlin 타입이나 serializer 설정은 남지 않으므로, 이 호환성은 애플리케이션이 관리해야 합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션의 JSON 표준이 이미 Fastjson2이고, DB의 JSON 값을 도메인 타입으로 곧바로 읽으려 할 때 잘 맞습니다. Jackson의 annotation이나 module 생태계가 더 중요한 서비스라면 Jackson 모듈을 고르세요. 막연한 성능 이미지로 선택하지 말고 실제 payload와 설정으로 측정해야 합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-fastjson2")
}
```

## 핵심 개념 {#concepts}

- `fastjson<T>`은 Dialect의 JSON 타입을, `fastjsonb<T>`는 JSONB 타입을 사용합니다.
- `DefaultFastjsonSerializer`는 공용 Fastjson2 serializer에 위임합니다.
- `ResultRow`와 R2DBC `Readable`에서 타입 값, 객체, 배열을 읽을 수 있습니다.
- `contains`, `exists`, `extract`가 만드는 SQL은 현재 Dialect에 따라 달라집니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
data class Settings(val theme: String, val alerts: Boolean)

object Accounts : LongIdTable("accounts") {
    val settings = fastjson<Settings>("settings")
}

transaction {
    Accounts.insert { it[settings] = Settings("dark", true) }
    val stored = Accounts.selectAll().single()[Accounts.settings]
}
```

## 작업별 API {#api-by-task}

| 작업 | 1.11 안정판 API |
| --- | --- |
| JSON/JSONB 컬럼 | `fastjson`, `fastjsonb`, `FastjsonColumnType`, `FastjsonBColumnType` |
| 타입을 지정한 행 읽기 | `getFastjson`, `getFastjsonOrNull` |
| 동적 객체·배열 읽기 | `getFastjsonObject`, `getFastjsonArray`와 nullable 변형 |
| JSON 조건 | `contains`, `exists` |
| 경로 값 추출 | `extract<T>` |

## 권장 패턴 {#patterns}

한 컬럼을 쓰고 읽는 모든 코드에 같은 serializer 정책을 적용하세요. 이전 행도 읽어야 한다면 새 필드에 기본값을 둡니다. 형태나 타입을 깨뜨리는 변경은 신·구 형태를 모두 읽는 코드를 먼저 배포하고, 데이터를 변환한 뒤 예전 reader를 제거하는 순서로 진행합니다.

## 연동 {#integrations}

Exposed core의 컬럼 DSL이며 JDBC `ResultRow`와 R2DBC `Readable` 양쪽에서 읽을 수 있습니다. JSON SQL 연산은 DB Dialect에 기대므로 PostgreSQL에서 동작한 경로 식이 다른 DB에서도 같다고 가정하면 안 됩니다.

## 설정 {#configuration}

이름 규칙, 다형성, 날짜·시간, 알 수 없는 필드 처리 방식이 기본값과 다르면 `FastjsonSerializer`를 직접 전달합니다. 이 설정은 저장 데이터의 스키마와 같습니다. SQL 컬럼이 그대로여도 설정을 바꾸면 기존 행을 읽지 못할 수 있습니다.

## 실패 유형과 해결 방법 {#failures}

- non-null `T`를 복원했는데 serializer가 `null`을 반환하면 `IllegalArgumentException`이 발생합니다.
- JSON이 깨졌거나 Kotlin 타입과 맞지 않으면 행을 읽는 시점에 실패합니다.
- 드라이버가 예상하지 못한 타입을 넘기면 `IllegalStateException`이 발생합니다.
- Dialect가 지원하지 않는 JSON 경로나 연산은 SQL 생성 또는 실행 중 실패합니다.
- 별칭이나 기본값 없이 Kotlin 프로퍼티 이름을 바꾸면 기존 행이 깨질 수 있습니다.

## 운영 {#operations}

codec 오류를 기록할 때는 테이블·컬럼·레코드 식별자까지만 남기고 민감한 JSON 본문은 로그에 쓰지 마세요. 신·구 reader를 함께 운영하는 기간에는 변환 실패 수를 따로 측정합니다. JSONB를 써도 인덱스 설계와 실행 계획 확인은 필요합니다.

## 테스트 {#testing}

대표 payload, 필드 누락·추가, nullable 컬럼, 깨진 JSON, 지원 범위에서 가장 오래된 저장 형태를 round-trip으로 검증합니다. 실제 사용하는 DB에서 JSON·JSONB와 `contains`·`exists`·`extract` 쿼리를 실행하세요.

```bash
./gradlew :bluetape4k-exposed-fastjson2:test
```

## 학습 경로와 예제 {#workshops}

먼저 [직렬화와 암호화 선택 가이드](../guides/serialization-and-encryption.md)를 읽으세요. 이어서 모듈 테스트에서 JSON·JSONB round-trip과 행 reader의 동작을 확인하고, repository에 적용하기 전 [트랜잭션 경계](../guides/transaction-boundaries.md)로 넘어가면 됩니다.

## 제약 사항 {#limitations}

이 모듈은 JSON 문서 버전을 관리하거나 기존 행을 변환하지 않습니다. 인덱스를 정해 주지도 않고 JSON 함수의 Dialect 간 동일 동작도 보장하지 않습니다. Fastjson2, Jackson 2, Jackson 3은 컬럼별 대안이며, 바꾸려면 기존 데이터가 호환된다는 증거가 필요합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Fastjson2 JSON column boundary

[![Fastjson2 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-diagram-01.svg)

_배포본 README: [`exposed/fastjson2/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/fastjson2/README.ko.md)_

### Fastjson2 JSON round trip

[![Fastjson2 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-fastjson2-flow-02.svg)

_배포본 README: [`exposed/fastjson2/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/fastjson2/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [Gradle 빌드 파일](../../../../exposed/fastjson2/build.gradle.kts)
- [JSON 컬럼 타입](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnType.kt)
- [JSONB 컬럼 타입](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonBColumnType.kt)
- [JSON 식](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/JsonFunctions.kt)
- [Round-trip 테스트](../../../../exposed/fastjson2/src/test/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnTest.kt)
