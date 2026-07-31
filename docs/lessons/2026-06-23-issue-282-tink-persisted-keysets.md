# 이슈 282 Tink 영속 keyset 교훈

Date: 2026-06-23
Issue: #282

## 교훈

암호화된 데이터베이스 column helper는 자체 process-local keyset을 생성하면 안 됩니다. 저장된 ciphertext는 재시작, 재배포, 다른 node에서 사라지는 key material에 의존하므로 편리한 기본 키가 데이터 손실 함정이 됩니다.

## 지침

- 영속 column 경계에서는 명시적인 `TinkAead` 또는 `TinkDeterministicAead`를 요구합니다.
- 생성한 keyset은 테스트와 예제에서만 보이게 하거나, 의도적으로 ephemeral한 helper 이름으로 감쌉니다.
- 기본 ciphertext length overload처럼 key material을 선택하지 않는 경우에만 편의 기능을 유지합니다.
- 회귀 테스트는 직렬화한 keyset material로 encryptor를 재구성하고, 새로 생성한 keyset이 기존 ciphertext를 읽지 못함도 증명해야 합니다.
- 영속 암호화 column의 README 예제는 생성 singleton factory가 아니라 durable keyset loading에서 시작해야 합니다.

## 후속 조치

이후 bluetape4k-tink가 first-class KMS-backed keyset loader를 제공하면, 이 예제는 cleartext JSON snippet보다 해당 loader를 우선하도록 업데이트합니다.
