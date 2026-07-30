# Issue 410 Exposed 심층 시각 해설서 재설계

## 1. 배경과 문제

Issue #410의 첫 구현은 트랜잭션 경계와 Spring Boot 활성화 조건을 간단한 카드와 상태 전환으로 표현했다. 자동 검증과 브라우저 렌더링은 통과했지만, 독자가 다음 질문에 답하기에는 설명의 깊이가 부족했다.

- JPA/Hibernate에 익숙한 개발자가 Exposed를 선택하면 사고방식이 어떻게 달라지는가?
- Exposed가 주는 명시성과 예측 가능성은 무엇이며, 그 대가로 무엇을 직접 책임져야 하는가?
- JDBC와 R2DBC에서 트랜잭션 소유권은 실제 호출 흐름에서 어떻게 드러나는가?
- Spring Boot 자동 구성은 무엇을 만들어 주고, 무엇을 애플리케이션에 남겨 두는가?
- 구성이나 경계가 잘못되었을 때 어떤 증상이 나타나며, 어떤 소스와 테스트로 확인할 수 있는가?

`bluetape4k.github.io`에 이미 게시된 여섯 개 시각화 자료는 문제 정의, 아키텍처, 실행 흐름, 실패 시나리오, 코드 연결, 검증 방법을 한 문서 안에서 연결한다. 반면 첫 구현은 트랜잭션 자료가 3개 구역과 4개 상태 버튼, 활성화 자료가 2개 구역과 조건 입력 위주여서 기존 자료의 해설 밀도와 학습 경로에 미치지 못했다.

이번 재설계는 기존 구현에 설명 카드를 추가하는 수준이 아니다. JPA/Hibernate와 Exposed의 서로 다른 사고방식에서 이야기를 시작하고, 두 실행 구조를 나란히 보여 주는 비교 Architecture Diagram과 기존 Exposed Architecture Diagram을 해설의 중심축으로 사용하며, 실제 소스와 테스트까지 추적할 수 있는 심층 시각 해설서로 다시 구성한다.

## 2. 목표

### 2.1 핵심 목표

1. JPA/Hibernate 및 Spring Data JPA 경험자가 Exposed의 장점과 비용을 균형 있게 이해한다.
2. 기존 매뉴얼의 Architecture Diagram을 시각적 기준점이자 구조적 사실의 원본으로 사용한다.
3. 트랜잭션 소유권과 Spring Boot 활성화를 아키텍처, 시퀀스, 상태 행렬, 코드, 실패 사례로 교차 설명한다.
4. 영어와 한국어 문서가 같은 구조와 의미를 유지한다.
5. 소스 저장소에서 독립 실행 가능한 HTML과 정적 대체 이미지를 결정적으로 생성하고 검증한다.
6. 게시 전에 사용자가 로컬 HTML을 직접 조작해 상호작용과 설명 품질을 확인한다.

### 2.2 비목표

- JPA 전체 명세나 모든 JPA 구현체를 설명하지 않는다.
- Hibernate와 Exposed의 성능을 일반화한 벤치마크 결과를 주장하지 않는다.
- Spring Boot의 전체 조건 평가 엔진을 브라우저에서 재현하지 않는다.
- 기존 Exposed Architecture Diagram의 구조적 주장을 비교 그림으로 덮어쓰거나 단순화하지 않는다.
- Exposed DSL, DAO, JDBC, R2DBC의 모든 API를 한 자료에 나열하지 않는다.
- 시각화 작업과 별개인 기존 매뉴얼의 구조적 부채는 Issue #411의 범위로 유지한다.

## 3. 대상 독자와 서술 관점

주요 독자는 다음 경험을 가진 Kotlin/Spring 개발자다.

- JPA/Hibernate와 Spring Data JPA의 저장소 및 서비스 계층에 익숙하다.
- 영속성 컨텍스트, 변경 감지, 지연 로딩, 선언적 트랜잭션을 사용해 보았다.
- Exposed를 단순히 “Kotlin용 ORM” 또는 “SQL DSL” 정도로만 알고 있다.
- JDBC와 R2DBC 중 어느 경로를 선택해야 하는지, Spring Boot가 무엇을 자동 구성하는지 알고 싶다.

문서에서 “JPA”라고 축약할 때는 별도 표시가 없는 한 **Hibernate와 Spring Data JPA를 함께 사용하는 전형적인 Spring 애플리케이션 경험**을 뜻한다. JPA 명세 자체의 보편적 특성으로 과도하게 일반화하지 않는다.

