# #858 R2DBC 경쟁 save fixture 격리 교훈

## 배경

`SimpleExposedR2dbcRepositoryTest`의 경쟁 save 테스트가 `withTables`가 연
fixture 트랜잭션 안에서 `SuspendedJobTester`를 실행하고 있었습니다. 코루틴
worker가 Exposed의 transaction context를 상속하면서 여섯 worker가 같은
`R2dbcTransaction`을 공유했고, PostgreSQL에서 `prepared statement "S_5" already
exists`가 간헐적으로 보고될 수 있는 경로가 만들어졌습니다.

## 결정 또는 발견

production repository 코드는 변경하지 않고, 테스트 fixture의 schema setup/cleanup과
경쟁 repository 호출을 분리했습니다. `withTopLevelUsers`로 테이블을 fixture
트랜잭션 밖에서 준비한 뒤 각 worker가 `save`의 독립 transaction을 시작하도록
했습니다. worker 진입 시 `TransactionManager.currentOrNull()`이 null인지 함께
검증해 외부 transaction context 재상속을 방지합니다.

## 결과

호출 경로(`save → persist → findRowById`)는 그대로 유지하면서 fixture가 production
repository 경로를 오염시키지 않게 되었습니다. 이 수정은 #839/#855의 SQL binder
변경과 무관한 테스트 격리 문제만 다룹니다.

## 검증

- 의도적으로 기존 `withTables` 구조에서 RED를 확인: H2/PostgreSQL 모두 6개
  worker가 동일한 `R2dbcTransaction`을 상속.
- 수정 후 대상 테스트 H2/PostgreSQL 통과.
- 대상 테스트 10회 순차 실행: 9회 테스트 PASS, 1회는 테스트 전 Dokka plugin
  초기화 오류(`kotlinx/serialization/StringFormat`)로 실패했고 동일 명령 재실행은
  PASS. `prepared statement` 오류는 관찰되지 않음.
- Spring Boot R2DBC 모듈 전체 232 tests, failures 0, errors 0, skipped 0.
- `detekt` 및 `checkKotlinAbi` 통과.

## 향후 지침

R2DBC 경쟁 테스트에서 `withTables` callback 내부에 worker를 직접 시작하지
않습니다. fixture transaction은 schema lifecycle에만 사용하고, repository 호출은
top-level `suspendTransaction` 경로에서 실행합니다. 실패 재시도나 Full Nightly
성공만으로 connection/transaction 공유 문제가 해결되었다고 판단하지 말고,
worker의 transaction context와 실제 driver 오류를 별도로 관찰합니다.
