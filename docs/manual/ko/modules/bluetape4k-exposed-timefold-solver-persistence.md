---
manualId: "bluetape4k-exposed-timefold-solver-persistence"
id: "bluetape4k-exposed-timefold-solver-persistence"
title: "Exposed Timefold Score 영속화"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-timefold-solver-persistence"
sourceDir: "exposed/timefold-solver-persistence"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-timefold-solver-persistence
---

# Exposed Timefold Score 영속화

> Timefold 2의 Score 값을 Exposed 컬럼에 저장합니다. Solver 저장소까지 제공하는 모듈은 아닙니다.

## 제공하는 기능 {#problem}

Timefold의 Score 클래스는 JDBC 기본 타입이 아닌 도메인 값입니다. 이 모듈은 Timefold 2에 내장된 Score 계열 8종을 Exposed 컬럼으로 쓰도록 컬럼 팩터리, 컬럼 타입, 변환기를 제공합니다. 계획 사실을 읽거나 해 전체를 저장하지 않으며, `SolverManager`를 실행하거나 풀이 작업의 생명주기를 관리하지도 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

마지막으로 채택한 해의 점수를 결과 레코드와 함께 보존하는 것처럼 Exposed 테이블에 타입이 있는 Score 컬럼이 필요할 때 사용하세요. 계획 엔티티 매핑과 Solver 생명주기는 애플리케이션이 맡아야 합니다. Timefold 의미와 무관한 숫자 순위만 필요하다면 일반 Exposed 숫자 컬럼이 더 단순합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-timefold-solver-persistence")
}
```

사용자는 중앙 `bluetape4k-dependencies` 버전만 지정하면 됩니다. 이 라이브러리에 별도 버전을 적지 마세요.

## 핵심 개념 {#concepts}

`SimpleScore`는 `BIGINT`에 대응하는 Exposed `LongColumnType`을 사용합니다. 나머지 7종은 `VARCHAR`에 저장하고 Timefold의 표준 `toString()`과 `parseScore(...)` 표현으로 왕복 변환합니다.

| Score 계열 | 컬럼 팩터리 | 저장 형식 |
| --- | --- | --- |
| `SimpleScore` | `simpleScore("score")` | `Long` |
| `SimpleBigDecimalScore` | `simpleBigDecimalScore("score")` | `VARCHAR` |
| `HardSoftScore` | `hardSoftScore("score")` | `VARCHAR` |
| `HardSoftBigDecimalScore` | `hardSoftBigDecimalScore("score")` | `VARCHAR` |
| `HardMediumSoftScore` | `hardMediumSoftScore("score")` | `VARCHAR` |
| `HardMediumSoftBigDecimalScore` | `hardMediumSoftBigDecimalScore("score")` | `VARCHAR` |
| `BendableScore` | `bendableScore("score", length)` | `VARCHAR` |
| `BendableBigDecimalScore` | `bendableBigDecimalScore("score", length)` | `VARCHAR` |

문자열 컬럼도 Score 값 자체는 정확하게 복원하지만, 데이터베이스의 문자열 정렬 순서는 Timefold의 Score 비교 순서와 다릅니다. 이런 컬럼에 단순히 `ORDER BY score`를 적용해 최적 점수를 찾으면 안 됩니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
object PlanningResults : LongIdTable("planning_results") {
    val name = varchar("name", 120)
    val score = hardSoftScore("score")
    val bendable = bendableScore("bendable_score", length = 500).nullable()
}

transaction {
    PlanningResults.insert {
        it[name] = "vehicle-routing-42"
        it[score] = HardSoftScore.of(-2, -35)
        it[bendable] = BendableScore.of(
            longArrayOf(-1, 0),
            longArrayOf(-10, -20, -5),
        )
    }
}
```

실제 Score 문자열 길이를 기준으로 `VARCHAR` 크기를 정하세요. Bendable Score는 hard/soft 단계 수에 따라 문자열이 길어지므로 기본값이나 예제 값을 그대로 쓰면 부족할 수 있습니다.

## 작업별 API {#api-by-task}

- 컬럼 정의: Score 종류에 맞는 `Table.*Score(...)` 확장 함수를 호출합니다.
- 문자열 용량 조정: 문자열 기반 팩터리에 `length`를 지정합니다.
- 변환 방식 확인: 같은 이름의 `*ScoreColumnType`과 `*ScoreTransformer`를 봅니다.
- 새 데이터베이스 검증: 양수, 음수, 0, 미초기화, 소수, Bendable 값을 넣고 다시 읽습니다.

변환기는 기반 구현입니다. 애플리케이션 테이블에서는 변환기 클래스를 직접 만들기보다 컬럼 팩터리를 사용하는 편이 낫습니다.

## 권장 패턴 {#patterns}

