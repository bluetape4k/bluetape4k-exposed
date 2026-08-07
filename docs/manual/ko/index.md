---
manualId: "repository-overview"
title: "Bluetape4k Exposed 매뉴얼"
locale: "ko"
releaseRef: "1.12.1"
---

# Bluetape4k Exposed 매뉴얼

`bluetape4k-exposed`는 JetBrains Exposed를 사용하는 애플리케이션에 저장소 패턴, 트랜잭션 경계, 캐시, 데이터베이스별 확장과 애플리케이션 연동을 제공한다. 이 매뉴얼은 기능 목록보다 먼저 선택 기준을 설명한다. JDBC와 R2DBC 중 어느 경로를 택할지, 캐시는 언제 붙일지, 데이터베이스 어댑터와 Spring Boot·Ktor 연동을 어디에 배치할지를 순서대로 확인할 수 있다.

![Exposed 저장소 구성 개요](../assets/overview/repository-overview.png)

## 핵심 기능

- **저장소 기반 기능:** [Core](modules/bluetape4k-exposed-core.md), [DAO](modules/bluetape4k-exposed-dao.md), 저장소 규칙을 이용해 Exposed 테이블과 엔티티를 재사용 가능한 Kotlin 데이터 접근 구성 요소로 만듭니다.
- **JDBC와 R2DBC:** 블로킹 트랜잭션에는 [JDBC](modules/bluetape4k-exposed-jdbc.md), 코루틴 중심의 논블로킹 접근에는 [R2DBC](modules/bluetape4k-exposed-r2dbc.md)를 사용합니다. 두 경로의 트랜잭션 소유권과 취소 규칙은 서로 다릅니다.
- **트랜잭션과 배치:** [트랜잭션 경계](guides/transaction-boundaries.md)와 [배치 유틸리티](modules/bluetape4k-exposed-batch.md)에서 작업 조합, 일괄 처리, 실패 동작을 설명합니다.
- **캐시:** [캐시 선택 가이드](guides/cache-selection.md)를 따라 JDBC·R2DBC 저장소에 공통 캐시 계약과 Caffeine, Lettuce, Redisson 구현을 연결할 수 있습니다.
- **데이터베이스와 데이터 형식 확장:** [데이터베이스 어댑터 표](guides/database-adapter-matrix.md)와 [직렬화·암호화 가이드](guides/serialization-and-encryption.md)에서 DB별 SQL, JSON, 측정값, 암호화 컬럼을 다룹니다.
- **애플리케이션 연동:** [Spring Boot와 Ktor](guides/spring-and-ktor.md) 모듈이 설정, 생명 주기, 프레임워크별 트랜잭션 연결을 맡습니다.

## 버전 기준

사용자가 직접 선택하는 버전은 `bluetape4k-exposed`의 저장소 버전이 아니라 중앙 BOM인 `io.github.bluetape4k:bluetape4k-dependencies:<version>`이다. 이 문서의 기술 기준은 `bluetape4k-exposed 1.12.1`이며, 안정 릴리스에 포함된 Gradle 프로젝트 40개만 설명한다.

- 릴리스 태그: [`1.12.1`](https://github.com/bluetape4k/bluetape4k-exposed/tree/1.12.1)
- 릴리스 커밋: [`4cc2cce07087241ec24a597d8464615434ea2b81`](https://github.com/bluetape4k/bluetape4k-exposed/commit/4cc2cce07087241ec24a597d8464615434ea2b81)
- 주요 경로: JDBC, R2DBC, 캐시, 데이터베이스 어댑터, 애플리케이션 연동

## 어디서 시작할까

- 프로젝트에 의존성을 추가하려면 [시작하기](getting-started.md)에서 중앙 BOM과 JDBC/R2DBC 선택 기준을 확인한다.
- 전체 저장소가 어떻게 나뉘는지 보려면 [저장소 지도](architecture/repository-map.md)를 읽는다.
- 예제와 워크숍을 따라가려면 [학습 경로](guides/learning-path.md)에서 목표에 맞는 순서를 고른다.
- 특정 모듈의 좌표와 소스 위치가 필요하면 [모듈 목록](modules/bluetape4k-exposed-bom.md)에서 관련 모듈로 이동한다.

## 책임 경계

이 저장소는 애플리케이션의 데이터 접근 경로를 맡는다. 객체 이력, 변경 비교, JaVers 커밋 메타데이터가 필요하다면 저장소 계층에 기능을 억지로 넣지 말고 [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers)로 이동한다. JaVers는 감사 이력을 보완하지만 Exposed 저장소와 캐시를 대체하지 않는다.
