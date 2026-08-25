# Issue #729 Spring Data common 계획 Step 3-R 검토

## 검토 범위와 근거

- 대상 계획: `docs/superpowers/plans/2026-08-25-spring-data-common-plan.md`
- 기준 명세: `docs/superpowers/specs/2026-08-25-spring-data-common-design.md`
- 검토 기준: `$bluetape-full-feature`의 `review-perspectives.md`, `step-3r-plan-review.md`, 저장소 `AGENTS.md`, `$bluetape-kotlin-patterns`
- 저장소 근거: `settings.gradle.kts`, `spring-boot/jdbc/build.gradle.kts`, `spring-boot/r2dbc/build.gradle.kts`, `exposed/bom/build.gradle.kts`, `build.gradle.kts`, `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`, JDBC/R2DBC production import 및 test assertion 사용 현황
- 실행 형태: 1인 개발자 `main-session`에서 성능·안정성·보안·운영·개발자/API·사용자 관점을 순차적으로 독립 검토
- 자체 검증: 계획 파일의 `git diff --check` 통과, 미완성 작업 토큰 미검출

## 여섯 관점 순차 검토

| 관점 | 확인한 계획 범위와 저장소 근거 | 결과 |
|---|---|---|
| 성능 | Task 3의 query/sort hot path, SQL round trip·blocking·reflection 금지, 반복 metadata lookup, Spring Data 전용 benchmark 부재 시 N/A 기록 | P0/P1 없음. benchmark 의존성을 새로 추가하지 않고 statement-count/반복 lookup 근거를 남기는 조건을 계획에 반영했다. |
| 안정성 | Task 2/5/6의 concurrent mapping cache, bean lifecycle, rollback, coroutine cancellation, resource cleanup, 순차 Testcontainers | P0/P1 없음. race·duplicate bean·취소 후 cleanup·backend capability failure를 명시했다. |
| 보안 | Task 5/6의 raw `@Query` parameter binding, identifier allow-list, unknown sort/property negative test, 새 reflection/classpath scan·민감 정보 logging 금지 | P0/P1 없음. 입력 경계와 안전한 실패 경로를 계획 task로 고정했다. |
| 운영 | Task 1/7/8/9/10의 publication/BOM/ABI/manual inventory, CI/Nightly path와 Kover, rollback evidence, Lore/PR gate | P0/P1 없음. BOM은 현재 `exposed/bom/build.gradle.kts`의 publishable-subproject 자동 constraint를 먼저 검증하고, common test/coverage를 CI/Nightly Spring Boot job에 포함하도록 명시했다. |
| 개발자/API | Task 4/5/7의 canonical package, JDBC legacy facade, erased descriptor, `typealias` 금지, R2DBC JDBC import 제거, ABI baseline | P0/P1 없음. facade ownership과 R2DBC import migration을 명세와 동일하게 유지하며, downstream Spring Modulith compile/test도 추가했다. |
| 사용자/호출자 | Task 1/4/5/8의 README·manual EN/KO parity, before/after import, unsupported backend, examples, CHANGELOG/release note | P0/P1 없음. R2DBC의 old JDBC import migration과 JDBC compatibility 경계를 caller 문서·release 기록으로 고정했다. |

## 통합 결과와 잔여 우선순위

| 우선순위 | 관점 | 근거 | 계획 반영 및 재검토 |
|---|---|---|---|
| P2 | 성능 | 이 저장소에는 Spring Data 전용 benchmark task가 없고, 변경은 기존 query/sort hot path의 module 이동이다. | Task 3의 benchmark 존재 확인, SQL statement-count regression, 반복 metadata lookup test, N/A 사유 기록. implementation review에서 fresh 결과 재확인. |
| P2 | 운영 | BOM constraint는 subproject 자동 생성이고, CI Spring Boot job은 현재 JDBC/R2DBC만 실행한다. | Task 8에서 generated BOM/POM/module metadata를 검증하고 `.github/workflows/ci.yml`·`nightly-tests.yml`에 common test/Kover를 추가. Task 9에서 Kover aggregation을 실행. |
| P2 | 사용자/호출자 | R2DBC production source와 README에 기존 `jdbc.annotation.*` import가 존재하여 migration 안내가 필요하다. | Task 5/8에서 canonical common import와 before/after 예제를 문서화하고 Task 8에서 `CHANGELOG.md`/선택적 `WIP.md`를 갱신. |
| P3 | 개발자/API | 새 common public symbol은 ABI baseline·manual inventory·downstream consumer 동기화가 필요하다. | Task 7/8/9/10에서 exact generated diff, `checkProductionAbi`, manual validator, Spring Modulith compile, 7-Tier review로 확인. |

