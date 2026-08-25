# Issue #721 `jdbc-tests` assertion 정규화 lesson

## 배경

`exposed/jdbc-tests`의 migration drift fixture가 JUnit raw assertion을 직접
사용하고 있어, bluetape4k test-support API가 공개 의존성 표면과 테스트 표현에
동시에 반영되지 않았다. Issue #721의 범위는 `bluetape4k-assertions` direct
`api` 선언, `JdbcMigrationDriftTest`의 intent-specific matcher 전환, 그리고
module-local import 재발 방지 guard다.

## 결정

- equality는 `shouldBeEqualTo`, boolean은 `shouldBeTrue`·`shouldBeFalse`,
  identity는 `shouldBeSameInstanceAs`, 예외는 `assertFailsWith`로 표현했다.
- `assertFailsWith(message = statement)`를 사용해 migration SQL 원문이 실패
  진단 prefix로 남도록 했다. primary 예외와 suppressed cleanup 예외의 identity와
  cleanup/drop 순서는 바꾸지 않았다.
- `src/main/kotlin`과 `src/test/kotlin`을 고정 root로 검사하는
  `verifyBluetapeAssertionImports`를 만들고 `test`, `migrationDriftTest`,
  `check`에 연결했다. canonical containment, source inventory, symlink,
  alias·wildcard·backtick·dotted spacing·semicolon·comment bypass를 fail-closed로
  처리한다.
- guard는 출력용 `println`이나 별도 logger를 추가하지 않는다. Gradle task 실패
  메시지는 기존 검증 표면의 예외 진단으로만 사용한다.

## 검증 결과

- guard RED는 기존 raw import 5건을 잡았고, alias·dotted spacing/backtick·semicolon·
  block-comment probe도 모두 실패하도록 동작했다. probe 파일은 매번 삭제했다.
- guard GREEN, `compileTestKotlin`, H2 module `test`가 통과했다. H2 migration은
  7/0/0/0, PostgreSQL과 MySQL_V8 migration은 각각 8/0/0/0
  (tests/failures/errors/skipped)였다.
- Docker/Colima preflight는 running/default/virtiofs였고, migration 전후 container
  잔류가 없었다.
- `outgoingVariants`, publication POM/module JSON, `apiElements` direct edge,
  `checkKotlinAbi`가 통과했다. ABI baseline SHA-256은
  `bd73cb0494910a92c40443441b02cb7db202229a08b97d34df2fa20c3306d9b7`로 유지됐다.
- global `mavenLocal()` 없이 별도 임시 Maven repository에 게시한 `jdbc-tests`
  artifact/POM checksum이 local publication과 일치했고, assertion matcher를
  호출하는 isolated consumer `compileKotlin`이 통과했다.

## 예상 밖의 문제

생성된 `jdbc-tests` POM은 upstream pre-release `bluetape4k-bom` import를 포함한다.
첫 consumer 시도는 global repository를 사용하지 않는 조건에서 이 BOM 좌표가
임시 repository에 없어 실패했다. 임시 repository에 해당 pre-release BOM 좌표와
이미 빌드된 assertion publication artifact/POM을 staging하고, unrelated
`junit5`·`testcontainers` pre-release edge를 제외한 뒤 다시 실행해 해결했다. 이
실패를 성공으로 숨기지 않고 격리 fixture의 prerequisite로 문서화한다.

## 재발 방지

새 Kotlin source root나 test-support 모듈을 추가할 때는 guard의 고정 root와
actual `KotlinCompile.inputs.sourceFiles` inventory를 함께 갱신한다. public
assertion API를 추가할 때는 source compile만으로 끝내지 말고 POM/module JSON의
`apiElements` direct edge, ABI baseline, isolated consumer compile을 모두
확인한다. 다음 단계는 Lore commit과 Korean PR의 live metadata/DoD 검증이며,
merge는 별도 승인 없이는 수행하지 않는다.
