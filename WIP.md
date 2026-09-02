# 진행 중인 작업 - bluetape4k-exposed

기준 시점: 2026-09-02 KST<br>
기준 브랜치: `develop` (`4b0b6eac5ecd019b6d60f0608807f44b1b245557`, PR
[#783](https://github.com/bluetape4k/bluetape4k-exposed/pull/783) 병합 직후)<br>
개발선: `2.0.0` (`gradle.properties`의 `baseVersion=2.0.0`)<br>
최신 안정 릴리스: `1.12.1` (2026-08-06)

## 현재 상태

`2.0.0` milestone의 구현 queue는 닫혔습니다. 최신 구현 train의 마지막 항목인
TenantContext 기반 Ktor JDBC/R2DBC transaction bridge가 이슈
[#763](https://github.com/bluetape4k/bluetape4k-exposed/issues/763) 및 PR
[#764](https://github.com/bluetape4k/bluetape4k-exposed/pull/764)로 병합되었습니다. 이어
[#781](https://github.com/bluetape4k/bluetape4k-exposed/issues/781)과
[PR #783](https://github.com/bluetape4k/bluetape4k-exposed/pull/783)이 batch 2.0
migration의 legacy active row 차단과 schema artifact 패키징을 완료했으며, 현재
`develop`을 대상으로 한 열린 구현 PR은 없습니다. 이슈
[#780](https://github.com/bluetape4k/bluetape4k-exposed/issues/780)은
`2.0.0` 정식 배포 source를 stable Dependencies catalog에 고정하는 release-prep
작업이며 새 기능이나 API 변경을 포함하지 않습니다.

공개 릴리스 이후의 versioned manual 승격은 매뉴얼이 이전된
[`bluetape4k.github.io` #399](https://github.com/bluetape4k/bluetape4k.github.io/issues/399)에서
추적합니다. 기존 Exposed [#651](https://github.com/bluetape4k/bluetape4k-exposed/issues/651)과
[#662](https://github.com/bluetape4k/bluetape4k-exposed/issues/662)는 이 저장소가 더 이상
release manual의 source of truth가 아니므로 닫혔습니다.

## 최근 완료된 주요 train

- [#781](https://github.com/bluetape4k/bluetape4k-exposed/issues/781) / [PR #783](https://github.com/bluetape4k/bluetape4k-exposed/pull/783): active legacy parameter hash를
  preflight에서 fail-closed로 차단하고 H2·MySQL·PostgreSQL migration의 재실행과
  core·aggregate JAR schema resource 패키징을 검증했습니다.
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

## 안정판 문서 경계

현재 저장소의 `docs/manual/**`은 versioned manual의 배포 source가 아닙니다.

- versioned manual과 release anchor는 `bluetape4k.github.io`가 소유합니다.
- Exposed 2.0.0 tag, Maven Central artifact와 GitHub Release가 공개된 뒤 #399에서
  exact tag commit에 맞춰 승격합니다.
- 이 저장소에 남은 역사적 manual 산출물을 새 release anchor로 다시 승격하지 않습니다.

## 다음 단계

1. #780의 release-prep 검증을 끝낸 exact `develop` head에 `2.0.0` tag를 생성합니다.
2. `2.0.0` tag, Maven artifact, GitHub Release가 실제로 공개되면 Dependencies stable
   catalog를 승격합니다.
3. 같은 공개 증거로 `bluetape4k.github.io` #399의 versioned manual과 release anchor를
   갱신하고 site build 및 공개 페이지를 검증합니다.
