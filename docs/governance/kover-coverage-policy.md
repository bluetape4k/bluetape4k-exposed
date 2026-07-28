# Kover Coverage 정책

## 현재 상태

`bluetape4k-exposed`는 CI/Nightly에서 Kover report를 생성합니다. 일부 module은 benchmark 또는
test-fixture support package를 측정에서 제외하지만, repository-wide coverage threshold enforcement는
활성화되어 있지 않습니다.

## 정책

상태: report-only transition.

이 repository는 Exposed core extension, JDBC/R2DBC integration, serialization module, Spring Boot
auto-configuration, database-specific dialect를 포함합니다. 단일 repository threshold는 module별 위험을
가릴 수 있습니다.

## Threshold 계획

- Kover는 build gate가 아니라 trend signal로 취급합니다.
- CI/Nightly report로 coverage regression을 식별합니다.
- module에 coverage repair가 필요하면 focused issue를 엽니다. failing threshold를 default enforcement
  mechanism으로 도입하지 않습니다.
- generated, benchmark, test-fixture helper code는 명시적으로 제외합니다.

## CI/Nightly 계약

CI/Nightly는 가시성을 위해 module coverage artifact를 upload합니다. future issue가 해당 gate를 명시적으로
다시 도입하지 않는 한, 특정 module이 고정 coverage percentage보다 낮다는 이유만으로 실패하면 안 됩니다.
