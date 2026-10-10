# Issue #885 Gradle 의존성 그래프와 중앙 catalog 정렬

## 배경

`[#885](https://github.com/bluetape4k/bluetape4k-exposed/issues/885)`은 성공한 Dependency Submission 뒤 Dependabot이 같은 SHA의 Maven 좌표를 제출된 그래프에서 찾지 못했다고 보고한 문제다. 저장소의 플러그인 buildscript 구성과 일반 프로젝트 구성이 서로 다른 전이 의존성 버전을 선택했다. `buildEnvironment`와 프로젝트 구성은 서로 다른 Gradle 의존성 그래프이므로 둘 다 조사해야 한다.

## 결정

공유 버전은 바꾸지 않고 immutable `bluetape4k-dependencies` catalog를 단일 버전 원본으로 유지한다. buildscript classpath와 프로젝트 구성의 의존성 선택 규칙은 해당 catalog에서 목표 좌표 버전을 읽는다. Gradle 플러그인의 오래된 전이 의존성 요청은 exact module metadata 규칙으로 해당 edge와 constraint만 제거하고 catalog 버전을 다시 선언한다. 각 프로젝트의 metadata 규칙을 유지하면서 buildscript 및 프로젝트 구성의 선택 규칙을 분리해 적용한다.

검증 작업은 각 프로젝트의 모든 resolvable buildscript 구성과 프로젝트 구성을 조사한다. 목표 좌표의 선택 버전이 catalog와 다르거나 해석되지 않으면 실패한다. 그래프에 목표 좌표가 없다는 이유만으로 실패하지는 않는다. 해당 좌표가 실제로 제출되고 Dependabot 경고에서 사라졌는지는 hosted 검증으로 확인한다.

CI는 고정된 중앙 catalog checkout을 검증한 뒤 같은 파일 경로를 `BLUETAPE4K_DEPENDENCIES_CATALOG_PATH`로 전달하고, 정렬 task를 configuration cache 없이 실행한다. Dependency Submission도 dependency graph를 올리기 직전에 같은 정렬 task를 실행한다. CI는 `settings.gradle.kts`의 `bluetape4kDependenciesCatalogRef` 선언을 읽고, 고정된 중앙 checker가 요구하는 workflow ref mirror와 일치하는지 확인한 뒤 같은 immutable ref를 사용한다.

## CI catalog ref 검사 계약

고정된 중앙 `sync-shared-versions.py`는 `settings.gradle.kts`의 immutable ref와 `.github/workflows/ci.yml`의 `BLUETAPE4K_DEPENDENCIES_CATALOG_REF` 값을 비교한다. workflow에서 YAML 값을 없애고 step output만 사용하면 checker가 `central=<missing>`으로 판단해 `catalog-governance`가 실패한다. 따라서 CI는 `settings.gradle.kts`의 지정 변수 선언에서 fallback SHA를 읽고, checker가 요구하는 workflow 값과 일치하는지 확인한 뒤 해당 ref로 catalog를 checkout한다.

workflow 값은 고정된 checker와의 호환을 위한 commit ref 검사용 값이다. 의존성 alias와 버전 값의 기준 데이터 원본은 계속 중앙 catalog 하나뿐이다. parser는 파일 어디에나 있는 `.orElse("<SHA>")`를 찾지 말고 지정한 변수 선언만 해석해야 한다. 무관한 SHA가 추가되어도 선택 결과가 바뀌지 않는지, ref 불일치·선언 누락·중복 선언을 거부하는지 fixture로 확인한다.

## 결과

이전 회귀 실행은 플러그인과 프로젝트 그래프에서 중앙 버전과 다른 선택을 찾아 실패했다. 규칙 등록 시점을 프로젝트 평가 뒤로 옮겨 플러그인이 추가한 resolution rule 뒤에서도 중앙 버전이 최종 선택되도록 했다. buildscript의 모든 resolvable 구성을 검증 범위에 포함했다.

고정 catalog commit `198147fd6ca2992fae32e175bd5f3d396c497096`으로 실행한 로컬 검증:

