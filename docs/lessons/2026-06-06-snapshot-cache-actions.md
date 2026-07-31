# Snapshot 캐시 조치

## 배경

이 저장소는 Central snapshots의 변경 가능한 bluetape4k SNAPSHOT 아티팩트에 의존하지만,
CI와 Nightly는 의존성을 강제로 새로고침하고 있었습니다.

## 결정

`--refresh-dependencies`와 Nightly의 `cache-disabled: true`를 제거하고, root의
changing-module 캐시 TTL을 0초에서 하루로 변경합니다.

## 결과

CI와 Nightly는 기존 테스트 태스크 구조를 유지하지만, 일반 의존성 해석은 모든 job에서
Central snapshot 메타데이터 요청을 강제하는 대신 Gradle 캐시 메타데이터를 사용할 수
있습니다.

## 검증

- `actionlint .github/workflows/*.yml`
- `rg -n -- '--refresh-dependencies|cache-disabled: true' .github/workflows` -> no matches
- `./gradlew help --no-daemon`
- `git diff --check`

## 향후 지침

명시적 의존성 새로고침은 전용 post-publish freshness 검사에서만 사용하세요. 일반 CI,
Nightly, Examples 워크플로는 캐시된 changing-module 메타데이터에 의존하고, 테스트 전용
SNAPSHOT 의존성이 필요할 때만 대상 warm-up을 추가해야 합니다.
