# Kafka4 catalog 동기화 리뷰

## 범위

- `gradle/libs.versions.toml`
- `kafka4` 호환성 라인 alias만 해당

## 결과

- P0/P1 지적 사항: 0
- 변경 내용은 `bluetape4k-dependencies`의 source-of-truth 값과 일치합니다.
- Exposed module은 Kafka를 직접 소비하지 않습니다. 이 repository에는 오래된 managed catalog alias만 남아 있었습니다.

## 검증

- `sync-shared-versions.py --workspace /tmp/bt4k-kafka4-sync-workspace --check --summary`: `Shared versions are aligned.`
- `./gradlew help --no-daemon --no-configuration-cache`: `BUILD SUCCESSFUL in 5s`
- `git diff --check`
