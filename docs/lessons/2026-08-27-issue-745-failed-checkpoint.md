# Issue #745 FAILED checkpoint 보존 교훈

## Context

배치 청크 하나가 성공적으로 저장된 뒤 다음 청크에서 reader 또는 writer가
실패하면, 같은 jobName과 parameters로 재시작할 때 마지막 성공 keyset부터
이어져야 한다. JDBC와 R2DBC 저장소는 `StepReport`와 checkpoint JSON을 함께
저장하므로 두 adapter가 같은 재시작 계약을 지켜야 한다.

## Root cause

`BatchStepRunner`의 일반 `Throwable` 경로가 `FAILED` 보고서를 만들 때
checkpoint를 생략했다. JDBC와 R2DBC의 완료 갱신은 null checkpoint를 그대로
column에 대입했고, InMemory 저장소도 기존 값을 null로 바꿨다. 따라서 마지막
성공 청크의 checkpoint가 실패 상태 저장 중 사라졌다.

## Decision

1. 일반 실패도 reader의 마지막 checkpoint를 조회해 `FAILED` 보고서에 담는다.
   비취소 checkpoint 조회 실패는 원인 예외에 suppressed로 연결하고 null로
   남긴다.
2. 완료 보고서의 null checkpoint는 삭제 명령이 아니라 새 값이 없다는 의미로
   정의한다. 세 저장소는 기존 checkpoint를 유지하고, 새 값이 있을 때만
   직렬화·갱신한다.
3. 성공 청크 이후 실패, 실패 상태 저장, JSON 왕복, 동일 parameters 재시작을
   InMemory/JDBC/R2DBC 공통 회귀 테스트로 고정한다.
4. checkpoint 조회 자체가 `CancellationException`을 던지면 저장된 checkpoint를
   보존한 `STOPPED` 상태 저장을 먼저 시도하고, 원래 실패를 suppressed로
   연결한 뒤 취소를 다시 전파한다.
5. owner-aware checkpoint UPDATE 커밋과 갱신된 version 수신은
   `NonCancellable` 구간에서 완료한다. 커밋 직후 반환 전 취소가 발생해도
   runner가 stale version으로 `STOPPED` CAS를 시도하지 않도록 한다.

## Outcome

`BatchStepRunner`, InMemory/JDBC/R2DBC repository가 같은 null checkpoint 및
재시작 계약을 사용한다. 성공한 청크 뒤 실패한 실행은 마지막 성공 key를
보존하고, checkpoint 조회 중 발생한 취소도 `STOPPED` 상태와 lease 해제를
남긴 뒤 계속 전파한다. EN/KO batch manual,
core/JDBC/R2DBC manual, `CHANGELOG.md`에 이 계약을 반영했다.

## Miss and surprise

초기 구현 리뷰에서 일반 실패 경로의 checkpoint 조회가
`CancellationException`까지 `Throwable`로 처리하는 경로를 놓쳤다. 독립 운영
리뷰에서 이를 P1로 확인한 뒤 `STOPPED` 저장·lease 해제와 전용 취소 전파
회귀 테스트를 추가했다. 또한 core 테스트 runtime에 PostgreSQL/MySQL
driver가 없다는 점을 확인해 공통 H2 테스트와 adapter별 provider 통합
테스트를 분리했다. 후속 성능 리뷰에서는 checkpoint UPDATE 커밋 직후 반환 전
취소가 stale version을 만들 수 있음을 발견해, 갱신 version 수신까지
`NonCancellable`로 묶고 세 repository의 STOPPED·재claim 회귀를 추가했다.

## Verification

- production 수정 전 `./gradlew :bluetape4k-exposed-batch-core:test --tests
  'io.bluetape4k.batch.core.BatchFailurePersistenceTest' --no-build-cache`는
  기존 16건 통과, 새 InMemory/JDBC/R2DBC 회귀 3건 실패로 결함을 재현했다.
- 최종 `BatchFailurePersistenceTest`는 H2에서 26건, failure/error 0으로
  통과했다. commit-후-반환-전 취소 회귀는 InMemory/JDBC/R2DBC H2에서
  각각 STOPPED, checkpoint, lease 해제와 즉시 재claim을 검증한다. core runtime에
  provider driver를 추가하지 않고, JDBC/R2DBC
  adapter 통합 테스트의 새 `FAILED 청크 뒤 마지막 checkpoint를 보존하고
  재시작한다` 케이스가 H2·PostgreSQL·MySQL V8 각각에서 통과하도록 분리했다.
