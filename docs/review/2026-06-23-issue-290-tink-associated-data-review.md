# 리뷰: 이슈 #290 Tink 연관 데이터 바인딩

## 범위

- 모듈: `:bluetape4k-exposed-tink`
- 변경 사항: AEAD/DAEAD 암호화 컬럼의 암호문을 연관 데이터에 바인딩한다.
- 검토 파일: Tink 컬럼 타입, 테이블 확장 헬퍼, 테스트, README 문서.

## 검토 결과

### P1: 공개 컬럼 생성자를 직접 사용하면 빈 연관 데이터가 명시되지 않은 채 유지됨

첫 구현에서는 `Table.tinkAead*`와 `Table.tinkDaead*` 헬퍼 경로를 테이블+컬럼 연관 데이터에 바인딩했지만, 빈 연관 데이터를 명시 없이 사용하는 공개 no-AD 컬럼 생성자는 그대로 두었다. 따라서 `registerColumn(..., Tink*ColumnType(...))`을 직접 사용하는 경우 기존 재생/복호화 취약점에 계속 노출되었다.

조치:

- no-AD 컬럼 생성자를 사용 중단 예정이자 마이그레이션 전용으로 표시했다.
- 생성자를 직접 사용할 때의 동작과 권장 헬퍼/명시적 AD 경로를 문서화했다.
- 명시적 연관 데이터를 전달하고 컬럼 간 암호문 재생을 거부하는 직접 `registerColumn` 회귀 테스트를 추가했다.

## 판정

수정 후 승인한다. P0/P1 이슈는 해결되었다.

## 검증 근거

- RED: 구현 전에는 새 컬럼 간/테이블 간 재생 테스트가 `Expected Exception but no exception was thrown` 오류로 실패했다.
- 수정 후 표적 검증: H2, PostgreSQL, MySQL V8에 걸쳐 연관 데이터 테스트 9개가 통과했다.
- 전체 모듈: `./gradlew :bluetape4k-exposed-tink:test :bluetape4k-exposed-tink:build detekt --no-build-cache`
  - `157 passing`
  - `BUILD SUCCESSFUL`
  - `:detekt NO-SOURCE`
- 패치 형식 검사: `git diff --check` 명령이 통과했다.
