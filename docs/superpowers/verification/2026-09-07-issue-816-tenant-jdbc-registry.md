# #816 tenant JDBC registry 구현 검증

검증 대상은 `origin/develop`의 `d98954c1c0e6e8da7132d1c73c0f7e7f96f6da6a`부터
구현 SHA `dafe7ddb4dd2161f5e358b21c7bf98270bd2ae12`까지다. 검증 날짜는
2026-09-07이며, 새 opt-in JDBC registry artifact와 그 publication·CI 계약을 다룬다.

## 수용 기준 대응

| 기준 | 구현·검증 근거 | 결과 |
|---|---|---|
| AC-01 | string·enum tenant로 `resourceFor`, `databaseFor`, `dataSourceFor`를 조회하고 같은 entry identity를 확인했다. | PASS |
| AC-02 | 두 H2 database의 schema/data 격리, 서로 다른 proxy 허용, 같은 `DataSource` reference 거부와 1회 cleanup을 검증했다. | PASS |
| AC-03 | unknown/null/duplicate/empty 입력, factory 미호출, fixed message, secret-bearing key의 `toString()` 미호출을 검증했다. | PASS |
| AC-04 | factory 반환 전·후, connect·commit·publish 실패와 fatal assembly에서 인수한 resource의 역순 cleanup을 검증했다. | PASS |
| AC-05 | ordinary/fatal 우선순위, suppressed identity/order, self-suppression 제외, interrupt 복원, provider log 비복제를 검증했다. | PASS |
| AC-06 | real Exposed unregister 후 dispose, 역순 cleanup, 재진입·반복·동시 close, dispose 1회와 같은 최종 결과를 검증했다. | PASS |
| AC-07 | immutable configured tenant view, stable key, hash collision, 32 thread×10,000 lookup identity를 검증했다. | PASS |
| AC-08 | OPEN을 관찰한 lookup과 close 경합, close 선점 후 고정 상태 오류, lookup 비-lease 계약을 검증했다. | PASS |
| AC-09 | runtimeClasspath와 생성 POM/module metadata에 Spring, Ktor, HikariCP, Micrometer, Reactor가 없음을 확인했다. | PASS |
| AC-10 | settings 자동 등록, BOM/publication inventory, CI/Nightly H2 test·Kover, ABI 45/45, root 문서 연결을 확인했다. | PASS(로컬); hosted run PENDING |
| AC-11 | `registry::databaseFor` compile fixture와 test-scope Hikari consumer를 실행했다. 기존 Ktor production dependency는 바꾸지 않았다. | PASS |
| AC-12 | public API baseline, 한국어 KDoc, 영어·한국어 README와 용어 검토를 완료했다. | PASS |

## 실행 환경과 순서

- Colima: macOS Virtualization.Framework, runtime `docker`, 정상 실행.
- Docker context: `default`; server `29.2.1`, Ubuntu 24.04.4 LTS.
- Testcontainers 명령에만 `DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock`와
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 적용했다.
- Gradle은 `--no-daemon --no-configuration-cache --no-parallel --max-workers=1`로
  직렬 실행했다.

## 테스트와 정적 검증

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:build \
  :bluetape4k-exposed-jdbc:test \
  :bluetape4k-exposed-ktor-tenant-jdbc:compileTestKotlin \
  checkProductionAbi detekt \
  --no-daemon --no-configuration-cache --no-parallel --max-workers=1
