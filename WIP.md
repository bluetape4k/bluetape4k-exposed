# 진행 중인 작업 - bluetape4k-exposed

기준 시점: 2026-08-30 KST<br>
기준 브랜치: `develop` (`42e2e6ad6583e6631fc0db244a09546f1df9b056`, PR
[#764](https://github.com/bluetape4k/bluetape4k-exposed/pull/764) 병합 직후)<br>
개발선: `2.0.0` (`gradle.properties`의 `baseVersion=2.0.0`)<br>
최신 안정 릴리스: `1.12.1` (2026-08-06)

## 현재 상태

`2.0.0` milestone의 구현 queue는 닫혔습니다. 최신 구현 train의 마지막 항목인
TenantContext 기반 Ktor JDBC/R2DBC transaction bridge가 이슈
[#763](https://github.com/bluetape4k/bluetape4k-exposed/issues/763) 및 PR
[#764](https://github.com/bluetape4k/bluetape4k-exposed/pull/764)로 병합되었고,
현재 `develop`을 대상으로 한 열린 구현 PR은 없습니다. 문서 동기화 PR은 이
구현 상태와 별도로 열릴 수 있습니다.

`2.0.0` 구현 대기열에서 현재 추적하는 작업은 공개 릴리스 이후 매뉴얼 승격
handoff뿐입니다.

| 우선순위 | 이슈 | Milestone | 다음 조건 |
|---|---|---|---|
| P1 | [#651](https://github.com/bluetape4k/bluetape4k-exposed/issues/651) | `2.0.0-post-release` | 공개 `2.0.0` tag, artifact, GitHub Release가 모두 확인된 뒤 `docs/manual/manifest.yaml`의 `releaseRef`·`releaseCommit`을 승격 |
| P1 | [#662](https://github.com/bluetape4k/bluetape4k-exposed/issues/662) | `2.0.0-post-release` | #651 승격과 전체 manual validator 결과를 handoff에 기록하고 epic을 닫음 |

## 최근 완료된 주요 train

- [#763](https://github.com/bluetape4k/bluetape4k-exposed/issues/763) / [PR #764](https://github.com/bluetape4k/bluetape4k-exposed/pull/764): Ktor `ApplicationCall`에
  binding한 `TenantId`를 검증된 resolver로 전달하는 opt-in JDBC/R2DBC transaction
  adapter를 추가했습니다. JDBC는 애플리케이션 소유 blocking dispatcher를 사용하고,
  R2DBC는 coroutine-native로 실행하며 기본 tenant fallback은 없습니다.
- [#748](https://github.com/bluetape4k/bluetape4k-exposed/issues/748), [#762](https://github.com/bluetape4k/bluetape4k-exposed/issues/762): Ktor backend-selective
  artifact와 dependency allowlist 경계를 고정했습니다.
- [#746](https://github.com/bluetape4k/bluetape4k-exposed/issues/746), [#747](https://github.com/bluetape4k/bluetape4k-exposed/issues/747), [#758](https://github.com/bluetape4k/bluetape4k-exposed/issues/758): batch artifact 소유권과
  `FAILED` checkpoint 재시작 경계를 정렬했습니다.
- [#755](https://github.com/bluetape4k/bluetape4k-exposed/issues/755): Caffeine lifecycle coordinator와
  conformance suite를 도입했습니다.
- [#729](https://github.com/bluetape4k/bluetape4k-exposed/issues/729), [#642](https://github.com/bluetape4k/bluetape4k-exposed/issues/642), [#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643): Spring Data 공통 SPI와
  JDBC/R2DBC Query by Example 경로를 정리했습니다.

## 안정판 매뉴얼 경계

현재 안정판 매뉴얼 기준은 변경하지 않습니다.

- `docs/manual/manifest.yaml`의 `releaseRef`는 `1.12.1`입니다.
- `releaseCommit`은 `4cc2cce07087241ec24a597d8464615434ea2b81`입니다.
- `2.0.0` 공개 증거가 준비되기 전에는 안정판 `docs/manual/**` 링크와 release anchor를
  `develop` 또는 `2.0.0`으로 바꾸지 않습니다. 이 승격은 #651과 #662의 범위입니다.
- 현재 개발 전용 TenantContext 매뉴얼은 `releaseStatus: develop-only`로 유지합니다.

## 다음 단계

1. 새 기능이 `develop`에 병합될 때 이 문서와 `CHANGELOG.md`의 `Unreleased` 항목을 함께
   갱신합니다.
2. `2.0.0` tag, Maven artifact, GitHub Release가 실제로 공개되면 #651의 release anchor
   승격을 실행하고 #662 handoff를 완료합니다.
3. 승격 시 `export_manifest`, manual validator, 한영 parity, release commit readback을
   순서대로 검증합니다.
