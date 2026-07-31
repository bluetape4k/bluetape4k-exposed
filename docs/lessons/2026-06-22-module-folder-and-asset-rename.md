# 모듈 폴더 및 README 자산 이름 변경

Date: 2026-06-22
Repo: `bluetape4k-exposed`

## 배경

이 저장소는 `exposed/exposed-core`, `spring-boot/exposed-jdbc`처럼 물리 폴더에
published-style 이름을 유지했습니다. 아티팩트 명명 계약이 안정된 뒤에는 이 접두사가
매핑되는 Gradle 프로젝트 이름보다 로컬 경로를 더 장황하게 만들었습니다.

README 다이어그램과 chart 자산 이름에도 `exposed-exposed-*`라는 중복 패턴이 있었고,
BOM 자산은 `exposed-bluetape4k-exposed-bom-*`을 사용했습니다.

## 결정

Gradle 프로젝트 이름과 Maven 아티팩트 이름은 변경하지 않고, 물리 경로와 README에
노출되는 자산 파일 이름만 단순화합니다.

- `exposed/exposed-core` -> `exposed/core`
- `exposed/bluetape4k-exposed-bom` -> `exposed/bom`
- `spring-boot/exposed-jdbc` -> `spring-boot/jdbc`
- `spring-boot/exposed-spring-modulith` -> `spring-boot/spring-modulith`
- `docs/images/readme-diagrams/exposed-exposed-core-diagram-01.png` ->
  `docs/images/readme-diagrams/exposed-core-diagram-01.png`
- `docs/images/readme-diagrams/exposed-bluetape4k-exposed-bom-diagram-01.png` ->
  `docs/images/readme-diagrams/exposed-bom-diagram-01.png`

`spring-boot/batch-exposed`는 `exposed-` 접두사 패턴을 사용하지 않고 이름이 Spring
Batch 통합을 설명하므로 그대로 유지했습니다.

## 검증

이런 이름 변경에는 경로 수준 검증을 사용합니다.

- `./gradlew -q projects --no-configuration-cache --no-daemon`으로 프로젝트 이름이
  여전히 새 디렉터리에 매핑되는지 증명합니다.
- `./gradlew build -x test --parallel --no-configuration-cache --no-daemon`으로
  compile-only build wiring이 계속 동작하는지 증명합니다.
- workflow path-filter를 수정한 뒤에는
  `actionlint .github/workflows/ci.yml .github/workflows/migration-smoke.yml`을
  실행합니다.
- SVG geometry가 아니라 이름과 참조만 바뀌었으므로 이름을 바꾼 SVG 자산에는
  `xmllint --noout`을 실행합니다.
- 자산 경로를 바꾼 뒤 README 이미지 참조의 존재 여부를 검사합니다.
- `git diff --check` before commit.

## 후속 보호 장치

앞으로 `exposed/` 아래에 모듈을 추가할 때는 짧은 물리 폴더 이름을 사용하고
`settings.gradle.kts`로 published-style Gradle 프로젝트 이름에 매핑해야 합니다.
README에 노출되는 생성 자산 이름은 디렉터리 또는 아티팩트 접두사를 반복하지 않아야
합니다.
