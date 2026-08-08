# 계획 — 이슈 #256 Druid JDBC 쿼리 전용 실험

## 작업

1. 모듈과 의존성 별칭을 등록한다.
   - `exposed/druid/build.gradle.kts`를 추가한다.
   - Avatica catalog 별칭을 추가한다.
   - 기존 `settings.gradle.kts` 자동 검색으로 `:bluetape4k-exposed-druid`를 등록한다.
2. 쿼리 전용 API를 구현한다.
   - `DruidConnectionOptions`가 공식 Avatica JDBC URL과 Properties를 만든다.
   - `DruidJdbc`가 `connection`, `query`, `queryList`, `listColumns` 헬퍼를 제공한다.
   - Exposed dialect, DDL, DML, DAO, repository, migration API는 추가하지 않는다.
3. 테스트와 smoke 계약을 추가한다.
   - 단위 테스트에서 옵션 검증, properties, 쿼리 전용 SQL 형태를 검증한다.
   - fixture datasource를 사용할 수 있고 `EXPOSED_DRUID_SMOKE=true`일 때 환경으로 제어되는
     smoke 테스트에서 연결, 메타데이터 검색, SELECT를 검증한다.
4. 사용자 문서를 추가한다.
   - `exposed/druid/README.md`와 `README.ko.md`를 갱신한다.
   - 루트 README 모듈 표와 AGENTS 모듈 목록을 갱신한다.
5. CI/Nightly를 등록한다.
   - path-filter 출력과 전용 직렬 `test-druid` job을 추가한다.
   - coverage/CI 상태 집계의 needs를 추가한다.
6. 검증한다.
   - `./gradlew --no-parallel :bluetape4k-exposed-druid:compileTestKotlin :bluetape4k-exposed-druid:test`.
   - `./gradlew projects`로 모듈 검색을 확인한다.
   - workflow를 편집하면 `actionlint`를 실행한다.
   - `git diff --check`, `gno update`.
7. 리뷰, lessons, 커밋, PR, CI, 병합을 진행한다.
