# 이슈 280 Caffeine 후순위 쓰기 내구성 검토

날짜: 2026-06-23
범위: `exposed/jdbc-caffeine`, `exposed/r2dbc-caffeine`
이슈: #280

## 판정

P0 발견 사항: 0
P1 발견 사항: 0

Caffeine 후순위 쓰기 작업자는 데이터베이스 플러시가 성공했다고 보고되기 전까지 배치를 플러시된 것으로
표시하지 않는다. 일시 중단 가능한 후순위 쓰기 `put` 경로는 이제 먼저 큐에 삽입하고, 큐 전송이 성공한
후에만 캐시에 반영한다.

## 검토 의견

- JDBC와 R2DBC 후순위 쓰기 루프는 실패한 배치를 메모리에 유지하고, 이후 플러시가 성공하여 배치를
  비울 때까지 큐 깊이를 그대로 유지한다.
- 일반적인 플러시 실패 시에도 `lastFlushError`를 갱신하며, 재시도가 성공하면 오류를 지우고 깊이를 줄인다.
- 이제 R2DBC와 일시 중단 가능한 JDBC `put` 경로는 큐 전송이 실패하거나 취소되거나 닫힌 채널을 대상으로
  할 때 오염된 캐시 항목이 생기지 않도록 한다.
- 기존 큐 오버플로 검증은 동기식 JDBC 경로에 유지되며, 새로운 일시 중단 큐 삽입 테스트에서는 가득 찬
  큐에서의 취소와 닫힌 큐 동작을 다룬다.
- 회귀 테스트에서는 영구적인 플러시 실패 보고, 일시적인 플러시 실패 후 재시도, 취소된 전송, 닫힌 큐,
  캐시 반영 없이 적용되는 가득 찬 큐의 역압력을 다룬다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc-caffeine:testClasses :bluetape4k-exposed-r2dbc-caffeine:testClasses --rerun-tasks`
  - 결과: 성공.
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test --continue --rerun-tasks`
  - 결과: 성공, JDBC 테스트 329개 중 22개 건너뜀, R2DBC 테스트 66개 중 1개 건너뜀.
- `git diff --check`
  - 결과: 성공.

## 잔여 위험

- 후순위 쓰기는 여전히 비동기 내구성의 절충안이다. 프로세스가 비정상 종료되면 수락되었지만 데이터베이스에
  도달하지 않은 인메모리 쓰기가 손실될 수 있다. 이번 수정은 관찰된 플러시 또는 큐 삽입 실패 후 데이터가
  조용히 소진되는 문제를 방지한다.
