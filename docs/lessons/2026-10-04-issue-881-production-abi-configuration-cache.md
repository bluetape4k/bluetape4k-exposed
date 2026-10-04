# #881 production ABI task의 configuration cache 검증

## 배경과 결정

`checkProductionAbi`의 `doLast` 구현은 root Gradle script와 `Project` 값을 캡처해 엄격한 configuration-cache 저장을 실패시켰다. 비슷한 script 객체 캡처는 [#878 batch 빌드 교훈](2026-09-14-pr-878-build-and-assertions.md)에서도 확인했다. 이번에는 aggregate inventory 계산을 typed `CheckProductionAbiTask`로 옮기고, 프로젝트 이름·기준선 파일·실제 ABI dump를 task 입력으로, report를 출력으로 선언했다. task action은 Gradle script나 `Project`를 읽지 않는다.

## 검증과 리뷰

- TDD RED에서 strict configuration-cache 실행이 기존 closure의 script 객체와 `Project` 캡처 때문에 실패하는 것을 확인했다.
- 회귀 테스트는 산출물을 먼저 no-cache 실행으로 준비한 뒤, strict configuration-cache 첫 실행에서 저장 메시지와 실제 task action을, 동일 인자 두 번째 실행에서 cache 재사용 메시지와 실제 task action을 확인한다. 두 실행 모두 `--rerun-tasks`를 사용하고 task가 `UP-TO-DATE`로 건너뛰지 않았는지 검사한다.
- 같은 테스트에서 `--no-configuration-cache --rerun-tasks` 제어 실행도 통과했다. 세 실행의 report는 모두 45개 module·baseline·actual dump, orphan 0개, 빈 baseline 0개를 유지했다.
- `:buildSrc:test --tests CheckProductionAbiConfigurationCacheTest`, 전체 `:buildSrc:test`, `:buildSrc:check`, 기본 `checkProductionAbi` 연속 2회가 통과했다. 최종 독립 리뷰는 `P0=0`, `P1=0`이다.

## 검증에서 놓치기 쉬운 점

no-cache 준비 실행이 report를 생성하면 configuration-cache 실행의 aggregate task가 `UP-TO-DATE`로 끝날 수 있다. 이때 `BUILD SUCCESSFUL`과 cache 저장·재사용 메시지만 확인하면 task action의 configuration-cache 호환성을 검증했다고 볼 수 없다. regression test는 첫 저장과 재사용 실행 모두에서 task action이 실제 실행되는지 명시적으로 확인한다.

workflow receipt도 명령 성공 전에 완료됐다고 보고하지 않는다. helper 초기화 결과의 run ID, manifest hash, state root, 등록 component를 확인하고, 실행을 명시적 `--state-root`로 시작했다면 이후 helper 명령에도 같은 경로를 전달한다.

## 향후 보호 기준

1. root aggregate task의 action에 Gradle script나 `Project`를 캡처하지 않는다. 파일·값은 typed task property로 선언하고, 경로 inventory는 configuration 시점에 provider/file collection으로 구성한다.
2. configuration-cache 회귀 테스트는 준비 실행, strict cache 저장, 동일 인자 재사용, no-cache 제어를 각각 확인한다. 저장·재사용 메시지와 별개로 task action 실행 여부를 검증한다.
3. 기존 fail-closed inventory 수와 report 행을 검증해 누락·orphan·빈 기준선을 성공으로 처리하지 않는다.
