# Issue #77 ArtifactId 계약 이름 변경 설계

## 요약

첫 공개 release 전에 `bluetape4k-exposed` 저장소의 게시 artifactId와
Gradle project path를 긴 `bluetape4k-*` coordinate에서 짧은 Exposed
domain coordinate로 변경한다.

groupId는 변경하지 않는다.

```text
io.github.bluetape4k.exposed
```

새 artifact 계약은 artifactId에서 반복되는 `bluetape4k-` prefix를 제거하고
artifactId를 저장소 domain과 일치시킨다.

## 범위

이 spec은 순서상 첫 PR인 `bluetape4k-exposed` 저장소 이름 변경을 다룬다.

포함 범위:

- `settings.gradle.kts`의 Gradle project path 이름 변경
- project name을 통한 Maven publication artifactId 변경
- 내부 `project(":...")` dependency 갱신
- BOM constraint와 BOM POM name 갱신
- root 및 module README coordinate 갱신
- `AGENTS.md` / `CLAUDE.md` command와 module mapping 갱신
- `AGENTS.md` / `CLAUDE.md`의 groupId를 `io.github.bluetape4k.exposed`로 수정
- CI와 Nightly workflow task path 갱신
- 변경된 coordinate의 generated POM 검증
- 현재 old -> new coordinate migration note인
  `docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md`

이 PR의 범위 밖:

- `bluetape4k-dependencies` 갱신
- consumer/example repository 갱신
- remote Nightly(full)과 snapshot publish 실행
- PR merge

이 작업은 PR CI와 Nightly(full)이 성공하고 PR이 merge되며 새 Exposed
snapshot이 게시된 뒤에만 진행한다.

## 결정

| Current artifactId | New artifactId |
|---|---|
| `bluetape4k-exposed-core` | `exposed-core` |
| `bluetape4k-exposed-dao` | `exposed-dao` |
| `bluetape4k-exposed-jdbc` | `exposed-jdbc` |
| `bluetape4k-exposed-r2dbc` | `exposed-r2dbc` |
| `bluetape4k-exposed-jdbc-tests` | `exposed-jdbc-tests` |
| `bluetape4k-exposed-r2dbc-tests` | `exposed-r2dbc-tests` |
| `bluetape4k-exposed-cache` | `exposed-cache` |
| `bluetape4k-exposed-jdbc-caffeine` | `exposed-jdbc-caffeine` |
| `bluetape4k-exposed-jdbc-lettuce` | `exposed-jdbc-lettuce` |
| `bluetape4k-exposed-jdbc-redisson` | `exposed-jdbc-redisson` |
| `bluetape4k-exposed-r2dbc-caffeine` | `exposed-r2dbc-caffeine` |
| `bluetape4k-exposed-r2dbc-lettuce` | `exposed-r2dbc-lettuce` |
| `bluetape4k-exposed-r2dbc-redisson` | `exposed-r2dbc-redisson` |
| `bluetape4k-exposed-jackson2` | `exposed-jackson2` |
| `bluetape4k-exposed-jackson3` | `exposed-jackson3` |
| `bluetape4k-exposed-fastjson2` | `exposed-fastjson2` |
| `bluetape4k-exposed-tink` | `exposed-tink` |
| `bluetape4k-exposed-measured` | `exposed-measured` |
| `bluetape4k-exposed-postgresql` | `exposed-postgresql` |
| `bluetape4k-exposed-mysql8` | `exposed-mysql8` |
| `bluetape4k-exposed-bigquery` | `exposed-bigquery` |
| `bluetape4k-exposed-clickhouse` | `exposed-clickhouse` |
| `bluetape4k-exposed-trino` | `exposed-trino` |
| `bluetape4k-exposed-duckdb` | `exposed-duckdb` |
| `bluetape4k-exposed-timefold-solver-persistence` | `exposed-timefold-solver-persistence` |
| `bluetape4k-spring-boot-exposed-jdbc` | `exposed-spring-boot-jdbc` |
| `bluetape4k-spring-boot-exposed-r2dbc` | `exposed-spring-boot-r2dbc` |
| `bluetape4k-spring-boot-batch-exposed` | `exposed-spring-boot-batch` |
| `bluetape4k-spring-boot-exposed-spring-modulith` | `exposed-spring-modulith` |
| `bluetape4k-batch` | `exposed-batch` |
| `bluetape4k-exposed-bom` | `exposed-bom` |
| `bluetape4k-spring-boot-exposed-jdbc-demo` | `exposed-spring-boot-jdbc-demo` |
| `bluetape4k-spring-boot-exposed-r2dbc-demo` | `exposed-spring-boot-r2dbc-demo` |
| `bluetape4k-examples-exposed-clickhouse-oltp-olap` | `examples-exposed-clickhouse-oltp-olap` |