## 4. 비교 원칙

Exposed에는 SQL에 가까운 타입 안전 DSL과 객체 지향 DAO가 모두 있다. 따라서 “Exposed는 ORM이 아니다”와 같은 단정은 사용하지 않는다. 또한 Exposed DAO는 현재 JDBC 경로에서만 지원되므로 R2DBC 설명에서는 DSL 및 저장소 구현을 기준으로 삼는다.

| 비교 축 | 전형적인 JPA/Hibernate + Spring Data JPA | Exposed |
|---|---|---|
| 중심 추상화 | 영속성 컨텍스트가 관리하는 엔티티와 객체 그래프 | 타입 안전 SQL DSL, 선택적인 JDBC DAO, 명시적 저장소 |
| 상태 변경 | 관리 엔티티의 변경을 감지하고 flush 시점에 동기화 | `insert`, `update`, `delete` 등 쓰기 의도를 코드에 명시 |
| 조회와 연관 | 프록시, 지연 로딩, fetch join, EntityGraph 등으로 객체 그래프 로딩을 조절 | 필요한 테이블, join, projection, mapping을 쿼리에서 명시 |
| 트랜잭션 문맥 | Spring 트랜잭션과 영속성 컨텍스트가 결합되는 경우가 일반적 | 모든 DB 작업이 `transaction {}` 또는 `suspendTransaction {}` 문맥 안에서 실행 |
| 경계 소유권 | 서비스/호출자가 업무 트랜잭션을 소유하는 것이 일반적인 모범 사례 | 호출자가 단일·복수 저장소를 묶는 경계를 명시하며 저장소는 기존 문맥에 참여 |
| JDBC/R2DBC | 전통적 JPA는 블로킹 JDBC 중심 | JDBC와 R2DBC 저장소 경로를 제공하며 R2DBC는 코루틴 문맥을 사용 |
| 스키마 도구 | 구현체별 DDL 생성과 검증 기능이 있으나 운영 마이그레이션은 별도 도구를 흔히 사용 | `SchemaUtils`를 제공하지만 운영 스키마 버전 관리 책임은 애플리케이션 선택 |
| 대표 장점 | 객체 그래프 중심 생산성, 변경 감지, 풍부한 생태계와 Spring 통합 | SQL 의도가 선명하고 I/O가 예측 가능하며 Kotlin DSL과 JDBC/R2DBC 대칭성이 높음 |
| 대표 비용 | 숨은 쿼리, N+1, flush 시점, 프록시 수명주기를 이해해야 함 | 쿼리·매핑·쓰기·경계를 더 명시적으로 작성하고 애플리케이션이 더 많은 결정을 소유 |

비교 자료는 어느 한쪽을 우월하다고 결론 내리지 않는다. 객체 그래프 중심 도메인 모델과 자동 변경 감지가 주는 생산성이 중요한 경우 JPA가 적합할 수 있고, SQL 및 트랜잭션 흐름을 코드에서 직접 통제하려는 경우 Exposed가 적합할 수 있음을 실제 선택 기준으로 제시한다.

## 5. 선택한 접근

### 5.1 소스 저장소가 소유하는 생성형 독립 HTML

시각화 원본과 생성 파이프라인은 `bluetape4k-exposed`가 소유한다.

```text
docs/visual-companions/
├── data/
│   ├── transaction-ownership.json
│   └── spring-boot-activation.json
├── assets/
│   ├── transaction-ownership.{en,ko}.{light,dark}.png
│   └── spring-boot-activation.{en,ko}.{light,dark}.png
├── en/
│   ├── transaction-ownership.html
│   └── spring-boot-activation.html
├── ko/
│   ├── transaction-ownership.html
│   └── spring-boot-activation.html
└── manifest.json

scripts/visual-companions/
├── build.mjs
├── validate.mjs
└── capture.mjs
```

- `data/*.json`은 시나리오, 상태, 단계, 소스 근거, 로케일 문자열을 보관하는 단일 구조화 원본이다.
- `build.mjs`는 영어/한국어 독립 HTML을 생성한다.
- `capture.mjs`는 영어/한국어 × 밝은/어두운 테마의 정적 대체 PNG를 생성한다.
- `build.mjs --check`는 생성 결과가 저장소의 산출물과 정확히 일치하는지 검증한다.
- HTML은 외부 CDN, 글꼴, 스크립트, 네트워크 요청 없이 단독 실행된다.

