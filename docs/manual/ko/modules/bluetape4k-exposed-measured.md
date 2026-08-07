---
manualId: "bluetape4k-exposed-measured"
id: "bluetape4k-exposed-measured"
title: "Exposed 측정 지원"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-measured"
sourceDir: "exposed/measured"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-measured
---

# Exposed 측정 지원

> 라이브러리 모듈

## 제공하는 기능 {#problem}

물리 측정값을 Exposed 컬럼에 저장하면서 Kotlin 코드에서는 `Measure`, `Temperature`, `TemperatureDelta` 타입으로 다룰 수 있게 합니다. DB에는 기준 단위로 변환한 `DOUBLE`만 저장되며 단위 정보는 남지 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

도메인 코드에서 측정 차원을 타입으로 구분하고, 컬럼마다 기준 단위를 하나로 고정할 수 있을 때 적합합니다. 금액처럼 십진 정밀도가 정확해야 하거나 행마다 입력 단위를 보존해야 한다면 다른 저장 모델을 사용하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-measured")
}
```

## 핵심 개념 {#concepts}

- `MeasureColumnType`은 선언한 기준 단위로 변환한 `Double`을 저장합니다.
- 편의 DSL의 기준은 m, kg, s, m², m³, rad, Pa, byte, Hz, J, W로 고정됩니다.
- `temperature`는 Kelvin, `temperatureDelta`는 Kelvin 차이를 저장합니다.
- 사용자가 처음 입력한 단위와 단위 객체는 저장되지 않습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
object Sensors : LongIdTable("sensors") {
    val cableLength = length("cable_length_m")
    val ambient = temperature("ambient_kelvin")
}

transaction {
    Sensors.insert {
        it[cableLength] = 150.centimeters()
        it[ambient] = 25.celsius()
    }
}
```

DB에는 각각 약 `1.5` metre와 `298.15` Kelvin이 저장됩니다.

## 작업별 API {#api-by-task}

| 작업 | 1.11 안정판 API |
| --- | --- |
| 사용자 정의 차원·기준 단위 | `measure(name, baseUnit)` |
| 일반 측정 | `length`, `mass`, `time`, `area`, `volume`, `angle`, `pressure` |
| 데이터·에너지 | `storage`, `binarySize`, `frequency`, `energy`, `power` |
| 온도 | `temperature`, `temperatureDelta` |

## 권장 패턴 {#patterns}

컬럼 이름이나 스키마 문서에 기준 단위를 적어 두세요. 단위 변환은 입력·출력 경계에서만 합니다. 기준 단위 변경은 데이터 마이그레이션입니다. Reader는 기존 `1000.0`이 metre인지 millimetre인지 알아낼 수 없습니다.

## 연동 {#integrations}

`bluetape4k-measured` 도메인 타입과 Exposed core 컬럼 타입을 연결합니다. JDBC와 R2DBC 드라이버에는 평범한 `DOUBLE`로 보이므로 DB 함수와 인덱스는 기준 단위의 숫자에 적용됩니다.

## 설정 {#configuration}

설정할 단위 registry는 없습니다. 테이블 선언이 기준 단위를 정합니다. 유한값 여부, 물리적 범위, 반올림 허용 오차, `NaN`과 무한대 허용 여부는 애플리케이션에서 검증해야 합니다.

## 실패 유형과 해결 방법 {#failures}

- 드라이버가 `Number`가 아닌 값을 반환하면 컬럼 타입이 표시된 오류가 발생합니다.
- 행을 변환하지 않고 기준 단위를 바꾸면 같은 숫자의 의미가 조용히 달라집니다.
- `DOUBLE` 변환에는 이진 부동소수점 오차가 있습니다.
- 애플리케이션이 막지 않으면 `NaN`과 무한대가 도메인 가정을 깨뜨릴 수 있습니다.
- 절대온도와 온도차를 혼동하면 타입은 읽혀도 의미가 틀립니다.

## 운영 {#operations}

마이그레이션, 대시보드, export, alert에 단위를 표시하세요. 비현실적인 범위와 유한하지 않은 값을 일찍 감지해야 합니다. 변환된 `Double`을 정확히 같다고 비교하지 말고 도메인에 맞는 허용 오차를 정합니다.

## 테스트 {#testing}

여러 입력 단위, 음수와 경계값, round-trip 허용 오차, 운영 DB Dialect를 검증합니다. 단위 계약이 중요하면 DB에 저장된 원시 숫자도 확인하세요. 기준 단위나 정밀도 정책을 바꿀 때는 마이그레이션 테스트를 추가합니다.

```bash
./gradlew :bluetape4k-exposed-measured:test
```

## 학습 경로와 예제 {#workshops}

[직렬화와 암호화 선택 가이드](../guides/serialization-and-encryption.md)에서 타입 컬럼의 공통 경계를 먼저 살펴보세요. 이어서 모든 편의 단위를 다루는 테이블 DSL 테스트와 오류·정밀도 동작을 확인하는 컬럼 타입 테스트를 읽으면 됩니다.

## 제약 사항 {#limitations}

단위 metadata, 측정 출처, 불확도, 유효 숫자, 임의 정밀도 십진수를 저장하지 않습니다. 도메인 범위도 검증하지 않습니다. 기준 단위나 숫자 표현을 바꾸려면 스키마와 데이터를 명시적으로 마이그레이션해야 합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Measured column DSL coverage

[![Measured column DSL coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-01.svg)

_배포본 README: [`exposed/measured/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.ko.md)_

### Measured column conversion 흐름

[![Measured column conversion 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-02.svg)

_배포본 README: [`exposed/measured/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.ko.md)_

### Measured column round trip

[![Measured column round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-sequence-01.svg)

_배포본 README: [`exposed/measured/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [Gradle 빌드 파일](../../../../exposed/measured/build.gradle.kts)
- [측정 컬럼 타입](../../../../exposed/measured/src/main/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypes.kt)
- [DSL 범위 테스트](../../../../exposed/measured/src/test/kotlin/io/bluetape4k/exposed/core/measured/TableDslMeasuredColumnsTest.kt)
- [Round-trip 테스트](../../../../exposed/measured/src/test/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypesTest.kt)
