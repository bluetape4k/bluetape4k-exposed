# Issue #637 R2DBC `transactionManagerRef` 계약 정렬 최종 리뷰

## 검토 범위와 기준

- 기준: branch `fix/issue-637-r2dbc-transaction-manager-contract`, base
  `develop`, 구현 기준 commit `1cbf6b912d98a6f0df762998c9d8e33aafc0f5e5`와
  이후 working-tree diff
- 모듈 slice: `spring-boot/r2dbc`
- 변경 범위: annotation KDoc/deprecation, repository configuration extension의
  등록 단계 검증, registrar/extension 회귀 테스트, EN/KO README, issue
  spec/plan
- 제외: repository factory 실행 경로, `R2dbcDatabase` 수명주기, `docs/manual/**`
  1.12.1 릴리스 고정 문서, CI workflow와 dependency/catalog

## Six-perspective 결과

| 우선순위 | 관점 | 근거 | 판정 및 후속 조치 |
| --- | --- | --- | --- |
| N/A | Performance | production diff는 저장소 호출 hot path가 아니라 annotation registration의 상수 비교 한 건이다. 대상 모듈 `test`와 Kover가 통과했다. | 추가 benchmark 불필요. N/A |
| PASS | Stability | `postProcess`는 custom 설정만 등록 시 거부하며 기존 factory/repository/Flow/취소 경로를 변경하지 않는다. 대상 모듈 260 tests 중 2 skipped, Kover 통과. | P0=0, P1=0 |
| N/A | Security | 새 입력은 annotation 문자열이고 외부 실행·SQL·자격 증명·직렬화 경로가 없다. 오류 메시지는 입력값과 사용 API만 포함한다. | 보안 전용 수정 불필요. N/A |
| PASS | Operator/Ops | 애플리케이션 소유 `R2dbcDatabase`와 리소스 수명주기를 유지한다. rollback은 두 Kotlin 파일과 README/test의 좁은 revert로 가능하며 1.12.1 manual은 diff에 없다. | P0=0, P1=0 |
| PASS | Developer/API | `transactionManagerRef` 이름과 `springTransactionManager` default를 유지하고 deprecation만 추가했다. reflection, direct extension, 실제 registrar test가 계약을 고정한다. | P0=0, P1=0 |
| PASS | User/Caller | EN/KO README와 annotation KDoc가 custom 값의 등록 거부와 `suspendTransaction(database)`/`streamAll(database)` migration을 동일하게 설명한다. | P0=0, P1=0 |

## Lane 운영 기록

독립 lane으로 `code-reviewer`(developer/API·user/caller), `architect`
(performance/stability·security/Ops), `verifier`(test/evidence·release/docs)를
bounded read-only로 요청했다. runtime lane은 P0=0/P1=0 중간 결과를 회신했다.
나머지 두 lane은 bounded wait 동안 최종 mailbox 결과를 제출하지 않아 중단했고,
해당 두 관점은 위 표에서 main-session fallback으로 동일 diff와 fresh test
evidence를 재검토했다. 이 운영 gap은 구현 결함이 아니라 review 실행 상태이며,
최종 판정은 main integration이 소유한다.

## Main-session integration

1. `repository candidate == 0`이면 Spring Data가 repository bean을 만들지 않아
   `postProcess`가 호출되지 않을 수 있다는 가능성을 검토했다. 이 경우 custom
   설정이 실행 경로를 만들지 않으므로 새 동작을 우회하는 런타임 결함이 아니다.
   repository가 실제로 발견되면 registrar test와 동일하게 등록 단계에서 거부된다.
2. `suspendTransaction(database)`는 저장소 호출 바깥의 공유 트랜잭션 경계를,
   `streamAll(database)`는 stream 자체의 DB 선택을 담당한다. 기존
   `SimpleExposedR2dbcRepositoryTest`의 explicit database/Flow/rollback 테스트와
   대상 모듈 전체 테스트가 이 의미를 확인하므로 추가 API bridge는 만들지 않는다.
3. public KDoc은 한국어이며 README 두 locale의 changed paragraph는 API 이름,
   오류 경계, migration 선택을 일치시킨다. `CHANGELOG`와 release note는
   1.13.0 publish 범위가 아니므로 변경하지 않는다.
4. concurrency quick scan에서 변경 파일에는 `GlobalScope`, `runBlocking`,
   `Thread.sleep`, `synchronized`가 없었다. 기존 factory의 `runCatching`은
   Java default method handle binding 오류를 `IllegalStateException`으로
   감싸는 기존 경로이며 본 diff와 무관하다.

## 최종 수렴

- P0: 0
- P1: 0
- P2: 0 (runtime lane의 candidate-zero/stream-boundary는 근거 확인 후
  신규 finding으로 승격하지 않음)
- P3: 0
- 판정: `PASS` — 구현·테스트·문서·release/manual 경계가 승인된 option 2와
  일치한다.

## Writer gate

- `SPW-01`: PASS — issue, branch/base, module slice, changed paths와 근거를
  고정했다.
- `SPW-02`: PASS — six-perspective 표, lane 운영 상태, integration, severity,
  disposition, verdict를 포함했다.
- `SPW-03`: PASS — 한국어 기술 문체를 적용하고 API·명령어·식별자를 보존했다.
- `SPW-04`: PASS — source/test/README/manual 범위를 fresh evidence와 대조했다.
- `SPW-05`: PASS — Markdown read-back에서 표, code span, 목록, 수치와 경로를
  확인했다.
