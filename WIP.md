# WIP - bluetape4k-exposed

- 기준일: 2026-11-26 KST
- 최신 안정 버전: `2.1.0`
- 안정 tag commit: `0c3f6a4a1c195926e7727c5a6e1f7dbc8667937a`
- 현재 개발선: `2.2.0-SNAPSHOT`
- 현재 milestone: `2.2.0`

## 현재 상태

`2.1.0` artifact, GitHub Release, stable manual 배포를 완료했다. `develop`은 `2.2.0` minor 개발선을 사용한다. Batch, Ktor tenant, Spring Boot common 모듈은 안정판 `2.1.0` BOM에 포함된다.

## #942 Exposed R2DBC DAO 연동

- `exposed/r2dbc-dao`는 JetBrains Exposed 1.6.1의 실험 R2DBC DAO를 별도 소비자 경로로 제공한다.
- H2 계약 테스트는 9개 통과·PostgreSQL 조건 1개 제외, PostgreSQL Testcontainers 실행은 10개 모두 통과했다. staged Maven consumer의 47개 publication, 93 compile task, 93 runtime task와 opt-in 음성 진단도 통과했다.
- 중앙 catalog `exposed-dao-r2dbc` alias는 dependencies PR #298에 이미 반영되어 있어 별도 catalog 변경은 없다.
- ABI 검사 46/46, 전체 `detekt`, `actionlint`, Ruby consumer 테스트 6 runs/78 assertions가 통과했다. 중앙 사이트 테스트 300개와 3,921 페이지 build도 통과했다.
- 7-Tier main-session inline review는 새 finding P0/P1/P2/P3=0이다. reviewer host 부재로 독립 review gate는 PENDING이다.
- 중앙 manual 전체 validator의 기존 5개 tenant-jdbc/Tink 오류는 사이트 #448에서 추적한다. 새 R2DBC DAO 페이지 오류는 없다.
- 구현과 중앙 매뉴얼은 각 task worktree에 Lore commit으로 저장했고, PR [#955](https://github.com/bluetape4k/bluetape4k-exposed/pull/955)와 사이트 PR [#450](https://github.com/bluetape4k/bluetape4k.github.io/pull/450)로 제출했다. 두 PR은 `develop` 대상이며 열린 상태다. #955는 milestone `2.2.0`, assignee `debop`, `documentation`, `enhancement`, `feature`, `test` labels가 설정되어 있고, 사이트 저장소에는 `2.2.0` milestone이 없어 #450에는 `documentation` label과 `debop` assignee만 설정했다.
- 구현 head `7bfb6e80c4f8cb6518a6032f9727cdb865638c94`의 hosted CI run `45738600657`에서 모든 필수 job이 통과했다. `Docs-only validation`은 skip되었다. 사이트 PR #450의 head `405019ff711960d1f15d13ec15ed8268d457e693`에서 Build run `45736018130`이 통과했고 Deploy는 skip되었다. 해당 구현 head 검증에는 GitHub review 결정이 없으며, 독립 reviewer 실행도 완료되지 않아 독립 review gate는 PENDING이다.
- #942는 PR에서 자동 종료하지 않도록 `Refs #942`로 연결하고 OPEN으로 유지한다. 아직 merge하지 않았다.

## 다음 개발선 규칙

- `gradle.properties`는 `baseVersion=2.2.0`, 빈 `snapshotVersion`을 유지한다.
- SNAPSHOT workflow가 실행할 때만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- database migration과 publication artifact packaging 검증은 새 변경마다 유지한다.
- 중앙 catalog SHA는 `bluetape4k-dependencies`의 다음 개발선이 병합된 뒤 한 번만 갱신한다.

## 추적

생태계 전체 후속 작업은 [bluetape4k-dependencies #235](https://github.com/bluetape4k/bluetape4k-dependencies/issues/235)에서 추적한다. 신규 기능과 버그는 `2.2.0` milestone에서 관리한다.