- Score를 만든 해의 리비전이나 도메인 버전을 함께 저장합니다.
- Score 컬럼은 값의 스냅숏일 뿐, 해 전체가 존재하거나 최신이라는 증거가 아닙니다.
- 점수 비교는 파싱한 뒤 Kotlin에서 수행하거나, 데이터베이스 정렬이 꼭 필요하다면 비교 가능한 구성 요소를 별도 컬럼으로 저장합니다.
- 문자열 길이 변경은 스키마 마이그레이션으로 처리합니다. 잘린 Score 문자열은 다시 파싱할 수 없습니다.

## 연동 {#integrations}

Exposed Core와 Timefold Solver Core를 사용하므로 Exposed DSL과 DAO 테이블 모두에 적용할 수 있습니다. JDBC와 R2DBC 중 하나를 선택하거나 저장소를 제공하지 않으며 Spring/Ktor 생명주기에도 관여하지 않습니다. 이 책임은 해당 데이터베이스·프레임워크 모듈과 조합하세요.

## 설정 {#configuration}

Spring 프로퍼티나 런타임 서비스 빈은 없습니다. SQL 타입, null 허용 여부, 문자열 길이 같은 테이블 스키마가 곧 설정입니다. 중앙 BOM으로 Timefold 주 버전을 맞춰 직렬화 형식과 `parseScore(...)` 구현이 호환되게 유지하세요.

## 실패 유형과 해결 방법 {#failures}

- `Data too long` 또는 문자열 잘림: 마이그레이션으로 문자열 컬럼을 늘립니다.
- 읽을 때 파싱 실패: 예전 값이나 수동으로 바꾼 값을 확인합니다. Timefold 표준 Score 문자열이어야 합니다.
- 데이터베이스 정렬 결과가 틀림: `VARCHAR`의 사전식 순서는 Score의 의미상 순서가 아닙니다.
- Score를 읽었는데 계획 데이터가 없음: 이 모듈은 Score 값만 저장합니다.
- 소수 Score가 달라짐: 손실이 생기는 숫자 매핑을 새로 만들지 말고 표준 BigDecimal Score 문자열을 보존합니다.

## 테스트와 운영 {#operations}

배포본 테스트는 Score 계열 8종을 실제 Exposed insert/select로 왕복시킵니다. 애플리케이션 스키마에서는 가장 긴 Bendable 형태와 실제 소수 자릿수도 추가로 검증하세요. 마이그레이션 전에는 파싱하지 못하는 값과 컬럼 길이를 넘는 값을 먼저 집계합니다. 운영에서는 Score 하나만 떼어 보기보다 해 ID와 리비전을 함께 기록해야 원인을 추적하기 쉽습니다.

## 테스트 {#testing}

```bash
./gradlew :bluetape4k-exposed-timefold-solver-persistence:test
```

모듈 테스트는 컬럼 계열 8종의 변환과 데이터베이스 왕복을 검증합니다. Timefold 풀이 작업이나 계획 모델 전체를 저장하는 기능까지 확인하는 테스트는 아닙니다.

## 학습 경로와 예제 {#workshops}

먼저 `SimpleScoreTest`를 보고, 문자열 기반인 `HardSoftScoreTest`와 Bendable 테스트를 비교하세요. 그다음 애플리케이션 테이블에 Score 컬럼을 하나 정의하고 실제 모델이 만들 수 있는 가장 긴 값을 왕복시켜 봅니다. JDBC 테이블과 트랜잭션은 [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop), R2DBC 애플리케이션은 [Exposed R2DBC 워크숍](https://github.com/bluetape4k/exposed-r2dbc-workshop)으로 이어서 학습할 수 있습니다. 계획 도메인과 Solver 생명주기는 Timefold 공식 문서를 기준으로 삼으세요.

## 제약 사항 {#limitations}

이 라이브러리는 Score 컬럼 어댑터입니다. 계획 엔티티 저장, `Solution` 복원, Solver 작업 조정, 낙관적 잠금, 문자열 Score의 데이터베이스 공통 의미상 정렬은 제공하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Timefold Score column families 다이어그램

[![Timefold Score column families 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-diagram-01.svg)

_배포본 README: [`exposed/timefold-solver-persistence/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/timefold-solver-persistence/README.ko.md)_

### Score persistence round trip 다이어그램

[![Score persistence round trip 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-flow-02.svg)

_배포본 README: [`exposed/timefold-solver-persistence/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/timefold-solver-persistence/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [지원하는 Score 컬럼 계열](../../../../exposed/timefold-solver-persistence/README.ko.md)
- [`SimpleScore` Long 매핑](../../../../exposed/timefold-solver-persistence/src/main/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/SimpleScore.kt)
- [`HardSoftScore` 문자열 매핑](../../../../exposed/timefold-solver-persistence/src/main/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/HardSoftScore.kt)
- [왕복 테스트](../../../../exposed/timefold-solver-persistence/src/test/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/SimpleScoreTest.kt)
