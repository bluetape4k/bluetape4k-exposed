# #828 persisted hook 종료 검증 리뷰

## 범위와 판정

`develop@fe7724304a1ffd744fa2e6aa6863a6c775ce0402` 대비
`JdbcCaffeinePersistedHookTest.kt`의 diff를 native `code-reviewer`가 읽기 전용으로 검토했다.
독립 판정은 APPROVE, P0=0/P1=0이다. reviewer는 테스트·LSP를 실행하지 않았다.

- 66~67행: `join(1_000)` 뒤 실제 종료 여부를 검사하므로 latch 알림과 종료 사이의 경쟁 조건을 제거한다.
- 68~79행: hook 해제 전 상태 확인, finally의 해제·interrupt·5초 join과 invalidation 검증은 유지된다.
- production·API·의존성 변경은 없다. 새 false-positive는 발견되지 않았다.
- 별도 Thread의 close 예외를 직접 전파하지 않는 기존 한계는 남지만 이번 변경으로 악화되지 않는다.

## main session의 검증

공통 옵션은 `--no-configuration-cache --no-build-cache --no-parallel --no-daemon`이다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-caffeine:cleanTest :bluetape4k-exposed-jdbc-caffeine:test --tests '*JdbcCaffeinePersistedHookTest' --no-configuration-cache --no-build-cache --no-parallel --no-daemon
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-caffeine:cleanTest :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-jdbc-caffeine:detekt --no-configuration-cache --no-build-cache --no-parallel --no-daemon
./gradlew :bluetape4k-exposed-jdbc-caffeine:detekt --no-configuration-cache --no-parallel --no-daemon
```

- 대상 테스트: 6개 성공, BUILD SUCCESSFUL in 35s.
- 전체 H2: 181개 중 178개 성공, 1개 실패, 기존 제외 2개. 수정 대상 테스트는 성공했다.
- 전체 테스트 실패로 detekt가 실행되지 않아 별도 실행했고 BUILD SUCCESSFUL in 49s를 확인했다.
- 기존 `SuspendedJdbcCaffeineRepositoryExtraTest.kt:913` unchecked cast 경고는 변경 범위 밖이다.
- 실제 컴파일·테스트·detekt로 검증했으며 IDE/LSP 검증으로 표기하지 않는다.

## PR 생성 차단 근거

변경하지 않은 `JdbcCaffeineRepositoryExtraTest.kt:738`의
`close - accepted put blocked before cache mutation cannot repopulate after timeout()`이
5초 timeout으로 실패했다. XML의 suppressed `InterruptedException`은 788행의
`dbWriteCompleted.await(5, TimeUnit.SECONDS)`를 가리킨다.

이 latch는 `BlockingCachePutJdbcRepository.afterPersisted()`의 1312행에서 감소한다.
그러나 production의 `settleSuccessfulWriteBehindBatch()`는 close timeout 이후
`writeBehindLateSideEffectGuard`가 활성화되면 hook을 호출하지 않는다.
테스트는 DB 쓰기 완료를 hook 호출과 동일시한다. 따라서 50ms close 제한 안에
hook이 예약되지 않으면, DB가 쓰였더라도 테스트의 latch는 해제되지 않을 수 있다.
이는 실패 로그와 소스를 연결한 원인 가설이며, 별도 테스트에서 결정적으로 재현한 결과는 아니다.

최초에는 승인 범위 밖이어서 수정을 보류했고, 사용자의 `확대해` 승인 후 순서를 수정했다.
hook 완료를 close 전에 확인하되 캐시 put 차단은 유지한다.
따라서 worker의 hook 호출 여부에 따라 달라지던 대기를 제거하면서 admission timeout 검증은 보존한다.
아래 후속 검증으로 최초 실패의 해결 여부를 판단하며, 재시도 성공으로 덮지 않는다.

## 문서·Kotlin 검토

기존 Kluent assertion과 Thread/join을 재사용하고 새 의존성·production monitor·예외 삼킴은 추가하지 않았다.
문서는 한국어 유지보수 독자를 대상으로 실제 CI XML, 소스 diff, 로컬 검증 출력과 독립 판정을 구분했다.
SPW-01~SPW-05 및 KO-01~KO-06 기준으로 범위·수치·식별자·링크·검증 한계를 대조했다.
실행 증거와 가설을 분리하고 전체 검증 실패 및 미실행 전달 단계를 명시했다.

## 확대 수정 후 검증

`--tests '*JdbcCaffeinePersistedHookTest'`와
`--tests '*close - accepted put blocked before cache mutation cannot repopulate after timeout*'`를
함께 지정한 fresh H2 실행에서 7개가 모두 성공했다(BUILD SUCCESSFUL in 49s).
실패했던 테스트의 대기 순서를 수정한 뒤 얻은 결과이며 무수정 재시도 결과가 아니다.

세 문서의 용어 검사 결과는 findings=0이다. SPW-01~05와 KO-01~07을 확대 범위에도 적용했다.
`gno update`는 성공했지만 `bluetape4k-docs`는 `.worktrees`를 제외하므로
이번 신규 교훈의 검색 노출을 증명하지 않는다. 교훈 파일은 PR에 포함하고,
통합 후 CG-18에서 인덱스 갱신·검색 노출을 확인한다. 인덱스 제외 설정은 변경하지 않는다.

전체 H2 후속 실행은 179개 성공·기존 제외 2개, 실패 0개이며 detekt도 통과했다
(BUILD SUCCESSFUL in 1m 38s). 두 수정 대상 모두 전체 모듈 안에서도 성공했다.

## 확대 diff의 inline fallback review

독립 reviewer에게 두 테스트의 재리뷰를 요청했으나 제한 시간 안에 사용 가능한 판정이 없어 중단했다.
워크스페이스 지침에 따라 main session이 같은 base 대비 exact diff를 직접 검토했다.
최초 단일 파일의 독립 APPROVE를 확대 diff의 독립 검증으로 재사용하지 않는다.

- `JdbcCaffeineRepositoryExtraTest.kt:779-785`: hook을 기다리는 동안 cache put은 아직 차단되어 있다.
  따라서 DB 반영이 완료돼도 admission은 남으며 close의 50ms timeout 원인은 유지된다.
- `:782-783`: 완료 listener 등록은 기존 `Job.invokeOnCompletion`을 유지하여 완료를 놓치는 별도 latch 경쟁을 추가하지 않는다.
- `:788-820`: 잘못된 캐시 값 반영, 읽기 결과, TIMEOUT 예외, 캐시 제거와 finally 정리를 그대로 검사한다.
- `JdbcCaffeinePersistedHookTest.kt:66-75`: bounded join과 실제 종료 assertion, hook 해제 순서를 재확인했다.
- production·공개 API·보안 정책·의존성 변경은 없다. 새 coroutine 취소·monitor·예외 삼킴도 없다.
- 판정: inline fallback review PASS, P0=0/P1=0. 테스트 실행은 위 main session 결과다.

Kotlin 체크리스트: KT-01~05와 KT-FIN의 적용 항목을 검토했다.
caller validation·공개 API 문서·HTTP·Spring·모듈 등록은 해당 변경이 없어 N/A다.
KT-TEST-01~03/05는 기존 bluetape4k assertion·fixture·동기화 수단과 fresh 순차 검증으로 확인했다.
새 stress harness는 없으며, 테스트가 제어하는 특정 Thread와 admission 단계 때문에 기존 신호를 재사용했다.
IDE 진단은 사용할 수 없어 compileTestKotlin·detekt·테스트·diff 검사로 대체했다.
원격 exact-head CI, 머지, 발행 및 PostgreSQL·MySQL 로컬 실행은 이 검증에 포함하지 않는다.