P0=0, P1=0이다. P2/P3는 구현 후 fresh evidence가 필요한 검증 항목으로 계획 task와 재검토 지점을 갖는다. 별도 후속 issue를 지금 생성할 정도로 범위가 불명확한 항목은 없다.

## Step 3-R 필수 체크

| 체크 | 결과 | 계획 근거 |
|---|---|---|
| 명세 DoD의 concrete task 매핑 | PASS | Task 1–10과 완료 조건이 common 분리, JDBC ABI, R2DBC graph, BOM/manual/CI, 테스트, review/PR을 모두 다룬다. |
| 구현 가능한 순서 | PASS | 모듈 등록 → common TDD → JDBC facade → R2DBC graph → 통합 테스트 → ABI/docs → 검증 → review/PR 순서다. |
| 후속 산출물 선행 의존성 차단 | PASS | common API와 tests가 adapters보다 먼저 생기며, ABI/manual/PR은 구현 및 검증 뒤에 온다. |
| 성공·실패·edge·concurrency·coroutine·lifecycle·backend capability | PASS | Task 2/5/6에 각각 mapping race, unsupported query, duplicate bean, rollback, cancellation, cleanup, PostgreSQL/MySQL capability가 명시되어 있다. |
| concrete verification commands | PASS | module test/compile/ABI/Detekt/Kover, dependency insight, manual validators, source guard, `git diff --check`가 Task 9와 명령 목록에 있다. |
| README와 locale parity | PASS | common/JDBC/R2DBC README와 manual EN/KO, examples before/after가 Task 1/8에 있다. |
| 한국어 KDoc·comments·GitHub·CHANGELOG·release notes | PASS | Task 2/4의 KDoc/migration, Task 8의 `CHANGELOG.md`/release-note 입력, Task 10의 Korean PR/Lore를 명시했다. |
| 새 모듈 settings/BOM/CI/Nightly/resources/coverage | PASS | Task 1 settings/test resources, Task 8 BOM/CI/Nightly, Task 9 Kover aggregation이 있다. |
| Spring Boot conditional/order | PASS | Task 4/5/6에서 `@ConditionalOnMissingBean`, auto-config ordering, standalone/combined context를 검증한다. |
| Exposed deprecated import/receiver shadowing | PASS | Task 3/5의 canonical import, receiver-shadowing 방지, R2DBC JDBC import source guard가 있다. |
| coroutine cancellation/dispatcher boundary | PASS | Task 5/6에서 `suspendTransaction`, dispatcher, cancellation/resource cleanup 불변을 테스트한다. |
| performance/stability/cleanup/Testcontainers | PASS | Task 3/5/6/9에 hot path, race, close/rollback, 순차 PostgreSQL/MySQL/Redis 검증이 있다. |
| cross-module duplication 결정 | PASS | 명세의 common ownership과 JDBC-only facade/entity information 경계를 Task 2–5에 반영했다. |
| rollback/compatibility/migration 위험 | PASS | Task 4 legacy facade/ABI, Task 5 import migration, Task 9 rollback evidence, Task 8 docs/release 기록이 있다. |

## 계획 수정 이력

초기 검토에서 발견될 수 있었던 누락 위험을 계획에 반영했다.

1. common/JDBC/R2DBC 테스트의 `bluetape4k-assertions` 직접 의존성과 assertion 사용을 명시했다.
2. mapping cache 동시성, repository lifecycle/cleanup, raw query/sort 입력 negative test를 추가했다.
3. Spring Data 전용 benchmark 부재를 확인하는 절차와 SQL statement-count/반복 lookup 대체 근거를 추가했다.
4. Kover XML 및 `.github/scripts/aggregate-kover-coverage.py` aggregation을 추가했다.
5. CI/Nightly Spring Boot job에 common test/Kover를 포함하고 path filter/artifact 누락을 검사하도록 했다.
6. BOM 자동 constraint를 검증하고 불필요한 중복 source 수정을 피하도록 했다.
7. `CHANGELOG.md`, 선택적 `WIP.md`, release-note 입력과 Spring Modulith downstream compile/test를 추가했다.

## 최종 판정

`Step 3-R: PASS` — 최신 계획 기준 P0=0, P1=0. 계획 승인 전에는 production code·tests·README·CHANGELOG·PR을 변경하지 않는다. 사용자의 계획 승인이 다음 게이트이며, 승인 후 `executing-plans` 방식의 1인 순차 TDD 구현으로 전환한다. 구현 완료 후에는 별도의 7-Tier review, Lore commit, exact-head push, PR 생성 및 hosted evidence 확인을 수행하고, merge는 fresh 승인을 기다린다.
