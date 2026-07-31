# Timefold Solver 2

## 배경

Timefold Solver 2.1은 score package를 flatten하고 integer score API를 long-backed
record로 바꿨으며 여러 1.x artifact와 score class를 제거했습니다.

## 결정

Exposed persistence module을 실제 2.1 public API와 정렬합니다. 지원하지 않는 long
score column을 제거하고 score import를 업데이트하며 줄어든 supported score set과
documentation을 맞춥니다.

## 결과

- score import는 이제 `ai.timefold.solver.core.api.score.*`를 사용합니다.
- 지원하지 않는 `*LongScore` Exposed column helper와 test를 제거했습니다.
- 2.1.0에 publication되지 않아 `timefold-solver-persistence-common` 및
  `timefold-solver-test` alias/usage를 제거했습니다.
- `SimpleScore`는 이제 `LongColumnType`에 long value를 저장합니다.
- README와 Korean README는 지원하는 8개 score type을 설명합니다.
- AWS SDK Java, AWS SDK Kotlin, Fory Kotlin, MyBatis Dynamic SQL은 coordinated
  dependency PR batch의 일부로 central catalog에서 materialize되었습니다.

## 검증

- `./gradlew :bluetape4k-exposed-timefold-solver-persistence:compileTestKotlin --no-daemon`
