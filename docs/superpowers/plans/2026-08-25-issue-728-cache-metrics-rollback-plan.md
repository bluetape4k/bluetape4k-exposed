# Issue #728 Ktor cache metrics rollback 실행 계획

## 목표

Ktor cache metrics 설치 실패에서 rollback 제거 실패를 조용히 버리지 않고,
bluetape4k Kotlin 패턴과 `bluetape4k-assertions`를 사용해 구조화된 진단과 결정적인
재설치 계약을 제공한다.

## 순서

- [x] **1. 범위·baseline 확인** — live issue #728, Type C bug-fix, 기존 11개 metrics
  테스트, `runCatching` cleanup gap, Ktor logger 금지 guard를 확인한다.
- [x] **2. RED 회귀 테스트 작성** — remove failure의 suppressed diagnostic, residual
  meter, successful rollback 후 retry를 먼저 실패시키고 증거를 남긴다.
- [x] **3. 구현** — best-effort rollback 카운터, sanitized primary failure, suppressed
  `CacheMeterRollbackDiagnostic`, 내부 KDoc을 구현한다.
- [x] **4. GREEN 검증** — targeted metrics 14개와 Ktor module test를 순차 실행하고,
  compile/Detekt 및 raw output/`println` guard를 확인한다.
- [x] **5. 문서·7-Tier review** — 영·한 README 계약, 설계/lesson/review 문서를 갱신하고
  security·reliability·Kotlin/assertions·integration 증거를 기록한다.
- [ ] **6. 전달** — Lore commit, branch push, Korean PR 생성 및 exact-head metadata/
  checks/review 상태를 재확인한다. merge는 별도 승인 전까지 하지 않는다.

## 중단 조건

- Ktor health-route의 logger 금지 guard를 약화하지 않는다.
- registry 원본 예외 메시지나 credential/tag secret을 public diagnostic에 복사하지 않는다.
- module test 또는 Detekt가 실패하면 PR 전달 전에 원인을 수정한다.
