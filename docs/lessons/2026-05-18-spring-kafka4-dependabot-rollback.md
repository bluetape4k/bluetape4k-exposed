# Spring Kafka 4 Dependabot Rollback

## 배경

Dependabot PR #139가 `gradle/libs.versions.toml`의 `spring-kafka4` version-catalog
alias를 `4.0.5`에서 `3.3.15`로 변경했습니다.

## 결정

`spring-kafka4`를 `4.0.5`로 복원하고 Dependabot에서
`org.springframework.kafka:*` 및 `spring-kafka*` version-alias update를 ignore합니다.

## 결과

Spring Kafka 3 및 Spring Kafka 4 compatibility line은 수동으로 관리됩니다.
Dependabot은 같은 Maven coordinate에 서로 다른 compatibility baseline을 쓰는 두
alias를 추론할 수 없습니다.

## 검증

- `bluetape4k-exposed`의 `origin/develop`을 확인했습니다.
- Spring Boot, Jackson, Kafka, Spring Kafka line의 compatibility alias drift를 위해
  archive되지 않은 모든 `bluetape4k` GitHub repository를 비교했습니다.

## 향후 guard

version catalog가 하나의 Maven coordinate에 여러 alias를 유지할 때 alias를 분리하거나
compatibility line을 유지한 채 group화할 수 없다면 Dependabot이 해당 coordinate를
업데이트하지 않도록 합니다.
