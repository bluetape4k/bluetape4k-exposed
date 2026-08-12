# Issue #649 JDK 25 provider 런타임 정렬 교훈

## 배경

리포지토리의 JVM 기본 툴체인은 JDK 25인데, `spring-boot/batch-exposed`와
`benchmark/exposed-benchmark`의 테스트·벤치마크 classpath가
`bluetape4k-virtualthread-jdk21` provider를 직접 선택하고 있었다. `utils/batch`는
이미 JDK 25 provider와 JDK 21 제외 규칙을 일부 갖고 있었지만, 테스트 의존성에
JDK 21을 다시 선언해 경계가 불분명했다.

## 결정 및 발견 사항

- 테스트와 benchmark runtime은 `bluetape4k-virtualthread-jdk25`만 선택한다.
- `spring-boot/batch-exposed`와 benchmark runtime classpath에서
  `bluetape4k-virtualthread-jdk21`을 명시적으로 제외한다.
- `utils/batch`의 중복 JDK 21 테스트 의존성을 제거한다.
- `utils/batch`의 production `runtimeOnly(jdk21)`은 JDK 21 소비자 호환성을
  유지하기 위해 남긴다. 테스트 runtime을 JDK 25로 맞추는 결정과 production
  소비자 지원 범위는 같은 계약이 아니다.

JDK 25에서 `StructuredTaskScope`를 사용하는 테스트·benchmark는 provider의
classfile과 런타임 선택도 JDK 25에 맞아야 한다. 직접 JDK 21 provider를 선언한
모듈만 고치면 transitive classpath가 다시 legacy provider를 끌어올 수 있으므로,
선택(alias)과 runtime exclusion을 함께 고정한다.

## 결과

세 모듈의 의존성 그래프는 모두 `bluetape4k-virtualthread-jdk25:1.12.1`을
선택했고 `bluetape4k-virtualthread-jdk21`은 검색 결과에 나타나지 않았다.
JDK 21 production runtime 지원은 축소하지 않았다.

## 검증

- `:bluetape4k-exposed-spring-boot-batch:test` — 성공.
- `EXPOSED_TEST_DB=H2 :bluetape4k-exposed-batch:test` — 239개 실행, 4개 건너뜀,
  성공.
- `EXPOSED_TEST_DB=POSTGRESQL :bluetape4k-exposed-batch:test` — 301개 실행,
  4개 건너뜀, 성공.
- `EXPOSED_TEST_DB=MYSQL_V8 :bluetape4k-exposed-batch:test` — 301개 실행,
  7개 건너뜀, 성공.
- `:benchmark-exposed-benchmark:benchmarkClasses`와 bounded
  `:benchmark-exposed-benchmark:benchmarkSmokeBenchmark` — 성공.
- 세 변경 모듈의 `detekt`와 `git diff --check` — 성공.

환경 변수를 지정하지 않은 원래 전체 선택 실행은 로컬 Docker socket mount 오류로
실패했지만, H2·PostgreSQL·MySQL 8을 각각 지정한 회귀 경로는 모두 통과했다. 이
두 결과를 provider 결함과 Docker 준비 상태의 증거로 섞지 않는다.

## 향후 지침

- JDK toolchain을 올릴 때 provider alias, 각 테스트/benchmark runtime graph,
  ServiceLoader 실행 경계를 함께 검사한다.
- `dependencyInsight --dependency bluetape4k-virtualthread`를 CI와 로컬
  회귀 체크에 포함해 legacy provider의 재유입을 조기에 드러낸다.
- production runtime 호환성과 테스트 실행 provider를 한 의존성 선언으로
  추정하지 않는다. 소비자 지원을 바꾸려면 별도 API·릴리스 결정으로 다룬다.
