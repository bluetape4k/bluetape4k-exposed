# Module exposed-measured

[English](./README.md) | 한국어

`exposed-measured`는 `bluetape4k-measured` 값을 Exposed `DOUBLE` 컬럼에 매핑합니다. Table DSL 헬퍼가 기준 단위를 고정하고, `ColumnType`이 DB 경계에서 값을 변환하며, 조회 시에는 `Measure<T>`, `Temperature`, `TemperatureDelta` 객체로 복원합니다.

## Column DSL 커버리지

![Measured column DSL coverage](../../docs/images/readme-diagrams/exposed-exposed-measured-diagram-01.png)

## 지원 헬퍼

- `measure(name, baseUnit)`
- `length(name)`, `mass(name)`, `time(name)`, `area(name)`, `volume(name)`
- `angle(name)`, `pressure(name)`, `storage(name)`, `binarySize(name)`, `frequency(name)`
- `energy(name)`, `power(name)`
- `temperature(name)`, `temperatureDelta(name)`

## 변환 흐름

![Measured column conversion flow](../../docs/images/readme-diagrams/exposed-exposed-measured-diagram-02.png)

## 저장/조회 시퀀스

![Measured column round trip](../../docs/images/readme-diagrams/exposed-exposed-measured-sequence-01.png)

## 예제

```kotlin
object ProductTable: Table("products") {
    val width = length("width")
    val weight = mass("weight")
    val duration = time("duration")
    val storage = storage("storage")
    val binarySize = binarySize("binary_size")
    val temp = temperature("temp")
}
```

## 참고 사항

- 모든 generic `Measure<T>` 헬퍼는 값을 해당 헬퍼의 기준 단위로 변환한 `Double`로 저장합니다.
- `temperature(name)`은 Kelvin, `temperatureDelta(name)`은 Kelvin delta 기준으로 저장합니다.
- DB에는 원래 표시 단위가 저장되지 않으므로, 컬럼마다 기준 단위를 안정적으로 유지해야 합니다.