### 5.2 비교 Architecture Diagram과 기존 상세 Diagram을 함께 사용

트랜잭션 해설서의 첫 Architecture Diagram은 JPA/Hibernate와 Exposed의 책임 구조를 같은 추상화 수준에서 비교한다. JPA/Hibernate는 전형적인 Spring Data JPA + Hibernate 애플리케이션을 기준으로 하고, Exposed는 JDBC와 R2DBC 실행 경로를 내부 하위 레인으로 나눈다. JPA/Hibernate를 JDBC, R2DBC와 같은 드라이버 레인으로 배치하지 않는다.

비교 Diagram의 JPA/Hibernate 쪽은 `bluetape4k-hibernate`의 실제 역할을 다음과 같이 제한한다.

- `JpaRepository`와 직접 `EntityManager` 사용은 서로 대체 가능한 접근 경로로 표시한다.
- `bluetape4k-hibernate`는 `EntityManager`, `Session`, Criteria, Querydsl에 Kotlin 편의 기능을 더하는 확장 계층으로 표시한다.
- `bluetape4k-hibernate`가 Spring 트랜잭션 관리자나 저장소 추상화를 소유한다고 표현하지 않는다.
- 관리 엔티티, 영속성 컨텍스트, 변경 감지, flush와 JDBC 경로를 JPA/Hibernate의 핵심 상태·I/O 모델로 표시한다.

Exposed 쪽은 호출자가 트랜잭션 경계를 소유한다는 공통 원칙 아래에서 JDBC와 R2DBC를 구분한다.

- JDBC: `transaction {}` → `JdbcTransaction` → `JdbcRepository` / DSL → JDBC
- R2DBC: `suspendTransaction {}` → `R2dbcTransaction` → `R2dbcRepository` / DSL → R2DBC

비교 Diagram 다음에는 기존 트랜잭션 소유권 Diagram을 그대로 배치해 Exposed 내부 구조를 확대 설명한다. 다음 매뉴얼 자산을 구조적 사실의 원본으로 사용한다.

| 해설서 | 기준 Architecture Diagram |
|---|---|
| 트랜잭션 소유권 | `docs/manual/assets/persistence/jpa-exposed-comparison.en.svg` |
| 트랜잭션 소유권 | `docs/manual/assets/persistence/jpa-exposed-comparison.ko.svg` |
| 트랜잭션 소유권 | `docs/manual/assets/persistence/transaction-ownership.svg` |
| Spring Boot 활성화 | `docs/manual/assets/spring/jdbc-auto-configuration.svg` |
| Spring Boot 활성화 | `docs/manual/assets/spring/r2dbc-auto-configuration.svg` |

비교 Diagram은 독자용 문구가 있으므로 영어와 한국어 SVG/PNG를 각각 유지한다. 생성기는 현재 locale의 SVG를 결정적으로 HTML에 내장하고, 모델에 locale별 SHA-256을 기록한다. 검증기는 현재 SVG 해시와 기록된 해시가 다르면 실패시켜 Architecture Diagram 변경이 시각 해설서에 조용히 누락되지 않도록 한다.

내장된 Diagram은 다음 기능을 제공한다.

- 원본 비율을 유지한 전체 너비 표시
- 확대/축소 및 전체 화면에 가까운 라이트박스
- 키보드로 열고 닫을 수 있는 접근성
- Diagram 옆의 구조 설명과 소스 근거 연결
- 현재 선택한 시나리오와 관련된 책임 영역을 별도 DOM 레이어와 범례로 강조

강조 레이어는 원본 SVG의 구조적 의미를 수정하지 않는다. 상호작용용 시퀀스와 상태 모델은 구조화 데이터에서 별도로 생성하며, Architecture Diagram과 다른 주장을 만들지 않는다.

## 6. 해설서 1: JPA에서 Exposed로 — 트랜잭션 소유권

### 6.1 독자가 얻어야 할 결론

- Exposed의 핵심 차이는 “트랜잭션이 없다”가 아니라 **트랜잭션 문맥과 SQL 실행을 코드에서 더 명시적으로 보이게 한다**는 점이다.
- 저장소 하나의 편의 함수가 아니라 업무 유스케이스를 호출하는 계층이 복수 저장소의 원자성을 소유해야 한다.
- JDBC의 `transaction {}`과 R2DBC의 `suspendTransaction {}`은 같은 소유권 원칙을 서로 다른 실행 모델로 표현한다.
- R2DBC `Flow`는 생성 시점이 아니라 수집 시점의 문맥이 중요하며, 트랜잭션 밖으로 지연 실행을 탈출시키면 경계가 깨질 수 있다.

