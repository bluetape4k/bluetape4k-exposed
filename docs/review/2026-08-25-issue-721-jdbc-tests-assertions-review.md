# Issue #721 `jdbc-tests` assertion 설계 7-Tier 검토

## 검토 범위와 상태

- Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/721
- 기준 ref: `origin/develop@1242e5eb990a1f362233dba9542aa6e4d7192730`
- 검토 대상: `docs/superpowers/specs/2026-08-25-issue-721-jdbc-tests-assertions-design.md`,
  `docs/superpowers/plans/2026-08-25-issue-721-jdbc-tests-assertions-plan.md`
- 구현 상태: 아직 source mutation 전. 이 문서는 Type A 설계·계획 gate의 검토 결과다.
- workflow: `20260825T041335Z-a4e6d514` (Type A), six independent read-only lanes

## 독립 렌즈 결과

| 렌즈 | 역할·provenance | P0 | P1 | P2 | P3 | 최종 |
|---|---|---:|---:|---:|---:|---|
| performance | `code-reviewer`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| stability | `verifier`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 1 WATCH | PASS |
| security | `code-reviewer`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 0 | PASS |
| ops/reliability | `verifier`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 1 non-blocking | PASS |
| API/ABI | `code-reviewer`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 1 non-blocking | PASS |
| user/maintainer | `writer`, `gpt-5.6-luna/max` | 0 | 0 | 0 | 1 non-blocking | PASS |

모든 렌즈에서 P0/P1/P2 blocker는 0건이다. P3 사항은 assertion subtype이
비공개 구현 계약이라는 점, provider matrix가 workflow YAML 자동 gate가 아니라
local receipt gate라는 점, metadata 검색을 literal command로 유지하라는 권고다.

## 7-Tier 판정

| Tier | 검토 내용 | 판정 | 근거/후속 증거 |
|---|---|---|---|
| 1. 요구사항·범위 | `bluetape4k-assertions` direct `api`, migration fixture 정규화, module-local raw import 금지, #721 단일 모듈 | PASS | spec의 목표·책임 경계; `Assertions.kt`와 fixture 외 production 변경 금지 |
| 2. 구조·의존성 | 중앙 catalog alias만 사용하고 published API surface를 `apiElements`/POM/module JSON로 검증 | PASS | `outgoingVariants`, `generatePomFileForBluetapeExposedPublication`, `generateMetadataFileForBluetapeExposedPublication` 계획 |
| 3. 동작·의미 | equality/identity/boolean/exception matcher 매핑, SQL assertion diagnostic와 실제 exception message 분리 | PASS | `shouldBeEqualTo`, `shouldBeSameInstanceAs`, `shouldBeTrue/False`, `assertFailsWith(message = statement)` 계약 |
| 4. 보안·경계 | fixed `src/main/kotlin`·`src/test/kotlin`, regular `.kt`, symlink 미추적, wildcard/alias 금지, annotation 허용 | PASS | RED probe와 `verifyBluetapeAssertionImports` 설계 |
| 5. 성능·자원 | production hot path와 DB 실행 경로 불변, guard inputs 선언, 병렬 migration 금지 유지 | PASS | performance lens; `maxParallelForks=1`, cache/retry 경계 |
| 6. 운영·검증 | H2·PostgreSQL·MySQL_V8 순차 실행, full test `--no-build-cache`, Docker 부재 시 PENDING | PASS | plan Task 4 exact commands; 실제 실행 receipt는 implementation gate에서 수집 |
| 7. 유지보수·전달 | Kotlin testing checklist, Korean SPW-01..05, lesson, Lore commit, Korean PR/DoD | PASS | plan Task 5–6; review/lesson 경로 고정 |

## Kotlin 패턴·문서 checklist

- KT-01..KT-11: intent-specific Bluetape4k matcher, null/identity/equality 의미,
  예외 message와 suppressed 관계, 테스트 격리·cleanup을 계획에 명시했다.
- SPW-01: source ledger에 Issue URL, 기준 ref, source anchors, GNO/live metadata를
  기록했다.
- SPW-02: spec·plan·review·lesson·PR body의 산출물 계약과 DoD를 고정했다.
- SPW-03: Korean terminology audit는 spec·plan 대상 `findings=0`이다.
- SPW-04: 주장마다 source path, Gradle task, expected output을 연결했다.
- SPW-05: Markdown read-back, `git diff --check`, placeholder scan을 통과했다.

## Gate 결론

설계·계획 gate는 **PASS**다. 다음 구현 단계는 계획의 RED probe부터 시작하고,
source mutation 후 `apiElements`/POM/module JSON, 세 dialect, full module,
guard, diff/static evidence를 모두 fresh receipt로 수집해야 한다. 실제 구현·테스트
증거가 없으므로 현재 문서만으로 최종 PR DoD를 완료로 표시하지 않는다.
