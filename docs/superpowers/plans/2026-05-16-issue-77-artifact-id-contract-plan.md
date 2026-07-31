# Issue #77 ArtifactId 계약 이름 변경 계획

## 목표

공개 release 전에 `bluetape4k-exposed` 저장소의 Gradle project path와
게시 artifactId를 더 짧은 Exposed domain 계약으로 변경한다. directory
layout과 groupId는 유지한다.

기준 spec:

```text
docs/superpowers/specs/2026-05-16-issue-77-artifact-id-contract-design.md
```

## 중단 조건

다음 항목 중 하나라도 남아 있으면 PR push 전에 중단한다.

- `./gradlew -q projects`가 이 저장소 module의 기존 `bluetape4k-*` project path를 출력한다.
- 대표 generated POM에 새 artifactId가 없다.
- Gradle script에 `project(":bluetape4k-...")` reference가 남아 있다.
- historical 문서가 아닌 Kotlin source에 기존 저장소 내부 artifact 이름이 남아 있다.
- `ci.yml` 또는 `nightly.yml`이 기존 project path를 참조한다.
- 이름 변경과 관련된 compile/configuration 이유로 대상 local test가 실패한다.
- 변경한 workflow에 대해 `actionlint`가 실패한다.

이 작업 항목에서는 PR을 merge하지 않는다. Remote CI, PR branch
Nightly(full), develop Nightly(full), snapshot publish,
`bluetape4k-dependencies` snapshot publish, consumer repository PR은 순차
후속 gate로 남긴다.

## 구현 순서

1. `settings.gradle.kts`를 갱신한다.
   - Exposed 및 example module에서 `bluetape4k` prefix를 제거한다.
   - `utils/batch`와 demo를 포함한 모든 `spring-boot/*` module을 explicit mapped include로 등록한다.
   - directory는 변경하지 않는다.
   - `rootProject.name = "bluetape4k-exposed"`는 게시 artifactId가 아니라 repository slug이므로 유지한다.

2. project path와 artifactId를 기계적으로 치환한다.
   - `:bluetape4k-exposed-*` -> `:exposed-*`
   - `:bluetape4k-spring-boot-exposed-jdbc` -> `:exposed-spring-boot-jdbc`
   - `:bluetape4k-spring-boot-exposed-r2dbc` -> `:exposed-spring-boot-r2dbc`
   - `:bluetape4k-spring-boot-batch-exposed` -> `:exposed-spring-boot-batch`
   - `:bluetape4k-spring-boot-exposed-spring-modulith` -> `:exposed-spring-modulith`
   - `:bluetape4k-batch` -> `:exposed-batch`
   - `:bluetape4k-spring-boot-exposed-jdbc-demo` -> `:exposed-spring-boot-jdbc-demo`
   - `:bluetape4k-spring-boot-exposed-r2dbc-demo` -> `:exposed-spring-boot-r2dbc-demo`
   - `:bluetape4k-examples-exposed-clickhouse-oltp-olap` -> `:examples-exposed-clickhouse-oltp-olap`

   다음 repository identity 문자열은 변경하지 않는다.

   - `https://github.com/bluetape4k/bluetape4k-exposed`
   - `scm:git:git://github.com/bluetape4k/bluetape4k-exposed.git`
   - `scm:git:ssh://github.com/bluetape4k/bluetape4k-exposed.git`
   - `rootProject.name = "bluetape4k-exposed"`
   - `BOM for bluetape4k-exposed`와 같은 repository context 설명

3. Gradle build logic을 갱신한다.
   - Root BOM skip guard: `exposed-bom`
   - Root Kover aggregation exclusion: `exposed-bom`
   - BOM constraints guard와 POM name: `exposed-bom`
   - publication name `BluetapeExposed`는 유지한다.

4. workflow를 갱신한다.
   - `.github/workflows/ci.yml`의 기존 task path를 치환한다.
   - `.github/workflows/nightly.yml`의 기존 task path를 치환한다.
   - `publish-snapshot.yml`은 project path를 참조하는 경우에만 변경한다.

5. 활성 문서를 갱신한다.
   - `AGENTS.md`와 `CLAUDE.md`: project path, command, 실제 groupId `io.github.bluetape4k.exposed`.
   - Root README locale pair와 module README pair: dependency snippet, module name, task path, artifact별 badge.
   - migration note `docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md`를 추가한다.
   - migration note에 repo URL, repo slug, root project name, groupId를 다루는 "변경되지 않는 항목" section을 포함한다.

6. local 검증을 실행한다.
   - Project graph와 기존 path 부재를 확인한다.
   - 대표 generated POM을 확인한다.
   - 대상 test batch를 실행한다.
   - workflow lint와 기존 workflow reference 부재를 확인한다.