### 6.2 문서 구성

#### 1) JPA와 Exposed의 정신 모형

JPA의 관리 엔티티, 변경 감지, flush와 Exposed의 명시적 쿼리·쓰기·문맥을 작은 비교 흐름으로 소개한다. 단순 장단점 표에 그치지 않고 동일한 “주문 상태 변경” 유스케이스를 두 방식의 코드와 I/O 타임라인으로 나란히 보여 준다.

#### 2) JPA/Hibernate와 Exposed 비교 Architecture Diagram

`jpa-exposed-comparison.{en,ko}.svg`를 전체 너비로 먼저 배치한다. 왼쪽은 `@Transactional`, `JpaRepository` 또는 직접 `EntityManager` 사용, Hibernate 영속성 컨텍스트, JDBC를 보여 준다. 오른쪽은 호출자가 소유하는 명시적 경계 아래에서 Exposed JDBC와 R2DBC를 하위 레인으로 나눈다.

그다음 `transaction-ownership.svg`를 전체 너비로 배치한다. JDBC와 R2DBC 두 레인을 기준으로 호출자, 트랜잭션 문맥, 저장소, 드라이버, 데이터베이스가 어떤 책임을 소유하는지 단계별로 해설한다.

#### 3) 상호작용형 시나리오 탐색기

다음 시나리오를 탭 또는 키보드 선택으로 전환한다.

1. JDBC 단일 저장소 성공
2. JDBC 복수 저장소를 호출자 경계로 묶은 성공
3. R2DBC 단일 저장소 성공
4. R2DBC `Flow`를 경계 안에서 수집한 성공
5. `Flow` 또는 DAO 접근이 경계 밖으로 탈출한 실패
6. 예외 또는 코루틴 취소로 인한 롤백

각 시나리오는 동일한 구조화 원본에서 다음을 함께 갱신한다.

- 현재 참여자와 활성화 구간
- 번호가 붙은 메시지
- 실행되거나 실행되지 않은 SQL
- commit/rollback 결과
- 경계를 소유하는 호출자
- 관련 실제 소스 및 테스트

#### 4) 정식 시퀀스 다이어그램

카드 배열 대신 참여자, 생명선, 활성화 막대, 번호 메시지, `alt`/실패 프레임이 있는 시퀀스 다이어그램을 사용한다.

```text
Use case caller -> transaction/suspendTransaction
transaction context -> repository A
repository A -> driver -> database
transaction context -> repository B
alt success: commit
alt failure/cancellation: rollback
```

JDBC와 R2DBC의 공통 원칙은 같은 색과 위치로 유지하고, 블로킹 호출과 suspend/Flow 경로의 차이는 실행 표기와 설명으로 구분한다.

#### 5) 소유권 행렬

| 책임 | 호출자/서비스 | 트랜잭션 문맥 | 저장소 | 애플리케이션 인프라 |
|---|---|---|---|---|
| 업무 원자성 범위 결정 | 소유 | 참여 | 참여 | 해당 없음 |
| JDBC/R2DBC 문맥 시작 | 요청 | 소유 | 기존 문맥 사용 | 연결 제공 |
| 쿼리와 매핑 | 해당 없음 | 문맥 제공 | 소유 | 해당 없음 |
| commit/rollback | 결과 유발 | 소유 | 직접 소유하지 않음 | 드라이버/연결 지원 |
| Flow 수집 위치 | 소유 | 유효 범위 제공 | Flow 제공 가능 | 디스패처·연결 수명주기 소유 |

#### 6) 코드 연결

JPA 스타일의 서비스 예제와 Exposed 저장소 호출 예제를 나란히 보여 주되, Exposed 쪽은 저장소의 실제 구현과 테스트로 연결한다. 예제는 BOM 사용 원칙을 지키며 불필요한 개별 라이브러리 버전을 넣지 않는다.

#### 7) 장점, 비용, 선택 기준

다음 질문에 답하는 결정 가이드를 제공한다.

- 변경 감지와 객체 그래프 탐색이 중요한가, SQL 흐름의 명시성이 중요한가?
- 한 요청에서 여러 저장소를 묶는 원자성이 필요한가?
- JDBC 블로킹 모델과 R2DBC 코루틴 모델 중 어느 실행 방식을 운영할 수 있는가?
- 지연 실행 객체가 트랜잭션 경계 밖으로 나갈 가능성을 통제할 수 있는가?
- 쿼리 및 매핑의 명시적 코드량을 수용할 수 있는가?