Demo와 example module은 내부 Gradle project이지만 workflow task path를
일관되게 유지하기 위해 짧은 저장소 내부 project path 규칙을 따른다.

- `bluetape4k-spring-boot-exposed-jdbc-demo` -> `exposed-spring-boot-jdbc-demo`
- `bluetape4k-spring-boot-exposed-r2dbc-demo` -> `exposed-spring-boot-r2dbc-demo`
- `bluetape4k-examples-exposed-clickhouse-oltp-olap` -> `examples-exposed-clickhouse-oltp-olap`

`exposed-spring-modulith`는 의도적인 naming exception이다. 이 artifact는
Exposed JDBC 기반 Spring Modulith adapter이므로
`exposed-spring-boot-modulith`보다 간결한 domain integration 이름을
사용한다. `exposed-spring-boot-jdbc` dependency는 Gradle metadata에
명시적으로 유지한다.

## 현재 근거

`settings.gradle.kts`는 현재 `val projectName = "bluetape4k"`에서 이름을 파생한다.

- `includeModules("exposed", withBaseDir = false)`는 `bluetape4k-exposed-*`를 생성한다.
- `includeModules("utils", withBaseDir = false)`는 `bluetape4k-batch`를 생성한다.
- `includeModules("spring-boot", withBaseDir = true)`는 `bluetape4k-spring-boot-*`를 생성한다.
- `includeModules("examples", withBaseDir = true)`는 `bluetape4k-examples-*`를 생성한다.

`build.gradle.kts`와 `exposed/exposed-bom/build.gradle.kts`는
`bluetape4k-exposed-bom`을 특별 취급하므로 BOM 이름 변경 시 해당 guard를
함께 갱신해야 한다.

`ci.yml`과 `nightly.yml`에는 기존 Gradle project path가 하드코딩되어 있다.
따라서 workflow 변경도 같은 atomic PR에 포함한다.

`publish-snapshot.yml`은 `develop`의 `Nightly` `workflow_run`을 기준으로
실행된다. PR branch Nightly(full)은 snapshot을 게시하지 않는다. snapshot
publish는 `develop` merge 후 또는 `develop` 대상 manual dispatch로만 검증할 수 있다.

`repo1.maven.org` metadata spot check에서는 대표 기존/신규 coordinate가 모두
`404`를 반환했다. 이는 이번 이름 변경을 공개 전 계약 정리로 취급할 근거다.

## architecture 선택지

### Option A: Maven artifactId만 재정의

Gradle project path는 유지하고 publication `artifactId`를 수동 설정한다.

거부한다. 기존 이름이 모든 Gradle task, CI/Nightly job, Kover aggregation,
developer command에 남고 Gradle project path와 Maven coordinate 사이에 drift가 생긴다.

### Option B: Gradle project path와 Maven coordinate를 함께 변경

`settings.gradle.kts` 이름 규칙을 변경하여 두 이름을 같은 짧은 값으로 수렴시킨다.

채택한다. local change는 더 크지만 project path, generated POM, BOM
constraint, workflow task path, 사용자 dependency snippet을 일치시킨다.

### Option C: directory도 이동

`exposed/exposed-core` 같은 directory를 `exposed/core`로 변경한다.

거부한다. directory 이름은 유용한 context를 제공하고 공개 Maven coordinate가
아니다. 이동은 게시 계약을 개선하지 않으면서 file churn만 늘린다.

## 설계

### Gradle project 이름

`settings.gradle.kts`의 전역 `bluetape4k` prefix를 explicit include 규칙으로 바꾼다.

- `exposed/*` -> directory name (`exposed-core`, `exposed-jdbc`, ...)
- `utils/batch` -> `exposed-batch`
- `spring-boot/exposed-jdbc` -> `exposed-spring-boot-jdbc`
- `spring-boot/exposed-r2dbc` -> `exposed-spring-boot-r2dbc`
- `spring-boot/batch-exposed` -> `exposed-spring-boot-batch`
- `spring-boot/exposed-spring-modulith` -> `exposed-spring-modulith`
- Spring Boot demo module은 `-demo`를 붙인 같은 짧은 규칙을 따른다.
- `examples/*` -> `examples-*`