- `./gradlew help --no-daemon` 통과.
- `./gradlew verifyCentralDependencySnapshotAlignment --no-daemon --no-configuration-cache` 통과. 출력: `absent coordinates: none`.
- `./gradlew verifyJacksonSecurityPublicationMetadata --no-daemon --no-configuration-cache` 통과. Jackson 2/3 검증 기대 버전은 `bt4kVersion("jackson2")`와 `bt4kVersion("jackson3")`에서 읽는다.
- Dependency Submission과 같은 환경 변수를 지정하지 않은 `verifyCentralDependencySnapshotAlignment` 실행이 통과했다. 기본 immutable catalog에서 모든 resolvable buildscript/project 구성을 확인하고 `absent coordinates: none`을 출력했다.
- `:bluetape4k-exposed-core:dependencyInsight`에서 `bcprov-jdk18on:1.85.2`가 중앙 catalog 규칙으로 선택됨.
- compile-only 전체 빌드 통과. 테스트 task와 ABI/Kover 검증은 제외했다.
- 고정 중앙 checker `bluetape4k-dependencies@198147fd6ca2992fae32e175bd5f3d396c497096`를 feature worktree에 직접 적용한 `sync-shared-versions.py --check --summary`와 두 workflow의 `actionlint` 통과.
- catalog ref resolver fixture 통과: 정확한 선언은 선택하고, 무관한 SHA는 무시하며, workflow ref 불일치·선언 누락·중복 선언은 거부한다.
- hosted PR CI, Dependency Submission, Dependabot 재실행, 보안 경고 상태는 아직 검증하지 않았다. 이슈 #885는 계속 OPEN으로 둔다.

## 교정한 workflow 상태 처리

작업을 재개할 때 `main-work` lane의 소유자를 확인하지 않고 `agent_id=root`로 heartbeat를 시도해 `coordinator_conflict`를 받았다. 이어서 `probe-sent`를 기록하기 전에 native agent 목록을 조회했다. 이 시도는 receipt를 바꾸지 않았지만 liveness gate 증거도 남기지 못했다. 후속 리뷰 lane에서는 native spawn 결과를 바로 `startup-ack`으로 기록하지 않아 deadline을 넘겼다. coordinator가 반환한 상태에 따라 `startup-ack` 또는 `liveness-check`와 stall/probe 절차를 기록하고, lane owner나 상태를 추측하지 않는다.

사용자는 중앙 catalog 변경에서 작업을 멈추지 말고 Exposed consumer의 Dependency Submission, Dependabot 실행, 경고 상태까지 확인하라고 바로잡았다. 중앙 catalog 반영은 중간 단계다. hosted consumer 증거와 이슈 완료 조건을 확인하기 전에는 #885를 OPEN으로 둔다.

## 검증 범위와 제한

- 중앙 정렬 task는 모든 resolvable buildscript 및 프로젝트 구성을 검사하고, 목표 좌표의 선택 버전과 해석 여부를 확인한다.
- `help`, 정렬 task, compile-only build는 로컬 통과 증거다. compile-only build는 unit/integration test를 포함하지 않는다.
- 로컬 정적 검사는 catalog 도입 스크립트와 `actionlint`가 통과했다. hosted job 결과는 PR 이후 확인한다.
- Gradle configuration cache를 사용하는 정렬 task는 실행 시점 `Project` 참조 직렬화 오류로 실패한다. CI에서는 이 task만 `--no-configuration-cache`로 실행한다.
- hosted Dependabot 오류 및 보안 경고 해소는 별도 원격 게이트다.

## 향후 기준

1. Dependency Submission 누락을 조사할 때 buildscript 그래프와 프로젝트 구성을 구분하고, 같은 SHA의 제출 그래프와 Dependabot 실패를 대조한다.
2. 요청 버전과 최종 선택 버전을 나눠 본다. `eachDependency`는 선택 버전을 바꿀 수 있으므로 오래된 metadata edge가 영향을 주면 exact component metadata 규칙을 좁게 적용한다.
3. 공용 의존성 버전은 중앙 catalog에서만 관리한다. 소비 저장소는 alias에서 버전을 읽고 buildscript와 프로젝트 해석에 적용한다.
4. 영향을 받는 모든 resolvable 구성을 CI에서 검사한다. 검증 task가 실행 시점 `Project`를 참조하면 해당 task에만 configuration cache를 끈다.
5. hosted Dependency Submission, Dependabot 실행, 보안 경고 갱신 근거를 확인하기 전에는 이슈를 닫지 않는다.
6. 중앙 adoption checker가 요구하는 workflow ref 검사용 값을 임의로 제거하지 않는다. 고정된 checker 계약을 확인하고, `settings.gradle.kts`의 지정 변수 선언과 CI 값을 비교해 다르면 checkout 전에 실패시킨다. dependency 버전은 중앙 catalog 하나에서 읽는다.
7. ref resolver는 지정 변수 선언만 파싱하고 무관한 SHA, 불일치, 누락, 중복 fixture로 실패 동작을 검증한다.
8. resumed workflow에서 lane 상태를 바꿀 때 owner를 추측하지 않는다. native spawn 결과를 즉시 `startup-ack`으로 기록하고, 의심된 stall의 native probe 또는 agent 목록 조회 전에는 `probe-sent`를 기록한다.
