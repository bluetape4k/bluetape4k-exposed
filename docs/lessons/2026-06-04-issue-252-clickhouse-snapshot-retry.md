# Issue #252: ClickHouse Snapshot Retry Backoff

## 배경

general exposed retry hardening 뒤에도 exposed Nightly full은 `Test / exposed-clickhouse`에서
계속 실패했습니다. 반복된 failure는 `bluetape4k-logging`의 Central snapshot metadata HTTP
403이었지만 local HEAD/GET check는 200을 반환했습니다.

## 결정

broader exposed retry policy는 유지하되 ClickHouse gate만 60초 delay, 35분 timeout의
8회 시도로 확장합니다. 이는 긴 Central edge failure가 반복된 job에만 추가 wait를 격리합니다.

## 결과

ClickHouse test gate는 모든 exposed Nightly job을 넓히지 않고도 더 긴 Central snapshot
metadata outage를 흡수할 수 있습니다.

## 검증

PR 생성 전에 `git diff --check`과 `actionlint`를 실행합니다. downstream repository를
계속하기 전에 exposed Nightly full을 다시 실행합니다.

## 향후 지침

general retry hardening 뒤 Central 403이 하나의 Testcontainers-backed job에만 나타나면
broad workflow churn을 피합니다. 먼저 그 job만 넓힌 뒤 dependency-ordered Nightly chain을
다시 실행합니다.