#### 8) 근거와 검증

문서 마지막에 사용된 소스, 테스트, 실행 명령, 예상 결과를 표로 제공한다. 독자가 애니메이션을 믿는 데 그치지 않고 저장소에서 직접 확인할 수 있어야 한다.

## 7. 해설서 2: JPA 자동 구성 기대에서 Exposed 명시적 활성화로

### 7.1 독자가 얻어야 할 결론

- Spring Data JPA 경험에서 생긴 “의존성만 넣으면 저장소와 트랜잭션이 모두 준비된다”는 기대를 Exposed에 그대로 적용하면 안 된다.
- Exposed 자동 구성은 조건에 맞을 때 필요한 어댑터를 제공하고, 애플리케이션이 이미 제공한 빈에는 물러난다.
- `DataSource`, `R2dbcDatabase`, 풀, 디스패처, 종료 수명주기 등 애플리케이션 인프라의 소유권은 명확히 구분해야 한다.
- 명시적 활성화는 초기 설정 비용을 늘리지만, 불필요한 구성과 숨은 소유권을 줄이고 JDBC/R2DBC 경로를 의도적으로 선택하게 한다.

### 7.2 문서 구성

#### 1) JPA 자동 구성 기대와 Exposed의 차이

Spring Boot + Spring Data JPA에서 흔히 기대하는 엔티티 탐색, 저장소 프록시, `EntityManager`, 트랜잭션 관리자의 흐름과 Exposed의 enable annotation, 조건부 자동 구성, registrar/factory 흐름을 비교한다.

#### 2) 기존 JDBC/R2DBC Architecture Diagram

`jdbc-auto-configuration.svg`와 `r2dbc-auto-configuration.svg`를 나란히 배치한다. 작은 화면에서는 탭으로 전환하고, 큰 화면에서는 비교 모드와 단일 확대 모드를 모두 제공한다.

각 Diagram은 다음 질문과 연결한다.

- 어떤 입력 빈을 애플리케이션이 제공해야 하는가?
- 어떤 조건이 충족될 때 자동 구성이 활성화되는가?
- enable annotation과 registrar는 무엇을 등록하는가?
- 기존 사용자 빈이 있을 때 어느 지점에서 back-off 하는가?
- 저장소가 실제 데이터베이스 접근으로 이어지는 경로는 무엇인가?

#### 3) 조건 프리셋 탐색기

개별 체크박스만 제공하지 않고 실제 구성 의도를 나타내는 프리셋을 제공한다.

1. JDBC 전용 정상 구성
2. R2DBC 전용 정상 구성
3. JDBC와 R2DBC를 함께 사용하는 이중 스택
4. 사용자 정의 JDBC `springTransactionManager` 존재
5. 사용자 정의 mapping context 존재
6. `DataSource` 누락 또는 애플리케이션 소유 R2DBC 인프라 누락
7. `EntityClass` 부재로 DAO 관련 구성이 불필요한 경우

고급 모드에서만 원시 조건을 직접 변경할 수 있다. 결과 패널은 생성, 재사용, back-off, 미생성 빈을 구분하며 각 결과의 근거 조건을 설명한다.

#### 4) 활성화 시퀀스

다음 참여자를 가진 시퀀스를 표시한다.

```text
Spring Boot
Auto-configuration import
Condition evaluation
Enable annotation / registrar
Repository factory
Application-owned infrastructure
```

조건 실패와 back-off는 `alt` 프레임으로 표현한다. 이는 Spring의 전체 조건 평가기를 흉내 내는 시뮬레이터가 아니라, 이 저장소가 보장하는 결정만 설명하는 교육 모델이다.

#### 5) 빈과 소유권 행렬

JDBC와 R2DBC는 트랜잭션 관리자 계약이 다르므로 하나의 “기본 관리자” 행으로 합치지 않는다.

