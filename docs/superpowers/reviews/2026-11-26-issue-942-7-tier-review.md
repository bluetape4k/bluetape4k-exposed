# #942 Exposed R2DBC DAO 연동 7-Tier 코드 리뷰

## 검토 범위와 provenance

- 저장소: `bluetape4k/bluetape4k-exposed`
- 이슈: [#942](https://github.com/bluetape4k/bluetape4k-exposed/issues/942), milestone `2.2.0`
- 기준: `feat/issue-942-r2dbc-dao` branch의 local Lore commit. PR은 아직 없다.
- 검토 범위: 새 dependency-only 모듈, ABI 허용 목록, 게시 consumer 검사기, CI/Nightly, README·CHANGELOG·WIP·AGENTS, 중앙 영문·한국어 매뉴얼.
- 방식: main session에서 여섯 관점을 각각 검토하고 일곱 번째 통합을 수행했다. 독립 reviewer 호스트 `/opt/homebrew/Caskroom/codex/0.171.0/bin/codex-code-mode-host`가 없어 독립 lane은 실행되지 않았다. 이 결과는 **inline·비독립**이며 독립 최종 승인으로 간주하지 않는다.
- 기준 정보: `exposed/r2dbc-dao/build.gradle.kts`, `exposed/r2dbc-dao/src/test/kotlin/io/bluetape4k/exposed/r2dbc/dao/ExposedR2dbcDaoContractTest.kt`, `build.gradle.kts`, `buildSrc/src/main/kotlin/ProductionAbiSupport.kt`, `scripts/publication/validate_downstream_consumer.rb`, 변경한 CI/Nightly와 README, 중앙 매뉴얼 manifest 및 EN/KO 페이지, live #942·#954와 사이트 #448.

## 7-Tier 검토 결과

| Tier | 관점 | 근거와 판정 | 새 미해결 finding |
|---|---|---|---|
| 1 | 성능 | 모듈에 production class나 실행 경로가 없다. API 의존성을 소비자에게 전달하는 빌드 구성만 추가하므로 별도 런타임 benchmark 대상이 아니다. | 0 |
| 2 | 안정성 | H2 계약 테스트와 PostgreSQL Testcontainers 테스트에서 생성 ID, 관계 `Flow`, `attach()`, 동시 transaction cache, 취소·rollback·connection 재사용을 확인했다. Docker가 없는 격리 실행은 환경 오류로 실패했지만 호스트 Docker에서 PostgreSQL 전용 테스트가 실행되어 전체 10개가 통과했다. | 0 |
| 3 | 보안 | 새 입력 파서, 인증 경계, 비밀값 처리, SQL 실행 또는 네트워크 endpoint를 추가하지 않는다. 의존성은 중앙 catalog의 `exposed = 1.6.1`에 고정된 alias를 사용한다. 이 검토에서 별도 취약점 스캔은 실행하지 않았다. | 0 |
| 4 | 운영 | `ConnectionFactory`, pool, `R2dbcDatabase`, transaction과 종료는 소비자 소유라고 README 및 EN/KO 매뉴얼에 명시했다. cleanup 실패에도 pool을 닫는 `finally`와 회귀 테스트가 있다. 로컬 CI workflow 검증은 가능하지만 PR이 없어 hosted CI 결과는 없다. | 0 |
| 5 | 개발자/API | `api(bt4k.exposed.dao.r2dbc)`와 core만 노출하고 Bluetape4k production API는 만들지 않는다. 빈 ABI baseline/dump는 이 모듈 하나에만 허용한다. staged consumer는 opt-in 컴파일·실행과 누락 opt-in의 예상 진단을 확인한다. | 0 |
| 6 | 사용자/호출자 | 사용 예제는 `ExperimentalR2dbcDaoApi`, `newSuspend`, transaction 안의 `Flow` 수집, transaction 간 `attach()`와 JDBC/R2DBC DAO 분리를 설명한다. upstream API의 실험 상태와 caller-owned lifecycle도 명시한다. 중앙 매뉴얼 신규 페이지는 `2.2.0` preview로 두고 안정 `2.0.0` 문서 경로는 갱신하지 않았다. | 0 |
| 7 | 통합 | 모듈 등록, ABI, consumer graph, CI/Nightly, 양언어 README와 중앙 매뉴얼, lesson 및 이슈 경계를 함께 확인했다. Dependencies PR #298의 catalog alias가 존재하므로 새 catalog tag는 필요하지 않다. 호환성 조합은 #954, 기존 사이트 manifest/Tink 참조 오류는 `bluetape4k.github.io#448`에서 추적한다. | 0 |

## 이전 반복의 finding과 조치

| 심각도 | 관찰 | 조치 및 현재 상태 |
|---|---|---|
| P2 | database registry 정리가 예외를 던지면 테스트가 connection pool을 폐기하지 않을 수 있었다. | `disposeDatabaseAndPool()`을 `try/finally`로 바꾸고 cleanup 예외 뒤 pool 폐기를 확인하는 회귀 테스트를 추가했다. 해결됨. |
| P2 | 게시 consumer가 Gradle Module Metadata를 사용하지 못해 POM fallback만으로 통과할 수 있었다. | staged repository에서 `.module` 파일을 필수 검사하고 consumer metadata source를 `gradleMetadata()`만 허용하게 했다. 전체 consumer 검증을 다시 통과했다. 해결됨. |
| P3 | dependency verification 상태 출력이 중복 접두사를 만들 수 있었다. | `present` 또는 `N/A (...)`로 정규화하고 출력 접두사를 한 번만 적용했다. 단위 테스트로 확인했다. 해결됨. |

이번 통합 검토에서 추가로 등록할 finding은 확인하지 않았다. P0/P1/P2/P3 미해결 수는 각각 0이다. 이는 main session의 inline 결과이며 독립 reviewer 결과가 아니다.

## 검증 증거와 경계

- DAO 계약 테스트: `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-dao:test --no-configuration-cache --no-daemon --rerun-tasks`에서 9개 통과, PostgreSQL 조건 테스트 1개 제외. `EXPOSED_TEST_DB=POSTGRESQL TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :bluetape4k-exposed-r2dbc-dao:test --no-configuration-cache --no-daemon --rerun-tasks`는 host Colima Docker socket을 사용해 테스트 10개 통과, 실패·건너뜀 0개였다.
- `checkProductionAbi`: 46/46 모듈, 빈 baseline 1개, 빈 actual dump 1개, dependency-only allowlist 1개.
- `validate_downstream_consumer.rb`: 게시 47개(라이브러리 46개)와 fixture 1개, compile/runtime synthetic task 각 93개 통과. Exposed DAO-R2DBC 1.6.1이 해석되고 게시 JAR에는 class가 없으며, opt-in 누락 소비자는 `ExperimentalR2dbcDaoApi` 진단으로 실패했다.
- `ruby scripts/publication/validate_downstream_consumer.rb`는 exit 0이다. Ruby consumer 단위 테스트 `ruby scripts/publication/validate_downstream_consumer_test.rb`는 6 runs, 78 assertions, 실패 0이다.
- `./gradlew checkProductionAbi detekt :bluetape4k-exposed-r2dbc-dao:jar exportPublicationInventory --no-configuration-cache --rerun-tasks`, `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`, Ruby syntax 검사와 `git diff --check`가 통과했다. ABI report는 `modules=46/46`, `baselines=46/46`, `actualDumps=46/46`, `emptyBaselines=1`, `emptyActualDumps=1`, `dependencyOnlyModules=1`이다.
- `gitleaks dir`는 변경 파일 28개만 격리해 검사했고 finding 0개였다. `.bluetape` runtime receipt와 owner handle은 소스 검사에서 제외했다.
- Dependencies `sync-managed-catalog.py --check --summary`는 alias 188개와 sub-BOM 8개를 확인했고, Exposed의 `sync-dependabot-ignores.py --check --summary`도 통과했다. PR #298은 merge commit `d9ad1af37d20545fa97af1c0d01ec05542a92e77`이며 catalog alias는 `exposed = 1.6.1`을 재사용한다.
- 중앙 사이트: `npm test` 300개 통과, `npm run check:manual` 통과, `npm run build` 3,921 페이지 생성. 사이트 전체 `validate_manuals.rb`에는 #448이 다루는 기존 5개 오류(tenant-jdbc manifest 누락과 Tink `Tables.kt` 참조)가 남아 있다. 새 R2DBC DAO 페이지 오류는 없다.
- 독립 reviewer 실행과 hosted PR CI는 이 worktree에서 증명하지 않았다. 중앙 매뉴얼 전체 validator의 기존 오류도 해결된 것으로 표시하지 않는다.

## 최종 판정

**Inline review: P0=0, P1=0, P2=0, P3=0 · COMMENT / WATCH**

코드와 계약 검토에서 새 수정 항목은 찾지 못했다. 그러나 독립 reviewer provenance와 hosted PR CI가 없어 Step 6-R의 독립 승인 상태는 **PENDING**이다. #942는 열린 상태로 유지한다. 새 이슈는 만들지 않았다. 기존 호환성 follow-up #954와 사이트 문서 이슈 #448이 해당 범위를 추적한다.

## Writer DoD

- SPW-01: 범위, 독자, 사실 기준과 독립 리뷰의 미확인 경계를 고정했다.
- SPW-02: 범위·finding·조치·검증·판정·잔여 공백을 기록했다.
- SPW-03: 한국어 기술 문체와 API token 표기를 검토했다.
- SPW-04: source, test XML, Gradle/consumer 결과, workflow와 live issue 상태를 대조했다.
- SPW-05: 최종 Markdown을 다시 읽고 링크·표·목록을 확인했다.