`spring-boot`와 `utils`에는 복잡한 generic rule보다 manual mapping을
사용한다. module 수가 적고 새 이름이 계약의 일부이기 때문이다.

```kotlin
includeModules("exposed", withBaseDir = false)
includeModules("examples", withBaseDir = true)

includeMappedModule("utils/batch", "exposed-batch")

includeMappedModule("spring-boot/exposed-jdbc", "exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/exposed-r2dbc", "exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "exposed-spring-boot-batch")
includeMappedModule("spring-boot/exposed-spring-modulith", "exposed-spring-modulith")
includeMappedModule("examples/jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("examples/r2dbc-demo", "exposed-spring-boot-r2dbc-demo")
```

### Maven publication

project path가 원하는 artifactId를 표현하므로 일반 `MavenPublication`은 계속
`project.name`을 사용한다.

불필요한 task name 변경을 피하기 위해 publication name `BluetapeExposed`를
유지한다. 검증 command도 `generatePomFileForBluetapeExposedPublication`을
계속 사용한다.

BOM module 변경:

- project path: `:exposed-bom`
- POM name: `exposed-bom`
- constraints guard: `exposed-bom` 제외

Root build에서도 모든 BOM name special case를 갱신한다.

- `build.gradle.kts` subproject skip guard
- `build.gradle.kts` Kover aggregation exclusion
- `exposed/exposed-bom/build.gradle.kts` constraints guard와 POM name

Root publish aggregation은 example과 demo를 제외한 모든 project를 계속 포함한다.

### 내부 dependency

모든 내부 `project(":bluetape4k-...")` reference를 대응하는 짧은 project path로 변경한다.

- `project(":bluetape4k-exposed-core")` -> `project(":exposed-core")`
- `project(":bluetape4k-spring-boot-exposed-jdbc")` -> `project(":exposed-spring-boot-jdbc")`
- `project(":bluetape4k-batch")` -> `project(":exposed-batch")`

### Workflow

`ci.yml`과 `nightly.yml`의 task path를 변경한다.

- `:bluetape4k-exposed-core:test` -> `:exposed-core:test`
- `:bluetape4k-spring-boot-exposed-jdbc:test` -> `:exposed-spring-boot-jdbc:test`
- `:bluetape4k-spring-boot-batch-exposed:test` -> `:exposed-spring-boot-batch:test`
- `:bluetape4k-batch:test` -> `:exposed-batch:test`

directory를 이동하지 않으므로 path filter의 의미는 변경하지 않는다.

PR push 전에 `actionlint`를 실행하고 기존 project path 및 잘못된 shell
quoting artifact가 없는지 확인한다.

- `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml` 결과 없음
- `rg -n -F "\\'" .github/workflows` 결과 없음

### 문서

다음 활성 문서를 갱신한다.

- root `README.md`와 `README.ko.md`
- `exposed/`, `spring-boot/`, `utils/` 아래 module README locale pair
- `exposed-bom` README locale pair
- `AGENTS.md`와 `CLAUDE.md`
- 현재 계약을 설명하는 research/spec 문서

과거 상태를 설명하는 historical lesson, changelog entry, 기존 design 문서는
기존 이름을 유지할 수 있다. history를 다시 쓰지 않고 현재 migration note를 추가한다.

```text
docs/superpowers/migrations/2026-05-16-issue-77-artifact-id-migration.md
```

migration note에는 old -> new coordinate 표와 downstream rollout gate 순서를
포함하여 `bluetape4k-dependencies` 및 consumer repository PR이 동일한 계약을
사용하게 한다.

### Downstream 순서

이 PR은 새 Exposed surface를 준비하는 데 한정한다. merge 뒤 조직 전체 rollout은 다음 순서다.

1. Exposed를 `develop`에 merge
2. Exposed `develop` Nightly(full)
3. Exposed snapshot publish
4. Central Snapshots에서 변경 coordinate resolution 확인
5. `bluetape4k-dependencies` 갱신 및 snapshot publish
6. dependencies snapshot을 사용하는 consumer/example repository 갱신

Exposed snapshot resolution을 확인하기 전에는 `bluetape4k-dependencies` PR을
막는다. consumer repository가 dependencies catalog/BOM을 먼저 사용하므로
dependencies snapshot 게시 전에는 consumer PR도 막는다.

## 검증