| 자원/빈 | 경로 | 소유 및 생성 계약 |
|---|---|---|
| `DataSource` | JDBC | 애플리케이션이 제공하고 풀·자격 증명·종료를 소유한다. |
| `springTransactionManager` | JDBC | 같은 이름의 빈이 없고 `DataSource`가 있을 때 자동 구성이 `SpringTransactionManager`를 제공한다. 사용자 빈이 있으면 back-off 한다. |
| `R2dbcDatabase`, connection pool, database dispatcher | R2DBC | 애플리케이션이 생성하고 수명주기를 소유한다. |
| Spring `ReactiveTransactionManager` bridge | R2DBC | 이 모듈은 Exposed 트랜잭션을 Spring reactive transaction manager에 연결하거나 새 관리자를 생성하지 않는다. |
| `ExposedMappingContext` | JDBC/R2DBC | JDBC 자동 구성에서 생성하며, R2DBC 자동 구성은 기존 빈이 없을 때만 제공한다. |
| Exposed 저장소 proxy/factory | JDBC/R2DBC | enable annotation과 registrar가 선언된 저장소를 검색하고 factory bean을 등록한다. |
| 스키마와 마이그레이션 | JDBC/R2DBC | 애플리케이션이 운영 정책과 실행 시점을 소유한다. |

정확한 빈 이름과 조건은 구현 소스에서 추출한 근거와 함께 표시한다. 특히 R2DBC 저장소 등록만으로 Spring `@Transactional` 의미가 적용된다고 표현하지 않는다. R2DBC factory는 suspend 메서드에서 Spring Data transaction interceptor를 우회하고 Exposed 구현에 위임하므로, Exposed 트랜잭션 경계는 `suspendTransaction` 기반 호출 흐름으로 따로 설명한다.

#### 6) 실제 구성 레시피

- JDBC 전용 최소 구성
- R2DBC 전용 최소 구성
- JDBC/R2DBC 이중 스택에서 명시적 이름과 경계를 사용하는 구성
- 사용자 정의 JDBC `springTransactionManager`를 제공해 자동 구성을 back-off 시키는 구성

각 레시피는 “무엇을 제공해야 하는가”, “무엇이 자동 생성되는가”, “어떻게 검증하는가”를 함께 제시한다.

#### 7) 실패 모드와 진단

- enable annotation 누락
- 필수 인프라 빈 누락
- 조건은 충족하지만 사용자 빈으로 인해 back-off
- JDBC와 R2DBC 빈의 역할 혼동
- R2DBC 저장소 등록만으로 Spring reactive `@Transactional`이 적용된다고 오해
- 저장소 스캔 범위 오류
- 애플리케이션이 소유해야 할 리소스 종료 누락

실패마다 관찰 가능한 증상, 확인할 condition report 또는 로그, 관련 소스와 테스트를 연결한다.

#### 8) 장점, 비용, 선택 기준

명시적 활성화의 장점은 소유권과 선택이 드러난다는 점이고, 비용은 초기 구성과 조건 이해가 필요하다는 점이다. 독자가 JDBC 전용, R2DBC 전용, 이중 스택 중 적합한 경로를 고를 수 있도록 결정표를 제공한다.

#### 9) 근거와 검증

자동 구성 클래스, annotation, registrar, repository factory, 관련 테스트와 실행 명령을 표로 제공한다.

## 8. 상호작용과 정보 구조

두 문서는 같은 상호작용 문법을 사용한다.

1. 상단에서 문서가 답할 질문과 JPA 대비 핵심 차이를 제시한다.
2. 한 화면의 주인공이 되는 Architecture Diagram을 먼저 보여 준다.
3. 시나리오를 선택하면 시퀀스, 상태, 책임, 코드 근거가 함께 바뀐다.
4. Architecture Diagram의 책임 영역을 선택하면 관련 설명과 소스 근거로 이동한다.
5. 실패 시나리오는 성공 흐름과 동일한 참여자를 유지해 어디서 갈라지는지 보여 준다.
6. 마지막에 장단점, 선택 기준, 검증 명령을 제공한다.

필수 접근성:

- 모든 시나리오와 Diagram 확대 기능을 키보드로 조작할 수 있다.
- 현재 선택 상태와 결과 변경을 적절한 ARIA 속성으로 전달한다.
- 색상만으로 성공, back-off, 실패를 구분하지 않는다.
- `prefers-reduced-motion`에서 애니메이션을 제거하거나 즉시 완료한다.
- 작은 화면에서 표와 시퀀스가 의미를 잃지 않도록 축약 보기와 가로 스크롤을 제공한다.

## 9. 설명 깊이의 완료 기준

줄 수나 카드 수만으로 품질을 판정하지 않는다. 각 해설서는 최소한 다음 질문에 답해야 한다.