7. PR 전에 lesson을 작성한다.
   - `docs/lessons/2026-05-16-issue-77-artifact-id-rename.md`를 추가한다.
   - 이름 변경 규칙, 검증 공백, downstream 순서를 기록한다.

8. Lore trailer를 포함해 commit하고 branch를 push한 뒤 `debop`에게 할당한 draft PR을 생성한다.
   - PR title/body는 English로 작성한다.
   - PR body에 local 검증 근거와 remote 후속 gate를 포함한다.
   - `bluetape4k-dependencies` snapshot publish가 consumer repository보다 먼저라는 downstream hard gate를 명시한다.

## 검증 command

Project path 검사:

```bash
./gradlew -q projects
./gradlew -q projects | rg 'bluetape4k-exposed-|bluetape4k-spring-boot-|bluetape4k-examples-|bluetape4k-batch'
rg -n 'project\(":bluetape4k-' **/*.gradle.kts settings.gradle.kts
rg -n 'bluetape4k-(exposed|spring-boot-|batch|examples-)' --type kotlin
```

마지막 세 command는 결과가 없어야 한다. `docs/superpowers/specs/**`와
`docs/lessons/**`의 historical 문서는 과거 상태를 명시적으로 설명하는
경우에만 기존 이름을 유지할 수 있다.

대표 POM 생성:

```bash
./gradlew \
  :exposed-core:generatePomFileForBluetapeExposedPublication \
  :exposed-jdbc:generatePomFileForBluetapeExposedPublication \
  :exposed-r2dbc:generatePomFileForBluetapeExposedPublication \
  :exposed-bom:generatePomFileForBluetapeExposedPublication \
  :exposed-batch:generatePomFileForBluetapeExposedPublication \
  :exposed-spring-boot-jdbc:generatePomFileForBluetapeExposedPublication \
  :exposed-spring-modulith:generatePomFileForBluetapeExposedPublication \
  --no-daemon \
  --no-configuration-cache
```

Generated POM artifactId 검사:

```bash
rg -n '<artifactId>(exposed-core|exposed-jdbc|exposed-r2dbc|exposed-bom|exposed-batch|exposed-spring-boot-jdbc|exposed-spring-modulith)</artifactId>' \
  **/build/publications/BluetapeExposed/pom-default.xml
rg -n '<packaging>pom</packaging>' exposed/exposed-bom/build/publications/BluetapeExposed/pom-default.xml
```

artifactId 검사는 적어도 7개 결과를 반환해야 한다.

대상 test:

```bash
./gradlew :exposed-core:test :exposed-dao:test --no-daemon
./gradlew :exposed-jdbc:test :exposed-jdbc-tests:test --no-daemon
./gradlew :exposed-r2dbc:test :exposed-r2dbc-tests:test --no-daemon
./gradlew :exposed-cache:test :exposed-jdbc-caffeine:test :exposed-r2dbc-caffeine:test --no-daemon
./gradlew :exposed-spring-boot-jdbc:test :exposed-spring-boot-r2dbc:test :exposed-spring-modulith:test --no-daemon
./gradlew :exposed-spring-boot-batch:test :exposed-batch:test --no-daemon
```

Workflow 검사:

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml
rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml
rg -n -F "\\'" .github/workflows
```

두 `rg` workflow 검사는 결과가 없어야 한다. escaped single-quote 검사는
workflow `run:` block에 실수로 들어간 shell quoting artifact를 방지한다.

README/reference 검사:

```bash
rg -n 'bluetape4k-(exposed|spring-boot-|batch|examples-)' README.md README.ko.md AGENTS.md CLAUDE.md exposed spring-boot utils examples
```

남은 결과는 repository identity, `bluetape4k-coroutines` 같은 이름을
변경하지 않는 bluetape4k platform dependency 또는 migration/lesson 문서에
명시한 historical context여야 한다.

## Remote 후속 gate

PR 생성 후 다음 순서를 지킨다.

1. PR CI가 성공한다.
2. PR branch Nightly(full)이 성공한다.
3. review와 remote check가 끝난 뒤에만 merge한다.
4. Develop Nightly(full)이 성공한다.
5. Exposed snapshot publish가 성공한다.
6. Central Snapshots에서 대표 변경 coordinate가 resolve되는지 확인한다.
7. `bluetape4k-dependencies` snapshot update를 열고 publish한다.
8. 그 후에만 consumer/example repository PR을 연다.

## rollback

merge 전에는 branch를 삭제하여 rollback한다.

merge 후 snapshot publish 전에는 기존 project path와 artifactId를 복원하는
revert PR을 사용한다.

snapshot publish 후에는 coordinate를 사용할 수 없는 경우가 아니라면
rollback하지 않는다. downstream repository가 명시적인 coordinate
resolution을 gate로 사용하므로 수정한 snapshot을 다시 publish하는 방식으로
진행한다.
