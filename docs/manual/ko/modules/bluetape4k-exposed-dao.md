---
manualId: "bluetape4k-exposed-dao"
id: "bluetape4k-exposed-dao"
title: "Exposed DAO 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-dao"
sourceDir: "exposed/dao"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-dao
---

# Exposed DAO 확장

> Exposed DAO 엔티티에 ID, 문자열 표현, 생성형 ID, 감사 필드 규칙을 더합니다.

## 제공하는 기능 {#problem}

Exposed DAO 엔티티는 트랜잭션에 묶여 있습니다. 동등성, 문자열 표현, 생성형 ID 엔티티, 감사 필드 갱신을 프로젝트마다 다르게 구현하면 경계가 흐려집니다. 이 모듈은 Exposed DAO 위에 필요한 공통 규칙만 얹습니다.

## 사용하기 좋은 경우 {#when-to-use}

영속성 모델이 Exposed `Entity`와 `EntityClass`를 사용하도록 설계했을 때 선택합니다. 트랜잭션 바깥으로 값을 넘겨야 한다면 JDBC/R2DBC 저장소에서 record나 DTO로 매핑하는 방식이 더 분명합니다.

## 의존성 좌표 {#coordinates}

중앙 `io.github.bluetape4k:bluetape4k-dependencies:<version>` BOM으로 관리되는 `io.github.bluetape4k.exposed:bluetape4k-exposed-dao`를 사용합니다.

## 핵심 개념 {#concepts}

- `idEquals`, `idHashCode`, 엔티티 문자열 빌더로 ID 기반 동작을 명시합니다.
- `StringEntity`와 생성형 ID 엔티티 계열은 core의 ID 테이블과 맞물립니다.
- `AuditableEntity`는 작업자 필드를 채우지만 `updatedAt`은 감사용 JDBC 저장소 업데이트에서만 보장됩니다.
- DAO 엔티티는 현재 Exposed 트랜잭션에 연결된 객체입니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
class Customer(id: EntityID<String>) : StringEntity(id) {
    companion object : StringEntityClass<Customer>(Customers)
    var name by Customers.name
}
```

지연 관계를 읽고 DTO로 변환하는 일은 트랜잭션이 닫히기 전에 끝냅니다.

## 작업별 API {#api-by-task}

| 작업 | API |
|---|---|
| ID 동등성/해시 | `idEquals`, `idHashCode` |
| 진단용 문자열 | `toStringBuilder`, `entityToStringBuilder` |
| 문자열 ID | `StringEntity`, `StringEntityClass` |
| 생성형 ID | KSUID, ULID, Snowflake, time-based UUID 엔티티 계열 |
| 감사 작업자 필드 | `AuditableEntity`와 타입별 구현 |

## 권장 패턴 {#patterns}

DAO 조회와 지연 관계 탐색은 호출자가 연 JDBC 트랜잭션 하나에서 끝냅니다. 그 경계에서 불변 DTO로 바꾸고 서비스가 `Entity`를 그대로 반환하지 않도록 합니다.

## 연동 {#integrations}

core와 Exposed DAO를 기반으로 하며 실행 시 JDBC가 필요합니다. JDBC 저장소와 같은 테이블 선언을 공유할 수 있습니다.

## 설정 {#configuration}

독립 설정은 없습니다. Exposed의 데이터베이스와 트랜잭션을 구성하고 감사 작업자가 필요하면 진입점에서 `UserContext`를 바인딩하세요.

## 실패 유형과 해결 방법 {#failures}

트랜잭션이 끝난 뒤 지연 속성을 읽으면 실패하거나 예상치 못한 쿼리가 생깁니다. 일반 엔티티 update는 `updatedAt`을 보장하지 않습니다. ID가 안정되기 전 엔티티의 동등성 비교도 주의해야 합니다.

## 운영 {#operations}

트랜잭션 시간을 짧게 유지하고 로그가 지연 속성을 읽어 추가 쿼리를 만들지 않게 합니다. 관계를 순회하는 경로는 쿼리 수를 함께 관찰하세요.

## 테스트 {#testing}

실제 트랜잭션과 데이터베이스 fixture를 사용합니다. DTO 변환이 끝난 뒤 값이 유지되는지, 감사 작업자가 전파되는지, 지원 dialect에서 ID 계열이 저장되는지 검증하세요.

## 학습 경로와 예제 {#workshops}

[매핑 규칙](bluetape4k-exposed-core/mapping-conventions.md)을 읽은 뒤 [JDBC 저장소 경로](bluetape4k-exposed-jdbc.md)로 이어가세요. Exposed DAO는 R2DBC 엔티티 모델이 아닙니다.

## 제약 사항 {#limitations}

이 모듈을 써도 DAO 엔티티가 detached 객체나 reactive 객체로 바뀌지 않습니다. 트랜잭션도 직접 열지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### AuditableEntity UML 클래스 다이어그램

[![AuditableEntity UML 클래스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-01.svg)

_배포본 README: [`exposed/dao/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.ko.md)_

### Generated-ID DAO 지원 범위표

[![Generated-ID DAO 지원 범위표](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-02.svg)

_배포본 README: [`exposed/dao/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.ko.md)_

### Entity Helper Pairing 지도

[![Entity Helper Pairing 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-diagram-03.svg)

_배포본 README: [`exposed/dao/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.ko.md)_

### DAO automatic field assignment 처리 흐름

[![DAO automatic field assignment 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-dao-sequence-01.svg)

_배포본 README: [`exposed/dao/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/dao/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [DAO 빌드](../../../../exposed/dao/build.gradle.kts)
- [엔티티 확장](../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/EntityExtensions.kt)
- [감사 엔티티](../../../../exposed/dao/src/main/kotlin/io/bluetape4k/exposed/dao/auditable/AuditableEntity.kt)