1. 무엇이 다른가?
2. 왜 이런 설계를 선택했는가?
3. 정상 흐름은 어떻게 동작하는가?
4. 실패하거나 조건이 맞지 않으면 어디서 갈라지는가?
5. 누가 어떤 책임을 소유하는가?
6. 실제 코드와 테스트에서 어디를 확인할 수 있는가?
7. 어떤 상황에서 이 접근을 선택하거나 피해야 하는가?

각 문서는 다음 표현을 모두 포함한다.

- 기존 Architecture Diagram
- 정식 시퀀스 다이어그램
- 책임 또는 조건 행렬
- 실제 코드 예제
- 성공 및 실패 시나리오
- 장점과 비용
- 선택 가이드
- 소스 및 테스트 근거
- 로컬 실행과 검증 방법

## 10. 시각 체계

- 기존 매뉴얼 Diagram의 승인된 어두운 시각 계열을 기본으로 사용한다.
- 새로운 장식적 스타일을 만들지 않고, 기존 Diagram의 색, 간격, 경계, 강조 규칙에서 파생한다.
- 밝은 테마에서도 정보 계층과 대비가 유지되도록 토큰을 별도로 정의한다.
- Architecture Diagram은 원본 SVG를 사용하며, 정적 PNG는 매뉴얼의 기존 쌍을 기준으로 검증한다.
- HTML의 전체 화면 대체 이미지는 영어/한국어 × 밝은/어두운 테마로 생성한다.
- 애니메이션은 흐름과 상태 전이를 설명할 때만 사용한다.

## 11. 소스 근거 원장

최종 문서는 실제 파일을 검토한 뒤 정확한 심볼과 줄 단위 링크를 생성 데이터에 기록한다. 최소 범위는 다음과 같다.

### 11.1 트랜잭션 소유권

- JDBC 저장소 인터페이스와 추상 구현
- R2DBC 저장소 인터페이스와 추상 구현
- `transaction {}` 및 `suspendTransaction {}` 경계 예제
- 복수 저장소를 묶는 서비스/테스트 사례
- R2DBC `Flow` 수집과 트랜잭션 문맥을 검증하는 테스트
- 예외 및 취소 시 rollback을 검증하는 테스트
- `docs/manual/assets/persistence/transaction-ownership.svg`

### 11.2 Spring Boot 활성화

- JDBC enable annotation
- R2DBC enable annotation
- JDBC/R2DBC auto-configuration
- registrar와 repository factory
- 조건부 빈 생성 및 back-off 테스트
- R2DBC suspend 메서드의 Spring Data transaction interceptor 우회 계약
- `docs/manual/assets/spring/jdbc-auto-configuration.svg`
- `docs/manual/assets/spring/r2dbc-auto-configuration.svg`

원장 항목에는 다음 필드를 둔다.

```text
id
claim
sourcePath
symbol
testPath
verificationCommand
expectedEvidence
```

HTML은 원장의 `claim`과 경로를 그대로 표시한다. 경로 존재 여부와 중복 ID는 검증기가 검사한다.

## 12. 생성 및 검증

### 12.1 정적 검증

- `node scripts/visual-companions/build.mjs --check`
- `node scripts/visual-companions/validate.mjs`
- manifest 스키마와 로케일 쌍 검사
- 링크, anchor, 소스 경로, 테스트 경로 존재 검사
- 영어/한국어 시나리오 ID와 의미 구조 동등성 검사
- Architecture Diagram SHA-256 일치 검사
- 외부 네트워크 의존성 부재 검사
- `git diff --check`

### 12.2 브라우저 검증

고정된 Chromium 환경과 viewport에서 다음 조합을 검증한다.

```text
transaction ownership × en/ko × light/dark
Spring Boot activation × en/ko × light/dark
```

각 조합에서 다음을 수행한다.

- 모든 시나리오 전환
- Architecture Diagram 확대/닫기
- 키보드 탐색
- 작은 화면 반응형 검사
- 콘솔 오류와 실패한 네트워크 요청 검사
- `prefers-reduced-motion` 검사
- 전체 페이지 PNG 생성
- 같은 입력으로 두 번 생성한 PNG의 해시 일치 검사

### 12.3 시각 검토

- 기존 SVG를 CairoSVG로 PNG 렌더링하고 커넥터와 잘림을 감사한다.
- 원본 크기의 PNG를 직접 열어 텍스트, 선, 대비, 화살표를 확인한다.
- HTML 전체 페이지 캡처를 기존 여섯 개 시각화 자료와 나란히 비교한다.
- 각 해설서 구현 후 다음 해설서로 넘어가기 전에 사용자에게 로컬 HTML을 실제로 열어 보여 준다.
- 사용자가 시나리오, 테마, Diagram 확대, 키보드 조작을 직접 확인한 뒤에만 해당 해설서의 시각 승인으로 간주한다.

