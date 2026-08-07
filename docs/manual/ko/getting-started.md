---
manualId: "getting-started"
title: "Bluetape4k Exposed 시작하기"
locale: "ko"
releaseRef: "1.12.1"
---

# Bluetape4k Exposed 시작하기

## 중앙 BOM으로 버전 맞추기

애플리케이션은 개별 Exposed 모듈의 버전을 따로 적지 않는다. `bluetape4k-dependencies` BOM 하나로 호환되는 버전 조합을 가져오고, 필요한 데이터 접근 경로만 추가한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
    // 코루틴 기반 비동기 데이터 접근이 필요하면 JDBC 대신 이 모듈을 선택한다.
    // implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc")
}
```

`<version>`에는 애플리케이션이 사용하는 `bluetape4k-dependencies` 배포 버전을 넣는다. 이 매뉴얼의 `1.12.1`은 문서가 검증한 Exposed 소스 기준이며, 위 `<version>` 자리에 넣는 버전이 아니다.

## JDBC와 R2DBC 중 하나를 먼저 선택하기

두 모듈을 습관적으로 함께 넣지 않는다. 드라이버 모델, 트랜잭션의 소유 주체, 블로킹 경계, 프레임워크 연동 방식으로 주 경로를 정한다.

| 확인할 항목 | JDBC | R2DBC |
| --- | --- | --- |
| 드라이버 | 블로킹 JDBC 드라이버 | 논블로킹 R2DBC 드라이버 |
| 호출 경로 | 블로킹 작업을 허용하거나 별도 영역에 격리 | 코루틴 기반 논블로킹 호출을 끝까지 유지 |
| 트랜잭션 | JDBC 트랜잭션 관리자가 소유 | R2DBC 트랜잭션 관리자와 코루틴 컨텍스트가 소유 |
| 대표 연동 | Spring MVC, 일반 배치 | Spring WebFlux, 코루틴 기반 서비스 |

기존 JDBC 드라이버와 트랜잭션 관리자를 쓰는 서비스라면 JDBC가 기본 선택이다. R2DBC는 함수에 `suspend`가 붙었다는 이유만으로 고르지 않는다. 드라이버와 커넥션 풀, 프레임워크, 호출 사슬 전체가 논블로킹 경계를 유지할 때 선택한다.

## 다음 단계

1. [`core`](modules/bluetape4k-exposed-core.md)와 [`dao`](modules/bluetape4k-exposed-dao.md)에서 ID, 엔티티, 매핑 개념을 확인한다.
2. [`jdbc`](modules/bluetape4k-exposed-jdbc.md) 또는 [`r2dbc`](modules/bluetape4k-exposed-r2dbc.md) 중 하나로 저장소와 트랜잭션 경계를 만든다.
3. 기본 경로가 테스트된 뒤에만 캐시, 데이터베이스 어댑터, Spring Boot·Ktor 연동을 추가한다.
4. 실행 가능한 순서는 [학습 경로](guides/learning-path.md)를 따른다.
