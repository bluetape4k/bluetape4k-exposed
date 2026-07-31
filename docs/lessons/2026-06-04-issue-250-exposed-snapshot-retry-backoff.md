# Issue #250: Exposed Snapshot Retry Backoff

## 배경

projects Nightly가 latest develop commit에서 통과한 뒤 exposed Nightly full은 Central
snapshots에서 upstream `1.11.0-SNAPSHOT` metadata를 resolve하는 `Build & Detekt`에서
실패했습니다.

## 결정

exposed CI와 Nightly Gradle gate 전반에 같은 bounded retry posture를 사용합니다. 30초
delay로 5회 시도합니다. 실패한 dependency resolution이 cache serialization noise를 만들지
않도록 이 CI command에서는 configuration cache를 disabled로 둡니다.

## 결과

workflow retry window는 downstream bluetape4k repository 전반에서 관찰한 transient
Central 403 pattern과 일치합니다.

## 검증

workflow file 편집 뒤 `git diff --check`과 `actionlint`를 실행하고 downstream repository를
계속하기 전에 exposed Nightly full을 다시 실행합니다.

## 향후 지침

upstream publish 뒤 Nightly가 Central snapshot metadata에서 실패하면 먼저 upstream
repository를 검증하고 dependency chain을 rerun하기 전에 downstream workflow retry window를
강화합니다.
