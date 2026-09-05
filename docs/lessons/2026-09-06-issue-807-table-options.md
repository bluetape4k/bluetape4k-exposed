# #807 custom DDL의 generic 옵션 경계

## 배경과 결정

기준은 Exposed `1.5.0`, 저장소 base `e20f0b1239affeb1c702424238f9643a906189b5`다.
`StarRocksTable`과 `ClickHouseTable`은 upstream SQL을 정제하고 자체 engine 절을
붙인다. `Table.options`·`storageParameters`가 dialect의 지원을 확인해 준다고
가정하면 MySQL engine이나 PostgreSQL WITH 절이 다른 DB의 DDL에 남을 수 있다.

- [Table의 옵션 API와 렌더링](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Table.kt)
- 확인일: 2026-09-06 KST. 공식 태그 소스를 직접 조회했고, 로컬 resolved JAR의
  nested `Table.TableOption`·`Table.TableStorageParameter`와 대조했다.
- `createStatement`는 옵션의 `toSQL()` 결과를 그대로 연결하며 dialect별 allowlist가 없다.
- 내장 engine은 MySQL 계열이며 OLAP/MergeTree로 변환할 계약이 없다.

두 custom table은 비어 있지 않은 generic 옵션 목록을 SQL 생성 전에
`IllegalArgumentException`으로 거부한다. raw·사용자 정의 옵션도 동일하며
`toSQL()`은 호출하지 않는다. 문자열 검사로 dialect 호환성을 추측하거나
PostgreSQL storage parameter를 ClickHouse settings/StarRocks properties로 변환하지 않는다.
일반 `Table`의 동작과 기존 custom engine DSL은 변경하지 않는다.

## 검증과 재발 방지

- TDD: transaction 없이 호출한 20건이 수정 전 `Expected IllegalArgumentException but got
  IllegalStateException`으로 실패했다. 이는 옵션 검증보다 transaction 조회가 먼저
  실행된다는 증거다. 수정 후 20건 모두 통과했다. 이 RED는 DB의 SQL 거부를 재현한
  결과가 아니라 **렌더링 전 입력 검증 순서**의 회귀 증거다.
- H2의 빈 옵션 DDL과 PostgreSQL `USING heap WITH (fillfactor=70,
  autovacuum_enabled=false)` 전체 SQL을 비교하고 실제 테이블을 생성했다.
  PostgreSQL `pg_class.reloptions`도 확인했다. H2 1건, PostgreSQL 선택 행렬 2건 통과.
- ClickHouse 전체 148건과 detekt·ABI 검사가 통과했다. 기존 engine/order-by/settings
  생성·실행 경로와 신규 거부 테스트를 함께 포함한다.
- StarRocks 최종 전체 실행은 36건 통과, 실패·건너뜀 0건이며 detekt·ABI 검사도 통과했다.
- JDBC H2 전체 실행은 235건 중 210건 통과, 기존 비활성/다른 DB 전용 25건 건너뜀,
  실패 0건이다. 신규 native 옵션 테스트는 실행·통과했고 detekt·ABI 검사도 통과했다.
  건너뛴 테스트를 통과로 계산하지 않는다. PostgreSQL 전체·MySQL 전체·CI는 이번 검증 범위 밖이다.
- 새 테스트의 긴 annotation 행이 detekt에서 실패했다. 줄을 나누고 동일 검사를
  재실행했다. 이후에는 parameterized test 데이터도 정적 검사 범위에 포함한다.
- StarRocks의 PK 컬럼에 `NOT NULL`이 남는다고 예상한 SQL 비교 검증은 실패했다.
  기존 renderer를 변경하지 않은 빈 옵션 경로에서 실제 SQL은 `id BIGINT`였다.
  PK 문법 제거와 nullability 정제는 서로 다른 단계이므로 기존 SQL을 확인한 뒤
  SQL 기대값을 고정한다. 이번 변경은 기존 PK/nullability 의미를 수정하지 않는다.
- 첫 StarRocks 전체 실행은 호스트 mapped port의 MySQL handshake에서 정지했다.
  컨테이너 내부 loopback·bridge 주소의 `SELECT 1`은 성공했고, 호스트 greeting은
  3초 진단 제한을 넘겼다. 해당 test worker만 SIGTERM으로 종료한 뒤 새 컨테이너로
  실행하자 DDL 테스트까지 진행했다. Docker 전체 재시작이나 무조건 반복은 하지 않는다.
- workflow 초기화에서 상대 owner 경로를 잘못 전달했다. helper가 안전하게 거부했고,
  worktree `.bluetape/handles` 아래 절대 경로로 다시 초기화했다. owner 경로는
  호출 cwd가 아닌 state-root 기준으로 확인한다.

## 호환성과 한계

generic 옵션을 지정하던 custom table 호출자는 이제 명시적인 오류를 받는다.
현재 검증된 generic 옵션 allowlist는 없으며, 지원 기능이 필요하면 해당 DB의 문법·
실제 생성·입력 검증을 함께 증명한 뒤 별도 변경으로 추가한다. subclass가
`createStatement` 자체를 재정의하면 그 SQL의 책임은 해당 subclass에 있다.

상속한 dialect는 지원 기능의 증거가 아니다. 새 옵션 경로를 추가할 때에는 빈 옵션의
기존 DDL, 지원 옵션의 보존, 미지원 옵션의 명시적 거부를 각각 테스트한다.
Exposed는 옵션 변경을 migration diff로 추적하지 않으므로, 기존 테이블은 별도 수동
migration과 데이터 보존·복구 검증이 필요하다. 새 의존성·catalog 변경은 없다.

## 최종 리뷰

- 독립 코드 리뷰는 응답 지연으로 중단했으며 성공으로 처리하지 않았다. 사용자 지정
  fallback에 따라 메인 세션에서 변경 파일 전체를 inline 리뷰했다. P0/P1 발견 0건.
- 입력 검증 순서: `ClickHouseTable.kt:36`, `StarRocksTable.kt:22`에서 목록 검사가
  `super.createStatement()`보다 먼저 수행된다. 사용자 renderer 실행·SQL 삽입 경로가 없다.
- 빈 옵션 호환성: 기존 정제/engine 코드가 그대로이며 반복 렌더링과 실제 DB 생성·삭제로 검증했다.
- API/ABI: 공개 시그니처와 의존성 변경은 없으며 세 모듈의 ABI 검사를 통과했다.
  비어 있지 않은 옵션의 동작 변경은 양 언어 README와 KDoc에 명시했다.
- 독립 설계 리뷰 `issue807_architecture_retry`는 `CLEAR`다. 미지원 옵션의 일괄 거부는
  이슈 수용 조건에 맞고 기존 engine 계약을 보존하며 설계 차단 사항이 없다고 확인했다.
- 문서 검토: 유지보수자 대상, 공식 태그/로컬 검증 근거, 지원 정책·수동 migration,
  양 언어 내용 일치, 한국어 용어·자연스러움의 다섯 항목을 확인했다.
