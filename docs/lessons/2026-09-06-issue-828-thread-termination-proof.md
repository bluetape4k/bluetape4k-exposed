# #828 완료 알림과 스레드 종료는 다르다

## 실패 근거

`develop@fe7724304a1ffd744fa2e6aa6863a6c775ce0402`의
[CI 작업](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34033943197/job/101489568668)에서
`JdbcCaffeinePersistedHookTest`의 close deadline 테스트가 실패했다.
H2 결과는 전체 181개 중 실패 1개, 기존 제외 2개였다.
XML의 실패 위치는 수정 전 71행이며 오류는 다음과 같다.

```text
Expected <true> to be <false>, but was not.
```

이 RED는 실제 CI artifact에서 확인했다. 로컬에서 실패를 새로 재현했다고 주장하지 않는다.

## 잘못된 가정과 수정

`closeCompleted.countDown()`은 스레드의 finally에서 실행되므로
`await()`가 돌아왔어도 스레드 종료는 아직 완료되지 않을 수 있다.
즉시 `isAlive`를 검사한 것이 경쟁 조건이다. repository가 close deadline을 넘겼다는 증거는 아니다.

완료 latch 대신 `closeThread.join(1_000)`으로 실제 종료를 기다린 뒤 `isAlive`를 검사한다.
기존 1초 제한을 유지하므로 close가 계속 블록되면 여전히 실패한다.
hook 해제 전 invalidation·worker 상태 검사와 finally 정리 순서는 그대로 둔다.
임의 sleep, timeout 증가, 재시도 추가, production 변경은 필요하지 않다.

## 재발 방지

테스트가 입증할 계약이 스레드 종료라면 작업 완료 신호가 아닌 종료 동기화 수단을 사용한다.
hook 종료와 close 종료를 별개로 제어해야 하므로 기존 Thread와 join을 재사용한다.
검증 결과와 범위 한계는 [리뷰](../superpowers/reviews/2026-09-06-issue-828-thread-termination-review.md)에 기록한다.

## 전체 검증에서 드러난 hook 순서 문제

전체 H2 실행은 수정 대상 외의 `close - accepted put blocked before cache mutation cannot repopulate after timeout()`에서
실패했다. `dbWriteCompleted`라는 latch가 실제로는 `afterPersisted`에서 해제되는데,
close timeout 뒤에는 late side-effect guard가 hook 호출을 생략할 수 있었다.
따라서 DB 쓰기 완료와 hook 호출은 동일한 계약이 아니다.

사용자의 범위 확대 승인 후 hook 완료를 close 전에 확인하도록 순서만 바꿨다.
캐시 put은 계속 차단되어 있으므로 close는 진행 중인 admission 때문에 timeout되며,
해제 후 잘못된 캐시 값이 노출되지 않고 캐시가 정리되는 기존 assertion을 유지한다.
새 완료 신호나 production callback을 추가하지 않는다.

앞으로 동시성 테스트의 대기 신호를 검토할 때 실제 발신 위치와 terminal 상태에서의 생략 조건을 확인한다.
timeout이 생략시키는 callback을 timeout 이후의 필수 완료 신호로 사용하지 않는다.
독립 타깃 성공만으로 마무리하지 않고 전체 모듈 검증에서 드러난 다른 실패도 기록한다.
