# bluetape4k-exposed WIP

> 버전: 1.8.0-SNAPSHOT | 브랜치: `develop`
> 최종 업데이트: 2026-05-08 (이슈 반영: #3 #4 #5)

---

## 우선순위 분류

- 🔴 **High** — 릴리스 전 반드시 처리
- 🟡 **Medium** — 다음 마일스톤 대상
- 🟢 **Low** — 장기 개선 과제

---

## 1. spring-boot3 모듈 그룹 제거 🔴

- Issue: [#3](https://github.com/bluetape4k/bluetape4k-exposed/issues/3)
- Spring Boot 3.5 EOL = **2026-06-30**. spring-boot4에 동일 구조 이미 존재.

### 제거 대상 (5개)

| 모듈 | 경로 |
|-----|------|
| `bluetape4k-spring-boot3-exposed-jdbc` | `spring-boot3/exposed-jdbc/` |
| `bluetape4k-spring-boot3-exposed-jdbc-demo` | `spring-boot3/exposed-jdbc-demo/` |
| `bluetape4k-spring-boot3-exposed-r2dbc` | `spring-boot3/exposed-r2dbc/` |
| `bluetape4k-spring-boot3-exposed-r2dbc-demo` | `spring-boot3/exposed-r2dbc-demo/` |
| `bluetape4k-spring-boot3-batch-exposed` | `spring-boot3/batch-exposed/` |

### 작업 목록

- [ ] `spring-boot3/` 디렉토리 5개 모듈 삭제
- [ ] `settings.gradle.kts` — `spring-boot3` includeModules 제거
- [ ] `buildSrc/` — spring-boot3 관련 상수 정리
- [ ] CI (`ci.yml`, `nightly.yml`) — spring-boot3 job/step 제거
- [ ] 전체 빌드 통과 확인
- [ ] `README.md` + `README.ko.md` 업데이트

---

## 2. exposed-bucket4j — 분산 Rate Limiting 🟢

- Issue: [#4](https://github.com/bluetape4k/bluetape4k-exposed/issues/4)
- 원본: bluetape4k-projects [#38](https://github.com/bluetape4k/bluetape4k-projects/issues/38)

### 신규 모듈 (2개)

| 모듈 | 설명 |
|-----|------|
| `exposed/exposed-bucket4j-jdbc` | JDBC 동기 방식 Rate Limiting |
| `exposed/exposed-bucket4j-r2dbc` | R2DBC 비동기/코루틴 Rate Limiting |

### 작업 목록

- [ ] `ExposedJdbcProxyManager` — `AbstractProxyManager<K>` 구현
- [ ] `ExposedR2dbcProxyManager` — `AsyncProxyManager<K>` 구현
- [ ] `BucketStateTable` — Exposed Table DSL 정의
- [ ] `settings.gradle.kts` 등록
- [ ] PostgreSQL / MySQL / H2 통합 테스트 (Testcontainers)
- [ ] KDoc + README.md + README.ko.md (Mermaid UML)

#### 참고 자료
- [Bucket4j GitHub](https://github.com/bucket4j/bucket4j)
- [Bucket4j JDBC 백엔드 구현](https://github.com/bucket4j/bucket4j/tree/main/bucket4j-postgresql)

---

## 3. spring-boot4/spring-modulith-exposed 🟢

- Issue: [#5](https://github.com/bluetape4k/bluetape4k-exposed/issues/5)
- 원본: bluetape4k-projects [#25](https://github.com/bluetape4k/bluetape4k-projects/issues/25)

### 신규 모듈 (1개)

| 모듈 | 경로 |
|-----|------|
| `bluetape4k-spring-boot4-spring-modulith-exposed` | `spring-boot4/spring-modulith-exposed/` |

### 작업 목록

- [ ] `ExposedEventPublicationRepository` — `EventPublicationRepository` 구현
- [ ] `EventPublicationTable` — Exposed Table DSL 정의
- [ ] `ExposedModulithAutoConfiguration` + `@EnableExposedModulith`
- [ ] `@ConditionalOnClass` 가드 적용
- [ ] H2 + PostgreSQL 통합 테스트
- [ ] KDoc + README.md + README.ko.md (Mermaid sequenceDiagram)

#### 참고 자료
- [Spring Modulith 공식 문서](https://docs.spring.io/spring-modulith/reference/)
- [spring-modulith-events-jdbc 소스](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-events/spring-modulith-events-jdbc)

---

## 완료 기준

각 항목은 다음 조건을 모두 만족해야 완료:

- [ ] 코드 변경 완료
- [ ] 단위/통합 테스트 통과 (커버리지 70%+)
- [ ] `ide_diagnostics` 오류 0
- [ ] README.md + README.ko.md 업데이트
- [ ] KDoc 추가/수정 완료
