# #844: StarRocks DDL 정제는 SQL 구조 밖의 키워드를 변경하지 않는다

## 배경

StarRocksTable은 Exposed가 생성한 CREATE TABLE에 남은 generic
PRIMARY KEY·nullable clause를 정리하고, 실제 ENGINE 절이 없을 때 고정된
ENGINE=OLAP PROPERTIES ("replication_num" = "1")을 추가한다. 기존 구현은
SQL 전체에 정규식 치환과 contains("ENGINE=") 검사를 적용했다.

그 결과 다음처럼 SQL 구조가 아닌 원문이 DDL 제약으로 오인될 수 있었다.

- DEFAULT 'x NULL PRIMARY KEY ENGINE=fake'의 기본값이 변경됨
- -- NULL PRIMARY KEY ENGINE=fake와 /* ... */ 주석이 변경됨
- 'PRIMARY KEY'와 'quoted NULL' 같은 backtick quoted identifier가 변경됨
- 문자열의 ENGINE=fake 때문에 실제 기본 ENGINE 절이 누락됨

이 lesson의 구현 범위는 #844의 StarRocks sanitizer와 회귀 테스트다.
ClickHouse 모듈 수정, 새 의존성, 공개 API 변경은 포함하지 않는다.

## 결정

- 문자열 리터럴, single quote·double quote·backtick으로 감싼 영역과 line/block
  comment는 같은 길이의 mask로 검색에서만 제외한다. mask를 결과로 반환하거나
  문자열·주석의 원문, 공백, escape를 재인코딩하지 않는다.
- 결과는 항상 원본 SQL에서 실제 구조 구간만 제거해 만든다. 따라서 문자열과
  주석 안의 NULL, PRIMARY KEY, ENGINE=은 입력과 동일하게 보존해야 한다.
  이것을 문자열·주석 원문 변형 금지 불변 조건으로 둔다.
- 첫 번째 괄호 깊이의 실제 PK·nullable clause만 보정하고, DEFAULT NULL과
  기존 NOT NULL 계약은 보존한다. 기본값 식 내부의 중첩 괄호도 nullable
  clause 검색 대상이 아니다.
- ENGINE은 mask된 SQL에서 괄호 깊이 0의 ENGINE\s*=만 실제 절로 판정한다.
  문자열·주석·컬럼 기본값의 ENGINE=fake는 기본 engine을 생략할 근거가 아니다.
- ClickHouse scanner는 private 구현이므로 공통 모듈로 추출하거나 API를
  노출하지 않고, StarRocks 파일 내부에 동일 길이 mask 원리를 국소 적용한다.

## 결과

- exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/StarRocksTable.kt
  - quote/comment-aware mask와 괄호 깊이 기반 제약 검색을 추가했다.
  - 기존 options·storageParameters fail-fast와 고정 StarRocks engine 계약을
    유지했다.
  - 공개 시그니처와 의존성은 변경하지 않았다.
- exposed/starrocks/src/test/kotlin/io/bluetape4k/exposed/starrocks/StarRocksDdlSanitizerTest.kt
  - 문자열·주석·quoted identifier 보존
  - 실제 PK·nullable clause 제거
  - fake ENGINE 오인 방지 및 기본 engine 추가
  - 실제 ENGINE 중복 방지
    시나리오를 회귀 테스트로 고정했다.

## 검증

- TDD RED:
  - 명령: ./gradlew :bluetape4k-exposed-starrocks:test --tests 'io.bluetape4k.exposed.starrocks.StarRocksDdlSanitizerTest'
  - 로그: /tmp/issue-844-red.log
  - compile 성공, 4 tests completed, 3 failed, 1 passing, exit 1
  - 기존 전역 치환이 quoted identifier·DEFAULT literal·주석의 키워드를 훼손하고
    fake ENGINE을 실제 절로 오인한다는 실패를 확인했다.
- TDD GREEN:
  - 동일 명령, 로그 /tmp/issue-844-green.log
  - 4 passing, BUILD SUCCESSFUL, exit 0
  - XML exposed/starrocks/build/test-results/test/TEST-io.bluetape4k.exposed.starrocks.StarRocksDdlSanitizerTest.xml:
    tests=4, skipped=0, failures=0, errors=0
- git diff --check와 변경 파일 trailing-whitespace 검사를 통과했다.
- 최종 통합 검증: escaped/doubled quote 회귀를 추가한 순수 sanitizer 5개를 포함해
  StarRocks 모듈 전체 41개가 실패·오류·skip 없이 통과했다. 기존 서버 기반
  DDL 테스트도 포함한다. 모듈 `detekt`와 `checkKotlinAbi`가 통과했다.

## 향후 지침

- DDL 변환 정규식을 추가할 때는 먼저 키워드가 포함된 문자열 리터럴,
  escape/doubled quote, line/block comment, quoted identifier, DEFAULT 식을
  RED로 고정한다.
- 문자열·주석의 내용을 정규식으로 수정하거나 mask 문자열을 결과 SQL로
  반환하지 않는다. 검색 projection과 출력 원문을 분리한다.
- 실제 DDL 구조를 판정할 때는 적어도 인용 영역 mask와 괄호 깊이를 함께 사용하고,
  실제 생성 SQL과 수동 대표 SQL을 모두 비교한다.
- 전체 StarRocks 테스트를 실행할 때도 순수 sanitizer 회귀 테스트의 5/5 통과와
  서버 기반 DDL 검증을 별도 증거로 기록한다. 한쪽 결과를 다른 쪽의 대체 근거로
  간주하지 않는다.
