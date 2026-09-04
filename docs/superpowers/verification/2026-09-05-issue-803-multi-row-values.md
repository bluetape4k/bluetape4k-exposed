# #803 구현 검증과 최종 리뷰

기준: `81ab8b0052734b18ece5e3673ffcc0fc285cc10b` → `feat/issue-803-multi-row-values`.
검증 날짜: 2026-09-05. 대상은 Exposed 1.5.0의 선택적 multi-row VALUES와 승인된
repository `true + ignore=true` 조기 거부 계약이다.

## 수용 기준 대응

| 설계 기준 | 구현·검증 근거 | 결과 |
|---|---|---|
| 기존 API 및 false 동작 | 두 Repository의 기존 overload 무변경, 신규 false 위임, 기존 writer secondary constructor | PASS |
| 입력 순회 전 조합 거부 | RepositoryMultiRowValuesTest: Iterable/Sequence/빈 입력/생성 값 true·false, 순회·바인더·INSERT 모두 0 | PASS |
| bounded 수집·한도 | repository는 허용 행 수 + 1 수집, writer는 List 크기 검사, Long 곱셈·requireLe | PASS |
| 실제 경계 성공·초과 거부 | 각 backend에서 repository 21,845행, writer 16,383행 성공 및 다음 행 거부 | PASS |
| 저장 데이터·생성 ID | nullable, 단일/다중 입력, SQL tuple/parameter-set, ID별 재조회, 명시 ID 생성 값 false | PASS |
| 트랜잭션·취소 | 중복 오류 후 선행 성공 보존, R2DBC 실제 child 취소·join·바인더 1회·미삽입 | PASS |
| 방언별 차이 | H2 UnsupportedByDialectException, PostgreSQL native 불완전 반환 및 JDBC Unit writer ignore | PASS |
| 문서·범위 | README 6개, 한국어 KDoc, API baseline 5개, dependency/catalog/saveAll/Spring Batch 무변경 | PASS |

A-VER-01~07: 승인 기준 대응, 계획 정합성, scope, 공개 문서, 위험 테스트,
최신 모듈 증거 및 미검증 목록을 위 표와 아래 결과로 확인했다. PR/CI는 구현 검증과 별도다.

## 실행 결과

공통 옵션: `--continue --max-workers=1 --no-configuration-cache --no-build-cache --console=plain`.
한 번에 Gradle 프로세스 하나만 실행하고 Testcontainers 경로를 순차 검증했다.

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc:test --tests '*RepositoryMultiRowValuesTest' \
  :bluetape4k-exposed-r2dbc:test --tests '*RepositoryMultiRowValuesTest' \
  :bluetape4k-exposed-batch-jdbc:test --tests '*WriterMultiRowValuesTest' \
  :bluetape4k-exposed-batch-r2dbc:test --tests '*WriterMultiRowValuesTest' \
  --continue --max-workers=1 --no-configuration-cache --no-build-cache --console=plain
