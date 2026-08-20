# Issue #701 cache-loader PostgreSQL CI 7-Tier review

## 검토 대상과 기준

- 대상: Issue [#701](https://github.com/bluetape4k/bluetape4k-exposed/issues/701),
  Epic [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) stacked slot
- branch: `ci/issue-701-cache-loader-postgres`
- base/head: `develop` / `c6903906704b89602fab17f8fd74959c538c4be0` (구현 전 base)
- 범위: 주간/manual full PostgreSQL cache-loader selector job, artifact와
  `nightly-status` 연결, 설계·계획·lesson evidence
- 제외: production Kotlin/API/ABI, 기존 full module job·coverage, `docs/manual/**`,
  안정 릴리스 `1.12.1`, hosted run 결과 생성

## 7-Tier 판정

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | CLEAR | #701 acceptance와 설계/계획이 세 selector, PostgreSQL/Testcontainers, 순차 실행, artifact/status, H2 분리를 같은 범위로 고정한다. |
| 2. 구조·API/ABI | CLEAR | 변경은 workflow YAML과 한국어 설계/계획/lesson/review뿐이며 production signature·constructor·ABI는 변경하지 않았다. |
| 3. Kotlin·coroutine | CLEAR/N/A | Kotlin source는 변경하지 않았다. 기존 R2DBC timeout selector와 caller scope 계약은 selector test source를 read-back하고 실제 PostgreSQL case로 확인했다. |
| 4. Exposed·DB 계약 | CLEAR/WATCH | Docker PostgreSQL selector가 JDBC Lettuce 6/6, JDBC Redisson 4/4, R2DBC Redisson PostgreSQL 6/6을 통과했다. hosted runner/image pull/queue는 아직 별도 증거다. |
| 5. 테스트·CI 증거 | CLEAR/WATCH | XML failures/errors=0이며 R2DBC H2 timeout assumption skip 1건은 의도된 N/A다. `actionlint`, 구조 assertion, 전체 `detekt`도 통과했다. hosted nightly/manual full은 PENDING이다. |
| 6. 문서·EN/KO·lesson | CLEAR | 설계·계획·lesson·review를 한국어로 기록했고 SPW-01~05와 terminology audit 3-file finding 0을 충족했다. 안정 manual은 건드리지 않았다. |
| 7. delivery·안전 | WATCH | 로컬 구현·검증은 완료했지만 PR/hosted CI/merge는 현재 요청 범위 밖이다. exact-head PR과 hosted evidence 없이 merge-ready를 선언하지 않는다. |

## Findings

### P0

없음.

### P1

없음. 새 job은 기존 full/coverage 경로와 분리되고, step 실패 전파·artifact 누락
오류·`nightly-status.needs` 집계를 함께 고정한다.

### P2 / 잔여 범위

1. GitHub-hosted 주간/manual full run의 실제 check와
   `test-results-cache-loader-postgresql` artifact URL이 아직 없다. PR 이후 첫
   hosted run 전까지 #701 hosted acceptance는 PENDING이다.
2. runner Docker/image pull/queue 상태는 local PostgreSQL PASS로 대체할 수 없다.
   hosted unavailable은 `N/A/PENDING`으로 남기고 H2 결과를 승격하지 않는다.

## 검증 증거

| 범위 | tests | failures | errors | skipped | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| JDBC Lettuce PostgreSQL selector | 6 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| JDBC Redisson PostgreSQL selector | 4 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| R2DBC Redisson PostgreSQL selector + timeout env | 12 | 0 | 0 | 1 (H2 assumption) | `BUILD SUCCESSFUL` |

추가 검증:

- `actionlint .github/workflows/nightly-tests.yml`: PASS
- bounded workflow assertion(job 조건·selector 순서·env·artifact·status·coverage
  비변경): PASS
- `./gradlew detekt --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL
- `git diff --check`: PASS
- `$bluetape-writer` terminology audit: 3 files, findings 0
- production/Kotlin/API/manual scope scan: 변경 없음

## 결론과 handoff

현재 worktree의 7-Tier 상태는 **P0=0, P1=0, P2=2, WATCH**다. 로컬 구현과 증거는
충족했지만 hosted nightly/manual full 결과가 없으므로 PR 생성·CI 모니터링·merge는
별도 delivery gate로 남긴다. PR을 만들 때는 본 review의 PENDING 항목과 exact-head
증거를 `## DoD Status` 마지막 heading에 재conciliate해야 한다.
