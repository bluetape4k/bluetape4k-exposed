# 진행 중인 작업 - bluetape4k-exposed

스냅숏: 2026-08-06 KST
범위: `1.12.1` 게시 메타데이터 교정 릴리스.

## 릴리스 목표

`1.12.0`의 POM은 유효했지만 Gradle Module Metadata의 개별 variant가 동일한
dependency-management 계약을 완전히 노출하지 못했습니다. `1.12.1`은 각 API/runtime
variant에 필요한 BOM 또는 constraint를 직접 연결하고, 실제 게시 산출물만 사용하는 격리된
하위 소비자 검증을 릴리스 게이트로 고정합니다.

## 검증 기준

- `baseVersion=1.12.1`, 빈 `snapshotVersion`, immutable catalog commit을 사용합니다.
- 35개 게시 산출물의 POM과 Gradle Module Metadata를 모두 검사합니다.
- 34개 일반 artifact와 1개 test fixture를 격리된 로컬 repository에 게시한 뒤 compile/runtime
  dependency resolution을 검증합니다.
- exact-head CI, Nightly, Snapshot, signed tag, Maven Central 게시 순서로 진행합니다.
- Maven Central의 `1.12.1` POM 및 `.module` 산출물과 실제 Gradle consumer resolution이
  확인된 뒤에만 milestone을 닫습니다.

## 현재 상태

- 교정 구현 PR [#620](https://github.com/bluetape4k/bluetape4k-exposed/pull/620)은 exact-head
  CI 전체 성공 후 `develop`에 병합되었습니다.
- 릴리스 후보는 `catalog/2026-08-06-02` 준비 commit을 사용하며, 공개 릴리스 완료 후
  해당 catalog train의 `bluetape4k-exposed` 버전을 `1.12.1`로 확정합니다.
- issue [#619](https://github.com/bluetape4k/bluetape4k-exposed/issues/619)와 `1.12.1`
  milestone은 Maven Central 공개 검증까지 추적합니다.