```

fixture는 POSTGRESQL 선택 시 H2도 포함한다. 신규 provider는 H2/PostgreSQL만 선택하며
MySQL/Oracle/SQLite 성공을 주장하지 않는다. 신규 테스트는 실패·오류·생략 모두 0이다.

| 모듈 | 신규 H2+PostgreSQL 통과 | 전체 H2 통과 | 전체 H2 생략 | 실패·오류 |
|---|---:|---:|---:|---:|
| exposed-jdbc | 16 | 202 | 25 | 0 |
| exposed-r2dbc | 16 | 207 | 7 | 0 |
| batch-jdbc | 14 | 53 | 5 | 0 |
| batch-r2dbc | 14 | 49 | 3 | 0 |
| 합계 | 60 | 511 | 40 | 0 |

전체 H2 검증은 위 4개 모듈의 `test`(필터 없음), 각 `detekt`, `checkProductionAbi`를
공통 옵션으로 실행했다. 최종 `BUILD SUCCESSFUL in 35s`, 204 tasks 중 49 executed.
JUnit XML에서 위 counts를 별도 집계했다. 생략 40개는 신규 기능 성공으로 계산하지 않는다.

- RED: 신규 인자/생성자 부재 컴파일 실패. 조합 거부 변경 RED는 기대한 IllegalArgumentException 대신 UnsupportedByDialectException이었다.
- GREEN: 신규 60/60 및 전체 H2 테스트·정적 검사 통과.
- ABI: `modules=44/44`, `baselines=44/44`, `actualDumps=44/44`, orphan/empty 0.
- ABI diff: 5개 `.api` 파일에 82행 추가, 삭제 0. 새 overload와 bridge만 추가.
- `javap -classpath utils/batch/jdbc/build/classes/kotlin/main io.bluetape4k.batch.jdbc.ExposedJdbcBatchWriter`: 기존 `(Database, Table, boolean, Function2)`와 `(Database, Table, boolean, Function2, int, DefaultConstructorMarker)` 유지.
- R2DBC `javap`: 기존 `(R2dbcDatabase, Table, Function2)` 유지.
- production 4개 파일의 GlobalScope/runBlocking/Thread.sleep/synchronized/runCatching 검사: 일치 0.
- `git diff --check`: PASS. IDE 진단 도구는 제공되지 않아 compile·detekt를 사용했다.

## 실패 이력과 복구

1. PostgreSQL 부분 ignore의 entity 매핑 실패를 native 진단으로 분리하고 사용자 승인을 받아 조기 거부로 바꿨다.
2. ABI 갱신과 검사를 한 호출에 넣어 Gradle implicit dependency 오류가 발생했다. 갱신 후 별도 검사로 44/44 통과했다. build script 변경은 하지 않았다.
3. detekt가 반환문 수 4건·테스트 줄 길이 4건을 발견했다. 반환 표현식과 줄 나눔으로 수정하고 전체 검사를 재실행했다.
4. 기존 batch-core configuration cache 오류는 `--no-configuration-cache`로 분리했다. 범위 밖 수정이나 healthy Colima 재시작은 하지 않았다.

## 모듈별 6관점 리뷰

신규 agent 생성은 `agent thread limit reached`로 실패했다. 아래 6관점은 주 세션이
module slice별로 수행한 대체 검토이며 독립 reviewer 여섯 명의 결과로 표기하지 않는다.
기존 `issue803_plan_ops` agent를 재사용한 독립 최종 검토와 수정 후 재검토를 수신·통합했다.

각 셀은 최종 P0/P1/P2/P3 개수다. 해결된 항목은 아래 이력에 남긴다.

| 관점 | JDBC repository | R2DBC repository | JDBC writer | R2DBC writer | 근거 |
|---|---|---|---|---|---|
| 성능 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 기본 경로 비용 유지, bounded 수집, 수치 성능 주장 없음 |
| 안정성 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 한도·중복·취소 검증, 기존 transaction/catch/retry 정책 보존 |
| 보안 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | Long 계산, payload 없는 사전 오류, SQL 바인딩 유지 |
| 운영 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | DB/pool 소유권 변화 없음, false 복귀, 범위 밖 driver 제한 명시 |
| API | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 필수 Boolean·기존 descriptor 보존, requireLe 재사용, 직렬화 테스트 DTO |
| 호출자 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | 0/0/0/0 | README 양 언어·KDoc 일치, repository와 Unit writer ignore 구분 |

주 세션에서 해결한 P2: writer transaction KDoc의 ambient 경계 모호성, 취소 완료 대기 부재,
PostgreSQL 가정의 타 방언 확장, 너무 넓은 H2 예외 assertion, 테스트 DTO 관례 누락.
동작/방언 테스트 및 detekt를 재실행했다. 최종 주 세션 P0=0/P1=0, 미해결 P2/P3=0.
독립 reviewer는 P0/P1=0, P2 두 건(취소 대기 timeout 부재, rollback 예외 원인 확인 부족)을 발견했다.
세 대기 지점에 10초 제한을 추가하고 SQLException/R2dbcException 원인 체인의
`sqlState == "23505"`를 검사하도록 수정했다. 수정 후 독립 재검토는 추가 지적 없이 PASS다.
writer H2/PostgreSQL 28/28 및 detekt를 다시 실행해 통과했다(`BUILD SUCCESSFUL in 32s`).
주 세션의 안정성·API·호출자 관점도 재검토했다. 최종 통합 P0=0/P1=0/P2=0/P3=0.
exact-head CI는 PR 생성 후 별도 확인하며, 로컬 검증만으로 머지 준비를 주장하지 않는다.

## 제한 및 비목표

- SQLite 한도 분기는 source와 upstream 정합성만 확인했다. SQLite/MySQL/Oracle 신규 성공 계약은 미검증이다.
- 다중-bind 표현식, 더 작은 driver 한도, 실제 네트워크 왕복·처리량·메모리 benchmark는 미검증이다. 성능 개선 배수 주장은 없다.
- R2DBC 서버 실행 중 취소와 pool 반환 전체 증거는 #808, Spring Batch writer는 #805 범위다.
- 신규 의존성/모듈/스키마/CI 정책 변경이 없어 등록·migration·actionlint는 N/A다. CHANGELOG/릴리스 노트는 2.1.0 release 작업에서 취합한다.
- 1인 개발 저장소의 추가 인간 reviewer는 N/A다. 독립 검토와 exact-head CI 자체는 생략하지 않는다.
- 재발 방지 규칙: [교훈](../../lessons/2026-09-05-issue-803-multi-row-values.md).

## 문서 검증

SPW-01~05 PASS: 한국어 내부 문서와 영어/한국어 README의 독자 구분, source와 수치 대조,
자연스러운 한국어 검토, locale 예시 동등성, 링크·Markdown·terminology 검사를 수행했다.
terminology 도구는 변경 밖 R2DBC README 232행의 DB `snapshot` 표현 1건을 탐지했다.
이는 페이지 간 DB 일관성을 설명하는 기존 기술 용어여서 문맥 예외로 유지했다.
이번 신규/변경 문서 구간에는 미해결 용어 지적이 없다.

현재 전달 상태: PR 생성 전. CI, 머지 및 cleanup은 미완료다.
