# Issue #637 R2DBC 설정 계약 lesson

## 상황

`@EnableExposedR2dbcRepositories`의 `transactionManagerRef`는 공개 속성이지만,
R2DBC repository factory는 Spring transaction interceptor와 별개로 Exposed
`suspendTransaction`을 직접 호출한다. 속성 이름만 보면 Spring transaction
manager 또는 `R2dbcDatabase` 선택이 가능하다고 해석할 수 있었다.

## 결정

ABI와 기본값을 유지하면서 속성을 deprecated 처리하고, 기본값이 아닌 값을
repository 등록 단계에서 `IllegalArgumentException`으로 거부한다. 다중 DB는
명시적 `suspendTransaction(database)` 또는 `streamAll(database)`로 표현한다.
새 Spring transaction manager/R2dbcDatabase bridge는 리소스 소유권과 실행 경계를
확장하므로 이번 범위에서 거부했다.

## 결과와 검증

- 실제 registrar 경로와 direct extension 경로 모두 custom 값을 거부한다.
- reflection test가 `springTransactionManager` annotation default를 고정한다.
- 대상 모듈 전체 test는 260 tests, 2 skipped로 통과했다.
- `detekt`, `koverVerify`, targeted 4-test run, `git diff --check`가 통과했다.
- EN/KO README는 동일한 migration 경계를 설명하고 `docs/manual/**` 1.12.1
  문서는 변경하지 않았다.

## 놓치기 쉬운 점

등록 설정의 이름이 실행 경계를 보장하지 않는다면, 속성을 제거하기 전에 실제
factory/transaction 소유권을 확인해야 한다. source/binary ABI를 유지해야 하는
릴리스에서는 deprecation, 등록 단계 fail-fast, 명시적 대체 API, locale parity를
한 묶음으로 검증한다.

## 다음 guard

새 Spring Data annotation 속성을 추가할 때는 다음을 checklist에 포함한다.

1. registrar가 속성을 실제 factory bean 또는 transaction 경계로 전달하는가?
2. 전달하지 않는다면 등록 단계에서 unsupported 값을 차단하는가?
3. annotation default와 deprecation 상태를 reflection/API 검사로 고정했는가?
4. public KDoc, EN/KO README, migration example이 동일한 책임 경계를 설명하는가?

## Writer gate

- `SPW-01`: PASS — 현재 source, issue, 실행 결과와 lesson 범위를 고정했다.
- `SPW-02`: PASS — context, decision, outcome, verification, surprise, future
  guard를 포함했다.
- `SPW-03`: PASS — 한국어 technical register와 식별자 보존을 확인했다.
- `SPW-04`: PASS — test/Detekt/Kover/diff/manual evidence를 대조했다.
- `SPW-05`: PASS — Markdown read-back을 완료했다.
