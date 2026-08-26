# Issue #729 — Spring Data common 분리 lesson

## 맥락

Spring Data JDBC와 R2DBC가 같은 mapping·query·sort 계약을 공유하지만 R2DBC가
JDBC adapter를 transitively 끌어오는 구조였다. Issue #729에서는 backend-neutral
공통 SPI를 소유하는 `spring-boot/common`을 추가하고, JDBC는 기존 public facade를
호환성 경계로 남기며 R2DBC는 common만 의존하도록 분리했다.

## 결정

- `ExposedEntity`, `Query`, mapping context, persistent entity/property, query
  creator, parameter metadata, sort 변환을 common package에 둔다.
- 신규 JDBC/R2DBC 내부 구현과 예제는 common API를 직접 사용한다.
- 기존 JDBC annotation·mapping·query·sort descriptor는 deprecated facade로
  유지한다. 특히 legacy JDBC mapping을 common interface 상속으로 바꾸지 않아
  기존 binary descriptor를 보존한다.
- R2DBC compile/runtime classpath에 `bluetape4k-exposed-spring-boot-jdbc`와
  `spring-jdbc`가 없음을 전용 boundary task로 검사한다.
- 테스트 assertion은 `bluetape4k-assertions`를 사용하고, mapping cache 동시성은
  `MultithreadingTester`로 검증한다. production `println`/`System.out`은 두지
  않고 운영 출력은 기존 logging 계약을 따른다.

## TDD와 구현 결과

공통 annotation/mapping과 query/sort에 대해 먼저 unresolved symbol RED를 확인한
뒤 production API를 추가해 GREEN으로 전환했다. common 테스트는 14건이 통과했고,
JDBC 예제의 annotation import도 common으로 이전했다. R2DBC 소스에는 legacy JDBC
import가 남지 않았다.

## 검증 근거

- common test: 14 passing
- JDBC test: 260 passing
- R2DBC test: 317 tests, 8 skipped
- common/JDBC/R2DBC module Detekt 및 `checkKotlinAbi`: 통과
- JDBC/R2DBC demo compile과 Spring Modulith compile/test: 통과
- manual inventory/manifest validation: `Manuals are aligned.`
- BOM generated POM에 `bluetape4k-exposed-spring-boot-common`: 확인
- aggregate Kover: 82.69%
- `git diff --check`, common/R2DBC import guard, production `println`/stdout/stderr
  guard: 통과

## 남은 제약

1. 기존 `exposed/jdbc`의 UUID repository API 2개가 현재 develop API baseline과
   어긋나는 pre-existing ABI mismatch가 있어 root `checkProductionAbi`는 별도
   P2로 남겼다. Issue #729 변경 경계 밖이므로 API baseline을 조작하지 않았다.
2. R2DBC boundary task는 resolved configuration을 실행 시 읽어 configuration
   cache 경고가 발생한다. `--no-configuration-cache` 경계를 명시했고, file-input
   기반 task로 바꾸는 후속 개선 대상으로 기록했다.
3. PR exact-head hosted CI와 human review는 PR 생성 후 fresh evidence가 필요하다.
   PR/CI/review/merge와 canonical `develop` sync는 서로 독립된 게이트다.

## 재발 방지 체크

- 신규 Spring Data 코드는 common package import를 우선 검색한다.
- R2DBC 의존성 변경 시 compile/runtime resolved artifact boundary를 확인한다.
- public facade를 변경하기 전 generated ABI와 legacy descriptor를 비교한다.
- 테스트에는 `bluetape4k-assertions`와 deterministic concurrency helper를 사용하고
  `println`/`!!`을 추가하지 않는다.
- manual manifest, BOM POM, downstream example compile, path-filtered CI를 같은
  변경에서 함께 검증한다.

## PR exact-head 사후 교훈

- API graph에서 coroutine을 노출하는 publishable module은 개별 runtime version이
  아니라 `api(platform(bt4k.kotlinx.coroutines.bom))`를 함께 선언해야 Gradle Module
  Metadata의 versionless dependency 검사를 통과한다.
- 새 production module을 ABI inventory에 추가하면 root build 파일뿐 아니라 CI의
  고정 cardinality 검사도 같은 변경에서 갱신해야 한다.
- 로컬 모듈 테스트가 process-spawn 자원 오류를 보이면 `--max-workers=1`로 재현해
  코드 실패와 실행기 자원 고갈을 분리한다. 이번 재실행은 JDBC 260 tests GREEN이었다.

## SPW DoD

- [x] 승인된 설계·계획과 Issue #729 범위를 구현에 반영했다.
- [x] 7-Tier review에서 P0/P1 없이 P2 두 건을 명시적으로 기록했다.
- [x] 로컬 테스트·정적 분석·ABI·문서·BOM·downstream 근거를 읽고 기록했다.
- [ ] hosted exact-head CI, human review, merge, canonical sync — PR 이후 게이트

상태: **IMPLEMENTATION_READY — DELIVERY_PENDING**
