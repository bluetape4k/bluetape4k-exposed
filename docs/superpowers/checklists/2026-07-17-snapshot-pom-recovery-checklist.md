# Exposed 1.12.0-SNAPSHOT POM 복구 체크리스트

## 권한과 범위

- 승인된 요청: `bluetape4k-javers` PR #249를 막는 malformed `bluetape4k-exposed` SNAPSHOT POM을 복구합니다.
- 워크플로우: Type C bug fix 이후 Type P `routine-snapshot` 복구를 수행합니다.
- 저장소: `bluetape4k/bluetape4k-exposed`.
- 기준 브랜치: `develop`.
- 작업 브랜치: `fix/snapshot-pom-versions`.
- 대상 버전: `1.12.0-SNAPSHOT`.
- 최근 관찰한 외부 snapshot: source SHA `38d13d906ae7d26552f1dec46f22e3e2b541a0ab`에서 나온 timestamped build `1.12.0-20260716.201738-20`.
- 대상 source authority: POM fix를 포함해 merge된 정확한 `develop` SHA입니다. merge 전에는 알 수 없으므로 publication은 hold 상태입니다.
- Consumer scope: `8e5a15c4274b0af91e2012a5eaad5cf463e743e8`의 `bluetape4k-javers` PR #249.
- 안정 release, tag, GitHub Release, milestone closeout, BOM train, 무관한 catalog 변경은 범위 밖입니다.

## Topology

- Repository class: 현재 next-line SNAPSHOT을 사용하는 stable-capable JVM library입니다.
- Selected flow: routine snapshot.
- Snapshot edge: `bluetape4k-javers`는 `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.0-SNAPSHOT`와 `bluetape4k-exposed-jdbc:1.12.0-SNAPSHOT`에 의존합니다.
- Execution order: Exposed POM generation 복구 -> PR/CI/review -> fresh merge approval -> merge -> 정확히 merge된 `develop` SHA publish -> public snapshot 검증 -> Javers PR CI 재실행.
- Graph: 이 복구에서는 acyclic입니다. downstream publication은 요청되지 않았습니다.

## 결함 근거

- Public POM: `bluetape4k-exposed-jdbc-1.12.0-20260716.201738-20.pom`.
- Consumer failure: Gradle이 public POM을 parsing하는 중 `Required version must not be null`을 보고했습니다.
- `develop` SHA `76e6278e2e94abc16c8df030d83de97784ed2e93`에서 local reproduction을 수행하면 JDBC POM에 versionless entry 5개가 생성됩니다.
- Kotlin stdlib, Kotlin reflect, kotlinx-coroutines-core entry는 같은 POM이 명시적으로 관리하므로 유효합니다. 잘못된 entry는 versionless Spring Boot 및 Exposed BOM import입니다.
- Root-cause boundary: central catalog가 이미 authoritative versioned BOM alias를 제공했지만, publication-facing platform declaration은 versionless local alias를 계속 사용했습니다.

## Bug-fix gate

- [x] C-01: deterministic public/local reproduction을 수집했습니다.
- [x] C-02: surgical scope와 regression shape가 승인되었습니다. 승인 범위에는 issue creation이 포함되지 않았습니다.
- [x] C-03: 초기 publication-POM audit는 3 tests/10 assertions를 실행했고, 생성된 JDBC POM의 versionless entry 5개로 RED를 보고했습니다. 정제된 regression suite는 유효한 managed dependency와 잘못된 BOM import를 구분합니다.
- [x] C-04: broad resolved-version rewriting 없이 publication-facing local BOM alias를 `bt4k.exposed.bom` 및 `bt4k.spring.boot4.dependencies`로 교체합니다.
- [x] C-05: audit, generated POM, 영향 compilation, proportional broader build가 GREEN임을 증명합니다.
- [x] C-06: 새로 발견한 catalog-alias failure mode를 기존 Central POM lesson에 반영합니다.
- [ ] C-07: 승인된 PR을 만들고 exact-head CI/review를 통과합니다.
- [ ] C-08: exact-head merge readiness를 보고합니다.
- [ ] C-09: fresh approval 이후에만 merge한 뒤 sync/cleanup합니다.

## Snapshot publication gate

- [x] PUB-01: routine-snapshot identity, target version, repository, consumer, current authority는 위에 고정되어 있습니다.
- [x] PUB-02: current workflow, prior snapshot run, public POM, previous POM fix PR #136, consumer failure를 live query했습니다.
- [ ] PUB-03: 정확히 merge된 candidate state와 generated artifact matrix를 증명합니다.
- [ ] PUB-04: generated BOM/POM metadata, license, signing diagnostics, publishable-module matrix를 audit합니다.
- [ ] PUB-05: dispatch 직전에 merge된 `develop` SHA를 고정하고 `.github/workflows/publish-snapshot.yml` 및 live external metadata를 다시 읽습니다.
- [ ] PUB-06: `Publish Snapshot`을 dispatch하고 정확한 run을 monitor한 뒤, 예상 public POM을 독립적으로 모두 검증합니다.
- [ ] PUB-07: routine snapshot에 GitHub Release 또는 milestone closeout이 포함되지 않음을 확인한 뒤에만 N/A 처리합니다.
- [ ] PUB-08: Javers consumer가 resolve되는지 검증하고 PR #249 CI를 재실행합니다.
- [ ] PUB-09: 이 복구가 active next development line을 이미 publish한다는 점을 확인한 뒤에만 N/A 처리합니다.
- [ ] PUB-10: same-version snapshot repair에 public install documentation 변경이 필요 없음을 확인한 뒤에만 N/A 처리합니다.
- [ ] PUB-11: 정확한 SHA, workflow URL, public metadata timestamp, artifact audit, consumer result, remaining risk를 보고합니다.

## Dispatch 보류

- Publication은 승인된 계획에 포함되지만, fresh approval 이후 fix PR이 merge될 때까지 hold 상태입니다.
- snapshot workflow는 현재 `workflow_dispatch` input을 선언하지 않고 default branch를 checkout합니다.
- dispatch 전에 default branch SHA가 승인된 merged fix SHA와 같은지 검증합니다.
- feature branch, stale `develop` SHA, 또는 generated POM audit가 missing version 0개를 보고하기 전에는 dispatch하지 않습니다.

## 로컬 검증 근거

- Publication audit tests: 5 runs, 16 assertions, 0 failures.
- Snapshot POM generation: public POM 35개를 성공적으로 생성했습니다.
- Structural audit: dependency entry 10,125개를 확인했고 invalid dependency-management version은 0개였습니다.
- Maven consumer-model audit: single reactor에서 effective model 35개를 검증했습니다.
- Catalog authority: platform declaration 28개가 `bt4k.exposed.bom` 또는 `bt4k.spring.boot4.dependencies`를 사용합니다. 중복 versionless local alias는 제거되었습니다.
- Proportional build: `./gradlew build -x test -x koverVerify --parallel --no-configuration-cache --no-daemon` 통과.
- Static checks: `actionlint`, `xmllint`, Ruby syntax, `git diff --check` 통과.
