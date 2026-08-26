# Issue #719 UUID 공개 ABI 7-Tier review

## 검토 범위와 기준

- 대상 branch: `fix/uuid-public-abi`
- 기준 base: `develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 대상: JDBC/R2DBC UUID repository canonical 이름, deprecated source-only alias,
  API baseline, 영어/한국어 README, migration 문서, 회귀 테스트와 Type A 산출물
- 검토 방식: source-read-only 독립 reviewer와 main-session 통합 검증을 대조했다.
- reviewer provenance: `code-reviewer · gpt-5.6-luna · max`

## 7-Tier 결과

| Tier | 검토 내용 | P0 | P1 | P2 | P3 | 결과 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1. 요구사항·범위 | 네 JDBC/R2DBC 일반·soft-delete 계열, 2.0 breaking migration, 범위 밖 데이터 계약 | 0 | 0 | 0 | 0 | PASS |
| 2. API·ABI | `KotlinUuid*`/`JavaUuid*` descriptor, generic key type, legacy class 제거, source-only alias | 0 | 0 | 0 | 0 | PASS |
| 3. Kotlin·Exposed 패턴 | 기존 repository 상속·generic/table bound 유지, Kotlin UUID opt-in과 null-safety | 0 | 0 | 0 | 0 | PASS |
| 4. assertions·logging | `io.bluetape4k.assertions` matcher 사용, 신규 `println`·stdout fallback·raw payload 로그 없음 | 0 | 0 | 0 | 0 | PASS |
| 5. 테스트·백엔드 | focused naming/legacy alias compile-smoke, JDBC/R2DBC H2 전체, Detekt와 ABI check | 0 | 0 | 0 | 0 | PASS |
| 6. 문서·정적 품질 | EN/KO README parity, migration 예제 문법, spec/plan/lesson, diff-check와 용어 감사 | 0 | 0 | 0 | 0 | PASS |
| 7. 전달·안전 | Lore commit/PR metadata, exact head, CI·merge 승인 경계, 비파괴 변경 | 0 | 0 | 0 | 0 | PASS (delivery pending) |

## Findings와 수정

초기 review에서 migration 문서의 interface 예제가 생성자 호출처럼 보이는 P2가
발견되었다. 네 예제에서 `()`를 제거하고, 필수 repository 멤버를 구현해야 하는
축약 예시임을 명시했다. 또한 8개 legacy 이름을 모두 참조하는 JDBC/R2DBC
compile-smoke 테스트를 추가해 source-only alias 계약을 직접 고정했다.

최종 reviewer 판정은 **PASS / APPROVE**이며 P0/P1/P2/P3는 모두 0이다.

## 검증 증거

- JDBC H2: 218개 실행, 25개 기존 환경/database skip, 실패 0
- R2DBC H2: 204개 실행, 7개 기존 환경/database skip, 실패 0
- focused JDBC/R2DBC naming 및 legacy alias compile-smoke: PASS
- JDBC/R2DBC `detekt`: BUILD SUCCESSFUL
- JDBC/R2DBC `checkKotlinAbi`: BUILD SUCCESSFUL
- 각 jar: canonical 8개 class entry, 대상 legacy class 0개, case-fold repository 중복 0개
- `javap`: Kotlin 계열은 `kotlin.uuid.Uuid`, Java 계열은 `java.util.UUID` generic 계약 유지
- `git diff --check`: PASS
- 변경 한국어 문서 용어 감사: 기존 `exposed/r2dbc/README.ko.md:202`의
  `snapshot` 1건만 검출되었고 이번 diff 밖의 문맥 예외로 기록했다.

Linux hosted artifact와 macOS artifact의 실제 교차 실행은 PR CI에서 exact head로
확인할 전달 단계이며, local code review의 P0/P1 blocker는 아니다. PR 병합과 issue
close는 fresh CI 및 명시적 merge approval 전까지 수행하지 않는다.

## DoD Status

- [x] 7-Tier source/API/Kotlin/assertions/test/docs/delivery review 완료
- [x] P0/P1/P2/P3 findings 0
- [x] reviewer provenance와 수정 전·후 evidence 기록
- [x] 로컬 모듈·ABI·정적·JAR/Javap 검증 완료
- [ ] exact-head hosted CI와 merge approval — PR 전달 후 대기

판정: **PASS for code review; PENDING for hosted delivery/merge gate**.
