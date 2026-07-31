# 이슈 280 Caffeine Write-Behind 내구성 교훈

Date: 2026-06-23
Issue: #280

## 교훈

Write-behind queue는 "메모리에 수락됨"과 "내구적으로 flush됨"을 별도 상태로 다뤄야 합니다. queue/flush 경계가 성공하기 전에 batch를 비우거나 cache 값을 publish하면 일시적 실패가 조용한 데이터 손실로 바뀝니다.

## 지침

- write-behind flush helper는 명시적인 성공/실패 신호를 반환해야 하며, 호출자가 삼킨 예외에서 성공을 추론하게 하면 안 됩니다.
- database transaction이 성공적으로 commit된 뒤에만 queue depth를 줄이고 batch를 비웁니다.
- suspend enqueue 경로에서는 cache에 publish하기 전에 `send`를 호출하여 cancellation, 닫힌 channel, 가득 찬 queue의 backpressure가 더러운 cache 상태를 남기지 않게 합니다.
- 회귀 테스트는 계속 보이는 영구 실패와 동일한 보존 batch를 재시도하는 일시적 실패를 모두 다뤄야 합니다.
- full queue 테스트는 queue 수락 전에 pending/cancelled send가 cache에 값을 publish하지 않음을 단언해야 합니다.

## 후속 조치

write-behind 내구성이 더 강한 제품 요구 사항이 되면 process-local queue에 의존하는 대신 재시도 상태를 durable outbox로 옮깁니다.
