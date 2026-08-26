# 이슈 #722 R2DBC cancellation·rollback 7-Tier 리뷰

## 범위와 기준

- 이슈: [#722](https://github.com/bluetape4k/bluetape4k-exposed/issues/722)
- 기준 base: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 구현 branch: `fix/r2dbc-cancellation-rollback`
- workflow run: `20260825T074540Z-66328bff` (Type A)
- 대상: `exposed/r2dbc-tests`
- GNO: 이 환경에는 `bluetape4k-github` collection/tool이 노출되지 않아 사용하지
  않았으며, live GitHub issue와 source를 기준으로 검토했다.

이번 변경은 `bluetape4k-assertions`를 published test-support의 직접 `api`
dependency로 선언하고, R2DBC helper의 cancellation·rollback·suppressed failure
계약을 고정한다. JDBC helper의 시작 시 `commit()` 패턴은 R2DBC 드라이버가
commit 후 auto-commit을 켜는 계약과 충돌하므로 복사하지 않고 savepoint를 사용했다.

## 변경 경로

- `exposed/r2dbc-tests/build.gradle.kts`
- `exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/Assertions.kt`
- `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/AssertionsTest.kt`
- `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt`
- 본 리뷰·lesson·설계·계획 문서

Production `exposed/r2dbc`, `withDb`/`withTables`, central catalog/BOM, workflow
YAML과 README/manual은 변경하지 않았다.

## 7-Tier 판정

| Tier | 검토 초점 | 판정 | 증적 |
| --- | --- | --- | --- |
| 1. 요구사항·범위·추적성 | #722 목표와 변경 경계가 일치하는가 | PASS | direct assertions API, migration matcher, cancellation/rollback 회귀 테스트, production 범위 제외 |
| 2. API/ABI·Kotlin 사용성 | published consumer가 API dependency를 해석하고 helper 계약이 명확한가 | PASS | `generateMetadataFileForBluetapeExposedPublication` PASS; `module.json` `apiElements`에 `io.github.bluetape4k:bluetape4k-assertions` 확인 |
| 3. Transaction correctness | block DML이 실제로 되돌아가고 cleanup 실패가 primary를 덮지 않는가 | PASS | H2/PostgreSQL/MySQL_V8 Assertions matrix 19/19; savepoint rollback 후 `discarded` row가 남지 않음 |
| 4. Coroutine cancellation·lifecycle | 예상하지 않은 cancellation을 삼키지 않고 cleanup을 완료하는가 | PASS | unexpected cancellation 원본 instance, expected cancellation, `NonCancellable` cleanup, migration child cancellation 8/8 |
| 5. Security·side effect·logging | 불필요한 출력·외부 side effect·raw assertion이 없는가 | PASS | touched module에서 `println`, `System.out`, `System.err`, raw JUnit/kotlin.test/AssertJ/Kluent assertion scan 결과 0건 |
| 6. Test strength·failure proof | positive/negative/lifecycle 경로가 deterministic한가 | PASS | AssertionsTest 19 passing; H2 migrationDriftTest 8 passing; affected module H2 63 passing, 5 pending 명시 |
| 7. Docs·operator·delivery | 재현 절차와 전달 상태가 추적 가능한가 | PENDING | 설계/계획/리뷰/lesson과 fresh local evidence는 준비됐으나 hosted PR CI/review는 PR 생성 후 대기 |

P0/P1: **0건**.

P2: publication POM 생성 task가 기존 `withXml()` 경로에서 다음 오류로 실패했다.

```text
Cannot invoke "org.gradle.api.artifacts.ConfigurationContainer.detachedConfiguration(org.gradle.api.artifacts.Dependency[])" because "this.delegate" is null
```

Gradle module metadata 생성과 `apiElements` dependency 증적은 통과했으므로 현재
변경의 direct API 계약은 확인했지만, POM task와 hosted CI는 PR에서 재확인해야 한다.

## 검증 명령과 결과

| 명령 | 결과 |
| --- | --- |
| `./gradlew :bluetape4k-exposed-r2dbc-tests:compileTestKotlin --no-build-cache --rerun-tasks --no-daemon` | PASS |
| `./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests io.bluetape4k.exposed.r2dbc.tests.AssertionsTest -Dgroups='' --no-build-cache --rerun-tasks --no-parallel --no-daemon` | PASS, 19/19 |
| `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-tests:migrationDriftTest --no-build-cache --rerun-tasks --no-parallel --no-daemon` | PASS, 8/8 |
| `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-tests:test --no-build-cache --rerun-tasks --no-parallel --no-daemon` | PASS, 63 passing, 5 pending |
| `./gradlew :bluetape4k-exposed-r2dbc-tests:detekt --no-build-cache --rerun-tasks --no-daemon` | PASS |
| `./gradlew :bluetape4k-exposed-r2dbc-tests:generateMetadataFileForBluetapeExposedPublication --no-build-cache --rerun-tasks --no-daemon` | PASS |
| `git diff --check` | PASS |

모든 Gradle 명령은 repository context-mode build gate를 통해 실행했다. Docker
기반 dialect 전체 matrix와 hosted CI/review/merge는 이 local DoD에 포함하지
않으며, H2 PASS를 해당 범위의 PASS로 일반화하지 않는다.

## 전달 상태

- commit: 아직 생성 전
- push/PR: 아직 생성 전
- merge: 수행하지 않음
- 다음 단계: review/lesson read-back 후 Lore commit → exact-head push → 한국어 PR
  생성. PR CI와 reviewer 결과를 확인한 뒤에만 이슈 #723으로 이동한다.
