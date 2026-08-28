# Issue #754 Ktor selective dependency allowlist lesson

## 맥락

기존 `checkKtorDependencyBoundary`는 Exposed sibling/backend artifact만
금지했기 때문에 `io.github.bluetape4k.exposed` namespace 밖의 임의 third-party
좌표와 `kotlinx-serialization` 비-JVM variant를 통과시킬 수 있었다. 또한 POM
검사가 `dependencyManagement` catalog까지 직접 dependency로 수집하고 있었다.

## 실패한 가정과 교정

1. **실패한 가정/판단**: sibling artifact 금지만으로 selective Ktor 경계를
   보장할 수 있다.
   **발견 증거 또는 교정**: 설계와 issue acceptance가 source/direct,
   compile/runtime closure, POM, Gradle metadata 모두의 fully-qualified
   `group:name` allowlist와 namespace deny-by-default를 요구했다.
   **수정 결정**: 공통 46개 canonical 좌표와 모듈별 Exposed/backend 좌표를
   JSON 정책으로 고정하고 네 검증 표면에 동일하게 적용했다.
   **향후 예방 확인**: 정책 회귀 테스트가 임의 third-party와 sibling edge를
   네 selective module 각각에서 거부한다.

2. **실패한 가정/판단**: 모든 XML `dependency` element가 POM의 직접 의존성이다.
   **발견 증거 또는 교정**: `getElementsByTagNameNS`는 `dependencyManagement`
   catalog까지 포함해 실제 direct dependency보다 많은 좌표를 수집했다.
   **수정 결정**: `<project><dependencies>`의 직접 child만 순회하고,
   `dependencyManagement`는 dependency boundary 대상에서 제외했다.
   **향후 예방 확인**: boundary receipt의 published POM 목록이 직접 dependency
   수와 일치하고 Gradle metadata는 별도로 모든 publication variant를 검사한다.

3. **실패한 가정/판단**: JVM artifact 정규화는 build task에만 있으면 충분하다.
   **발견 증거 또는 교정**: 외부 consumer closure는 base coordinate와 `-jvm`
   coordinate를 함께 보고하므로 validator가 정책과 다른 결과를 낼 수 있었다.
   **수정 결정**: Gradle task와 consumer validator가 같은 JSON alias map으로
   base→JVM을 정규화하고, alias가 없는 `-js`/`-native` variant는 거부한다.
   **향후 예방 확인**: Ruby fixture가 alias target 허용과 비-JVM serialization
   variant 거부를 동시에 검증한다.

4. **실패한 가정/판단**: Ktor job에 정책 테스트를 추가하면 정책 파일만
   변경한 PR에서도 자동으로 실행된다.
   **발견 증거 또는 교정**: `dorny/paths-filter`의 `ktor` 경로에
   `scripts/verification` 파일이 없어 정책 전용 변경이 Ktor job을 건너뛸 수
   있었다.
   **수정 결정**: allowlist JSON과 두 validator/test 경로를 Ktor filter에
   명시하고, 정책 테스트가 세 경로의 filter 항목을 고정하도록 했다.
   **향후 예방 확인**: 정책 경로를 변경할 때마다 Ktor CI reachability 회귀
   assertion이 먼저 실패한다.

## 검증

- `ruby scripts/verification/ktor_dependency_allowlist_test.rb` → 8 tests,
  200 assertions, 0 failures.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml` → PASS.
- `./gradlew checkKtorDependencyBoundary --no-configuration-cache --no-daemon
  --no-build-cache --rerun-tasks --console=plain` → selectiveArtifacts=4,
  외부 consumer 4개와 POM/Gradle metadata PASS.
- Gradle task receipt에 정책 파일, schema, 공통 좌표 수와 모듈별 좌표 수를
  기록한다.

## 다음 변경을 위한 guard

- 새 Ktor 또는 backend dependency는 먼저
  `scripts/verification/ktor-dependency-allowlist.json`의 모듈별 명시 좌표와
  실제 compile/runtime closure를 함께 갱신하고, sibling/variant 회귀 테스트를
  유지한다.
- POM 검사에서 전체 DOM descendant를 다시 사용하지 말고 직접
  `<project><dependencies>` 경계를 유지한다.
- 외부 consumer validator가 local fixture project 좌표를 외부 allowlist와
  혼동하지 않도록 local project coordinate를 별도로 제외한다.

## DoD Status

- [x] fail-closed fully-qualified allowlist를 source/resolved/POM/metadata에 적용했다.
- [x] JVM alias normalization과 비-JVM serialization 거부 회귀 fixture를 추가했다.
- [x] 정책 테스트를 CI와 nightly Ktor job에 연결했다.
- [x] local boundary task와 external consumer 검증을 통과했다.
