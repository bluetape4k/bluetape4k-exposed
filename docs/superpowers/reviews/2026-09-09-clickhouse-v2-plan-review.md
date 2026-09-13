# ClickHouse JDBC V2 stacked train 계획 리뷰

## 리뷰 범위·방식

- 대상 계획: `docs/superpowers/plans/2026-09-09-clickhouse-v2-stacked-train.md`
- 대조 설계: `docs/superpowers/specs/2026-09-09-clickhouse-v2-stacked-train-design.md`
- 기준 commit: `06e0b58d585f5ad98f2f4c82a8a1cbdbbc0e3bae`
- 리뷰 방식: `inline fallback·비독립` (독립 reviewer lane 결과를 가장하지 않음)
- 범위: 구현 전 계획 검토만 수행. production source·test source·Gradle·README·benchmark output·GitHub PR은 변경하지 않음.

## SPW 결과

| ID | 검토 항목 | 확인한 근거 | 결과 | P0/P1 |
|---|---|---|---|---|
| SPW-01 | 설계·파일·API traceability | 설계의 #865~#868 경계와 계획의 파일 소유권 지도·Task 1~10·DS-01~DS-09를 일대일 대조 | 각 issue가 options → types → RowBinary → diagnostics 순서로 분리되고 child가 부모 계약만 사용 | 0 |
| SPW-02 | 공개 API·ABI·Kotlin/Exposed 경계 | 기존 `ClickHouseDatabase.connect`, `chArray`, `queryList`, `queryFlow` descriptor 보존 문장과 신규 overload code block, `checkKotlinAbi`·Java reflection·`javap` 명령 | 기존 surface 보존, 신규 options/diagnostics overload는 별도 descriptor, nullable container/element 축 분리 | 0 |
| SPW-03 | TDD·검증·환경 | 모든 production task 앞의 RED selector, 동일 GREEN selector, H2/ClickHouse selector 분리, `--no-parallel --max-workers=1`, JUnit lock/property, DS receipt schema | compile/assertion RED 조건과 XML failures/errors/skipped=0, Testcontainers image/digest/container/seed receipt가 명시됨 | 0 |
| SPW-04 | stacked topology·rollback·GitHub contract | child worktree Step 0의 `git fetch`·`rev-parse`·`git worktree add`·exact-head test, PR base/head, Lore commit, `Closes #865`~`#868`, merge 별도 승인 | 각 child가 이전 exact head를 base로 삼고 metadata read-back·rollback/PENDING 조건이 있음 | 0 |
| SPW-05 | 보안·성능·문서·운영 | auth/raw/header/throwable/callback redaction, profile isolation, setter 이후 fallback 금지, 36조합×3 process benchmark, deterministic EN/KO chart, writer/final review 및 중앙 manual traceability | secret·replay·private driver buffer 과장·central manual 중복 생성을 막고 운영 실패 matrix와 residual risk 기록을 요구함 | 0 |

## 7-Tier 확인

| Tier | 확인 결과 | 상태 |
|---|---|---|
| API/ABI | options/connect와 diagnostics overload의 source·JVM descriptor 보존 규칙이 계획에 고정됨 | PASS |
| Kotlin/Exposed idiom | immutable data/value, 기존 transaction DSL, nullable element/container 분리, caller-owned resource가 명시됨 | PASS |
| Integration/transaction | DriverManager·provider·DataSource/pool·ambient transaction ownership과 commit/rollback 금지가 task에 있음 | PASS |
| Security/redaction | auth one-of, URL auth fail-fast, allowlist, exception graph·logger·callback redaction과 secret provider 경계가 있음 | PASS |
| Performance/resource | RowBinary preflight/fallback state machine, input-row cap, private buffer 미보장, 36조합 raw/provenance/chart가 있음 | PASS |
| Tests/CI | RED→GREEN selectors, H2/ClickHouse 분리, locks, ABI/detekt/full test, hosted CI/read-back 조건이 있음 | PASS |
| Docs/operations | EN/KO README/KDoc/lesson, writer receipt, central traceability, `Closes` metadata, rerun/PENDING 운영 지침이 있음 | PASS |

## 수정·잔여 범위

- 계획 중복 Task 4~6 블록을 제거하고 Task 0~12 순서를 단일화했다.
- 기존 `chArray` signature를 보존하고 nullable element/container용 별도 adapter 이름·constructor를 명시했다.
- `toEffectiveProperties` 내부 signature, `ClickHouseBatchPath`, `UnsupportedConfiguration`, diagnostics model/overload를 계획 안에서 동일한 이름으로 맞췄다.
- socket/request timeout의 `0` 허용, connection TTL의 `-1` 허용을 설계 표와 일치시켰다.
- 계획 단계에서는 신규 API·benchmark·PR·merge를 실행하지 않는다. 계획 승인 후에만 `$executing-plans` inline execution을 시작한다.
- 독립 reviewer lane이 실패한 상태이므로 이 리뷰는 독립 PASS가 아니며, 실행 중 같은 원칙으로 inline fallback과 비독립성을 계속 기록한다.

## 결론

계획은 승인된 설계의 issue 순서·API/보안 경계·TDD·benchmark·문서·운영·rollback 조건을 구체적인 파일과 명령으로 닫았다. 확인된 P0/P1은 0건이다. 상태는 **계획 승인 대기(PENDING)**이며, 이 리뷰는 merge·push·PR 생성을 승인하지 않는다.
