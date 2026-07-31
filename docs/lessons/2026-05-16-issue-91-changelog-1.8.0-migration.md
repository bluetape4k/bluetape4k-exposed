# CHANGELOG [Unreleased] → [1.8.0] 전환

**Date**: 2026-05-16
**Issue**: #91
**Type**: Maintenance (docs)

## 요약

`[Unreleased]` CHANGELOG section을 version이 명시된 `[1.8.0] - 2026-05-16` release
entry로 전환했습니다. standalone repository를 처음 만든 이후 누적된 모든 변경과
PR #95–#106의 12개 pre-release bug-fix entry(#79–#90)를 포함합니다.

## 수행한 작업

1. `[Unreleased]` content(Added / Changed / Fixed subsection)를 제거했습니다.
2. 다음을 합친 `[1.8.0] - 2026-05-16` section을 만들었습니다.
   - 초기 repository setup의 기존 `[1.8.0] - 2026-05-07` Added 항목
   - 이전 `[Unreleased]` block의 생성 이후 Added/Changed 항목
   - #79–#90의 12개 bug-fix entry 전체
3. 이후 unreleased work를 위해 상단에 비어 있는 새 `[Unreleased]` header를 남겼습니다.

## 향후 지침

- 16개 pre-release PR이 모두 merge되면 이 CHANGELOG entry가 최종 1.8.0 Maven
  Central release를 나타냅니다.
- 다음 변경 cycle이 시작될 때까지 `[Unreleased]`는 비워 둡니다.
- PR이 중요한 새 feature를 추가하면 같은 PR에서 즉시 `[Unreleased]`를 업데이트합니다.
