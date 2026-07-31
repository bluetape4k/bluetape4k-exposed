# Issue 318 Exposed Modulith 관측성 리뷰

## 범위

- 이슈: #318 `feat(spring-modulith): add optional observability for Exposed event publications`
- 모듈: `spring-boot/spring-modulith`
- 변경 형태: 선택적 Micrometer 자동 구성, 집중 테스트, README/README.ko 업데이트

## 근거

- Spring Modulith 공식 소스와 문서는 `module.events.published`를 이벤트 발행 메트릭 계열로 정의하고 이벤트 메트릭 사용자 지정 API를 제공한다. 이 PR은 Exposed 메트릭을 영속 저장소 상태 게이지로 분리해 유지한다.
- 기존 로컬 소스에는 `ExposedModulithAutoConfiguration`, `ExposedModulithProperties`, `ExposedEventPublicationRepository`가 있었지만 Micrometer 통합은 없었다.
- 클래스패스와 빈 활성화는 Spring Boot의 선택적 자동 구성 규칙을 따른다. Micrometer는 `compileOnly`이고, 자동 구성은 `@ConditionalOnClass(name = [...])`로 보호되며, 빈은 `ExposedEventPublicationRepository`와 `MeterRegistry`가 모두 있을 때만 생성된다.

## 리뷰 결과

| 관점 | 상태 | 근거 |
|---|---|---|
| Tier 4 구현 | PASS | 선택적 자동 구성은 저장소와 `MeterRegistry`가 모두 있을 때만 게이지를 등록하며, 기존 저장소의 상태 변경 의미는 바뀌지 않았다. |
| Tier 5 테스트 | PASS | 집중 자동 구성 테스트가 활성화 경로, 비활성화 속성, Micrometer 클래스패스 부재, `MeterRegistry` 부재, 저장소 부재, 로드할 수 없는 토글, 구성된 태그를 검증한다. 전체 모듈 테스트도 통과했다. |
| Tier 7 문서 | PASS | `README.md`와 `README.ko.md`가 활성화 조건, 미터 이름, 태그 카디널리티, Spring Modulith 메트릭 경계를 설명한다. |
| 다이어그램 리뷰 | PASS | 다이어그램 자산은 추가하지 않았다. 이번 변경은 새로운 토폴로지나 이벤트 수명주기가 아니라 운영 게이지의 활성화 조건과 태그 계약을 추가한다. 여기서는 README의 글머리표 목록이 다이어그램보다 소스 근거를 더 명확하게 전달한다. |
| 동시성 게이트 | PASS | 동시성, 스레드 안전성, 코루틴, 가상 스레드 동작은 바뀌지 않았으므로 `MultithreadingTester`와 관련 스트레스 도우미는 해당하지 않는다. |

## 참고

- 저장소 읽기가 실패하면 게이지 콜백은 `Double.NaN`을 사용해 메트릭 스크레이프 경로에서 예외가 발생하지 않게 한다. 저장소 정확성은 계속 저장소 테스트가 검증하며, 이 PR은 관측성 표면만 추가한다.
- 추가로 구성하는 태그는 애플리케이션, 리전, 환경과 같은 낮은 카디널리티의 배포 태그로 제한해야 한다.

## 검증

- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --tests 'io.bluetape4k.spring.modulith.exposed.config.ExposedModulithObservabilityAutoConfigurationTest' --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - 결과: `SUCCESS: Executed 7 tests in 2.6s`; `BUILD SUCCESSFUL in 6s`
- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - 결과: `SUCCESS: Executed 61 tests in 16.9s`; `BUILD SUCCESSFUL in 20s`
- `git diff --check`
  - 결과: PASS
- `mcp__omx_code_intel.lsp_servers`
  - 결과: Kotlin/IntelliJ 진단 백엔드를 사용할 수 없어 Gradle `compileKotlin`, 집중 테스트, 전체 모듈 테스트를 대체 근거로 사용했다.
