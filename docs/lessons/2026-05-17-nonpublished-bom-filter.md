# Non-published Module BOM Filter

## 배경

example 및 demo module은 validation에는 유용하지만 consumer BOM이나 Central Portal
artifact가 되어서는 안 됩니다.

## 결정

BOM constraint, NMCP aggregation, publication/signing setup 전반에서 example,
`*-examples`, `*-demo`, `benchmark/`, `*-benchmark`에 하나의 normalized
non-published module filter를 사용합니다.

## 결과

Exposed BOM과 publishing aggregation은 이제 library module만 포함합니다.

## 검증

- `./gradlew clean generatePomFileForBluetapeExposedPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성된 BOM POM scan에서 `examples`, `demo`, `benchmark` entry가 발견되지 않았습니다.
