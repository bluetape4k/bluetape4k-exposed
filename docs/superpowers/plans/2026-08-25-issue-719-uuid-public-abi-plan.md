# Issue #719 UUID 공개 ABI 충돌 제거 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JDBC와 R2DBC의 Kotlin/Java UUID repository 특수화를 filesystem-safe canonical JVM 이름으로 이관하고 2.0 source migration 계약을 검증한다.

**Architecture:** 기존 repository 동작과 generic key type은 유지하고 public interface 이름만 `KotlinUuid*`/`JavaUuid*`로 분리한다. 1.x 이름은 JVM class를 만들지 않는 deprecated typealias로 제공하며, API baseline은 실제 산출물에서 재생성한다.

**Tech Stack:** Kotlin 2.4, Gradle 9.7, KGP binary compatibility validator, Exposed JDBC/R2DBC, JUnit 5, `bluetape4k.assertions`, Detekt.

---

## 파일 책임

- `exposed/jdbc/.../JdbcRepository.kt`, `SoftDeletedJdbcRepository.kt`: JDBC canonical interface와 source-only alias.
- `exposed/r2dbc/.../R2dbcRepository.kt`, `SoftDeletedR2dbcRepository.kt`: R2DBC canonical interface와 source-only alias.
- `api/bluetape4k-exposed-jdbc.api`, `api/bluetape4k-exposed-r2dbc.api`: 실제 public descriptor baseline.
- `exposed/jdbc/README*.md`, `exposed/r2dbc/README*.md`: locale별 사용자 migration 표.
- `docs/superpowers/migrations/2026-08-25-issue-719-uuid-public-abi.md`: 2.0 이관 계약과 검증 명령.
- `exposed/*/src/test/.../UuidRepositoryNamingTest.kt`: canonical class 충돌 회귀 테스트.

## Task 1: canonical public API와 source alias 고정

- [x] `Uuid*` Kotlin interface를 `KotlinUuid*`로, `UUID*` Java interface를 `JavaUuid*`로 변경한다.
- [x] 네 파일의 기존 generic key type, repository 상속, soft-delete table bound를 보존한다.
- [x] 기존 8개 이름을 `@Deprecated` `typealias`와 `ReplaceWith`로 추가하고 binary forwarding class는 추가하지 않는다.
- [x] KDoc에 2.0 canonical 이름, source-only alias, 재컴파일 요구사항을 기록한다.

검증: `./gradlew :bluetape4k-exposed-jdbc:compileKotlin :bluetape4k-exposed-r2dbc:compileKotlin --rerun-tasks --no-daemon --console=plain`이 성공하고 old names가 interface가 아닌 typealias로만 검색된다.

## Task 2: RED/GREEN naming 회귀 테스트

- [x] JDBC와 R2DBC 각 모듈에 canonical 네 class의 JVM 이름을 수집하는 JUnit 테스트를 추가한다.
- [x] `names.distinct().size shouldBeEqualTo names.size`, `shouldBeTrue`/`none`을 사용해 case-only collision과 중복을 검증한다.
- [x] 테스트 파일에서 `io.bluetape4k.assertions`를 import하고 `println`이나 직접 stdout assertion을 사용하지 않는다.

RED 근거는 변경 전 macOS `checkKotlinAbi` 실패로 고정했고, 아래 focused test
명령을 canonical 이름 적용 후 GREEN 증거로 사용한다. 두 테스트 모두
`bluetape4k.assertions` matcher를 통해 PASS한다.

```bash
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests io.bluetape4k.exposed.jdbc.repository.UuidRepositoryNamingTest \
  :bluetape4k-exposed-r2dbc:test \
  --tests io.bluetape4k.exposed.r2dbc.repository.UuidRepositoryNamingTest \
  --rerun-tasks --no-daemon --console=plain
```

## Task 3: API baseline과 문서 migration 갱신

- [x] `updateKotlinAbi`로 JDBC/R2DBC baseline을 갱신한다.
- [x] `checkKotlinAbi`로 baseline이 canonical descriptor와 일치하는지 확인하고 legacy class 삭제를 단순 suppression으로 처리하지 않는다.
- [x] README 영어/한국어 표를 canonical 이름으로 바꾸고 old→new 매핑과 binary 재컴파일 요구사항을 병기한다.
- [x] migration 문서에 대안, 호환성 계약, 예제, 범위 밖 항목, ABI/JAR/`javap` 검증을 기록한다.

검증 명령:

```bash
./gradlew :bluetape4k-exposed-jdbc:updateKotlinAbi \
  :bluetape4k-exposed-r2dbc:updateKotlinAbi --rerun-tasks --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc:checkKotlinAbi \
  :bluetape4k-exposed-r2dbc:checkKotlinAbi --rerun-tasks --no-daemon --console=plain
```

## Task 4: 모듈·산출물 검증

- [x] JDBC H2 전체 테스트를 단독 실행하고 결과를 기록한다.
- [x] R2DBC H2 전체 테스트를 JDBC와 분리해 단독 실행하고 결과를 기록한다.
- [x] JDBC/R2DBC Detekt를 fresh rerun한다.
- [x] 각 jar에서 canonical class 8개 family를 찾고 대상 legacy class가 없는지 검사한다.
- [x] `javap`로 canonical interface의 `kotlin.uuid.Uuid`/`java.util.UUID` generic 계약을 확인한다.
- [x] `git diff --check`와 old declaration/source stdout scan을 실행한다.

검증 결과: JDBC 218/25 skip, R2DBC 204/7 skip, Detekt/ABI/diff-check PASS, jar negative scan 0건이다.

## Task 5: 7-Tier 리뷰와 PR 전달

- [ ] 독립 `code-reviewer · gpt-5.6-luna · max`가 현재 diff를 source-read-only로 검토한다.
- [ ] P0/P1 findings가 0인지 확인하고 finding이 있으면 영향 파일만 수정한 뒤 해당 검증을 재실행한다.
- [ ] Lore trailer를 포함한 한국어 commit을 만들고 `fix/uuid-public-abi`를 push한다.
- [ ] Issue #719의 milestone/labels/assignee를 mirror한 한국어 PR을 `develop` 대상으로 생성한다.
- [ ] PR body 마지막 heading을 `## DoD Status`로 유지하고 exact head·checks·review를 live read-back한다.
- [ ] merge는 수행하지 않고 fresh merge approval 대기 상태로 남긴다.

최종 명령: `git diff --check`, `gh pr view <number> --json ...`, exact head SHA 비교.
