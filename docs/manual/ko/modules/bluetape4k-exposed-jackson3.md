---
manualId: "bluetape4k-exposed-jackson3"
id: "bluetape4k-exposed-jackson3"
title: "Exposed Jackson 3 직렬화"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-jackson3"
sourceDir: "exposed/jackson3"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jackson3
---

# Exposed Jackson 3 직렬화

> 라이브러리 모듈

## 제공하는 기능 {#problem}

1.12.1 안정판에 들어 있는 Jackson 3 API로 Kotlin 값을 Exposed JSON·JSONB 컬럼에 매핑합니다. Jackson 2 모듈과 같은 컬럼·행 reader·Dialect 식 역할을 `io.bluetape4k.exposed.core.jackson3` 패키지에서 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 Jackson 3와 `tools.jackson` 타입을 채택했을 때 사용하세요. Jackson 2를 쓰던 서비스라면 이 의존성만 교체하지 말고 애플리케이션 module과 저장 JSON을 함께 검증하며 옮겨야 합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson3")
}
```

## 핵심 개념 {#concepts}

- `jackson<T>`과 `jacksonb<T>`는 안정판의 `core.jackson3` 패키지에 있습니다.
- Jackson 3 기본값은 `DefaultJacksonSerializer`이며 직접 만든 serializer도 전달할 수 있습니다.
- JDBC `ResultRow`와 R2DBC `Readable`에서 `getJackson`·`getJsonNode`를 쓸 수 있습니다.
- `contains`, `exists`, `extract<T>`의 SQL은 현재 Dialect가 만듭니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
import io.bluetape4k.exposed.core.jackson3.jacksonb

data class Profile(val displayName: String, val labels: List<String> = emptyList())

object Members : LongIdTable("members") {
    val profile = jacksonb<Profile>("profile")
}

transaction {
    Members.insert { it[profile] = Profile("Ada", listOf("admin")) }
    val value = Members.selectAll().single()[Members.profile]
}
```

## 작업별 API {#api-by-task}

| 작업 | 1.11 안정판 API |
| --- | --- |
| JSON/JSONB | `core.jackson3.jackson`, `jacksonb`, `JacksonColumnType`, `JacksonBColumnType` |
| 타입 읽기 | `getJackson`, `getJacksonOrNull` |
| Tree 읽기 | `getJsonNode`, `getJsonNodeOrNull` |
| 조건 | `contains`, `exists` |
| 값 추출 | `extract<T>` |

## 권장 패턴 {#patterns}

1.12.1 태그에서 확인한 API만 사용하세요. develop에 나중에 추가된 API는 이 매뉴얼의 범위가 아닙니다. 컬럼별로 프로퍼티 이름, module, subtype id, 알 수 없는 필드 정책을 고정하고, Jackson 2 writer를 바꾸기 전에 예전 행을 읽을 수 있는지 증명해야 합니다.

## 연동 {#integrations}

Exposed core와 JDBC·DAO·R2DBC 행 경로에 연동됩니다. `com.fasterxml.jackson`과 `tools.jackson`의 tree/model 타입을 서로 변환해 주지는 않습니다. JSON SQL 동작도 DB마다 다릅니다.

## 설정 {#configuration}

기본 mapper 정책이 맞지 않으면 Jackson 3 `JacksonSerializer`를 직접 전달합니다. Mapper 설정을 persistence 경계에 두고 스키마처럼 버전 관리하세요. DDL이 바뀌지 않아도 serializer 변경은 데이터 마이그레이션일 수 있습니다.

## 실패 유형과 해결 방법 {#failures}

- 함수 이름이 같아도 Jackson 2 패키지를 import하면 전혀 다른 타입을 사용하게 됩니다.
- 생성자 값, subtype id, module이 빠지면 복원 중 실패합니다.
- non-null 매핑이 `null`로 복원되면 즉시 실패합니다.
- 지원하지 않는 JSON 함수와 경로는 Dialect에 따라 실패합니다.
- 예전 행 fixture 없이 writer를 바꾸면 되돌리기 어려운 비호환성이 생길 수 있습니다.

## 운영 {#operations}

마이그레이션 중에는 복원 실패 수를 추적하고, 예전 행과 writer를 모두 처리할 때까지 rollback 경로를 유지하세요. 로그에는 식별자만 남기고 문서 본문은 쓰지 않습니다. JSONB 인덱스와 실행 계획은 운영과 비슷한 데이터로 확인합니다.

## 테스트 {#testing}

1.12.1 Jackson 3 import를 컴파일 단계에서 확인합니다. 현재·예전 fixture, Kotlin 기본값, 알 수 없는 필드, nullable 값, tree 읽기, 깨진 JSON, DB별 JSON 식을 round-trip으로 검증하세요.

```bash
./gradlew :bluetape4k-exposed-jackson3:test
```

## 학습 경로와 예제 {#workshops}

[직렬화와 암호화 선택 가이드](../guides/serialization-and-encryption.md)를 읽은 뒤 Jackson 2·3 모듈 테스트를 저장 fixture와 비교하세요. Repository에 넣기 전 [트랜잭션 경계](../guides/transaction-boundaries.md)로 이어 가면 됩니다.

## 제약 사항 {#limitations}

Jackson 2 마이그레이션, 저장 JSON 재작성, 두 패키지 생태계의 상호 변환을 자동화하지 않습니다. 문서 버전 관리나 Dialect 간 JSON 함수 이식성도 제공하지 않습니다. 1.11 태그 뒤 develop에 추가된 API는 의도적으로 제외했습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Jackson 3 JSON column boundary

[![Jackson 3 JSON column boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-diagram-01.svg)

_배포본 README: [`exposed/jackson3/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson3/README.ko.md)_

### Jackson 3 JSON round trip

[![Jackson 3 JSON round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jackson3-flow-02.svg)

_배포본 README: [`exposed/jackson3/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jackson3/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [Gradle 빌드 파일](../../../../exposed/jackson3/build.gradle.kts)
- [JSON 컬럼 타입](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnType.kt)
- [JSONB 컬럼 타입](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonBColumnType.kt)
- [행 reader](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/ResultRowExtensions.kt)
- [안정판 테스트](../../../../exposed/jackson3/src/test/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnTest.kt)
