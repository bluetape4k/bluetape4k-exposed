# Batch 외부 writer 안전성 체크리스트

`BatchJob`의 lease-loss fencing은 새 writer 호출을 시작하지 않도록 보호하지만,
이미 시작된 네트워크 호출이나 외부 transaction을 되돌릴 수는 없습니다. 외부
side effect가 있는 writer는 배포 전에 다음 중 하나 이상의 방어 증적을 등록해야
합니다.

- item/chunk idempotency key와 upsert
- 원격 시스템의 조건부 version 또는 fencing
- transactional outbox 또는 중복 제거 저장소

`batch-writer-safety.example.yaml`은 application receipt의 최소 예시이고,
`batch-writer-inventory.example.yaml`은 실제로 설정된 writer id 목록의 예시입니다.
운영 release owner는 비밀값·token·raw payload를 넣지 말고 restricted evidence
store의 opaque URI와 lowercase SHA-256만 기록해야 합니다.

검증 명령:

```bash
ruby scripts/batch/validate_batch_writer_safety.rb \
  utils/batch/operations/batch-writer-safety.example.yaml \
  utils/batch/operations/batch-writer-inventory.example.yaml \
  --expected-release-head aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
```

lease-loss가 발생하면 새 owner가 같은 execution을 즉시 retry하지 않습니다. 먼저
correlation id로 마지막 성공 renewal과 blocked mutation을 조회하고, DB의
owner/version/lease를 read-only로 확인한 뒤, 외부 idempotency/outbox receipt를
reconcile합니다. reconcile이 끝난 후에만 새 실행 여부를 결정합니다.