```

결과: `BUILD SUCCESSFUL`, 254 tasks 중 95 executed·159 up-to-date.

신규 module은 `cleanTest`, `test`, `koverXmlReport`를 `--rerun-tasks`로 다시
실행했다. JUnit XML과 Kover XML을 별도로 파싱한 결과는 다음과 같다.

| 대상 | tests | failures | errors | skipped | 추가 증거 |
|---|---:|---:|---:|---:|---|
| `bluetape4k-exposed-tenant-jdbc` | 27 | 0 | 0 | 0 | Kover XML 19,171 bytes, 13 classes |
| `bluetape4k-exposed-jdbc` | 603 | 0 | 0 | 20 | 기존 assumption 기반 skip이며 신규 성공 수로 세지 않음 |

전체 detekt와 Ktor tenant-jdbc test compilation도 같은 통합 명령에서 통과했다.
리뷰 중 concurrency class를 직렬로 5회 반복했고 final stability reviewer는 lifecycle·
concurrency 15 tests를 별도로 실행했다. 병렬 Gradle build-output 충돌이 발생한 한
reviewer의 추가 실행은 증거에서 제외하고 주 세션의 직렬 재실행을 최종 근거로 삼았다.

## ABI와 publication

`build/abi/reports/production-abi.txt`의 fresh 결과:

```text
modules=45/45
baselines=45/45
actualDumps=45/45
orphanBaselines=0
orphanActuals=0
emptyBaselines=0
```

전체 publication metadata와 POM을 `-PsnapshotVersion=-SNAPSHOT`으로 생성하고
검사했다.

| 검사 | 결과 |
|---|---|
| Gradle metadata test | 15 runs, 27 assertions, failures/errors/skips 0 |
| POM audit test | 10 runs, 30 assertions, failures/errors/skips 0 |
| metadata validator | failures=0, files=46, variants=94, dependencies=1,084 |
| POM validator | failures=0, files=46, dependencies=13,431, maven_models=46 |

`bluetape4k-exposed-tenant-jdbc`는 publication inventory와 BOM constraint에 포함된다.
runtime dependency graph에는 Exposed JDBC/Core 1.5.0이 있고 금지한 Spring, Ktor,
HikariCP, Micrometer, Reactor production 좌표는 없다.

## CI와 문서 계약

- `PYTHONPATH=scripts/ci python3 -m unittest
  scripts/ci/validate_ci_matrix_contract_test.py`: 7 tests, OK.
- `python3 scripts/ci/validate_ci_matrix_contract.py`: global-change matrix aligned.
- `py_compile`, CI/Nightly YAML parse, 두 workflow의 actionlint: PASS.
- CI와 Nightly `test-jdbc-h2`는 tenant module test, Kover XML 생성, non-empty·class
  fail-closed 검사, test/coverage artifact 업로드를 포함한다.
- `git diff --check`: PASS.
- 영어·한국어 module README는 heading 8/8, fenced code block과
  `transaction(db = database)` guidance가 동등하다.

terminology audit는 15개 관련 파일을 확인했다. 신규 module README, 설계·계획,
KDoc에서는 지적이 없었다. root CHANGELOG/README의 변경 밖 기존 문장에 있는
`snapshot`, `대기열` 7건은 각각 개발판·큐 문맥의 기존 표현이므로 이번 범위에서
바꾸지 않았다.

## 리뷰 증거

| 관점 | 최종 provenance | P0/P1/P2/P3 |
|---|---|---|
| 성능 | 독립 exact-head 재검토 `APPROVE` | 0/0/0/0 |
| 안정성 | 독립 exact-head 재검토 `PASS` | 0/0/0/0 |
| 보안 | 독립 exact-head 재검토 `PASS` | 0/0/0/0 |
| 운영 | 독립 exact-head 검토 `COMMENT`; hosted·LSP/AST 공백 명시 | 0/0/0/0 |
| 개발/API | 독립 exact-head 검토 `COMMENT`; hosted·LSP 공백 명시 | 0/0/0/0 |
| 사용자/호출자 | 독립 exact-head 검토 `COMMENT`; hosted·LSP 공백 명시 | 0/0/0/0 |

main integration review는 독립 verdict와 별개로 AC-01~AC-12, dependency, tests,
ABI, publication, CI, 문서를 대조했다. 해결한 P1 1건과 P2 3건의 내역은
[`구현 리뷰`](../../review/2026-09-07-issue-816-tenant-jdbc-registry-implementation-review.md)에
남겼다.

## 실패 이력과 정정

1. `checkProductionAbi`를 configuration cache와 함께 실행했을 때 Gradle script object
   serialization 오류가 발생했다. repository가 요구하는
   `--no-configuration-cache`로 다시 실행해 45/45와 orphan/empty 0을 확인했다.
2. CI validator test를 script 경로에서 직접 실행한 초기 시도는 import/cwd 계약과
   맞지 않았다. root에서 `PYTHONPATH=scripts/ci python3 -m unittest ...`로 실행해
   7/7을 확인했다.
3. reviewer가 공유 Gradle output을 병렬로 건드린 실행은 충돌했다. 최종 증거는
   하나의 Gradle process와 worker 1로 다시 실행한 결과만 사용한다.

위 이력은 제품 결함으로 숨기지 않으며, 성공한 교정 명령과 실패한 시도의 범위를
구분한다.

## 남은 gate와 비목표

- GitHub PR exact-head CI와 required checks는 아직 없다.
- Full Nightly를 dispatch하거나 terminal job까지 확인하지 않았다.
- Maven Central 개발판/정식 publication과 외부 published-coordinate consumer는
  검증하지 않았다.
- downstream `exposed-workshop#269`의 두 Spring MVC 예제 이전은 provider artifact를
  사용할 수 있게 된 뒤 별도 작업으로 수행한다.
- #817 범위의 Exposed 내부 cleanup 정책은 이번 provider에서 변경하지 않는다.
- Spring Boot binding, 인증·인가, fallback tenant, implicit transaction routing,
  health/readiness, drain, timeout/retry, telemetry는 caller 또는 adapter 책임이다.
- 1인 개발 저장소의 별도 인간 reviewer subgate만 N/A다. 독립 기술 리뷰, CI,
  Full Nightly와 fresh merge 승인은 N/A가 아니다.

## 문서 검증 DoD

- [x] **SPW-01**: issue, SHA, 검증 환경, 포함·제외 범위와 대상 독자를 명시했다.
- [x] **SPW-02**: 수용 기준, 실행 명령, 수치, 실패 이력, 미실행 gate를 분리했다.
- [x] **SPW-03**: 독립 리뷰와 main review provenance를 구분하고 고정 기술 토큰을
  보존했다.
- [x] **SPW-04**: JUnit XML, Kover XML, ABI report, publication validator와 CI validator
  결과를 source 주장에 대조했다.
- [x] **SPW-05**: final read-back, 링크, fenced block, locale parity, terminology,
  `git diff --check`를 검사했다.
- [x] **KO-01~KO-07**: 사실·식별자 보존, 자연스러운 어순, 일관된 용어, 책임 주체,
  수치 단위, 명시적 한계와 과장 금지를 확인했다.

## DoD Status

- [x] AC-01~AC-12의 로컬 구현·검증 근거 수집.
- [x] 신규 module 27 tests와 Kover, 기존 JDBC 603 tests, 통합 compile/detekt 통과.
- [x] ABI 45/45, publication audit, runtime dependency와 CI contract 통과.
- [x] 여섯 관점 exact-head 리뷰와 발견 사항 해결.
- [x] 한국어 검증 문서와 writer audit 작성.
- [ ] PR 생성, push와 exact-head hosted CI.
- [ ] Full Nightly terminal 검증.
- [ ] merge, release, downstream migration.

최종 상태: **로컬 구현·검증 DONE, delivery PENDING**.