자동 스크린샷이나 에이전트의 브라우저 검사만으로 사용자 시각 승인을 대체하지 않는다.

## 13. 매뉴얼과 게시 구조

- `docs/manual/`은 사용자 동작을 설명하는 원본이며 영어/한국어 랜딩의 링크 동등성을 유지한다.
- 매뉴얼의 정적 SVG/PNG는 빠른 개요와 인쇄 가능한 기준 그림으로 남긴다.
- 관련 장에서는 같은 개념의 심층 HTML 해설서로 연결한다.
- source PR이 병합된 뒤 `bluetape4k.github.io` Issue #304에서 정확한 release ref와 commit으로 결과물을 가져온다.
- 사이트는 source artifact를 임의로 수정하지 않고 게시 경로와 탐색 구조만 소유한다.

## 14. 전달 순서와 승인 경계

1. 이 재설계 문서를 자체 검토하고 커밋한다.
2. 사용자가 서면 설계를 검토하고 승인한다.
3. 승인된 설계를 기반으로 별도 구현 계획과 테스트 명세를 작성한다.
4. 트랜잭션 소유권 해설서를 먼저 구현하고 자동 검증을 완료한다.
5. 로컬 HTML을 사용자에게 열어 상호작용 검토를 받는다.
6. 피드백을 반영하고 트랜잭션 해설서의 시각 승인을 받는다.
7. Spring Boot 활성화 해설서를 구현하고 같은 검증 및 사용자 검토를 반복한다.
8. PR #412를 갱신하고 exact-head CI, 리뷰, thread, DoD 상태를 확인한다.
9. 별도의 명시적 merge 승인을 받은 뒤 source PR을 병합한다.
10. immutable ref를 사용해 `bluetape4k.github.io` Issue #304를 수행한다.

현재 PR의 HTML이 자동 검증을 통과했다는 사실은 이 설계의 사용자 시각 승인이나 merge 준비 상태를 뜻하지 않는다.

## 15. 알려진 위험과 대응

| 위험 | 대응 |
|---|---|
| JPA와 Exposed 비교가 과도하게 단순화됨 | 비교 범위를 전형적인 Hibernate/Spring Data JPA 사용으로 명시하고 절대적 우열 주장을 금지 |
| Architecture Diagram과 HTML 설명이 드리프트함 | 원본 SVG 내장, SHA-256 guard, 구조적 주장의 단일 원본 유지 |
| 상호작용이 장식으로 변함 | 시나리오 선택이 시퀀스, 책임, 코드, 근거를 함께 변경할 때만 유지 |
| Spring 조건 탐색기가 실제 엔진처럼 오해됨 | 저장소가 보장하는 교육 모델임을 명시하고 소스 근거를 모든 결과에 연결 |
| 한국어와 영어의 의미가 달라짐 | 동일한 구조화 데이터와 시나리오 ID 사용, 로케일 의미 동등성 검사 |
| 캡처가 환경마다 달라짐 | Chromium/viewport/font 조건 고정, 반복 해시 검증 |
| 설명 범위가 끝없이 커짐 | 두 핵심 개념과 정의된 성공/실패 시나리오로 제한, 나머지는 후속 이슈로 분리 |

## 16. 완료 정의

- 두 해설서가 JPA 대비 Exposed의 특징, 장점, 비용, 선택 기준에서 출발한다.
- 기존 세 개 Architecture Diagram이 해설의 중심축으로 실제 내장된다.
- 각 해설서가 아키텍처, 시퀀스, 행렬, 코드, 실패 흐름, 근거, 검증을 포함한다.
- 영어/한국어 독립 HTML과 네 가지 정적 대체 이미지가 결정적으로 생성된다.
- 기존 Diagram 및 실제 소스와 설명 사이의 드리프트를 자동 검출한다.
- 접근성, 반응형, 브라우저 콘솔, 네트워크 독립성 검사가 통과한다.
- 사용자가 로컬 HTML을 직접 조작해 두 해설서를 각각 승인한다.
- PR #412는 exact-head 검증과 별도 merge 승인 전까지 병합하지 않는다.
- 사이트 게시 Issue #304는 source merge 후 immutable ref로 진행한다.