- `:bluetape4k-exposed-batch-core:test`, `:bluetape4k-exposed-batch-jdbc:test`,
  `:bluetape4k-exposed-batch-r2dbc:test`의 H2 전체 테스트와 JDBC/R2DBC
  provider 전체 테스트를 각각 순차 실행해 확인했다. 첫 R2DBC 통합 클래스
  실행에서 MySQL 동시 실행 테스트가 일시적으로 180초 timeout 되었지만,
  전체 MySQL 모듈 재실행은 66건 모두 통과했다.
- core/JDBC/R2DBC detekt, `git diff --check`, manual inventory 생성과
  `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json
  docs/manual/manifest.yaml`가 통과했다. 변경된 production/test 경로에는
  `println`, `System.out`, `System.err`, `printStackTrace`가 없다.
- 한국어 manual·lesson 용어 audit는 5개 파일, findings 0건이었다.
- 테스트는 `io.bluetape4k.assertions`의 `shouldBe`, `shouldBeEqualTo`,
  `shouldNotBeNull`, `assertFailsWith`를 사용하며 직접 표준 출력에 의존하지
  않는다.
- EN/KO batch, core, JDBC, R2DBC manual과 `CHANGELOG.md`의 실패 계약을 같은
  의미로 동기화했다.

## Evidence basis and traceability

- Issue contract: `https://github.com/bluetape4k/bluetape4k-exposed/issues/745`의
  FAILED checkpoint 보존, 동일 parameters 재시작, JDBC/R2DBC, EN/KO manual 및
  release-facing metadata 완료 조건을 기준으로 삼았다.
- Implementation: `BatchStepRunner.kt`의 FAILED checkpoint·취소 전파,
  `InMemoryBatchJobRepository.kt`, `ExposedJdbcBatchJobRepository.kt`,
  `ExposedR2dbcBatchJobRepository.kt`의 null 보존을 각각 회귀 테스트와
  대조했다.
- User-facing contract: `docs/manual/{en,ko}/modules/bluetape4k-exposed-batch*.md`
  8개와 `CHANGELOG.md`의 key 경계·null 의미를 코드와 다시 읽었다.
- Known gap: 외부 exactly-once side effect와 hosted exact-head CI는 이 로컬
  lesson의 검증 범위가 아니며 PR 단계에서 별도 확인한다.

## Future Guidance

- 실패 상태 완료 API에서 nullable field를 지울 수 있는 값으로 해석하지 말고,
  명시적인 clear 계약이 필요하면 별도 API와 회귀 테스트를 추가한다.
- reader checkpoint 조회 실패와 저장소 완료 갱신 실패를 서로 다른 suppressed
  원인으로 관찰하고, 조회 취소 시에도 `STOPPED`와 lease 해제를 확인한다.
- JDBC와 R2DBC는 동일한 재시작 fixture를 유지하고 H2 뒤 PostgreSQL, MySQL을
  직렬로 실행해 driver별 null update 차이를 놓치지 않는다. 일시적 timeout은
  동일 명령 재실행으로 안정성을 확인하고 최종 증거에 남긴다.

## SPW 체크리스트

- [x] **SPW-01** Issue #745, core/JDBC/R2DBC 소스, EN/KO manual, `CHANGELOG.md`를
  audience와 목적의 근거로 고정했고 exactly-once와 hosted CI를 미검증 범위로
  명시했다.
- [x] **SPW-02** Context, Root cause, Decision, Outcome, Miss and surprise,
  Verification, Evidence basis, Future Guidance 구조로 lesson 계약을 충족했다.
- [x] **SPW-03** 한국어 기술 문체와 `FAILED`, `checkpoint`, `key`, `parameters`,
  `CancellationException` 용어를 고정하고 audit 결과 5 files/findings 0건을
  기록했다.
- [x] **SPW-04** Issue 완료 조건을 runner/repository/test/manual/changelog
  근거에 매핑하고 core H2 회귀와 JDBC/R2DBC H2·PostgreSQL·MySQL provider
  회귀 및 known gap을 구분했다.
- [x] **SPW-05** 최종 Markdown을 다시 읽고 명령·숫자·경로·URL·코드 토큰을
  확인했으며, 위 Verification과 Evidence basis에 read-back 결과를 기록했다.
