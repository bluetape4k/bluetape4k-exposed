# Issue #31 CockroachDB 코드 검토

날짜: 2026-06-06
워크플로 게이트: 5단계 검증, 6-R 단계 구현 차이 검토
범위: `exposed/exposed-cockroachdb`, README 로케일 쌍, 변경 로그, 명세/계획/조사/검토 산출물

## 5단계 검증

| 항목 | 상태 | 근거 |
|---|---|---|
| 명세에서 수용한 요구 사항을 코드/테스트/문서에 대응 | 완료 | `CockroachDbCompatibility` 매트릭스가 지원/보류/범위 밖 항목을 다루며, `CockroachDdlCompatibilityTest`가 수용 및 보류 경로를 입증한다. |
| 계획된 작업 완료 또는 명시적 보류 | 완료 | 수용한 DDL이 통과했으므로 사용자 정의 방언은 계속 범위 밖으로 두며, 마이그레이션 차이 없음은 보류 사항으로 문서화했다. |
| 저장소 차이에 관련 없는 산출물이나 생성 산출물 없음 | 완료 | `git status --short --branch`; 변경 파일은 모듈 문서, 테스트 의존성, 호환성 소스/테스트, 변경 로그, 워크플로 문서다. |
| 공개 API/KDoc 및 README 영향 처리 | 완료 | 새 공개 API는 없다. README와 README.ko에 범위, 매트릭스, 의존성, Hikari 예제, 검증 명령을 문서화했다. |
| 테스트로 동작/실패/호환성 위험 입증 | 완료 | 생성 ID, 고유 값 중복 실패, 원시 `RETURNING`, 메타데이터, 마이그레이션 차이, 지원하지 않는 구문을 포함한 CockroachDB 테스트 9개가 통과했다. |
| 최신이며 모듈 범위에 한정된 검증 근거 | 완료 | 이 작업 트리에서 컴파일, Testcontainers 테스트, Kover XML, 정적 grep, `git diff --check`를 실행했다. |
| 알려진 공백 기록 | 완료 | 완전한 PostgreSQL 동등성, 마이그레이션 차이 없음 보장, 사용자 정의 방언, 재시도 도우미, R2DBC는 후속 후보 또는 #32 범위로 남았다. |

5단계 검증 판정: 통과.

## 6-R 단계 7단계 검토

| 단계 | 검토 범위 | P0 | P1 | P2 | P3 | 근거 |
|---|---|---:|---:|---:|---:|---|
| 1 보안 | 고정 테스트 SQL, README 예제, 검증 경로 | 0 | 0 | 0 | 0 | 비밀 정보를 추가하지 않았다. SQL은 고정된 호환성 근거다. 지원하지 않는 정리는 테스트 전용이다. |
| 2 운영/SRE 신뢰성 | Cockroach 컨테이너, HikariCP 풀, 정리 경로 | 0 | 0 | 0 | 0 | 테스트는 `CockroachServer.Launcher.cockroach`를 사용한다. Hikari 풀은 `.use`로 닫는다. 스키마 정리는 `finally`에서 실행한다. |
| 3 구조적 영향 | 모듈 의존성 방향 및 API 표면 | 0 | 0 | 0 | 0 | 새 호환성 모델은 `internal`이다. 새 공개 방언/API는 없다. 테스트 의존성에는 `bluetape4k-jdbc`, HikariCP, Exposed migration JDBC만 추가한다. |
| 4 Kotlin/API 품질 | Kotlin 소스/테스트 관용구 및 bluetape4k 규칙 | 0 | 0 | 0 | 0 | 검증에 `requireNotBlank`를 사용한다. 테스트는 bluetape4k 단언문, `bluetape4k-jdbc`, `bluetape4k-testcontainers`를 사용한다. 프로덕션 동시성 빠른 검사 결과는 0건이었다. |
| 5 테스트/타입/무응답 실패 | 테스트 단언 및 지원하지 않는 기능 경계 | 0 | 0 | 0 | 0 | 테스트에서 개수, 생성 ID, 중복 실패, 메타데이터 내용, 비어 있지 않은 마이그레이션 차이, 지원하지 않는 SQL 실패를 단언한다. |
| 6 성능/안정성 | 4-P/6-R 단계 성능 검사 | 0 | 0 | 0 | 0 | 프로덕션 핫 패스 변경이 없다. Hikari 풀은 제한되며 닫힌다. Testcontainers 픽스처는 싱글턴/직렬 실행을 유지한다. |
| 7 문서/릴리스/근거 | README 쌍, 변경 로그, 조사/위키, 검토 근거 | 0 | 0 | 0 | 0 | README 로케일 쌍과 변경 로그를 갱신했다. 조사 노트를 보존했다. PR/완료 정의 근거 경로를 사용할 수 있다. |

## 구체적인 검토 결정

- #31에서는 `DriverManager`를 기반으로 하는 기존의 단순한
  `CockroachDatabase.connect(jdbcUrl, ...)` 경로를 유지한다. 이를 숨겨진 HikariCP
  풀로 교체하면 풀 소유권과 종료 책임이 불분명해진다. 더 안전한 생태계 경로는 기존의
  호출자 관리형 `DataSource` 오버로드와 `bluetape4k-jdbc`/Hikari 예제 및 테스트다.
- #31에는 `CockroachDbDialect`를 추가하지 않는다. 수용한 DDL 경로는 도우미 전용
  PostgreSQL 유선 프로토콜 계약으로 통과한다. 마이그레이션 차이의 시퀀스 소유권만
  불필요한 결과를 내며 보류 사항으로 문서화했다.
- 이 범위에서는 마이그레이션 차이 이슈를 생성하지 않는다. 이제 README는 이를 이미
  생성된 이슈가 아니라 상위 에픽의 후속 후보라고 설명한다.

## 검증 근거

| 명령 | 결과 |
|---|---|
| `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon` | 통과 |
| `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon` | 통과, 테스트 9개 |
| `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon` | 통과 |
| `git diff --check` | 통과 |
| `rg "GlobalScope|runBlocking\\(|Thread\\.sleep|delay\\(|synchronized\\(|@Synchronized|runCatching\\s*\\{" exposed/exposed-cockroachdb/src/main/kotlin` | 통과, 프로덕션 검색 결과 0건 |
| `rg "DriverManager\\.getConnection|GenericContainer|JUnit.*assert|assertThrows|kotlin\\.test\\.assertFailsWith|org\\.junit\\.jupiter\\.api\\.Assertions" exposed/exposed-cockroachdb/src/test exposed/exposed-cockroachdb/README.md exposed/exposed-cockroachdb/README.ko.md docs/superpowers docs/review` | 구현/문서 기준 통과. 검토/명세의 과거 가드 문구와 이전 #30 명세 문구만 거부된 패턴을 언급한다. |

## 6단계 체크리스트 완료 보고

| 항목 | 상태 | 비고 |
|---|---|---|
| 필수 6-R 단계 참고 자료 로드 | 완료 | `step-6r-code-review.md`, `step-4p-perf-scan.md`, `bluetape4k-code-patterns`를 로드했다. |
| 7단계 검토 수행 | 완료 | 위 단계 표를 참조한다. |
| P0/P1 수렴 확인 | 완료 | P0 = 0, P1 = 0. |
| P2/P3 처리 기록 | 완료 | P2/P3 검토 결과가 남아 있지 않다. |
| 검증 근거 기록 | 완료 | 컴파일, 테스트, Kover, 정적 검사, 차이 검사 명령을 나열했다. |

## 판정

P0 = 0
P1 = 0

6-R 단계 통과.
