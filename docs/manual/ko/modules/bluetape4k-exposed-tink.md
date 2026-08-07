---
manualId: "bluetape4k-exposed-tink"
id: "bluetape4k-exposed-tink"
title: "Exposed Tink 암호화"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-tink"
sourceDir: "exposed/tink"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-tink
---

# Exposed Tink 암호화

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Google Tink의 AEAD 또는 Deterministic AEAD로 Exposed `VARCHAR`, binary, BLOB 값을 컬럼 경계에서 암호화합니다. 애플리케이션의 평문 값과 DB의 암호문을 연결할 뿐, keyset을 발급·저장·회전·복구하지는 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

id로 읽기만 하고 동등 검색이 필요 없는 민감한 값에는 무작위 AEAD를 사용하세요. 동등 검색이 꼭 필요하고 반복 패턴 노출을 받아들일 수 있을 때만 Deterministic AEAD를 선택합니다. 식별자에 따라서는 hash나 tokenization이 더 나은 검색 설계일 수 있습니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-tink")
}
```

## 핵심 개념 {#concepts}

- AEAD는 같은 평문도 매번 다른 암호문으로 만들기 때문에 동등 조건과 일반 인덱스로 찾을 수 없습니다.
- Deterministic AEAD는 평문과 associated data가 같으면 같은 암호문을 만들어 동등 검색이 가능하지만 반복 패턴이 드러납니다.
- `VARCHAR`는 인코딩한 암호문을, binary와 BLOB 변형은 byte를 저장합니다.
- 기본 associated data는 `bluetape4k-exposed-tink:v1:<table>:<column>`에 암호문을 묶습니다.
- Keyset 수명 주기는 애플리케이션의 secret/KMS 인프라가 맡습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val aead = TinkAead(loadAeadKeysetFromKms())
val daead = TinkDeterministicAead(loadDaeadKeysetFromKms())

object Customers : LongIdTable("customers") {
    val note = tinkAeadVarChar("note", 1024, aead)
    val email = tinkDaeadVarChar("email", 512, daead).index()
}

transaction {
    Customers.insert { row ->
        row[note] = "private note"
        row[email] = "ada@example.com"
    }
}
```

## 작업별 API {#api-by-task}

| 작업 | 1.11 안정판 API |
| --- | --- |
| 무작위 text/bytes/blob | `tinkAeadVarChar`, `tinkAeadBinary`, `tinkAeadBlob` |
| 검색 가능한 결정적 text/bytes/blob | `tinkDaeadVarChar`, `tinkDaeadBinary`, `tinkDaeadBlob` |
| Associated-data 영역 | `TinkColumnAssociatedDataProvider`, `TableAndColumn`, `Empty` |
| 저수준 매핑 | `Tink*Aead*ColumnType`과 transformer. 일반 코드에서는 테이블 DSL 권장 |

## 권장 패턴 {#patterns}

테이블을 선언하기 전에 KMS나 보호된 secret 저장소에서 영속 keyset을 불러오세요. Associated data를 일관되게 적용하려면 테이블 DSL을 사용합니다. 회전이 필요하면 key id/version을 암호문 밖에 기록하세요. 예전 데이터를 읽는 reader를 먼저 배포하고 새 primary key로 행을 다시 쓴 뒤, 변환 범위를 증명한 후에 예전 key를 폐기합니다.

## 연동 {#integrations}

`bluetape4k-tink` primitive와 Exposed `ColumnWithTransform`을 사용합니다. Exposed가 애플리케이션 값을 DB 표현으로 바꿀 때 암호화하고, 컬럼을 읽을 때 복호화합니다. 이 경로로 저장하면 DB에는 평문이 전달되지 않습니다.

## 설정 {#configuration}

AEAD/DAEAD 방식, 암호문 길이, keyset 출처, 회전 정책, associated-data provider를 정해야 합니다. 기본 provider는 안정적인 테이블명과 컬럼명을 포함합니다. 둘 중 하나를 바꾸면 associated data도 달라지므로 기존 암호문을 재암호화하거나 마이그레이션 중 호환 provider로 읽어야 합니다.

## 실패 유형과 해결 방법 {#failures}

- Keyset이 없거나 재생성됐거나 잘못되면 저장된 행을 복호화할 수 없습니다.
- 테이블·컬럼명 변경을 포함해 associated data가 달라지면 인증에 실패합니다.
- AEAD는 매번 nonce가 달라져 동등 조건으로 찾을 수 없습니다.
- DAEAD는 같은 평문의 반복과 빈도를 노출합니다.
- 암호문이 `VARCHAR`·binary 용량보다 크면 DB가 거부하거나 잘라낼 수 있습니다.
- 빈 컬럼명과 0 이하 길이는 테이블 DSL 검증에서 실패합니다.

## 운영 {#operations}

Keyset은 DB와 별도로 백업하고 둘을 함께 복구하는 훈련을 하세요. 평문·암호문·key material을 로그에 쓰지 않으면서 key 접근과 회전을 감사해야 합니다. 복호화·인증 실패를 alert로 연결합니다. 이름 변경과 key 회전은 재개 가능한 backfill과 rollback을 갖춘 데이터 마이그레이션으로 다룹니다.

## 테스트 {#testing}

Round-trip, 잘못된 key, 잘못된 associated data, 변조된 암호문, nullable, 최대 payload, 영속 keyset을 사용한 재시작·다중 노드를 검증합니다. DAEAD는 동등 검색이 되는지, AEAD는 같은 평문이 다른 암호문을 만들고 동등 검색 대상으로 쓰지 않는지 확인하세요.

```bash
./gradlew :bluetape4k-exposed-tink:test
```

## 학습 경로와 예제 {#workshops}

[직렬화와 암호화 선택 가이드](../guides/serialization-and-encryption.md)를 먼저 읽으세요. 이어서 DSL 검증과 associated data는 `TinkTableTest`, AEAD는 `TinkColumnTypeTest`, 결정적 검색은 `TinkDaeadColumnTypeTest`에서 확인할 수 있습니다.

## 제약 사항 {#limitations}

필드 암호화 모듈이지 key 관리 시스템, 권한 계층, 범용 검색 암호, DB 전체 암호화의 대체재가 아닙니다. Deterministic AEAD도 동등 검색만 지원하며 범위·prefix·정렬·부분 문자열 조건은 보존하지 않습니다. Key를 잃으면 이 모듈로 복구할 수 없습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Tink encrypted column boundary 다이어그램

[![Tink encrypted column boundary 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-01.svg)

_배포본 README: [`exposed/tink/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/tink/README.ko.md)_

### AEAD and DAEAD behavior 처리 흐름

[![AEAD and DAEAD behavior 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-02.svg)

_배포본 README: [`exposed/tink/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/tink/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [Gradle 빌드 파일](../../../../exposed/tink/build.gradle.kts)
- [테이블 DSL](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/Tables.kt)
- [Associated-data provider](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnAssociatedDataProvider.kt)
- [AEAD 테스트](../../../../exposed/tink/src/test/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnTypeTest.kt)
- [DAEAD 테스트](../../../../exposed/tink/src/test/kotlin/io/bluetape4k/exposed/core/tink/TinkDaeadColumnTypeTest.kt)
