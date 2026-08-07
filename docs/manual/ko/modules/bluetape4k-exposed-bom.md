---
manualId: "bluetape4k-exposed-bom"
id: "bluetape4k-exposed-bom"
title: "Exposed BOM"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-bom"
sourceDir: "exposed/bom"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-bom
---

# Exposed BOM

> 배포된 `bluetape4k-exposed` 라이브러리의 버전을 맞춥니다. 애플리케이션에서는 보통 이 저장소의 BOM을 직접 고르지 않고 중앙 `bluetape4k-dependencies` BOM을 사용합니다.

## 제공하는 기능 {#problem}

실제 애플리케이션은 core와 JDBC 또는 R2DBC, 데이터베이스 어댑터, 프레임워크 연동 모듈을 조합합니다. 각 버전을 따로 적으면 함께 배포하거나 검증한 적 없는 의존성 조합이 만들어질 수 있습니다. 이 BOM은 저장소에서 배포하는 라이브러리의 버전 제약을 한곳에 모읍니다.

## 사용하기 좋은 경우 {#when-to-use}

여러 bluetape4k 저장소를 함께 쓴다면 `io.github.bluetape4k:bluetape4k-dependencies:<version>` 플랫폼을 선택하세요. 의존성 관리 범위를 `bluetape4k-exposed` 하나로 제한해야 할 때만 이 저장소 BOM을 직접 가져오는 편이 낫습니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
}
```

저장소 BOM의 직접 좌표는 `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.1`입니다.

## 핵심 개념 {#concepts}

- BOM은 버전 제약만 추가하며 런타임 클래스는 제공하지 않습니다.
- 저장소 BOM은 배포 대상 라이브러리를 제약하고 예제, 데모, 벤치마크는 제외합니다.
- 매뉴얼 버전은 문서의 소스 기준을 나타냅니다. 애플리케이션 버전은 특별한 이유가 없다면 중앙 BOM에서 정합니다.

## 빠르게 시작하기 {#quick-start}

플랫폼은 하나만 가져오고 관리 대상 bluetape4k 라이브러리에는 버전을 쓰지 않습니다. 그런 다음 Gradle이 실제로 선택한 의존성 그래프를 확인하세요. 같은 제약을 반복하려고 두 BOM을 함께 가져올 필요는 없습니다.

## 작업별 API {#api-by-task}

| 작업 | Gradle API |
|---|---|
| 관리 버전 사용 | `implementation(platform(...))` |
| 모든 제약 강제 | `implementation(enforcedPlatform(...))` |
| 특정 의존성 확인 | `dependencyInsight` |
| 구성 전체 확인 | `dependencies --configuration runtimeClasspath` |

## 권장 패턴 {#patterns}

중앙 BOM 버전은 버전 카탈로그나 convention plugin 한곳에서 관리합니다. 버전 하나를 올린 뒤 컴파일과 데이터베이스 통합 테스트를 함께 실행하는 방식이 가장 안전합니다.

## 연동 {#integrations}

BOM은 라이브러리 버전을 맞출 뿐 JDBC/R2DBC 드라이버, 커넥션 풀, Spring starter, Testcontainers를 설치하지 않습니다. 선택한 실행 경로에 맞춰 필요한 런타임 의존성을 별도로 선언해야 합니다.

## 설정 {#configuration}

런타임 설정은 없습니다. Gradle이 의존성 그래프를 계산할 때 플랫폼 제약을 적용합니다.

## 실패 유형과 해결 방법 {#failures}

- 관리 대상 라이브러리에 버전을 다시 적으면 검증된 조합을 덮어쓸 수 있습니다.
- BOM을 일반 라이브러리처럼 취급하면 실제 실행 모듈이 빠집니다.
- 서로 다른 릴리스 계열의 중앙 BOM과 저장소 BOM을 섞으면 예상하지 못한 버전이 선택될 수 있습니다.

## 운영 {#operations}

배포 이력에는 중앙 BOM 버전을 남기세요. 클래스패스 문제를 조사할 때는 빌드 파일만 보고 추측하지 말고 `dependencyInsight` 결과를 확보합니다.

## 테스트 {#testing}

의존성 해석 결과를 확인하고 선택한 JDBC 또는 R2DBC 경로를 컴파일한 뒤 데이터베이스 통합 테스트를 실행합니다. BOM 자체에는 런타임 동작이 없습니다.

## 학습 경로와 예제 {#workshops}

[빠르게 시작하기](../getting-started.md)를 읽은 다음 [JDBC와 R2DBC 선택](../guides/jdbc-vs-r2dbc.md)으로 이어가세요. workshop 저장소도 같은 중앙 의존성 관리 방식을 사용합니다.

## 제약 사항 {#limitations}

Exposed, Kotlin, 드라이버, 프레임워크 버전을 애플리케이션이 따로 덮어쓰면 BOM만으로 호환성을 보장할 수 없습니다. JDBC와 R2DBC 중 어느 쪽을 쓸지도 BOM이 결정하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Exposed BOM managed artifact 지도

[![Exposed BOM managed artifact 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bom-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bom-diagram-01.svg)

_배포본 README: [`exposed/bom/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bom/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [BOM 빌드](../../../../exposed/bom/build.gradle.kts)
- [저장소 설정](../../../../settings.gradle.kts)
