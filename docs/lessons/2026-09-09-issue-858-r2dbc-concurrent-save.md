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
- 수정 후 대상 테스트 H2/PostgreSQL 통과. barrier가 여섯 worker의 도달을
  bounded하게 맞추고 `peakWorkers == 6`을 확인해 실제 동시 진입을 고정한다.
- 대상 테스트 10회 순차 실행: 9회 테스트 PASS, 1회는 테스트 전 Dokka plugin
  초기화 오류(`kotlinx/serialization/StringFormat`)로 실패했고 동일 명령 재실행은
  PASS. `prepared statement` 오류는 관찰되지 않음.
- Spring Boot R2DBC 모듈 전체 232 tests, failures 0, errors 0, skipped 0.
- `detekt` 및 `checkKotlinAbi` 통과.

## 독립 리뷰와 fallback

exact HEAD `5cde8896`에 대한 독립 리뷰는 P0/P1/P2/P3 코드 이슈를 찾지
않았지만, 리뷰 환경에서 `lsp_diagnostics`를 사용할 수 없고 Kotlin LSP도
JDK 호환성 문제로 종료되어 엄격한 게이트 판정은 `REQUEST CHANGES`였다.
이에 inline fallback으로 최종 diff의 source/caller 경로, 테스트 oracle과
동시성 barrier, ABI 및 문서/CI 범위를 다시 대조했다. 별도 코드 결함은 없었고,
compileTestKotlin·detekt·checkKotlinAbi를 실행 가능한 정적 검증으로 유지한다.
LSP 및 호스팅 CI 결과는 PR 게이트에서 확인할 미해결 검증 공백으로 남긴다.

## 향후 지침

R2DBC 경쟁 테스트에서 `withTables` callback 내부에 worker를 직접 시작하지
않습니다. fixture transaction은 schema lifecycle에만 사용하고, repository 호출은
top-level `suspendTransaction` 경로에서 실행합니다. 실패 재시도나 Full Nightly
성공만으로 connection/transaction 공유 문제가 해결되었다고 판단하지 말고,
worker의 transaction context와 실제 driver 오류를 별도로 관찰합니다.
