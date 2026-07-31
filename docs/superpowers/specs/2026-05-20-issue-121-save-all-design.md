# Issue 121 SaveAll 저장소 API 설계

## 배경

GitHub issue #121은 milestone 1.8.1에서 핵심 `JdbcRepository`와
`R2dbcRepository` 계약에 `saveAll(entities: Iterable<E>)`를 추가하도록
요구한다.

현재 source는 issue의 표현과 다음과 같이 다르다.

- `JdbcRepository`와 `R2dbcRepository`에는 핵심 단일 entity
  `save(entity)` 계약이 없다.
- 핵심 저장소 인터페이스가 `table`, `extractId`, `toEntity`만 알기 때문에
  저장소 테스트 fixture는 임시 `save()` 메서드를 구현한다.
- 저장소가 column binding 확장 지점을 제공하지 않으면 `E`만으로 범용
  batch insert를 구현할 수 없다.

이 작업에서는 Claude advisor/review를 사용하지 않는다. 구독 등급 변경으로
Claude Code review를 사용할 수 없다는 사용자 지시를 따른다. 외부 프로세스로
Codex CLI review도 실행하지 않으며, 현재 Codex 세션이 구현·검토·검증을 소유한다.

현재 IntelliJ에는 `bluetape4k-exposed`가 아니라 `bluetape4k-workshop`이 열려
있으므로 이 worktree에 대한 IDE reference/diagnostic 도구를 사용할 수 없다.
대신 repository search와 대상 Gradle compile/test를 근거로 사용한다.

## API 결정

작은 binding 확장 지점과 기본 `saveAll` 구현을 추가한다.

```kotlin
fun BatchInsertStatement.bindSave(entity: E)
fun saveAll(entities: Iterable<E>): List<ID>
```

확장 지점은 각 저장소 인터페이스의 member extension이다. 기본 `saveAll`을
사용하려는 구현은 이를 재정의하여 entity 값을 table column에 할당한다.
기본 확장 지점은 명확한 메시지와 함께 `UnsupportedOperationException`을
던진다. 따라서 기존 저장소는 source compatibility를 유지하고, binding 없이
`saveAll`을 호출할 때만 명시적으로 실패한다.

`saveAll`은 입력 `Iterable`을 한 번만 materialize하고, 입력이 비어 있으면
`emptyList()`를 반환한다. 그렇지 않으면 Exposed `batchInsert`를 호출하고
삽입 순서대로 생성된 primary key 값을 반환한다.

## 범위

- `JdbcRepository`에 `saveAll(Iterable<E>): List<ID>`를 추가한다.
- `R2dbcRepository`에 `suspend saveAll(Iterable<E>): List<ID>`를 추가한다.
- 두 계약 모두에 동일한 `BatchInsertStatement.bindSave(entity)` 확장 지점을 추가한다.
- 일반 저장소에서 100개 이상 및 10k개 이상 일괄 삽입을 검증한다.
- 감사 저장소 variant에서 일괄 삽입 후 table 기본값이 감사 기본값을 계속 생성하는지 검증한다.

## 범위 밖

- 핵심 인터페이스에 `save(entity)`를 추가하지 않는다. 기존 테스트 저장소에는
  이미 `save(entity): E`가 있으므로, 매개변수는 같고 반환 타입만 다른 메서드를
  추가하면 source conflict가 발생한다.
- reflection으로 entity-to-column mapping을 추론하지 않는다.
- Spring Data `ExposedJdbcRepository` / `ExposedR2dbcRepository`를 변경하지
  않는다. 이들은 이미 Spring Data `saveAll`을 상속하거나 구현한다.
- 새 dependency를 추가하지 않는다.

## 검증

- 영향받는 모듈 compile:
  - `./gradlew :bluetape4k-exposed-jdbc:compileKotlin`
  - `./gradlew :bluetape4k-exposed-r2dbc:compileKotlin`
- 집중 저장소 테스트:
  - JDBC 일반/감사 `saveAll` 테스트
  - R2DBC 일반/감사 `saveAll` 테스트
- 현재 Codex 세션에서 최종 diff를 검토하고 Claude/Codex 외부 review 제약을 DoD에 기록한다.
