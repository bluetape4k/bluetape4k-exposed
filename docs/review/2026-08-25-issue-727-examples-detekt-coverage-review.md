# 이슈 #727 examples Detekt 범위 확대 7-Tier 리뷰

## 리뷰 범위와 기준

- 이슈: [#727](https://github.com/bluetape4k/bluetape4k-exposed/issues/727)
- 유형: Type E — examples build/CI/static-analysis maintenance
- 기준 커밋: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 설계/계획:
  - `docs/superpowers/specs/2026-08-25-issue-727-examples-detekt-coverage-design.md`
  - `docs/superpowers/plans/2026-08-25-issue-727-examples-detekt-coverage-plan.md`
- 검토 대상: root `build.gradle.kts`, `.github/workflows/nightly-tests.yml`, 6개 examples Gradle/Kotlin source set
- 책임 경계: DDD Modulith UUID v7 전환은 선행 PR [#741](https://github.com/bluetape4k/bluetape4k-exposed/pull/741)이 소유한다. 따라서 이 PR의 직접 UUID guard는 `examples/ktor-exposed-demo`에 적용하고, DDD 경로를 중복 수정하지 않았다.

## 발견 사항과 조치

| 단계 | 증거 | 조치 | 결과 |
| --- | --- | --- | --- |
| baseline | 6개 example `detekt`가 `NO-SOURCE`로 성공 | root `/examples/` blanket `exclude("**")` 제거 | 실제 source 입력 복구 |
| RED | Detekt 43건: `TooGenericExceptionCaught` 15, `MagicNumber` 15, `SwallowedException` 4, `ThrowsCount` 2, `MaxLineLength` 2, `SerialVersionUIDInSerializableClass` 2, `LongMethod` 2, `ReturnCount` 1 | 예제별 구체 예외/상수/함수 분리, 필요 lifecycle 경계에만 함수 단위 suppression | GREEN에서 0건 |
| pattern inventory | raw assertion/production `!!`/`println`/direct UUID/System output 25건, 9개 파일 | `bluetape4k-assertions`, `KLogging`, `Uuid.V7`로 교체하고 root guard 추가 | `Example pattern rules passed` |
| CI | 기존 nightly가 `detekt`만 실행하고 examples 범위를 로그로 증명하지 않음 | `detekt exampleDetekt` 실행, analyzed project와 pattern guard 로그를 `grep`으로 fail-closed 확인 | workflow YAML/actionlint PASS |

## 7-Tier 결과

| Tier | 검증 질문 | 결과와 근거 | 판정 |
| --- | --- | --- | --- |
| T1 Performance | lint 범위 확장이 런타임/API 성능 계약을 깨뜨리는가 | production API/schema 변경 없음. `exampleDetekt` 6개 프로젝트 집계가 `BUILD SUCCESSFUL in 7s` | PASS |
| T2 Stability | lifecycle, cache, event, error 경계가 유지되는가 | Ktor unit `10`, DDD `10`, JDBC `27`, R2DBC `36`, ClickHouse `1`, Ktor PostgreSQL `4` tests PASS | PASS |
| T3 Security | 운영 출력과 assertion이 민감정보를 노출하지 않는가 | `println`, `System.out`, `System.err`, production `!!`, Ktor `UUID.randomUUID` 잔여 0. Logging sink는 `code/correlationId/component/operation/phase/outcome`만 기록하며 secret sanitization test PASS | PASS |
| T4 Operator/Ops | CI가 실제 examples 정적검사와 보고서를 관찰 가능한가 | nightly가 `detekt exampleDetekt`와 `Example Detekt analyzed projects:`/`Example pattern rules passed:`를 검사하고 XML artifact를 업로드 | PASS |
| T5 Developer/API | Kotlin 패턴, helper, public API 경계가 일관적인가 | `bluetape4k-assertions`, `Uuid.V7`, `KLogging` 직접 사용. Ktor/DDD/R2DBC compileKotlin 및 ClickHouse compileTestKotlin PASS | PASS |
| T6 User/Caller | 예제 호출 계약과 문서 계약이 유지되는가 | Ktor route/application tests와 각 demo tests PASS. README 동작 설명 변경 없음이므로 English/Korean parity는 N/A (docs behavior diff 없음) | PASS/N/A |
| T7 Integration | root aggregate와 실제 container path가 함께 검증되는가 | root `detekt` 44 tasks `BUILD SUCCESSFUL`; ClickHouse Testcontainers `1`, PostgreSQL Testcontainers `4` PASS | PASS |

### 심각도 판정

- P0: 0
- P1: 0
- P2: 0 — 남은 항목은 없음
- `exampleDetekt`의 UUID 직접 생성 guard는 #726/#741과 겹치지 않도록 Ktor production scope로 제한했다. 이 allowlist가 아니라 책임 경계 분리이며, #741 병합 후 DDD 경로는 선행 PR에서 이미 `Uuid.V7`로 전환된다.

## 실행 증거

| 명령 | 결과 |
| --- | --- |
| `./gradlew exampleDetekt --no-daemon --no-parallel --no-configuration-cache --console=plain` | 6개 프로젝트 non-empty XML, `Example pattern rules passed: production=36, tests=18`, `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --no-parallel --no-configuration-cache --console=plain` | root 44 tasks, `BUILD SUCCESSFUL` |
| `./gradlew :examples-ktor-exposed-demo:test ...` | 10 tests PASS |
| `./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest ...` | PostgreSQL Testcontainers 4 tests PASS |
| `./gradlew :examples-ddd-spring-modulith-demo:test ...` | 10 tests PASS |
| `./gradlew :exposed-spring-boot-jdbc-demo:test ...` | 27 tests PASS |
| `./gradlew :exposed-spring-boot-r2dbc-demo:test ...` | 36 tests PASS |
| `./gradlew :examples-exposed-clickhouse-oltp-olap:test ...` | ClickHouse Testcontainers 1 test PASS |
| `actionlint .github/workflows/nightly-tests.yml` 및 Ruby YAML parse | PASS (로컬 `actionlint` 사용 가능) |
| `git diff --check` | PASS |
| `node .../audit-korean-terms.mjs --json <changed Korean docs>` | 설계/계획/리뷰/lesson 4개 파일 findings 0 |
| 임시 `PatternGuardProbe.kt`에 `println`을 넣은 negative run | `exampleDetekt`가 `production println` 위치를 보고하고 실패한 뒤 probe 제거, GREEN 재실행 PASS |

## Kotlin/문서 체크리스트

- Kotlin pattern: null-safe expression, immutable collections, narrow exception boundaries, no production `!!`, named constants, existing ecosystem helper 재사용 — PASS.
- Assertions: 변경된 Ktor tests의 identity 검증을 `shouldBeTrue`로 전환하고 `assertFailsWith`/`shouldBeEqualTo`를 유지 — PASS.
- Logging: `StderrDemoDiagnosticSink`의 `println`/`System.err`를 `LoggingDemoDiagnosticSink`와 `KLogging`으로 전환 — PASS.
- SPW-01~SPW-05: scope/evidence, artifact contract, Korean technical register, source read-back, final Markdown read-back — PASS.
- KO-01~KO-07: 사실/식별자 보존, 근거 기반 문장, 번역투 제거, 용어 일관성, 과장 제거, reader surface 점검, terminology audit — PASS.

## 결론과 보류

로컬 구현과 7-Tier 검증은 완료했다. Lore 커밋과 push 후 PR을 생성하고, hosted CI와 사람 리뷰가 끝날 때까지 merge는 보류한다. 이 리뷰 문서는 PR의 `## DoD Status`에 연결하며, hosted 결과가 바뀌면 PR에서 최신 상태를 갱신한다.

**Required checks: 22/22; N/A: 1 (T6 README parity); Blocked: 0**
