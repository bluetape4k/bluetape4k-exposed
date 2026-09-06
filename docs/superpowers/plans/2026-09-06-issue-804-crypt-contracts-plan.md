# #804 해시 컬럼 계약 검증 계획

## 목표와 실행 경계

승인된 명세의 upstream 직접 사용 경로를 기존 test-support의 테스트와 README로 고정한다. production 코드·공개 API를 추가하지 않으므로 신규 production 기능의 RED/GREEN이 아니라 기존 upstream 동작의 characterization과 평문 검출 negative control을 사용한다. main 세션이 편집·순차 빌드를 소유한다. 명세·계획·구현 리뷰는 독립 실행을 먼저 시도하고 실행 불가 시 사유를 기록해 inline fallback으로 진행한다.

## 1. 기준과 의존성

- [x] 기존 `consumer.JdbcFixtureConsumerTest`: 1건 통과, `build/issue804/baseline.log`.
- [x] R2DBC `consumer.R2dbcFixtureConsumerTest`: 1건 통과, `build/issue804/baseline-r2dbc.log`.
- [x] `exposed/r2dbc-tests/build.gradle.kts`에 `testImplementation(libs.exposed.crypt)`만 추가한다.
- [x] 두 모듈의 `testRuntimeOnly(bt4k.bouncycastle.bcprov)`로 선택 알고리즘 검증을 활성화한다. 버전은 catalog alias를 그대로 사용한다.

```kotlin
testImplementation(libs.exposed.crypt)
testRuntimeOnly(bt4k.bouncycastle.bcprov)
testRuntimeOnly("org.springframework:spring-core")
```

## 2. backend별 같은 계약 테스트

신규 파일은 `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/crypt/JdbcHashedColumnContractTest.kt`와 `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/crypt/R2dbcHashedColumnContractTest.kt`다. 두 파일은 별도 backend transaction/DSL을 사용한다. production 공용 fixture로 추출하지 않는다.

```kotlin
val secret = varchar("secret", 512).hashed(hasher)
val optional = varchar("optional", 512).nullable().hashed(hasher)
// 원문을 DB에 전달하기 전에 컬럼의 hasher로 변환한다.
table.insert { it[secret] = secret.hash(plainText); it[optional] = null }
val loaded = table.selectAll().single()[secret]
loaded.matches(plainText).shouldBeTrue()
loaded.matches("wrong").shouldBeFalse()
loaded.toString() shouldBeEqualTo "Hashed(***)"
```

- [x] BCrypt, Argon2, PBKDF2, SCrypt 각각 실제 H2 저장·조회·matches 및 raw 문자열 비교. nullable null과 non-null 모두 검사.
- [x] 저장된 Hashed 재저장과 외부 encoded 값을 감싼 Hashed 저장 후 raw 값 동일성. custom Hasher는 BCrypt에 위임하고 hash 호출 수를 세어 재저장에 새 hash 호출이 없음을 검사.
- [x] BCrypt 4→5 및 BCrypt→PBKDF2 전환. DelegatingPasswordEncoder로 구 hash를 검증하고 성공한 경우에만 새 hash를 저장한다. 실패 입력은 기존 DB 값을 유지한다.
- [x] 트랜잭션 SQL logger의 렌더링 결과와 중복 키 예외 chain·suppressed 문자열에 평문이 없는지 검사. 검출기가 의도적인 평문 입력을 검출하는 negative control도 둔다. 해시 자체를 로깅해도 된다고 설명하지 않는다.
- [x] 모든 생성 테이블은 기존 `withTables`로 정리한다. R2DBC는 `runSuspendIO`, JDBC는 blocking withTables를 사용한다. 로거는 전역 appender가 아니라 transaction-local 수집기를 사용한다.

## 3. 문서·의존성 경계

- [x] JDBC/R2DBC README 양언어에 직접 의존성·column-bound hash·nullable·rehash 예제와 책임 경계를 기록한다.
- [x] Tink README 양언어에는 가역 암호화와의 차이 및 test-support 예제 링크만 추가한다. Tink production 의존성은 변경하지 않는다.
- [x] BCrypt 출력은 60자이지만 알고리즘 전환을 위해 예제 컬럼은 512자다. Argon2/SCrypt BouncyCastle 요구, 명시적 Crypto 의존성, 테스트 저비용 파라미터 비권장, 일반화한 인증 실패와 로깅 금지 정책을 설명한다.
- [x] CHANGELOG와 `docs/lessons/2026-09-06-issue-804-crypt-contracts.md`에 근거·결정·검증·알려진 한계를 기록한다.

## 4. 검증·PR

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:test --tests '*JdbcHashedColumnContractTest' --no-parallel
./gradlew :bluetape4k-exposed-r2dbc-tests:test --tests '*R2dbcHashedColumnContractTest' --no-parallel
./gradlew :bluetape4k-exposed-jdbc-tests:test :bluetape4k-exposed-jdbc-tests:detekt :bluetape4k-exposed-jdbc-tests:checkKotlinAbi --no-parallel
./gradlew :bluetape4k-exposed-r2dbc-tests:test :bluetape4k-exposed-r2dbc-tests:detekt :bluetape4k-exposed-r2dbc-tests:checkKotlinAbi --no-parallel
git diff --check
```

- [x] JUnit 건수와 기존 skip을 구분한다. 실패 후 재시도만으로 PASS 처리하지 않는다.
- [x] 생성 publication POM에서 신규 production dependency가 없음을 확인한다. 기존 JDBC crypt 노출은 새 API가 아니며 별도 정리하지 않는다.
- [ ] PR은 `bluetape4k/bluetape4k-exposed`, `develop` ← `feat/issue-804-exposed-crypt-contracts`, assignee debop, milestone 2.1.0, #804 labels를 사용한다.
- [ ] exact-head CI·리뷰를 마친 뒤 머지는 보류하고 #815 잔여 작업으로 이동한다.

## 수용 기준 연결과 복구

실행 보완: Spring Security 7.1.1에서 확인한 `StringUtils` 누락은 두 모듈의
testRuntimeOnly로 해결했다. production 의존성 변화는 없다. publication 태스크의 실제 이름은
`generatePomFileForBluetapeExposedPublication`이며 POM 생성은 configuration cache 충돌 때문에
`--no-configuration-cache`로 검증한다. 대상 16건은 모두 통과했고, H2 모듈 전체는 JDBC
111건(기존 제외 5건), R2DBC 102건(기존 제외 5건), 실패 0건이다.
detekt·checkKotlinAbi와 두 POM 생성이 통과했다. PR·CI 항목은 아직 대기다.

명세 1–5 → 작업 2, 명세 6 → 작업 1·3·4. 실패 재현 로그를 보존하고 해당 테스트만 수정·재실행한 뒤 module 전체를 다시 검사한다. 의존성 문제가 production 경로로 번지면 test 의존성 변경을 되돌리고 PR을 만들지 않는다. 배포·중앙 catalog·upstream 게시·머지는 이번 실행의 외부 동작에 포함하지 않는다.
