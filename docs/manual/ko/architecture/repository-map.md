---
manualId: "repository-map"
title: "Exposed 저장소 지도"
locale: "ko"
releaseRef: "1.12.1"
---

# Exposed 저장소 지도

`bluetape4k-exposed 1.12.1`의 40개 Gradle 프로젝트는 모두 같은 역할을 하지 않는다. 먼저 공통 기반 모듈 위에서 JDBC 또는 R2DBC 경로를 고른다. 그 뒤에 캐시와 직렬화·열 변환 모듈, 데이터베이스 어댑터를 필요한 만큼 붙인다. 마지막으로 애플리케이션 프레임워크에서 자원과 트랜잭션을 누가 소유하는지 확인하고 연동 모듈을 선택한다.

![Exposed 모듈 지도](../../assets/overview/module-map.png)

## 네 층으로 읽기

1. **공통 기반** — `core`와 `dao`는 공통 타입과 엔티티·ID 모델을 제공하고, `cache`는 캐시 계약을 정의한다. `bom`은 함께 사용할 모듈의 버전을 맞춘다.
2. **데이터 접근** — `jdbc`와 `r2dbc`는 드라이버와 트랜잭션 처리 방식이 서로 다르다. 애플리케이션의 주 경로는 둘 중 하나다.
3. **선택 기능** — Caffeine·Lettuce·Redisson 캐시, JSON·Tink 직렬화와 암호화, 측정값 열 매핑, 데이터베이스 어댑터가 기본 경로를 보강한다.
4. **애플리케이션 연동** — Spring Boot, Ktor, Batch, Spring Modulith 모듈은 프레임워크의 자원 소유권에 맞춰 Exposed를 연결한다.

데이터베이스 어댑터는 JDBC나 R2DBC를 대신하지 않는다. 데이터베이스별 기능을 보강하는 모듈이므로 먼저 기본 데이터 접근 경로를 정해야 한다. 캐시도 마찬가지다. 저장소와 트랜잭션의 동작을 정하기 전에 캐시를 넣으면 오래된 값을 읽었을 때의 처리 방법과 캐시 무효화 책임부터 불분명해진다.

## 릴리스 범위

이 지도에는 `1.12.1` 태그에 있는 모듈만 표시한다. 현재 개발 브랜치에만 있는 모듈은 다음 안정 마이너 버전의 문서가 나올 때까지 포함하지 않는다. 정확한 프로젝트 목록과 소스 경로는 [모듈 매뉴얼](../modules/bluetape4k-exposed-bom.md)에서 확인할 수 있다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k Exposed 모듈 구성도

[![Bluetape4k Exposed 모듈 구성도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/root-readme-module-relationships-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/root-readme-module-relationships-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/README.ko.md)_

### Bluetape4k Exposed 개요

[![Bluetape4k Exposed 개요](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/root-readme-overview-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/root-readme-overview-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/README.ko.md)_

<!-- release-readme-diagrams:end -->
