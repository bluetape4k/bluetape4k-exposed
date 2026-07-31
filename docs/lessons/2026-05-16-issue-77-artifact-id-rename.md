# Lessons Learned - Issue #77 ArtifactId Rename

## L1: 저장소 식별자와 Maven 좌표를 분리한다

artifact 이름을 바꾸더라도 저장소 식별 문자열까지 변경해서는 안 됩니다. 저장소
자체의 이름을 바꾸는 경우가 아니라면 다음 항목은 그대로 유지합니다.

- `rootProject.name = "bluetape4k-exposed"`
- `bluetape4k-exposed`로 끝나는 GitHub 및 SCM URL
- 저장소 slug를 참조하는 README/WIP/SECURITY 항목
- `io.bluetape4k.exposed` 아래의 Kotlin package 이름

Maven 좌표만 group `io.github.bluetape4k.exposed` 아래에서 변경합니다.

## L2: 디렉터리와 다른 이름은 명시적으로 매핑한다

`exposed/*`에는 디렉터리 기반 프로젝트 이름이 안전하지만, Spring Boot, utility,
demo, example 경로에는 명시적 매핑이 필요합니다. 다음 전용
`includeMappedModule` 항목을 사용합니다.

- `utils/batch` -> `:exposed-batch`
- `spring-boot/exposed-jdbc` -> `:exposed-spring-boot-jdbc`
- `spring-boot/exposed-r2dbc` -> `:exposed-spring-boot-r2dbc`
- `spring-boot/batch-exposed` -> `:exposed-spring-boot-batch`
- `spring-boot/exposed-spring-modulith` -> `:exposed-spring-modulith`

`exposed-spring-modulith`는 의도적으로
`exposed-spring-boot-modulith`가 아닙니다.

## L3: 생성된 POM으로 ArtifactId를 검증한다

프로젝트 경로를 바꾸는 것만으로는 충분하지 않습니다. `--no-configuration-cache`로
대표 publication POM을 생성하고 최상위 `<artifactId>` 값과 BOM constraint를
확인합니다. BOM은 `pom` packaging의 `exposed-bom`으로 publication해야 하며,
constraint에는 이름이 바뀐 exposed module을 포함하고 demo 및 example은
제외해야 합니다.

## L4: CI/Nightly 경로도 계약의 일부다

workflow task path는 Gradle graph와 함께 이름을 바꿔야 합니다. 항상 다음을
실행합니다.

- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/publish-snapshot.yml`
- `rg -n 'bluetape4k-' .github/workflows/{ci,nightly}.yml`
- `rg -n -F "\\\\'" .github/workflows`

두 번째와 세 번째 검사는 결과가 없어야 합니다.

## L5: downstream 적용 순서가 중요하다

exposed PR을 merge한 직후 consumer 저장소를 업데이트하지 않습니다. 안전한
적용 순서는 다음과 같습니다.

1. PR CI와 PR branch Nightly(full)가 통과한 뒤 exposed rename PR을 merge합니다.
2. exposed `develop`에서 Nightly(full)를 실행합니다.
3. exposed snapshot을 publication합니다.
4. Central Snapshots에서 대표적인 이름 변경 좌표가 resolve되는지 확인합니다.
5. 먼저 `bluetape4k-dependencies` snapshot을 업데이트하고 publication합니다.
6. dependencies snapshot을 기준으로 consumer/example 저장소를 업데이트합니다.

consumer 저장소는 dependencies catalog/BOM에 의존하므로 dependencies snapshot이
첫 번째 downstream 차단 조건입니다.

## L6: downstream generator가 새 settings 형태를 이해해야 한다

`bluetape4k-dependencies`는 version만 올리지 않습니다. 관리 저장소를 읽어
catalog alias와 BOM constraint를 생성합니다. 이 rename 이후에는 sync script가
`includeMappedModule(...)` 및 `includeModules("exposed", withBaseDir = false)`
관례를 이해해야 합니다. 그렇지 않으면 오래된 가정에서
`bluetape4k-exposed-*` alias를 다시 생성할 수 있습니다. 이는 consumer 저장소
정리가 아니라 dependencies PR의 일부로 처리합니다.

## 검증 현황

구현 branch의 로컬 검증은 project graph, 이전 path 부재, 생성된 publication POM,
workflow 문법, 그리고 H2, PostgreSQL, MySQL 8 경로의 targeted test를 다뤘습니다.
원격 PR CI, PR branch Nightly(full), develop Nightly(full), snapshot publication,
downstream 저장소 PR은 PR 이후의 gate로 남아 있습니다.
