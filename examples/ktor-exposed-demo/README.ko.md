# examples-ktor-exposed-demo

[English](./README.md) | 한국어

`bluetape4k-exposed-ktor`를 사용하는 Ktor smoke demo입니다. bluetape4k Ktor
core, Exposed 전용 `StatusPages` mapping, 호출자 소유 JDBC/R2DBC resource,
readiness route, JDBC transaction route를 한 애플리케이션에서 어떻게 조합하는지
보여줍니다.

## 개요

이 모듈은 의도적으로 작게 유지합니다. Demo 애플리케이션은 예제 프로세스 안에서
로컬 H2 JDBC/R2DBC resource를 만들고, 그 resource를
`installBluetape4kExposedKtor()`에 넘긴 뒤, Ktor application lifecycle에서 닫습니다.

라이브러리 계약은 명시적입니다.

- `installBluetape4kExposedKtor()`는 database, pool, dispatcher, content
  negotiation, 일반 Ktor core plugin을 만들지 않습니다.
- 애플리케이션이 `Database`, `R2dbcDatabase`, JDBC dispatcher, 종료 lifecycle을
  소유합니다.
- Ktor core와 Exposed error mapping은 하나의 `StatusPages` block에서 조합합니다.
- Exposed health/readiness route는 demo가 `installHealthRoutes = true`로 opt-in했기
  때문에 설치됩니다.

## 프로젝트 구조

```text
src/main/kotlin/io/bluetape4k/examples/exposed/ktor/
├── KtorExposedDemoApplication.kt    # Ktor plugin 조합과 route
└── KtorExposedDemoResources.kt      # demo용 H2 JDBC/R2DBC resource

src/test/kotlin/io/bluetape4k/examples/exposed/ktor/
└── KtorExposedDemoApplicationTest.kt # health, readiness, transaction smoke test
```

## Resource 소유권

`KtorExposedDemoResources.create()`는 demo-local resource를 만듭니다.

- HikariCP 기반 H2 JDBC `Database`
- H2 R2DBC `ConnectionPool`과 `R2dbcDatabase`
- 고정 크기 JDBC blocking dispatcher
- 두 개의 sample row를 가진 `ktor_demo_items` table

애플리케이션은 `ApplicationStopped`를 구독하고 이 resource들을 명시적으로 닫습니다.
운영 애플리케이션도 같은 소유권 규칙을 따라야 하지만, 보통 pool과 dispatcher는 자체
설정 계층에서 생성합니다.

## Ktor 조합

`installKtorExposedDemo(resources)`는 plugin을 다음 순서로 설치합니다.

1. status page와 일반 health route를 끈 상태로 `installBluetape4kKtorCore(...)` 설치
2. 하나의 공유 `StatusPages` block에 `bluetape4kErrorResponses()`와
   `bluetape4kExposedErrors()` 조합
3. 호출자 소유 JDBC/R2DBC resource와 `installHealthRoutes = true`로
   `installBluetape4kExposedKtor(...)` 설치
4. `ApplicationCall.exposedJdbcTransaction()`으로 `DemoItems`를 읽는 demo route 등록

## Routes

| Method | Path | 설명 |
|---|---|---|
| GET | `/healthz/exposed` | Exposed integration health route |
| GET | `/readyz/exposed` | JDBC/R2DBC readiness route |
| GET | `/transactions/jdbc-count` | JDBC transaction으로 demo row 수 조회 |

Smoke 응답 확인:

```bash
curl http://localhost:8080/healthz/exposed
curl http://localhost:8080/readyz/exposed
curl http://localhost:8080/transactions/jdbc-count
```

`/transactions/jdbc-count`는 demo resource가 삽입한 두 sample row 때문에 `2`를
반환합니다.

## 실행

Smoke test 실행:

```bash
./gradlew :examples-ktor-exposed-demo:test
```

애플리케이션 실행:

```bash
./gradlew :examples-ktor-exposed-demo:run
```

Embedded Netty server는 `8080` 포트에서 시작합니다.

## 같이 보기

- [`bluetape4k-exposed-ktor`](../../ktor/exposed/README.ko.md) — 공개 API,
  readiness 의미, rollback 가이드, non-goals