PR 전 local 검증:

- `./gradlew -q projects`
- `./gradlew -q projects | rg 'bluetape4k-exposed-|bluetape4k-spring-boot-|bluetape4k-examples-|bluetape4k-batch'`는 project path 결과가 없어야 한다.
- 대표 module의 generated POM task는 현재 publication task graph가 configuration cache와 호환되지 않으므로 `--no-configuration-cache`를 사용한다.
  - `:exposed-core:generatePomFileForBluetapeExposedPublication`
  - `:exposed-jdbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-r2dbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-bom:generatePomFileForBluetapeExposedPublication`
  - `:exposed-batch:generatePomFileForBluetapeExposedPublication`
  - `:exposed-spring-boot-jdbc:generatePomFileForBluetapeExposedPublication`
  - `:exposed-spring-modulith:generatePomFileForBluetapeExposedPublication`
- generated POM에서 다음 artifactId를 확인한다.
  - `<artifactId>exposed-core</artifactId>`
  - `<artifactId>exposed-jdbc</artifactId>`
  - `<artifactId>exposed-r2dbc</artifactId>`
  - `<artifactId>exposed-bom</artifactId>`
  - `<artifactId>exposed-batch</artifactId>`
  - `<artifactId>exposed-spring-boot-jdbc</artifactId>`
  - `<artifactId>exposed-spring-modulith</artifactId>`
- 대상 test:
  - `./gradlew :exposed-core:test :exposed-dao:test --no-daemon`
  - `./gradlew :exposed-jdbc:test :exposed-jdbc-tests:test --no-daemon`
  - `./gradlew :exposed-r2dbc:test :exposed-r2dbc-tests:test --no-daemon`
  - `./gradlew :exposed-cache:test :exposed-jdbc-caffeine:test :exposed-r2dbc-caffeine:test --no-daemon`
  - `./gradlew :exposed-spring-boot-jdbc:test :exposed-spring-boot-r2dbc:test :exposed-spring-modulith:test --no-daemon`
  - `./gradlew :exposed-spring-boot-batch:test :exposed-batch:test --no-daemon`
- workflow:
  - `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml`
  - `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml` 결과 없음
  - `rg -n -F "\\'" .github/workflows` 결과 없음

Downstream PR 전 remote 검증:

- PR CI 성공
- PR branch Nightly(full) 성공
- merge 후 `develop` Nightly(full) 성공
- `develop` snapshot publish 성공
- 자동 `workflow_run` trigger가 보이지 않으면 `develop`에서 `publish-snapshot.yml`을 manual dispatch
- 다음 대표 snapshot coordinate가 resolve되는지 확인:
  - `io.github.bluetape4k.exposed:exposed-bom`
  - `io.github.bluetape4k.exposed:exposed-core`
  - `io.github.bluetape4k.exposed:exposed-jdbc`
  - `io.github.bluetape4k.exposed:exposed-r2dbc`
  - `io.github.bluetape4k.exposed:exposed-batch`
  - `io.github.bluetape4k.exposed:exposed-spring-boot-jdbc`
  - `io.github.bluetape4k.exposed:exposed-spring-modulith`

## 위험

| 위험 | 완화 |
|---|---|
| Gradle project path 변경으로 내부 dependency가 깨짐 | 모든 `project(":bluetape4k-*")` reference를 치환하고 대표 compile/test 실행 |
| workflow task path 누락으로 CI/Nightly 실패 | `ci.yml`과 `nightly.yml` 갱신, `actionlint`, workflow task path 검사 |
| changed-module CI가 변경 surface를 놓침 | Nightly(full)을 merge gate로 사용 |
| BOM constraint가 기존 이름을 게시 | generated BOM POM 검사와 BOM build 검증 |
| Demo/example module이 publish aggregation에 포함 | example과 `-demo` 제외 filter 유지, 필요 시 publishable project 확인 |
| Downstream repository가 dependencies snapshot보다 먼저 진행 | dependencies snapshot publish 전 consumer PR 차단 |
| Historical 문서를 잘못 다시 작성 | 활성 문서만 갱신하고 현재 설치 안내가 아니면 historical record 유지 |

## 후속 작업

- Exposed snapshot publish 후 `bluetape4k-dependencies`에 별도 PR이 필요하다.
- dependencies snapshot publish 후 consumer repository에 별도 PR이 필요하다.
- remote Nightly(full) 또는 snapshot publish 실패 시 downstream PR을 차단한다.
