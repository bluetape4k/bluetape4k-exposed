# #613 Public API 호환성 검토

검토일: 2026-08-01 KST
기준 저장소: `bluetape4k-exposed` 1.12.0 local candidate
기준 Git head: local candidate commit on `fix/issue-600-review-findings` (`develop` 기준 `4a3c6de7`)

## 범위와 방법

이번 review follow-up에서 영향을 받은 11개 published artifact를 대상으로 현재 working
tree의 jar와 Maven Central의 immutable `1.11.0` jar를 비교했습니다. 현재 jar는 각 Gradle
module의 `jar` task로 생성했고, 이전 jar는 `repo1.maven.org`의 `1.11.0` artifact를
사용했습니다.

비교는 `javap -public -s` 결과에서 top-level class와 Kotlin facade의 public JVM
signature를 정규화하여 수행했습니다. `$` inner/synthetic class는 범위에서 제외했지만,
top-level facade 안의 Kotlin compiler generated `access$...` 항목은 원자료에 남겨
분류했습니다. 따라서 이 결과는 완전한 ABI 도구의 대체물이 아니라, 현재 변경에 의한
명백한 public signature 제거 여부를 확인하는 bounded evidence입니다.

재현 가능한 machine-readable 결과는
[`2026-08-01-issue-613-public-api-compatibility.json`](./2026-08-01-issue-613-public-api-compatibility.json)에
있습니다.

## 결과

| Artifact | 1.11.0 public lines | Candidate public lines | Removed | Added | 판단 |
|---|---:|---:|---:|---:|---|
| `bluetape4k-exposed-bigquery` | 194 | 196 | 0 | 2 | 제거 없음 |
| `bluetape4k-exposed-jdbc-lettuce` | 242 | 250 | 0 | 8 | 제거 없음; bulk API 추가 |
| `bluetape4k-exposed-r2dbc-lettuce` | 133 | 140 | 0 | 7 | 제거 없음; bulk API 추가 |
| `bluetape4k-exposed-jdbc-redisson` | 107 | 380 | 0 | 273 | 제거 없음; 기존 snapshot surface 포함 |
| `bluetape4k-exposed-r2dbc-redisson` | 58 | 58 | 0 | 0 | 변화 없음 |
| `bluetape4k-exposed-ktor` | 89 | 205 | 4 | 120 | 제거 항목은 internal/compiler-generated |
| `bluetape4k-exposed-spring-boot-jdbc` | 144 | 174 | 5 | 35 | 제거 항목은 synthetic + pre-existing internal facade |
| `bluetape4k-exposed-spring-boot-r2dbc` | 125 | 127 | 2 | 4 | 제거 항목은 synthetic |
| `bluetape4k-exposed-spring-modulith` | 59 | 106 | 0 | 47 | additive count/type query surface |
| `bluetape4k-exposed-batch` | 504 | 504 | 0 | 0 | 변화 없음 |
| `bluetape4k-exposed-jdbc` | 58 | 58 | 0 | 0 | 변화 없음 |

이번 issue-600 변경에서 stable source-level API의 제거는 확인되지 않았습니다. Lettuce의
`putAll`/`warmAll`은 의도된 additive API이고, Modulith의 count/type query는 기존
publication repository를 확장합니다.

### 원자료에 나타난 제거 항목과 분류

- Ktor의 `validateHealthRoutes$...()` → `validateHealthRoutes$...(boolean)`은
  `internal` helper의 JVM mangling 변화입니다. `access$probe...` 두 항목도 compiler
  generated accessor입니다. public installer/config contract는 제거되지 않았습니다.
- Spring JDBC/R2DBC의 `access$isWriteBehindStalled(...)`는 compiler generated
  accessor입니다.
- `SimpleExposedJdbcRepositoryKt.EXPOSED_TRANSACTION_MANAGER`는 6e4b28a4
  (`feat: auto-configure aggregate event publisher safely`)에서 이미 제거된
  `internal const val` facade 항목입니다. 이번 issue-600 working-tree 변경의 회귀로
  분류하지 않았습니다.

## 남은 호환성 한계

이번 비교는 top-level public JVM signature에 한정됩니다. inner class, annotation
속성, generic signature/metadata, Kotlin source compatibility, 모든 published artifact의
full API baseline은 별도 `japicmp`/binary-compatibility-validator task가 없으므로
검증하지 않았습니다. 따라서 이 보고서는 REV-01의 bounded evidence를 충족하지만,
REL-02의 exact candidate head full matrix 또는 publication 승인을 대체하지 않습니다.
