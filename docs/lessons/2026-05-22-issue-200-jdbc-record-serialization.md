# Issue 200 Jdbc Record Serialization

## 배경

Issue #200은 `AuditableEdgeCaseRecord`가 `java.io.Serializable`을 구현하지 않고
명시적인 `serialVersionUID`도 없다고 보고했습니다.

## 결정

보고된 record와 같은 package의 sibling `exposed-jdbc` repository test record를 함께
수정합니다. project rule은 모든 data class에 적용되며, 인접 record를 stable serialization
contract 없이 남기면 같은 daily review finding이 반복됩니다.

## 결과

모든 `exposed-jdbc` repository test record data class는 이제 `Serializable`을 구현하고
`serialVersionUID = 1L`을 정의합니다. focused regression test는 `ObjectStreamClass`로
Java serialization contract를 확인합니다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc:test --tests 'io.bluetape4k.exposed.jdbc.repository.JdbcRepositoryRecordSerializationTest' --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew :bluetape4k-exposed-jdbc:test --no-daemon --no-configuration-cache --no-build-cache`

## 향후 guard

daily review가 data class serialization miss 하나를 지적하면 PR을 열기 전에 같은 package의
sibling test fixture를 검사합니다. 수정이 contract-only면 작은 `ObjectStreamClass`
regression test를 우선합니다.
